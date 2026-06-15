# Ingestion Service — Python 3.11

## Dev Commands

```bash
pip install -r requirements.txt

# Persistent embed/parse/ingest API (must be running for the backend to function)
uvicorn src.embed_api:app --host 0.0.0.0 --port 8001

# One-shot document indexing (ingestion service must already be up)
python -m src.main --manifest manifests/og-manifest.yaml

# Regulatory crawlers — --source selects the target site (default: anp)
# CRAWLER_SOURCE env var is the equivalent for Cloud Run Jobs
INGEST_URL=http://localhost:8001/ingest python -m src.crawler --source anp                       # ANP PDFs/XLSX (default)
INGEST_URL=http://localhost:8001/ingest python -m src.crawler --source anp --mode html           # ANP HTML pages
INGEST_URL=http://localhost:8001/ingest python -m src.crawler --source anp --mode all            # ANP files + HTML
INGEST_URL=http://localhost:8001/ingest python -m src.crawler --source mme                       # MME PDFs/XLSX
INGEST_URL=http://localhost:8001/ingest python -m src.crawler --source mme --mode html           # MME HTML pages
INGEST_URL=http://localhost:8001/ingest python -m src.crawler --source epe                       # EPE PDFs (Playwright)

# Tests
pytest tests/                          # unit tests (no external services needed)
pytest tests/ -m integration           # requires QDRANT_URL + QDRANT_API_KEY env vars
make security-audit                    # full FGA/classification integration gate (from project root)
```

## Source Layout

```
src/
├── parsers/
│   ├── pdf_parser.py      PyMuPDF + Tesseract OCR fallback (por+eng)
│   ├── excel_parser.py    openpyxl (xlsx) + xlrd (xls), 30-row cap, header prefix
│   └── image_parser.py    Pillow + pytesseract OCR (por+eng)
├── crawlers/
│   ├── base.py            BaseCrawler ABC — shared HTTP, state, /ingest posting
│   │                      PloneMixin — Plone CMS HTML extraction (gov.br portals)
│   ├── anp.py             ANPCrawler — BFS over ANP E&P portal; BAR files → Confidential
│   ├── mme.py             MMECrawler — BFS over MME O&G secretariat; all Public
│   └── epe.py             EPECrawler — Playwright paginates listing (10 pages), plain
│                          requests finds PDF link on each detail page; all Public
├── chunker.py             LangChain RecursiveCharacterTextSplitter (512 tok / 64 overlap)
├── embedder.py            all-MiniLM-L6-v2 (384-dim, loaded once per Uvicorn worker)
├── qdrant_writer.py       upsert + delete_by_doc_id for idempotency
├── classifier.py          extracts classification_level (Public/Internal/Confidential)
├── embed_api.py           Uvicorn FastAPI — POST /embed, POST /parse, POST /ingest
│                          (2 pre-forked workers so model stays warm)
├── crawler.py             CLI entry point — --source anp|mme|epe, CRAWLER_SOURCE env var
└── main.py                one-shot manifest pipeline entry point
```

## API Endpoints (port 8001)

| Endpoint | Used by | Description |
|----------|---------|-------------|
| `POST /embed` | Spring Boot backend | Embeds a user prompt; returns 384-dim vector |
| `POST /parse` | Spring Boot backend | Parses an uploaded file ephemerally; returns extracted text (never stored) |
| `POST /ingest` | Crawler, `DocumentController` | Full pipeline: parse → chunk → embed → upsert into Qdrant with FGA metadata |
| `GET /health` | Docker healthcheck | Returns `{"status":"ok"}` |

## Crawler State Files

Each source maintains its own state file under `data/`:

| Crawler | State file |
|---------|-----------|
| ANP | `data/.crawler_state_anp.json` |
| MME | `data/.crawler_state_mme.json` |
| EPE | `data/.crawler_state_epe.json` |

State entry formats (same structure for all three):
- **File entries**: `{filename: {"sha": SHA-256(bytes), "size": Content-Length, "etag": ..., "last_modified": ...}}`
  - HEAD request compares ETag / Last-Modified / size against stored values — match skips re-download in 0.5 s
  - Older entries without the extra fields are migrated transparently on first run
- **HTML entries** (ANP and MME only): `{html::{slug}: SHA-256(extracted_text)}`
  - Hashed over extracted editorial text, not raw HTML — nav-chrome changes don't trigger re-ingests

Deleting a state file triggers a full re-ingest for that source on the next run — safe but slow. In Cloud Run Jobs, state files persist in the GCS bucket (`enp-securechat-crawler-state`) mounted at `/app/data`.

## Classification Levels

`classifier.py` assigns `classification_level` to every chunk:

| Source | How determined |
|--------|---------------|
| PDF | `fitz.metadata` keywords |
| XLSX | `openpyxl` document properties |
| Crawler HTML content | always `"Public"` |
| BU self-uploaded docs | defaults to `"Internal"` unless metadata says otherwise |

The three tiers map to Spring's `FgaService` clearance model:
- `"Confidential"` → visible only to `admin`, `reserves-coordination`, `reserves-management`
- `"Internal"` → visible to all roles except `employee` (public-only clearance)
- `"Public"` → visible to all authenticated users

## Critical Constraints

- The crawler (`src/crawler.py`) and manifest pipeline (`src/main.py`) are **independent paths** — never index the same file via both. Duplicate vectors result if the same file gets different `bu_path` values via each path.
- `ancestor_paths` field name in Qdrant payload must stay in sync with `FgaService.buildQdrantFilter()` in the backend. Changing it in either place silently breaks FGA.
- `classification_level` must be present on every chunk — the backend classification tier check depends on it.
- When the ingestion container image is rebuilt, restart it before running the crawler: `docker compose up -d --no-deps ingestion`. The crawler container and the ingestion service container are separate; the crawler sees the new image but the service still runs the old code until restarted.

## Test Fixtures

`tests/generate_test_fixtures.py` creates synthetic PDF + XLSX files at all three classification levels. Run it once before the integration tests if `tests/fixtures/` is empty.

`pytest.ini` registers the `integration` marker with `--strict-markers`. Bare `pytest` skips integration tests and prints a skip notice when `QDRANT_URL`/`QDRANT_API_KEY` are absent.
