from contextlib import asynccontextmanager

from fastapi import FastAPI
from pydantic import BaseModel
from sentence_transformers import SentenceTransformer

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
