.PHONY: security-audit security-audit-fixtures help

# ── Security Audit ────────────────────────────────────────────────────────────
#
# Runs the FGA Classification Sync integration test against Qdrant cloud.
# Credentials must be available in the environment before calling make.
#
# Quick start:
#   source infra/.env && make security-audit
#
# What it does:
#   1. Generates XLSX test fixtures (Public / Internal / Confidential)
#   2. Starts the ingestion service on :8001 (requires Python deps installed)
#   3. Runs pytest -m integration, which ingests the fixtures, validates
#      Qdrant payloads, checks FGA isolation, and deletes the fixtures on exit
#   4. Kills the ingestion service regardless of test outcome

security-audit: security-audit-fixtures ## Run FGA classification security regression tests
	@if [ -z "$$QDRANT_URL" ] || [ -z "$$QDRANT_API_KEY" ]; then \
		echo "ERROR: QDRANT_URL and QDRANT_API_KEY must be exported."; \
		echo "       Run: source infra/.env && make security-audit"; \
		exit 1; \
	fi
	@echo "Starting ingestion service on :8001..."
	@cd ingestion && \
	  QDRANT_URL="$$QDRANT_URL" QDRANT_API_KEY="$$QDRANT_API_KEY" \
	  uvicorn src.embed_api:app --host 0.0.0.0 --port 8001 & \
	  UVICORN_PID=$$!; \
	  trap "echo 'Stopping ingestion service...'; kill $$UVICORN_PID 2>/dev/null" EXIT; \
	  echo "Waiting for model to load (this may take ~30 s on first run)..."; \
	  until curl -sf http://localhost:8001/health | python3 -c \
	      "import sys,json; sys.exit(0 if json.load(sys.stdin).get('model_loaded') else 1)" \
	      2>/dev/null; do sleep 3; done; \
	  echo "Ingestion service ready."; \
	  QDRANT_URL="$$QDRANT_URL" \
	  QDRANT_API_KEY="$$QDRANT_API_KEY" \
	  INGEST_URL=http://localhost:8001 \
	  pytest tests/test_classification_fga_integration.py -m integration -v --tb=short

security-audit-fixtures: ## Generate XLSX test fixtures (Public / Internal / Confidential)
	cd ingestion && python tests/generate_test_fixtures.py

help: ## Show available make targets
	@grep -E '^[a-zA-Z_-]+:.*##' $(MAKEFILE_LIST) | \
	  awk 'BEGIN {FS = ":.*##"}; {printf "  %-26s %s\n", $$1, $$2}'
