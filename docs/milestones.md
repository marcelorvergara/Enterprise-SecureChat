# Enterprise SecureChat — Implementation History

Complete record of delivered milestones. All milestones are complete as of M20.

For current architecture, constraints, and API contracts see [CLAUDE.md](../CLAUDE.md), [spec.md](spec.md), and [cloud.md](cloud.md).

---

## Original Plan (M0–M7)

| Milestone | Description |
|-----------|-------------|
| **M0** | `infra/docker-compose.yml`, `init.sql`, `.env.example`, `docs/` scaffolding |
| **M1** | Spring Boot core: JWT security, `RolesExtractor`, `FgaService`, `HealthController` |
| **M2** | Python ingestion pipeline: PDF/Excel/image parsers, chunker, embedder, Qdrant writer, `/embed` API |
| **M3** | RAG orchestrator: `RagService`, `ClaudeService`, `QdrantSearchClient`, `EmbedClient`, conversation persistence, SHA-256 audit log |
| **M4** | DLP microservice (FastAPI + Presidio), `DlpClient`, wired into `RagService` after Claude response. NLP model: `pt_core_news_lg`. O&G entity set: `FINANCIAL_FIGURE`, `OG_VOLUMES`, `ANP_PROCESS`, `RESERVES_VARIATION`, `INVESTMENT_YEAR`, `OG_CONTRACT`, `COMMODITY_PRICE`, `DATE_TIME`. |
| **M5** | Angular 17 frontend: Auth0 OIDC (`@auth0/auth0-angular`), JWT interceptor, Material shell, chat UI, SafeMarkdownPipe, admin stub |
| **M6** | `AdminController` (restriction CRUD, `@PreAuthorize("hasRole('admin')")`), Bucket4j rate limiting (20 req/min per `sub`), security headers, Angular admin panel |
| **M7** | JUnit 5 + Mockito backend tests (`FgaServiceTest`, `RagServiceTest`), pytest ingestion tests (chunker, ancestor_paths, deterministic IDs), pytest DLP tests (FINANCIAL_FIGURE recognizer + redaction), root `README.md` |

## Post-Plan Sprints

| Sprint | Stories | Milestones | Outcome |
|--------|---------|-----------|---------|
| **Sprint 1 — User Trust & Mobility** | Source Deep-Link Previews (8 SP) · Export to PDF/Markdown (8 SP) | M13, M14 | Citation chips open Qdrant chunk previews; full conversation export to Markdown/Print-PDF |
| **Sprint 2 — Compliance Moat** | Enterprise Document Classification Sync (13 SP) | M15 | Three-tier clearance (Public / Internal / Confidential) enforced at Qdrant layer via `classification_level` `must_not` clause |
| **Sprint 3 — Engagement & Insights** | AI Suggested Follow-ups (8 SP) · Admin Security Heatmap (13 SP) | M16, M17 | Structured JSON from Claude with DLP-scanned chip suggestions; Chart.js bar charts for FGA block frequency and DLP redaction density |
| **Sprint 4 — Regulatory Data Expansion** | Multi-Source Crawler (13 SP) | M19 | `BaseCrawler` + `PloneMixin` refactor; `--source` flag; MMECrawler (gov.br Plone); EPECrawler (Playwright JS-paginated listing); three Cloud Run Jobs with staggered weekly schedules |
| **Sprint 5 — LLM Observability** | Bespoke Telemetry (ADR-002) (8 SP) | M20 | Fire-and-forget `llm_telemetry` logging from `RagService`; `GET /internal/llm-metrics` shared-secret endpoint polled by `monitoring-links` |

---

## Detailed Milestone Notes

### M8 — Document Verification
Ingestion `POST /parse` endpoint (PDF/Excel/image/text), `ParseClient`, `RagService.chatWithDocument()`, `POST /api/chat/verify` (multipart), `ClaudeService` maxTokens overload (2048), Angular file attachment UI with chip strip.

### M9 — ANP Regulatory Crawler
`ingestion/src/crawler.py` — BFS scraper (html depth≤4 / files depth≤2) over ANP E&P portal, SHA-256 state tracking, `subject_path` routing by URL keyword (`reserva`/`recursos`/`bar` → `bar-questions`; else → `corporate-answers`), `.xls` support via xlrd, Portuguese OCR (`por+eng`), 3 s rate limiting, 300 s ingest timeout for scanned PDFs.

### M10 — Crawler HTML Mode
`--mode html` / `--mode all` flags — extracts editorial text from ANP pages using Plone CMS selectors (`#content-core`, `.documentContent`, `#region-content`, `main`), strips nav/footer boilerplate, prepends breadcrumb hierarchy as RAG context (`Categoria: X > Y`), posts as `.txt` to `/ingest`. State key namespace `html::{slug}`. `CRAWLER_MODE` env var controls mode when triggered via the `/crawl` API endpoint.

### M11 — BU Document Self-Ingest
`POST /api/documents/ingest` (multipart) — allows `bu-user`, `reserves-management`, and `reserves-coordination` roles to permanently index documents into Qdrant. BU path derived server-side from `GROUP_BU_xxx` authority. `IngestClient` delegates to ingestion service `POST /ingest`. Angular `BuUploadModalComponent`. Accepted types: PDF, XLSX, XLS, PNG, JPG, JPEG, TIFF, TXT, MD, CSV.

### M12 — Conversation Deletion
`DELETE /api/conversations/{id}` — ownership-validated (403 if `user_sub` doesn't match), cascades to messages via DB `ON DELETE CASCADE`, returns 204. Sidebar delete button with `MatDialog` confirmation; router navigates away if the deleted conversation is active. Three new `ConversationServiceTest` cases.

### M13 — Source Deep-Link Previews
`chunkId` (Qdrant point UUID) added to `SourceCitation` DTO. `QdrantSearchClient.getPoint()` performs direct point lookup. New `GET /api/conversations/{id}/sources/{chunkId}` — ownership check + FGA prefix validation + classification check → 403 if blocked. `SourcePreviewDialogComponent` — spinner/403-error/text states, metadata chips, scrollable chunk body.

### M14 — Export Conversation to PDF/Markdown
`ConversationExportService` — `exportMarkdown()` builds `.md` string with messages and deduplicated Sources appendix; `exportPdf()` builds print-optimized HTML and opens a browser print window that auto-closes on `afterprint`. No new npm dependencies. Download icon with `mat-menu`: "Download as Markdown" / "Print as PDF".

### M15 — Enterprise Document Classification Sync
New `ingestion/src/classifier.py` extracts `classification_level` ("Public" | "Internal" | "Confidential") from PDF keywords, XLSX properties, or subject_path routing. Field added to Qdrant payload + KEYWORD index. `FgaService` extended with three-tier clearance hierarchy and `getBlockedClassifications(roles)`. `buildQdrantFilter` overloaded to append a `classification_level` `must_not` clause. 20 new pytest tests, 5 new JUnit tests. Synthetic fixture generator at `ingestion/tests/generate_test_fixtures.py`.

### M16 — AI-Powered Suggested Follow-ups
Claude instructed via `JSON_FORMAT_INSTRUCTION` to return `{"answer":"...","suggestions":[...]}`. `parseClaudeResponse()` strips JSON bounds before parsing; falls back to raw text + empty suggestions on failure. Each suggestion independently DLP-scanned. `STRUCTURED_CHAT_MAX_TOKENS = 1536`. Angular `<mat-chip-set>` row for suggestions; clicking a chip populates and submits the input. `ng2-charts@6` + `chart.js@4.4.0` installed with `--legacy-peer-deps`; `"skipLibCheck": true` in `tsconfig.json`.

### M17 — Admin Security Heatmap
`RestrictionAuditLogRepository.findTopRestrictedPaths()` — native PostgreSQL `unnest + COUNT(*)` returning top 20 blocked paths. `MessageRepository.findDlpDensityByDay()` — native `DATE_TRUNC('day') + SUM(dlp_redacted)` for last 30 days. `GET /api/admin/metrics/security-heatmap`. Angular admin panel adds two `<canvas baseChart>` charts in a CSS grid. `AdminControllerTest` with 3 security cases.

### M19 — Multi-Source Regulatory Crawler
`ingestion/src/crawlers/` package: `BaseCrawler` ABC (shared HTTP, SHA/ETag state, `/ingest` posting, BFS run loop) + `PloneMixin` (gov.br Plone HTML extraction — breadcrumb prefix, boilerplate stripping, slug-keyed HTML state). `ANPCrawler` refactored onto `BaseCrawler`; classification unchanged (BAR files → Confidential, HTML pages → Public). `MMECrawler` added — same Plone stack as ANP, all content Public, `subject_path: regulatory/mme`, `jurisdiction: br/mme`. `EPECrawler` added — Playwright headless Chromium paginates the Liferay JS-driven listing (up to 10 pages, ~100 pubs), plain `requests` finds the PDF link on each detail page; `subject_path: regulatory/epe`, all Public. `crawler.py` updated to a thin shim: `--source anp|mme|epe` CLI flag + `CRAWLER_SOURCE` env var. Dockerfile updated: Chromium system libs installed via `apt-get` (avoids Ubuntu-only `--with-deps` failure on Debian), `playwright install chromium` baked into the image layer. Three Cloud Run Jobs deployed: `anp-crawler-job` (512 Mi), `mme-crawler-job` (512 Mi), `epe-crawler-job` (1 Gi for Playwright). Per-source state files (`.crawler_state_{source}.json`) in the shared GCS bucket. Weekly Cloud Scheduler triggers staggered at 03:00, 04:00, 05:00 BRT on Mondays.

### M18 — FGA Classification Security Audit (CI/CD)
`ingestion/tests/test_classification_fga_integration.py` — full pytest refactor with `pytest.mark.integration`, session-scoped `ingested_points` fixture, 4 test classes (`TestPayloadAudit`, `TestLowClearanceIsolation`, `TestHighClearanceElevation`, `TestIdEnumerationAttack`). `.github/workflows/security-audit.yml` — triggers on push/PR to `main` for `ingestion/src/**` or `backend/**/fga/**` changes; caches HuggingFace model; polls `/health`, runs pytest. `Makefile` at project root — `make security-audit` and `make security-audit-fixtures`.

### M20 — LLM Observability (ADR-002)
Bespoke async telemetry instead of a managed platform (Langfuse/Phoenix) — logs into the app's existing Neon database, aggregated by `monitoring-links` and rendered on the `mvergara.net` Mission Control dashboard, same shape as its existing `pipeline_health` panel. New `llm_telemetry` table (`infra/migrations/002_llm_telemetry.sql`). New `telemetry` package: `LlmTelemetryService` (its own `@Component`, not a `RagService` method, to avoid the `@Async` self-invocation proxy trap), `LlmCostEstimator` (chars/4 token heuristic + per-model USD/1K pricing table), `LlmTelemetryRepository` (native-query 24h aggregate projection), `InternalMetricsController`. Bounded `llmTelemetryExecutor` bean (core 2 / max 4 / queue 100, `DiscardPolicy`) added to `RestClientConfig`. `RagService.chat()`, `chatWithDocument()`, and `chatStream()` dispatch telemetry via `CompletableFuture.runAsync(...)` around their Claude calls, recording latency/tokens/cost/success/error without ever blocking the request thread or calling `.get()`/`.join()`. `GET /internal/llm-metrics` returns snake_case 24h aggregates (`requests_24h`, `avg_latency_ms`, `tokens_24h`, `cost_usd_24h`, `error_rate_pct`) matching the cross-repo contract with `monitoring-links` exactly, gated by a constant-time `X-Internal-Key` check against `INTERNAL_METRICS_KEY` instead of Auth0 JWT (`SecurityConfig` permits `/internal/**`). The controller fails fast at boot (`IllegalStateException`) if the secret is blank — `MessageDigest.isEqual` on two empty byte arrays returns `true`, so without that guard a misconfigured empty secret would silently authenticate any request sending an empty `X-Internal-Key` header. Deployed to Cloud Run via a new `INTERNAL_METRICS_KEY` Secret Manager entry (`DEPLOYMENT.md` Phase 1 + Phase 4 updated); `backend.yml`'s bare `gcloud run deploy` inherits it on future redeploys with no workflow change needed.
