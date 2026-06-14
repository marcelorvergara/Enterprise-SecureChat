---
description: Index documents into Qdrant using the manifest pipeline or re-index a specific BU. Runs the one-shot ingestion pipeline via docker compose.
---

Run the document ingestion pipeline for this project.

Steps:
1. Confirm the ingestion service is running: `docker compose -f infra/docker-compose.yml ps ingestion` — if it shows "Exit" or is absent, run `docker compose -f infra/docker-compose.yml up -d ingestion` first.
2. Run the one-shot manifest pipeline:
   ```bash
   cd infra && docker compose run --rm ingestion python -m src.main --manifest manifests/og-manifest.yaml
   ```
3. Report how many documents were indexed and whether any errors occurred.

If the user specifies a different manifest or a custom path, use that instead of `og-manifest.yaml`.

Note: ingestion is idempotent — re-running never creates duplicate vectors (`delete_by_doc_id` runs before every upsert).
