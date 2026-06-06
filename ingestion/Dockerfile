FROM python:3.11-slim

# tesseract-ocr: required by pytesseract for image OCR
RUN apt-get update && apt-get install -y \
    tesseract-ocr \
    tesseract-ocr-por \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pre-download the embedding model into the image layer so startup is instant.
# The model (~90 MB) is baked here — no download at container start.
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2')"

COPY . .

# Default command: run the embed sidecar with at least 2 workers so the model
# stays loaded in memory across requests (one process = one model load).
# Override at runtime for one-shot ingestion:
#   docker compose --profile ingestion run --rm ingestion python -m src.main --manifest manifests/example-manifest.yaml
CMD uvicorn src.embed_api:app --host 0.0.0.0 --port ${EMBED_SERVICE_PORT:-8001} --workers ${UVICORN_WORKERS:-2}
