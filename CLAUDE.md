# Enterprise SecureChat — Project Guide

Enterprise AI chat with RAG (document retrieval), FGA (fine-grained access control), and DLP (sensitive-data redaction). Every answer is drawn from indexed company documents, filtered by the user's role, and scrubbed of PII/financials before it reaches the browser.

## Architecture

```
Browser → Angular (Firebase Hosting / nginx:4200 local)
              ↓ /api/ proxy
        Spring Boot (:3000)
         ├─ JWT validation ← Auth0 (cloud)
         ├─ FGA lookup ──────────────────────── Neon (fga_registry DB)
         ├─ Embed call → Ingestion (:8001) /embed
         ├─ Parse call → Ingestion (:8001) /parse   ← /api/chat/verify only
         ├─ Ingest call → Ingestion (:8001) /ingest  ← /api/documents/ingest only
         ├─ Qdrant search (must_not filter) ── Qdrant (:6333)
         ├─ Claude API ──────────────────────── Anthropic cloud
         └─ DLP scrub → DLP service (:8000, internal-only)
```

**PostgreSQL runs on [Neon](https://neon.tech), not in Docker.** Identity is handled by Auth0 (cloud). The Angular frontend is on Firebase Hosting in production — not the Docker nginx container.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Frontend | Angular 17 (standalone) + Angular Material 17 |
| Backend | Spring Boot 3.3 / Java 21 |
| Identity | Auth0 (free tier, cloud) |
| Vector DB | Qdrant 1.9 (Docker local / Qdrant Cloud on GCP) |
| App DB | Neon (serverless PostgreSQL) |
| LLM | Claude `claude-sonnet-4-6` via Anthropic Messages API |
| Ingestion | Python 3.11 · sentence-transformers `all-MiniLM-L6-v2` · 384-dim vectors |
| DLP | Python 3.11 · FastAPI · Microsoft Presidio · spaCy `pt_core_news_lg` |

## Quick Start (local)

```bash
cp infra/.env.example infra/.env   # fill in credentials
# Paste infra/migrations/init.sql into the Neon SQL Editor once
cd infra && docker compose up -d
docker compose run --rm ingestion python -m src.main --manifest manifests/og-manifest.yaml
# Open http://localhost:4200 (run `npm start` in frontend/ separately)
```

See component guides for per-service dev commands:
- [backend/CLAUDE.md](backend/CLAUDE.md)
- [frontend/CLAUDE.md](frontend/CLAUDE.md)
- [ingestion/CLAUDE.md](ingestion/CLAUDE.md)
- [dlp-service/CLAUDE.md](dlp-service/CLAUDE.md)

See [docs/](docs/) for full specs, API contracts, cloud setup, and deployment:
- [docs/spec.md](docs/spec.md) — API contracts, data schemas, security model
- [docs/cloud.md](docs/cloud.md) — cloud services setup (Neon, Auth0, Anthropic, Qdrant)
- [docs/milestones.md](docs/milestones.md) — implementation history (M0–M18)
- [DEPLOYMENT.md](DEPLOYMENT.md) — GCP Cloud Run + Firebase deployment runbook

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
│   └── custom_recognizers/  financial_figures.py, og_rules.py
├── frontend/src/app/
│   ├── core/auth/      auth.interceptor.ts, auth.guard.ts
│   ├── core/services/  chat.service.ts, conversation-export.service.ts
│   ├── features/chat/  ChatComponent, BuUploadModalComponent, SourcePreviewDialogComponent
│   ├── features/admin/ AdminComponent (admin role only)
│   └── shared/pipes/   SafeMarkdownPipe (marked → DOMPurify → SafeHtml)
├── ingestion/
│   ├── src/
│   │   ├── parsers/        pdf_parser, excel_parser (xlsx + xls), image_parser (OCR por+eng)
│   │   ├── chunker.py      LangChain RecursiveCharacterTextSplitter (512 tok / 64 overlap)
│   │   ├── embedder.py     all-MiniLM-L6-v2
│   │   ├── qdrant_writer.py  upsert + delete_by_doc_id for idempotency
│   │   ├── classifier.py   extracts classification_level from doc metadata
│   │   ├── embed_api.py    Uvicorn FastAPI — POST /embed, POST /parse, POST /ingest
│   │   └── crawler.py      ANP E&P portal BFS scraper → /ingest API
│   └── manifests/og-manifest.yaml
├── infra/
│   ├── docker-compose.yml
│   ├── migrations/init.sql      Apply once via Neon SQL Editor
│   └── .env.example
└── docs/
    ├── spec.md · cloud.md · milestones.md · mermaid.md
```

## Critical Design Constraints

**Do not change these without understanding the reasoning:**

### 1. `RagService.chat()` is NOT `@Transactional`
External HTTP calls (embed ~5 s, Qdrant ~10 s, Claude ~60 s) happen inside this method. Annotating it `@Transactional` would hold a HikariCP connection open for the entire duration. With `maximum-pool-size: 5`, just five concurrent users would exhaust the pool. Each DB operation runs in its own short transaction via the injected services.

### 2. `/api/chat/stream` uses sentence-buffered SSE — `/api/chat` stays blocking
Regular chat uses `POST /api/chat/stream` (SSE, `text/event-stream`). Tokens from Claude are buffered by `SentenceBoundaryDetector` (ICU4J `pt_BR` `BreakIterator`); each confirmed sentence is DLP-scanned and emitted as an SSE `data:` event. A final `event: metadata` carries `conversationId`, sources, FGA flag, DLP count, and suggestions.

`/api/chat` (structured JSON) is kept for compatibility with `/api/chat/verify` and any client that needs a complete JSON payload. Do not remove it.

**Streaming constraints preserved:**
- FGA + Qdrant filter applied **before** streaming begins — constraint #3 is fully intact.
- SHA-256 audit log saved **before** streaming begins — constraint #4 is fully intact.
- `chatStream()` is NOT `@Transactional` — same HikariCP reasoning as constraint #1.
- DLP runs **per sentence**, not per token — Presidio NER accuracy is maintained because sentences carry enough context for entity boundary detection. True cross-sentence entity spans remain a known limitation of this approach.
- Suggestions are generated via a **separate non-streaming** `ClaudeService.complete()` call after the answer stream ends, then delivered in the closing metadata event. The messages list passed to that call must end with a `user` turn — the Anthropic API rejects assistant-final lists.

### 3. FGA enforces via Qdrant `must_not` filter — never in application code
`FgaService.buildQdrantFilter()` produces a `must_not` filter on the `ancestor_paths` payload field. This filter is applied at the Qdrant search layer, not by filtering results in Java. There is no code path to `/api/chat` that bypasses it.

### 4. Raw prompt is never stored
`AuditService.log()` stores `SHA-256(prompt)` in `restriction_audit_log.query_hash`. The raw text never touches the database. Do not add logging that writes `request.message()` to any store.

### 5. DLP service has no public port
`dlp-service` is on the `internal` Docker network only — no `ports:` mapping in docker-compose. The backend calls it at `http://dlp-service:8000`. The Angular app never calls it.

### 6. Qdrant `ancestor_paths` payload is the FGA contract
The ingestion pipeline writes `ancestor_paths: ["bu/santos", "bu/santos/reserves"]` for each chunk. `FgaService.buildQdrantFilter()` uses `must_not.match.any` on this field. If the field name changes in either place the security model silently breaks.

### 7. Ingestion container runs the embed API persistently — no `profiles:` key
The `ingestion` service in docker-compose has no profile so it starts with `docker compose up -d`. If the ingestion container is not running, all chat requests fail with `UnknownHostException: ingestion`.

### 8. DLP entity set — O&G domain, Portuguese NLP model
Runs `pt_core_news_lg` (not `en_core_web_lg`) so Brazilian basin/field names (Pré-Sal, Campos, Santos) are classified as LOC/GPE, not falsely flagged as PERSON. Do not remove `OG_VOLUMES` from `_DEFAULT_ENTITIES` in `analyzer.py` — it would silently stop redacting reserve figures.

### 9. `/api/chat/verify` — document text is never persisted
`RagService.chatWithDocument()` stores `request.message() + " [Attached: filename]"` as the user message. The raw document text extracted by `ParseClient` is injected into the Claude system prompt only and discarded afterwards. It never passes through `ConversationService`, `AuditService`, or any DB operation.

### 10. `ClaudeService.complete()` — `maxTokens` overload
The no-arg overload defaults to 1024 tokens (sufficient for regular chat). `/api/chat/verify` uses the two-arg overload with `maxTokens=2048`. Structured chat (AI suggestions) uses the three-arg overload with 1536. Do not increase the default — 1024 is intentional.

`/api/chat/stream` uses `streamComplete()` (1024 tokens) for the answer and `complete(..., 512)` for the follow-up suggestions call. 512 tokens is sufficient for a JSON array of 3 questions; do not reduce it — Claude may produce preamble text before the `[` which consumes part of the budget.

### 11. Crawler and manifest are two independent ingestion paths — do not merge
Both share the same `/ingest` endpoint. Never index crawler-managed files in the manifest or vice versa — duplicate vectors result if the same file gets different `bu_path` values via each path.

### 12. `POST /api/documents/ingest` — BU path is always server-side derived
`DocumentController.extractBuPath()` maps the caller's `GROUP_BU_xxx` authority to `bu/{name}/reserves`. Never add a `bu_path` request parameter — a user could index under another BU's path and bypass FGA.

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
| API identifier (audience) | `api.enpsecurechat.com` |
| Roles claim | `https://enpsecurechat.com/roles` |
| Frontend token caching | `localstorage` + `useRefreshTokens: true` |

O&G roles: `admin`, `employee`, `bu-user`, `reserves-management`, `reserves-coordination`, `reservoir-team`.

Roles injected via **Post-Login Action** into the `https://enpsecurechat.com/roles` claim on both ID and access tokens. `OgRolesAndGroupExtractor` reads this claim and produces `ROLE_admin`, `ROLE_employee`, etc.

## Environment Variables

| Variable | Used by | Notes |
|----------|---------|-------|
| `SPRING_DATASOURCE_URL` | backend | JDBC format with `sslmode=require` |
| `AUTH0_ISSUER_URI` | backend | Auth0 tenant URL |
| `AUTH0_AUDIENCE` | backend | Auth0 API identifier |
| `ANTHROPIC_API_KEY` | backend | `console.anthropic.com` |
| `QDRANT_API_KEY` | backend, ingestion | Set in Qdrant via `QDRANT__SERVICE__API_KEY` |
| `QDRANT_URL` | backend, ingestion | Cloud URL or `http://qdrant:6333` locally |
| `CRAWLER_MODE` | ingestion | `files` (default), `html`, or `all` |

All variables documented in `infra/.env.example`. Never commit `infra/.env`.
