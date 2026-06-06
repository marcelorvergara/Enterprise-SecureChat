import os
import tempfile
import uuid
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from pathlib import Path

from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from pydantic import BaseModel
from qdrant_client import QdrantClient
from sentence_transformers import SentenceTransformer

from src.parsers.pdf_parser import parse_pdf
from src.parsers.excel_parser import parse_excel
from src.parsers.image_parser import parse_image
from src.chunker import chunk_text
from src.qdrant_writer import ensure_collection, delete_by_doc_id, upsert_chunks


def _parse_text(path: str) -> list[dict]:
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        return [{"text": f.read()}]


_PARSE_DISPATCH = {
    ".pdf": parse_pdf,
    ".xlsx": parse_excel,
    ".xls": parse_excel,
    ".png": parse_image,
    ".jpg": parse_image,
    ".jpeg": parse_image,
    ".tiff": parse_image,
    ".tif": parse_image,
    ".txt": _parse_text,
    ".md": _parse_text,
    ".csv": _parse_text,
}

_model: SentenceTransformer | None = None
_qdrant: QdrantClient | None = None
_collection: str = os.getenv("QDRANT_COLLECTION", "enterprise_knowledge")


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _model, _qdrant
    _model = SentenceTransformer("sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
    qdrant_url = os.getenv("QDRANT_URL", "http://qdrant:6333")
    qdrant_api_key = os.getenv("QDRANT_API_KEY")
    _qdrant = QdrantClient(url=qdrant_url, api_key=qdrant_api_key or None)
    ensure_collection(_qdrant, _collection)
    yield
    _model = None
    _qdrant = None


app = FastAPI(title="Embed Sidecar", lifespan=lifespan)


class EmbedRequest(BaseModel):
    text: str


class EmbedResponse(BaseModel):
    vector: list[float]


@app.post("/embed", response_model=EmbedResponse)
def embed(request: EmbedRequest) -> EmbedResponse:
    vector = _model.encode(request.text, normalize_embeddings=True).tolist()
    return EmbedResponse(vector=vector)


@app.get("/health")
def health() -> dict:
    return {"status": "ok", "model_loaded": _model is not None}


class ParseResponse(BaseModel):
    text: str
    filename: str


@app.post("/parse", response_model=ParseResponse)
async def parse(file: UploadFile = File(...)) -> ParseResponse:
    filename = file.filename or "upload"
    ext = Path(filename).suffix.lower()
    parse_fn = _PARSE_DISPATCH.get(ext)
    if not parse_fn:
        raise HTTPException(status_code=400, detail=f"Unsupported file type: {ext}")
    content = await file.read()
    with tempfile.NamedTemporaryFile(delete=False, suffix=ext) as tmp:
        tmp.write(content)
        tmp_path = tmp.name
    try:
        pages = parse_fn(tmp_path)
    finally:
        os.unlink(tmp_path)
    text = "\n\n---\n\n".join(p["text"] for p in pages if p.get("text"))
    return ParseResponse(text=text, filename=filename)


class IngestResponse(BaseModel):
    status: str
    chunks: int
    path: str


@app.post("/ingest", response_model=IngestResponse)
async def ingest(
    file: UploadFile = File(...),
    bu_path: str = Form(...),
) -> IngestResponse:
    """Permanently index a BU-uploaded document into Qdrant under the given bu_path.

    The bu_path (e.g. "bu/campos/reserves") is set by the Spring Boot backend
    based on the user's Keycloak group — never trusted from the client directly.
    """
    filename = file.filename or "upload"
    ext = Path(filename).suffix.lower()
    parse_fn = _PARSE_DISPATCH.get(ext)
    if not parse_fn:
        raise HTTPException(status_code=400, detail=f"Unsupported file type: {ext}")

    content = await file.read()
    with tempfile.NamedTemporaryFile(delete=False, suffix=ext) as tmp:
        tmp.write(content)
        tmp_path = tmp.name

    try:
        pages = parse_fn(tmp_path)
    finally:
        os.unlink(tmp_path)

    if not pages:
        raise HTTPException(status_code=422, detail="No text could be extracted from the file.")

    # Deterministic doc_id: same file + same bu_path always produces the same ID,
    # so re-uploading replaces existing vectors (upsert idempotency via delete_by_doc_id).
    doc_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"{bu_path}/{filename}"))

    all_chunks: list[dict] = []
    for page in pages:
        page_text = page.get("text", "")
        if not page_text.strip():
            continue
        for text in chunk_text(page_text):
            all_chunks.append({
                "text": text,
                "page_number": page.get("page_number"),
                "sheet_name": page.get("sheet_name"),
            })

    if not all_chunks:
        raise HTTPException(status_code=422, detail="File produced no indexable chunks.")

    enriched = [
        {
            **chunk,
            "chunk_index": i,
            "vector": _model.encode(chunk["text"], normalize_embeddings=True).tolist(),
        }
        for i, chunk in enumerate(all_chunks)
    ]

    delete_by_doc_id(_qdrant, _collection, doc_id)
    upsert_chunks(
        client=_qdrant,
        collection=_collection,
        doc_id=doc_id,
        subject_path=bu_path,
        source_file=filename,
        source_type=ext.lstrip("."),
        chunks=enriched,
        ingested_at=datetime.now(timezone.utc).isoformat(),
    )

    return IngestResponse(status="indexed", chunks=len(enriched), path=bu_path)
