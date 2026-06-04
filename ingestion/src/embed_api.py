import os
import tempfile
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

from src.parsers.pdf_parser import parse_pdf
from src.parsers.excel_parser import parse_excel
from src.parsers.image_parser import parse_image


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

# Module-level reference — populated during lifespan startup, not inside handlers.
# With Uvicorn's --workers N, each worker process runs this lifespan independently,
# loading the model once per process. All requests in a worker share that single load.
_model: SentenceTransformer | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _model
    _model = SentenceTransformer("sentence-transformers/all-MiniLM-L6-v2")
    yield
    _model = None


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
