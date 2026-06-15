FROM python:3.11-slim

# tesseract-ocr: image OCR
# Chromium system deps are listed explicitly because --with-deps fails on Debian
# (python:3.11-slim is Debian Bookworm; ttf-ubuntu-font-family is Ubuntu-only)
RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr \
    tesseract-ocr-por \
    libnss3 libnspr4 libdbus-1-3 \
    libatk1.0-0 libatk-bridge2.0-0 libatspi2.0-0 \
    libcups2 libdrm2 libgbm1 libxkbcommon0 \
    libxcomposite1 libxdamage1 libxfixes3 libxext6 libxrandr2 \
    libpango-1.0-0 libcairo2 libasound2 \
    fonts-liberation \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHONUNBUFFERED=1

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

RUN playwright install chromium

# Pre-download the embedding model into the image layer so startup is instant.
# The model (~90 MB) is baked here — no download at container start.
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2')"

COPY . .

# Default command: run the embed sidecar with at least 2 workers so the model
# stays loaded in memory across requests (one process = one model load).
# Override at runtime for one-shot ingestion:
#   docker compose --profile ingestion run --rm ingestion python -m src.main --manifest manifests/example-manifest.yaml
CMD uvicorn src.embed_api:app --host 0.0.0.0 --port ${EMBED_SERVICE_PORT:-8001} --workers ${UVICORN_WORKERS:-2}
