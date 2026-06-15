import uuid
from qdrant_client import QdrantClient, models

VECTOR_SIZE = 384  # paraphrase-multilingual-MiniLM-L12-v2 output dimensions


def build_ancestor_paths(subject_path: str) -> list[str]:
    """Compute all ancestor path prefixes for a given subject_path.

    "bu/santos/reserves" → ["bu", "bu/santos", "bu/santos/reserves"]

    Storing every ancestor in the Qdrant payload means a single
    must_not.match.any filter on "bu/santos" automatically excludes all
    descendants — no recursive logic needed at query time.
    """
    parts = subject_path.split("/")
    return ["/".join(parts[: i + 1]) for i in range(len(parts))]


def ensure_collection(client: QdrantClient, collection: str) -> None:
    """Create the Qdrant collection and required payload indexes if they do not exist."""
    existing = {c.name for c in client.get_collections().collections}
    if collection not in existing:
        client.create_collection(
            collection_name=collection,
            vectors_config=models.VectorParams(
                size=VECTOR_SIZE,
                distance=models.Distance.COSINE,
            ),
        )
    # Payload indexes are required for filter-based deletes and FGA filtering.
    # create_payload_index is idempotent — safe to call on existing collections.
    for field in ("doc_id", "ancestor_paths", "classification_level", "origin_source"):
        client.create_payload_index(
            collection_name=collection,
            field_name=field,
            field_schema=models.PayloadSchemaType.KEYWORD,
        )


def delete_by_doc_id(client: QdrantClient, collection: str, doc_id: str) -> None:
    """Delete all Qdrant points associated with doc_id.

    Called before every upsert — guarantees that:
    1. Re-ingesting the same file replaces rather than duplicates chunks.
    2. Moving a file to a new subject_path destroys the old vectors with stale
       ancestor_paths before the new ones (with correct paths) are written.
    """
    client.delete(
        collection_name=collection,
        points_selector=models.FilterSelector(
            filter=models.Filter(
                must=[
                    models.FieldCondition(
                        key="doc_id",
                        match=models.MatchValue(value=doc_id),
                    )
                ]
            )
        ),
    )


def upsert_chunks(
    client: QdrantClient,
    collection: str,
    doc_id: str,
    subject_path: str,
    source_file: str,
    source_type: str,
    chunks: list[dict],
    ingested_at: str,
    classification_level: str = "Internal",
    origin_source: str | None = None,
    jurisdiction: str | None = None,
) -> None:
    """Write a batch of chunks into Qdrant with full FGA metadata.

    Point IDs are deterministic: uuid5(NAMESPACE_URL, "{doc_id}:{chunk_index}")
    so re-ingesting the same file produces the same IDs (upsert = overwrite).
    """
    ancestor_paths = build_ancestor_paths(subject_path)
    points = [
        models.PointStruct(
            id=str(uuid.uuid5(uuid.NAMESPACE_URL, f"{doc_id}:{chunk['chunk_index']}")),
            vector=chunk["vector"],
            payload={
                "subject_path": subject_path,
                "ancestor_paths": ancestor_paths,
                "source_file": source_file,
                "source_type": source_type,
                "chunk_text": chunk["text"],
                "chunk_index": chunk["chunk_index"],
                "page_number": chunk.get("page_number"),
                "sheet_name": chunk.get("sheet_name"),
                "ingested_at": ingested_at,
                "doc_id": doc_id,
                "classification_level": classification_level,
                "origin_source": origin_source,
                "jurisdiction": jurisdiction,
            },
        )
        for chunk in chunks
    ]
    if points:
        client.upsert(collection_name=collection, points=points)
