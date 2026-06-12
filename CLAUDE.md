# Enterprise SecureChat — Project Guide

Enterprise AI chat with RAG (document retrieval), FGA (fine-grained access control), and DLP (sensitive-data redaction). Every answer is drawn from indexed company documents, filtered by the user's role, and scrubbed of PII/financials before it reaches the browser.

## Architecture

```
Browser → Angular (nginx:4200)
              ↓ /api/ proxy
        Spring Boot (3000)
         ├─ JWT validation ← Auth0 (cloud)
         ├─ FGA lookup ──────────────────────── Neon (fga_registry DB)
         ├─ Embed call → Ingestion (8001) /embed
         ├─ Parse call → Ingestion (8001) /parse   ← /api/chat/verify only
         ├─ Ingest call → Ingestion (8001) /ingest  ← /api/documents/ingest only
         ├─ Qdrant search (must_not filter) ── Qdrant (6333)
         ├─ Claude API ──────────────────────── Anthropic cloud
         └─ DLP scrub → DLP service (8000, internal-only)
```

**PostgreSQL runs on [Neon](https://neon.tech), not in Docker.** There is no `postgres` service in docker-compose. The backend connects via `SPRING_DATASOURCE_URL` (JDBC format). Identity is handled by Auth0 (cloud) — no self-hosted identity container.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Frontend | Angular 17 (standalone) + Angular Material 17 |
| Backend | Spring Boot 3.3 / Java 21 |
| Identity | Auth0 (free tier, cloud) |
| Vector DB | Qdrant 1.9 (Docker) |
| App DB | Neon (serverless PostgreSQL) |
| LLM | Claude `claude-sonnet-4-6` via Anthropic Messages API |
| Ingestion | Python 3.11 · sentence-transformers `all-MiniLM-L6-v2` · 384-dim vectors |
| DLP | Python 3.11 · FastAPI · Microsoft Presidio · spaCy `pt_core_news_lg` |

## Quick Start

```bash
# 1. Copy and fill in credentials
cp infra/.env.example infra/.env

# 2. Apply DB schema once — paste infra/migrations/init.sql into the Neon SQL Editor
#    (fga_registry database)

# 3. Start all services
cd infra && docker compose up -d

# 4. Index documents (one-shot — ingestion service must already be up)
docker compose run --rm ingestion \
  python -m src.main --manifest manifests/og-manifest.yaml
```

## Per-Component Dev Commands

```bash
# Backend (Java 21 required)
cd backend
mvn spring-boot:run                  # dev server on :3000
mvn package -DskipTests              # build JAR

# Frontend
cd frontend
npm install
npm start                            # dev server on :4200 with /api proxy
npm run build                        # production build → dist/.../browser/

# DLP service
cd dlp-service
pip install -r requirements.txt && python -m spacy download en_core_web_lg
uvicorn src.main:app --host 0.0.0.0 --port 8000

# Ingestion / embed service
cd ingestion
pip install -r requirements.txt
python -m src.main --manifest manifests/og-manifest.yaml   # one-shot ingest
uvicorn src.embed_api:app --host 0.0.0.0 --port 8001            # persistent embed API

# ANP crawler (requires ingestion service to be running)
INGEST_URL=http://localhost:8001/ingest python -m src.crawler               # files only (default)
INGEST_URL=http://localhost:8001/ingest python -m src.crawler --mode html   # HTML page text only
INGEST_URL=http://localhost:8001/ingest python -m src.crawler --mode all    # files + HTML
# or via Docker:
# docker compose run --rm -e INGEST_URL=http://ingestion:8001/ingest ingestion python -m src.crawler --mode html
```

## Project Structure

```
Enterprise-SecureChat/
├── backend/src/main/java/com/enterprise/securechat/
│   ├── audit/          RestrictionAuditLog entity + AuditService (SHA-256 hashing)
│   ├── config/         RestClientConfig (5 typed RestClient beans), SecurityConfig
│   ├── conversation/   Conversation + Message entities, ConversationService/Controller
│   ├── fga/            FgaService — restriction lookup + Qdrant filter builder
│   ├── health/         HealthController
│   ├── rag/            RagController, RagService, ParseClient, EmbedClient, IngestClient,
│   │                   DocumentController, QdrantSearchClient, ClaudeService, DlpClient, dto/
│   └── security/       OgRolesAndGroupExtractor (pulls `https://enpsecurechat.com/roles` from Auth0 JWT)
├── dlp-service/src/
│   ├── main.py         FastAPI: POST /dlp/analyze, GET /health
│   ├── analyzer.py     Presidio engines (module-level singletons)
│   └── custom_recognizers/financial_figures.py
├── frontend/src/app/
│   ├── core/auth/      auth.interceptor.ts, auth.guard.ts (Auth0 `@auth0/auth0-angular`)
│   ├── core/services/  chat.service.ts
│   ├── features/chat/  ChatComponent (Material shell + custom CSS bubbles), BuUploadModalComponent
│   ├── features/admin/ AdminComponent (stub, guarded by adminGuard)
│   └── shared/pipes/   SafeMarkdownPipe (marked → DOMPurify → SafeHtml)
├── ingestion/
│   ├── src/
│   │   ├── parsers/        pdf_parser, excel_parser (xlsx + xls), image_parser (OCR por+eng)
│   │   ├── chunker.py      LangChain RecursiveCharacterTextSplitter (512 tok / 64 overlap)
│   │   ├── embedder.py     all-MiniLM-L6-v2
│   │   ├── qdrant_writer.py  upsert + delete_by_doc_id for idempotency
│   │   ├── embed_api.py    Uvicorn FastAPI — POST /embed, POST /parse, POST /ingest (2 pre-forked workers)
│   │   └── crawler.py      ANP E&P portal BFS scraper → /ingest API; --mode html extracts page text via Plone selectors + breadcrumb context
│   ├── data/
│   │   ├── bu/
│   │   │   ├── santos/reserves/    BU Santos reserves docs    (subject_path: bu/santos/reserves)
│   │   │   ├── campos/reserves/    BU Campos reserves docs    (subject_path: bu/campos/reserves)
│   │   │   └── solimoes/reserves/  BU Solimoes reserves docs  (subject_path: bu/solimoes/reserves)
│   │   ├── regulatory/
│   │   │   └── bar-questions/      ANP/BAR regulatory content (subject_path: bar-questions)
│   │   └── corporate-answers/      ANP crawler output (subject_path: corporate-answers)
│   │   └── .crawler_state.json     SHA-256 state map — tracks what the crawler has already ingested
│   └── manifests/
│       └── og-manifest.yaml        O&G document index — maps each file to its subject_path
├── infra/
│   ├── docker-compose.yml
│   ├── migrations/init.sql      Apply once via Neon SQL Editor
│   └── .env.example
└── docs/
    ├── plan.md · spec.md · cloud.md · mermaid.md
```

## Critical Design Constraints

**Do not change these without understanding the reasoning:**

### 1. `RagService.chat()` is NOT `@Transactional`
External HTTP calls (embed ~5 s, Qdrant ~10 s, Claude ~60 s) happen inside this method. Annotating it `@Transactional` would hold a HikariCP connection open for the entire duration. With `maximum-pool-size: 5`, just five concurrent users would exhaust the pool. Each DB operation runs in its own short transaction via the injected services.

### 2. `/api/chat` is blocking (non-streaming)
The DLP service requires the complete Claude answer to detect entities that span sentence boundaries. Streaming token-by-token breaks Presidio's NER models. When streaming is added in a future milestone, a sentence-buffer flush approach is required.

### 3. FGA enforces via Qdrant `must_not` filter — never in application code
`FgaService.buildQdrantFilter()` produces a `must_not` filter on the `ancestor_paths` payload field. This filter is applied at the Qdrant search layer, not by filtering search results in Java. There is no code path to `/api/chat` that bypasses it.

### 4. Raw prompt is never stored
`AuditService.log()` stores `SHA-256(prompt)` in `restriction_audit_log.query_hash`. The raw text never touches the database. Do not add logging that writes `request.message()` to any store.

### 5. DLP service has no public port
`dlp-service` is on the `internal` Docker network only — no `ports:` mapping in docker-compose. The backend calls it at `http://dlp-service:8000`. The Angular app never calls it.

### 6. Qdrant `ancestor_paths` payload is the FGA contract
The ingestion pipeline writes `ancestor_paths: ["bu/santos", "bu/santos/reserves"]` for each chunk. `FgaService.buildQdrantFilter()` uses `must_not.match.any` on this field. If the field name changes in either place the security model silently breaks.

### 7. Ingestion container runs the embed API persistently — no `profiles:` key
The `ingestion` service in docker-compose has **no profile** so it starts with `docker compose up -d` and keeps the `/embed` endpoint available at `:8001`. The backend calls this for every user prompt. If the ingestion container is not running, all chat requests fail with `UnknownHostException: ingestion`. One-shot document indexing is done with `docker compose run --rm ingestion python -m src.main --manifest ...` (no `--profile` flag needed).

### 9. `/api/chat/verify` — document text is never persisted
`RagService.chatWithDocument()` stores `request.message() + " [Attached: filename]"` as the user message. The raw document text extracted by `ParseClient` is injected into the Claude system prompt for that single request only and discarded afterwards. It never passes through `ConversationService`, `AuditService`, or any DB operation. The same DLP pass that covers `/api/chat` also covers `/api/chat/verify` — raw Claude output does not leave the backend.

### 10. `ClaudeService.complete()` — `maxTokens` overload
The no-arg overload defaults to 1024 tokens (sufficient for regular chat). `/api/chat/verify` calls the two-arg overload with `maxTokens=2048` so compliance comparison reports are not truncated. Do not increase the default — 1024 is intentional for chat responses.

### 12. `POST /api/documents/ingest` — BU path is always server-side derived
`DocumentController.extractBuPath()` maps the caller's `GROUP_BU_xxx` authority to `bu/{name}/reserves`. The client never sends a `bu_path` parameter. Do not add a `bu_path` request parameter — a user could index a document under another BU's subject path and bypass the FGA model.

### 11. Crawler and manifest are two independent ingestion paths — do not merge
The crawler (`src/crawler.py`) sends files to `POST /ingest` at runtime; the manifest pipeline (`src/main.py`) reads a YAML file at startup. Both share the same `/ingest` endpoint and the same `delete_by_doc_id` + upsert idempotency guarantee (`doc_id = uuid5(NAMESPACE_URL, f"{bu_path}/{filename}")`). Documents that arrive from the crawler are tracked in `data/.crawler_state.json` (SHA-256 hash per filename). If this file is deleted, the next crawler run re-ingests everything from scratch — safe but slow. Never index crawler-managed files in the manifest or vice versa; the two paths can produce duplicate vectors if the same file is given different `bu_path` values by each path.

HTML mode (`--mode html` / `--mode all`) uses the same state file but a separate key namespace: `html::{slug}` vs bare filename strings for files. The SHA-256 is computed over the **extracted text** (not raw HTML) so nav-chrome changes on gov.br do not trigger spurious re-ingests. HTML pages are always routed to `corporate-answers` regardless of URL keywords.

File entries in `.crawler_state.json` are stored as `{filename: {"sha": "hex", "size": N}}`. On subsequent runs, the crawler sends a HEAD request to check `Content-Length` before downloading — if the remote size matches `size`, the file is skipped immediately (0.5 s pause) without any download. HTML entries remain plain `{html::slug: sha_str}` strings (no size probe possible since content is extracted from fetched HTML). State entries written by older crawler versions (`{filename: sha_str}` without a size) are migrated transparently on the first run: a HEAD request populates the size without re-downloading.

### 8. DLP entity set — O&G domain, Portuguese NLP model
The DLP service runs `pt_core_news_lg` (not `en_core_web_lg`) so Brazilian geological basin/field names (Pré-Sal, Campos, Santos) are classified as LOC/GPE by spaCy's NER, not falsely flagged as PERSON. Industry acronyms (FPSO, LNG, PETROBRAS, etc.) are additionally allowlisted in `_PERSON_ALLOWLIST`.

Active entity types and their source files:

| Entity | File | Covers |
|---|---|---|
| `FINANCIAL_FIGURE` | `financial_figures.py` | Currency symbols, currency-code amounts, magnitude phrases, bare comma-grouped numbers (≥1 comma group, score 0.75) |
| `OG_VOLUMES` | `og_rules.py` | Reserve/production volumes: boe, MMboe, Mboe, bbl, bbl/d, m³/d |
| `ANP_PROCESS` | `og_rules.py` | Ofício Nº, Carta Nº, Processo SEI, bare SEI number |
| `RESERVES_VARIATION` | `og_rules.py` | Signed % + `variação/variações`; `fator de recuperação / recovery factor` |
| `INVESTMENT_YEAR` | `og_rules.py` | Year after investment keywords (fixed-length lookbehinds); year ranges fire only near context words (base score 0.30 < threshold) |
| `OG_CONTRACT` | `og_rules.py` | Contract end date/year (`prazo do contrato`, `término`, `vencimento`); `limite econômico / economic limit`; Cessão Onerosa / Transfer of Rights percentages |
| `COMMODITY_PRICE` | `og_rules.py` | `/bbl` and `/MMBtu` prices; labeled `preço do barril`, `preço de venda do gás`, `barrel price`, `gas price` |
| `DATE_TIME` | SpacyRecognizer | Document dates via spaCy NER — no custom recognizer needed |

`OG_VOLUMES` was previously registered but absent from `_DEFAULT_ENTITIES` (dead code); this is fixed. Do not remove `OG_VOLUMES` from `_DEFAULT_ENTITIES` again — it would silently stop redacting reserve figures.

## Key Configuration (application.yml)

```yaml
spring.security.oauth2.resourceserver.jwt.issuer-uri:  ${AUTH0_ISSUER_URI:https://dev-ll8lyragj23p2c7l.us.auth0.com/}
spring.security.oauth2.resourceserver.jwt.audiences:   ${AUTH0_AUDIENCE:api.enpsecurechat.com}
dlp-service.url:      ${DLP_SERVICE_URL:http://dlp-service:8000}
embed-service.url:    ${EMBED_SERVICE_URL:http://ingestion:8001}
qdrant.url:           ${QDRANT_URL:http://qdrant:6333}
anthropic.model:      ${CLAUDE_MODEL:claude-sonnet-4-6}
hikari.maximum-pool-size: 5   # Neon free tier limit
```

## Auth0 Tenant

Tenant: `dev-ll8lyragj23p2c7l.us.auth0.com` — SPA application + custom API.

| Setting | Value |
|---------|-------|
| Application type | Single Page Application |
| Allowed callback / logout / origins | `http://localhost:4200`, `https://enpsecurechat.com` |
| API identifier (audience) | `api.enpsecurechat.com` |
| Allow Offline Access | Enabled (issues refresh tokens) |
| Frontend token caching | `localstorage` + `useRefreshTokens: true` |

Roles injected via **Post-Login Action** (`Actions → Triggers → post-login`) into the `https://enpsecurechat.com/roles` claim on both ID and access tokens. `OgRolesAndGroupExtractor` reads this claim and produces `ROLE_admin`, `ROLE_employee`, etc.

O&G roles: `admin`, `employee`, `bu-user`, `reserves-management`, `reserves-coordination`, `reservoir-team`.

## Implementation Status

| Milestone | Status | Description |
|-----------|--------|-------------|
| **M0** | ✅ Complete | `infra/docker-compose.yml`, `init.sql`, Keycloak realm export, `.env.example`, `docs/` |
| **M1** | ✅ Complete | Spring Boot core: JWT security, `RolesExtractor`, `FgaService`, `HealthController` |
| **M2** | ✅ Complete | Python ingestion pipeline: PDF/Excel/image parsers, chunker, embedder, Qdrant writer, `/embed` API |
| **M3** | ✅ Complete | RAG orchestrator: `RagService`, `ClaudeService`, `QdrantSearchClient`, `EmbedClient`, conversation persistence, SHA-256 audit log |
| **M4** | ✅ Complete | DLP microservice (FastAPI + Presidio), `DlpClient`, wired into `RagService` after Claude response. NLP model: `pt_core_news_lg`. O&G entity set: `FINANCIAL_FIGURE`, `OG_VOLUMES`, `ANP_PROCESS`, `RESERVES_VARIATION`, `INVESTMENT_YEAR`, `OG_CONTRACT`, `COMMODITY_PRICE`, `DATE_TIME`. |
| **M5** | ✅ Complete | Angular 17 frontend: Auth0 OIDC (`@auth0/auth0-angular`), JWT interceptor, Material shell, chat UI, SafeMarkdownPipe, admin stub |
| **M6** | ✅ Complete | `AdminController` (restriction CRUD, `@PreAuthorize("hasRole('admin')")`), Bucket4j rate limiting (20 req/min per `sub`), security headers, Angular admin panel |
| **M7** | ✅ Complete | JUnit 5 + Mockito backend tests (`FgaServiceTest`, `RagServiceTest`), pytest ingestion tests (chunker, ancestor_paths, deterministic IDs), pytest DLP tests (FINANCIAL_FIGURE recognizer + redaction), root `README.md` |
| **M8** | ✅ Complete | Document Verification: ingestion `POST /parse` endpoint (PDF/Excel/image/text), `ParseClient`, `RagService.chatWithDocument()`, `POST /api/chat/verify` (multipart), `ClaudeService` maxTokens overload (2048), Angular file attachment UI with chip strip |
| **M9** | ✅ Complete | ANP Regulatory Crawler: `ingestion/src/crawler.py` — BFS scraper (html depth≤4 / files depth≤2) over ANP E&P portal, SHA-256 state tracking, `subject_path` routing by URL keyword (`reserva`/`recursos`/`bar` → `bar-questions`; else → `corporate-answers`), `.xls` support via xlrd, Portuguese OCR (`por+eng`), 3 s rate limiting, 300 s ingest timeout for scanned PDFs |
| **M10** | ✅ Complete | Crawler HTML mode: `--mode html` / `--mode all` flags — extracts editorial text from ANP pages using Plone CMS selectors (`#content-core`, `.documentContent`, `#region-content`, `main`), strips nav/footer boilerplate, prepends breadcrumb hierarchy as RAG context (`Categoria: X > Y`), posts as `.txt` to `/ingest`. State key namespace `html::{slug}` keeps HTML and file entries separate. `CRAWLER_MODE` env var controls mode when triggered via the `/crawl` API endpoint. |
| **M11** | ✅ Complete | BU Document Self-Ingest: `POST /api/documents/ingest` (multipart) — allows `bu-user`, `reserves-management`, and `reserves-coordination` roles to permanently index documents directly into Qdrant. BU path is derived server-side from the caller's `GROUP_BU_xxx` authority (never client-supplied). `IngestClient` delegates to ingestion service `POST /ingest`. Angular `BuUploadModalComponent` (cloud_upload button in chat input, hidden from other roles). Accepted types: PDF, XLSX, XLS, PNG, JPG, JPEG, TIFF, TXT, MD, CSV. |
| **M12** | ✅ Complete | Conversation Deletion: `DELETE /api/conversations/{id}` — ownership-validated (403 if caller's `user_sub` doesn't match), cascades to messages via DB `ON DELETE CASCADE`, returns 204. `ConversationService.delete()` is `@Transactional` (single short DB transaction, no external calls). Sidebar delete button (`delete_outline` icon, hover-reveal via CSS, `matListItemMeta` position) triggers a `MatDialog` confirmation; on confirm, the entry is removed from the local state array immediately and the router navigates away if the deleted conversation is active. Snackbar error feedback on API failure. Three new `ConversationServiceTest` cases (success, 403, 404). Pre-existing `RagServiceTest` regressions fixed (`eq(5)` → `anyInt()`, lenient `@BeforeEach` authority stub). |
| **M13** | ✅ Complete | Source Deep-Link Previews: `chunkId` (Qdrant point UUID) added to `SourceCitation` DTO and populated from `hit.id()` in `RagService` (both `chat()` and `chatWithDocument()`). `QdrantSearchClient.getPoint()` performs direct point lookup via `GET /collections/{col}/points/{id}`. New `SourcePreviewResponse` DTO. New `GET /api/conversations/{id}/sources/{chunkId}` in `ConversationController` — ownership check + FGA prefix validation on chunk `ancestor_paths` (mirrors search-time `must_not` filter) → 403 if blocked. New standalone `SourcePreviewDialogComponent` — spinner/403-error/text states, metadata chips (filename, path, page, sheet), scrollable chunk body, copy-filename footer. Citation chip click replaced from clipboard-copy to `openSourcePreview()` (`open_in_new` icon). `getConversation()` added to `ChatService` to fetch conversation title for export. |
| **M14** | ✅ Complete | Export Conversation to PDF/Markdown: New `ConversationExportService` — `exportMarkdown()` builds a `.md` string with all messages and deduplicated Sources appendix, triggers Blob download; `exportPdf()` builds print-optimized HTML (serif font, `[REDACTED]` styled in amber, `page-break-inside: avoid`) and opens a browser print window that auto-closes on `afterprint`. No new npm dependencies. `download` icon button added to composer (hidden when `messages.length === 0`) with `mat-menu`: "Download as Markdown" / "Print as PDF". Conversation title fetched on history load via `GET /api/conversations/{id}` and used as the export filename/heading. |
| **M15** | ✅ Complete | Enterprise Document Classification Sync: New `ingestion/src/classifier.py` extracts `classification_level` ("Public" \| "Internal" \| "Confidential") from PDF keywords (`fitz.metadata`), XLSX properties (`openpyxl.properties`), or subject_path routing for crawler content; defaults safely to "Internal". Field added to Qdrant payload + KEYWORD index across all three ingestion paths (manifest pipeline, `POST /ingest` BU self-upload, ANP crawler). `FgaService` extended with three-tier clearance hierarchy (`TIER_PUBLIC=0`, `TIER_INTERNAL=1`, `TIER_CONFIDENTIAL=2`), `ROLE_CLEARANCE` map (admin/reserves-coordination/reserves-management → Confidential; reservoir-team/bu-user/employee → Internal), new `getBlockedClassifications(roles)` method, and overloaded `buildQdrantFilter(paths, blockedClassifications)` that appends a `classification_level` `must_not` clause — all filtering at Qdrant layer (Constraint #3). `RagService.chat()` and `chatWithDocument()` both call the new overload. Sprint 1 source preview endpoint (`GET /api/conversations/{id}/sources/{chunkId}`) extended to also 403 on classification mismatch. `og-manifest.yaml` stamped with explicit classification per document. Test suite: 20 new pytest tests (`TestExtractClassification`, `TestClassifyBySubjectPath`, `TestUpsertChunks`), 5 new JUnit tests in `FgaServiceTest`. Synthetic fixture generator script (`ingestion/tests/generate_test_fixtures.py`) creates PDF + XLSX files at all three levels. |

## Environment Variables Reference

| Variable | Used by | Notes |
|----------|---------|-------|
| `SPRING_DATASOURCE_URL` | backend | JDBC format with `sslmode=require` |
| `AUTH0_ISSUER_URI` | backend | Auth0 tenant URL, e.g. `https://dev-xxx.us.auth0.com/` |
| `AUTH0_AUDIENCE` | backend | Auth0 API identifier, e.g. `api.enpsecurechat.com` |
| `ANTHROPIC_API_KEY` | backend | `console.anthropic.com` |
| `QDRANT_API_KEY` | backend, ingestion | Set in Qdrant via `QDRANT__SERVICE__API_KEY` |
| `QDRANT_URL` | backend, ingestion | Cloud URL or `http://qdrant:6333` locally |
| `CRAWLER_MODE` | ingestion | Crawler mode when triggered via `/crawl` API: `files` (default), `html`, or `all` |

All variables are documented in `infra/.env.example`. Never commit `infra/.env`.
