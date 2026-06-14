---
description: Run the ANP E&P regulatory crawler to discover and index content from the ANP portal. Supports three modes: files (default), html, or all.
---

Run the ANP regulatory crawler for this project.

Ask the user which mode they want if not specified:
- `files` (default) — downloads PDF, XLSX, XLS files found on ANP pages
- `html` — extracts editorial text from ANP HTML pages (Plone CMS selectors)
- `all` — both files and HTML page text (recommended for complete regulatory coverage)

Steps:
1. Confirm the ingestion service is running: `docker compose -f infra/docker-compose.yml ps ingestion`
2. Run the crawler with the chosen mode:
   ```bash
   cd infra && docker compose run --rm \
     -e INGEST_URL=http://ingestion:8001/ingest \
     ingestion python -m src.crawler --mode <MODE>
   ```
   (omit `--mode` for the default `files` mode)
3. Report how many files were indexed, how many were skipped (unchanged), and any errors.

Important notes:
- The crawler is rate-limited to 3 s between full downloads and 0.5 s between HEAD-only size probes.
- State is tracked in `ingestion/data/.crawler_state.json` — subsequent runs skip unchanged content.
- If the ingestion container image was rebuilt recently, restart it first: `docker compose -f infra/docker-compose.yml up -d --no-deps ingestion`
