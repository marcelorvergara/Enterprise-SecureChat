"""CLI entrypoint: python -m src.main --manifest manifests/og-manifest.yaml"""
import argparse
import os
from datetime import datetime, timezone
from pathlib import Path

from qdrant_client import QdrantClient

from .chunker import chunk_text
from .embedder import embed
from .manifest import doc_id_for, load_manifest
from .parsers.excel_parser import parse_excel
from .parsers.image_parser import parse_image
from .parsers.pdf_parser import parse_pdf
from .qdrant_writer import delete_by_doc_id, ensure_collection, upsert_chunks

_PARSERS = {
    ".pdf":  parse_pdf,
    ".xlsx": parse_excel,
    ".xls":  parse_excel,
    ".png":  parse_image,
    ".jpg":  parse_image,
    ".jpeg": parse_image,
    ".tiff": parse_image,
    ".tif":  parse_image,
}


def ingest_document(
    client: QdrantClient,
    collection: str,
    file_path: str,
    subject_path: str,
    ingested_at: str,
) -> int:
    """Parse, chunk, embed, and write a single document. Returns chunk count."""
    ext = Path(file_path).suffix.lower()
    parse_fn = _PARSERS.get(ext)
    if not parse_fn:
        print(f"  [SKIP] unsupported file type: {ext}")
        return 0

    doc_id = doc_id_for(file_path)

    # Delete existing vectors before upserting — handles both re-ingestion
    # (idempotency) and path moves (stale ancestor_paths removal).
    delete_by_doc_id(client, collection, doc_id)

    raw_pages = parse_fn(file_path)
    if not raw_pages:
        print(f"  [SKIP] no text extracted from {file_path}")
        return 0

    all_chunks: list[dict] = []
    for page in raw_pages:
        for text in chunk_text(page["text"]):
            all_chunks.append({
                "text": text,
                "page_number": page.get("page_number"),
                "sheet_name": page.get("sheet_name"),
            })

    if not all_chunks:
        print(f"  [SKIP] all pages produced empty chunks: {file_path}")
        return 0

    enriched = [
        {**chunk, "chunk_index": i, "vector": embed(chunk["text"])}
        for i, chunk in enumerate(all_chunks)
    ]

    upsert_chunks(
        client=client,
        collection=collection,
        doc_id=doc_id,
        subject_path=subject_path,
        source_file=Path(file_path).name,
        source_type=ext.lstrip("."),
        chunks=enriched,
        ingested_at=ingested_at,
    )
    return len(enriched)


def main() -> None:
    parser = argparse.ArgumentParser(description="Ingest documents into Qdrant")
    parser.add_argument("--manifest", required=True, help="Path to manifest YAML")
    args = parser.parse_args()

    manifest = load_manifest(args.manifest)
    collection: str = manifest.get("collection", "enterprise_knowledge")
    documents: list[dict] = manifest.get("documents", [])

    qdrant_url = os.getenv("QDRANT_URL", "http://localhost:6333")
    qdrant_api_key = os.getenv("QDRANT_API_KEY")
    client = QdrantClient(url=qdrant_url, api_key=qdrant_api_key or None)

    ensure_collection(client, collection)

    ingested_at = datetime.now(timezone.utc).isoformat()
    total_chunks = 0

    for doc in documents:
        file_path: str = doc["path"]
        subject_path: str = doc["subject_path"]

        if not Path(file_path).exists():
            print(f"[SKIP] file not found: {file_path}")
            continue

        print(f"[INGEST] {file_path} → {subject_path}")
        count = ingest_document(client, collection, file_path, subject_path, ingested_at)
        if count:
            print(f"  → {count} chunks written")
            total_chunks += count

    print(f"\nDone. {len(documents)} documents processed, {total_chunks} total chunks.")


if __name__ == "__main__":
    main()
