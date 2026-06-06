from sentence_transformers import SentenceTransformer

_model: SentenceTransformer | None = None


def _get_model() -> SentenceTransformer:
    global _model
    if _model is None:
        _model = SentenceTransformer("sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2")
    return _model


def embed(text: str) -> list[float]:
    """Embed a single text string into a 384-dim normalised float vector."""
    return _get_model().encode(text, normalize_embeddings=True).tolist()
