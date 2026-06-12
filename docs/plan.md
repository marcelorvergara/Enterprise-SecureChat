# Enterprise SecureChat — Implementation Plan

## Context
A side project to build a production-grade Enterprise AI Chat with:
- **RAG** (Retrieval-Augmented Generation) over company documents (PDFs, Excel, images)
- **FGA** (Fine-Grained Authorization) using a hierarchical subject-path restriction tree — restricting a parent path (e.g. `finance`) automatically blocks all children (`finance/payroll`, `finance/budgets`)
- **Dynamic DLP** (Data Loss Prevention) — Presidio redacts sensitive values (prices, PII) from LLM answers before they reach the UI
- **Identity layer** — Keycloak (Docker) emulates enterprise AD/IIQ, issuing JWTs with role claims
- The architecture diagram is already documented in `docs/mermaid.txt`

Chosen stack: **Angular** frontend, **Spring Boot (Java)** backend, **Claude API** (claude-sonnet-4-6) as LLM, **Keycloak** for identity, **Neon (serverless PostgreSQL)** for FGA registry and conversations, **Qdrant** as vector DB, **Python** for ingestion + DLP (Presidio).

**Database hosting decision:** PostgreSQL runs on [Neon](https://neon.tech) (free tier) instead of a local Docker container. This removes the `postgres` service from Docker Compose entirely and makes the database accessible from any environment. Two Neon databases are needed in one project: `fga_registry` (app data) and `keycloak` (Keycloak's internal schema). The `init.sql` schema is applied once via the Neon SQL Editor (or a one-time migration script) rather than automatically on container startup.

---

## Repository Structure (target)

```
Enterprise-SecureChat/
├── docs/
│   ├── mermaid.txt                    (exists — architecture diagram)
│   ├── plan.md                        This plan (persisted here for team reference)
│   ├── spec.md                        Technical specification (schemas, API contracts, security model)
│   └── cloud.md                       Cloud services setup guide (Neon, Anthropic, future deployment)
├── frontend/                          Angular 17+ standalone
├── backend/                           Spring Boot 3.x (Java 21)
├── ingestion/                         Python 3.11 pipeline
├── dlp-service/                       Python FastAPI + Presidio
└── infra/
    ├── docker-compose.yml             (postgres service removed — Neon replaces it)
    ├── keycloak/realm-export.json
    ├── migrations/init.sql            One-time schema applied via Neon SQL Editor
    └── .env.example
```

---

## Core Schemas

### PostgreSQL (FGA Registry + Conversations)

```sql
-- infra/postgres/init.sql
CREATE TABLE roles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  role_name TEXT UNIQUE NOT NULL
);

CREATE TABLE role_restrictions (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  role_name    TEXT NOT NULL REFERENCES roles(role_name) ON DELETE CASCADE,
  subject_path TEXT NOT NULL,   -- e.g. "finance/payroll"
  reason       TEXT,
  created_at   TIMESTAMPTZ DEFAULT now(),
  UNIQUE (role_name, subject_path)
);
CREATE INDEX idx_restrictions_role ON role_restrictions(role_name);

CREATE TABLE restriction_audit_log (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_sub        TEXT NOT NULL,
  role_names      TEXT[] NOT NULL,
  restricted_paths TEXT[] NOT NULL,
  query_hash      TEXT NOT NULL,    -- SHA-256 of prompt, never raw text
  accessed_at     TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE conversations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_sub TEXT NOT NULL,
  created_at TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE messages (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  role            TEXT NOT NULL CHECK (role IN ('user','assistant')),
  content         TEXT NOT NULL,
  sources         JSONB,
  created_at      TIMESTAMPTZ DEFAULT now()
);
```

### Qdrant Payload per Document Chunk

```json
{
  "subject_path": "finance/payroll",
  "ancestor_paths": ["finance", "finance/payroll"],
  "source_file": "payroll-2026.xlsx",
  "source_type": "excel",
  "chunk_text": "...",
  "chunk_index": 3,
  "page_number": null,
  "ingested_at": "2026-06-04T00:00:00Z",
  "doc_id": "uuid-stable-hash"
}
```

**FGA prefix filter trick:** `ancestor_paths` stores all path ancestors. Restricting `"finance"` means any doc with `"finance"` in `ancestor_paths` is excluded — no recursive logic needed. This is a single Qdrant `must_not.match.any` filter.

---

## REST API Contracts

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/health` | none | Liveness probe |
| POST | `/api/chat` | JWT | RAG query (main endpoint) |
| GET | `/api/conversations` | JWT | List user's conversations |
| GET | `/api/conversations/{id}/messages` | JWT | Conversation history |
| DELETE | `/api/conversations/{id}` | JWT | Delete a conversation (owner-only; cascades to messages) |
| GET | `/api/admin/roles` | admin JWT | List all roles + restrictions |
| POST | `/api/admin/roles/{role}/restrictions` | admin JWT | Add restriction |
| DELETE | `/api/admin/roles/{role}/restrictions/{path}` | admin JWT | Remove restriction |

Internal (Docker network only): `POST /dlp/analyze` on `dlp-service:8000`

---

## Milestones

### M0 — Infrastructure + Documentation (1–2 days)
**Goal:** All Docker services healthy before any app code. Neon databases provisioned. Project docs written.

Files to create:
- `infra/docker-compose.yml` — services: `keycloak`, `qdrant`, `dlp-service`, `backend`, `frontend` (**no postgres service** — replaced by Neon); networks: `internal` + `frontend-net`
- `infra/migrations/init.sql` — full schema (applied once manually in Neon SQL Editor, not on container start)
- `infra/keycloak/realm-export.json` — realm `enterprise-securechat`, client `securechat-frontend` (public OIDC), client `securechat-backend` (confidential), roles: `admin`, `employee`, `finance-analyst`, `hr-manager`
- `infra/.env.example` — all required env vars including `NEON_APP_URL` and `NEON_KEYCLOAK_URL`
- `docs/plan.md` — this plan, persisted for team reference
- `docs/spec.md` — technical specification (see outline below)
- `docs/cloud.md` — cloud setup guide (see outline below)

**docs/spec.md outline:**
- System overview and goals
- Architecture diagram reference (`mermaid.txt`)
- Component responsibilities (frontend, backend, ingestion, DLP, identity)
- Data schemas (PostgreSQL tables, Qdrant payload structure)
- API contracts (all REST endpoints with request/response shapes)
- Security model (FGA prefix filtering, JWT validation, DLP redaction)
- Design constraints (non-streaming Phase 1, blocking DLP, Neon scale-to-zero behaviour)
- Performance targets (DLP latency < 500ms, embed call timeout 5s)

**docs/cloud.md outline:**
- Neon: account creation, project setup, two-database structure, connection string format, applying `init.sql`, free tier limits
- Anthropic API: getting an API key, model used (`claude-sonnet-4-6`), rate limits and cost estimate
- Keycloak: Docker-hosted locally, Neon as its backing DB, realm import steps
- Qdrant: Docker-hosted locally, dashboard access, future cloud migration path
- Full `.env` variable reference with descriptions

Verify: Keycloak OIDC discovery doc reachable, Qdrant `/healthz` returns OK, Neon tables exist (query via Neon SQL Editor).

---

### M1 — Spring Boot Backend Core (2–3 days)
**Goal:** JWT-validated API with FGA restriction lookup. No RAG yet.

Key packages: `spring-boot-starter-security`, `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-security-oauth2-resource-server` (for JWKS validation against Keycloak).

Key classes to create under `backend/src/main/java/com/enterprise/securechat/`:
- `security/JwtSecurityConfig.java` — configures JWT decoder pointing to Keycloak JWKS URI
- `security/RolesExtractor.java` — extracts `realm_access.roles` from JWT claims
- `fga/FgaService.java` — `getRestrictedPaths(List<String> roles)` queries Postgres, `buildQdrantFilter(List<String> paths)` produces Qdrant JSON filter
- `health/HealthController.java`

JWT config points to: `${keycloak.url}/realms/enterprise-securechat/protocol/openid-connect/certs`

Verify: `GET /api/health` → 200, invalid JWT → 401, valid JWT → 200 (stub), FGA lookup returns correct restricted paths.

---

### M2 — Python Ingestion Pipeline (2–3 days)
**Goal:** Index documents into Qdrant with FGA metadata.

Files under `ingestion/src/`:
- `parsers/pdf_parser.py` — PyMuPDF, chunks per page then by size
- `parsers/excel_parser.py` — openpyxl, row-based chunks with column headers as prefix
- `parsers/image_parser.py` — Pillow + pytesseract OCR
- `chunker.py` — LangChain `RecursiveCharacterTextSplitter` (512 tokens, 64 overlap)
- `embedder.py` — `sentence-transformers/all-MiniLM-L6-v2` (384 dims, runs locally)
- `qdrant_writer.py` — Qdrant Python client, creates collection on first run, upserts with payload; **must include `delete_by_doc_id(doc_id)`** to purge stale vectors before re-ingesting a moved/renamed file
- `manifest.py` — reads YAML manifest mapping file paths to `subject_path`

Ingestion manifest format:
```yaml
collection: enterprise_knowledge
documents:
  - path: data/finance/q3-report.pdf
    subject_path: finance/reports
  - path: data/hr/org-chart.png
    subject_path: hr/org
```

**Idempotency:** deterministic point IDs via `uuid5(NAMESPACE_URL, f"{doc_id}:{chunk_index}")`.

**Vector lifecycle / path moves:** When a document's `subject_path` changes, the old vectors retain stale `ancestor_paths` in Qdrant. The ingestion script must call `delete_by_doc_id(doc_id)` (Qdrant `delete` with payload filter `doc_id == X`) before upserting the new vectors. The manifest can include an optional `previous_path` field to signal a move; the CLI will detect this and trigger the delete automatically.

Also expose `POST /embed` endpoint from this service (Uvicorn, **minimum 2 pre-forked workers** so the model stays warm) so the Spring Boot backend can embed user prompts using the same model without a Python dep in Java.

Verify: Qdrant dashboard shows correct chunk count + `ancestor_paths` field, re-running manifest doesn't increase count; after a path move, old `ancestor_paths` entries are gone and new ones are correct.

---

### M3 — RAG Orchestrator + FGA Enforcement (3–4 days)
**Goal:** `/api/chat` performs real FGA-filtered RAG. The security model is enforced here.

> **Design constraint (non-streaming):** `/api/chat` is intentionally **blocking** — the backend waits for Claude to finish generating the complete answer before passing it to the DLP service and returning to the client. Presidio's NER models require full sentence context to accurately detect entities; streaming tokens one-by-one would break redaction. Streaming can be added later via a sentence-buffer approach (hold tokens until punctuation, flush each sentence to DLP, stream the redacted chunk). For Phase 1, keep it simple and blocking.

New classes in Spring Boot:
- `rag/RagController.java` — `POST /api/chat`
- `rag/RagService.java` — orchestration: embed prompt → build Qdrant filter → search → assemble Claude prompt → call Claude API → return (DLP in M4)
- `rag/QdrantClient.java` — HTTP calls to Qdrant REST API with FGA `must_not` filter
- `rag/ClaudeService.java` — Anthropic SDK wrapper (model: `claude-sonnet-4-6`)
- `conversation/ConversationService.java` — store/retrieve message history from Postgres

RAG orchestration steps:
1. Extract roles from JWT → call `FgaService.getRestrictedPaths()`
2. POST user prompt to ingestion service `/embed` → get vector
3. Qdrant search with `must_not: [{ key: "ancestor_paths", match: { any: [restrictedPaths] }}]`
4. Assemble system prompt: `"Answer using ONLY the provided context. [chunks...]"`
5. Call Claude API with conversation history + context as system prompt
6. Return draft answer (raw for now; DLP added in M4)

Log to `restriction_audit_log` on every request (SHA-256 of prompt, not raw text).

Claude API call:
```java
// Use direct HTTP (RestTemplate/WebClient) to https://api.anthropic.com/v1/messages
// model: "claude-sonnet-4-6"   ← Claude 4.x Sonnet (current generation, correct ID)
// system: assembled context prompt
// messages: last 10 conversation turns + current user message
// NOTE: claude-sonnet-4-6 is correct. Do NOT use claude-3-5-sonnet-latest
//       which is the previous generation (Claude 3.5, not Claude 4.x).
```

**Embed service call config in Spring Boot** (`application.yml`):
```yaml
embed-service:
  url: ${EMBED_SERVICE_URL:http://localhost:8001}
  connect-timeout: 2000   # ms — fail fast if ingestion container is down
  read-timeout: 5000      # ms — embedding a prompt should complete well under 5s
```
The Uvicorn workers for the embed service are pre-forked (min 2) so the `all-MiniLM-L6-v2` model is always loaded in memory and first-request latency is eliminated.

Verify: finance-analyst with `finance/payroll` restriction cannot get payroll data; admin (no restrictions) can; sources array reflects which chunks were used.

---

### M4 — DLP Microservice (1–2 days)
**Goal:** Presidio redacts sensitive values from LLM answers before they leave the backend.

Files under `dlp-service/src/`:
- `main.py` — FastAPI app with `POST /dlp/analyze`
- `analyzer.py` — Presidio `AnalyzerEngine` + `AnonymizerEngine`; NLP engine: `pt_core_news_lg`
- `custom_recognizers/financial_figures.py` — `FINANCIAL_FIGURE`: currency symbols, currency-code amounts, magnitude phrases, bare comma-grouped numbers
- `custom_recognizers/og_rules.py` — O&G domain recognizers: `OG_VOLUMES`, `ANP_PROCESS`, `RESERVES_VARIATION`, `INVESTMENT_YEAR`, `OG_CONTRACT`, `COMMODITY_PRICE`

Default entities redacted: `PERSON`, `EMAIL_ADDRESS`, `PHONE_NUMBER`, `CREDIT_CARD`, `DATE_TIME`, `FINANCIAL_FIGURE`, `OG_VOLUMES`, `ANP_PROCESS`, `RESERVES_VARIATION`, `INVESTMENT_YEAR`, `OG_CONTRACT`, `COMMODITY_PRICE`.

Anonymization: replace with `[REDACTED]` (not fake values).

In `RagService.java`, after Claude responds: call `dlp-service:8000/dlp/analyze` with draft answer, return `cleaned_text` to client. Include `dlp_entities_redacted` count in response.

DLP service is internal-only — no external port in docker-compose.

Verify: answer containing `$125,000` → `[REDACTED]`; `450 MMboe` → `[REDACTED]`; `prazo do contrato: 31/12/2035` → `[REDACTED]`; non-sensitive answers pass through unchanged; DLP latency < 500ms.

---

### M5 — Angular Frontend (3–4 days)
**Goal:** Functional chat UI with Keycloak OIDC login, conversation history, source citations.

Key files:
- `app.config.ts` — `APP_INITIALIZER` for `keycloak-js`, `onLoad: 'login-required'`
- `core/auth/token.interceptor.ts` — adds `Authorization: Bearer` to all API calls, refreshes token if expiring in < 30s
- `core/auth/auth.guard.ts` — route guard checking role claims
- `features/chat/chat.component.ts` — text input (Enter = submit, Shift+Enter = newline), streaming-ready display, source citation pills, lock icon when `fga_applied: true`
- `features/admin/restrictions/` — role-gated admin panel for restriction management

Use `marked` + `DOMPurify` for rendering markdown in answers (XSS-safe).

nginx config: `try_files $uri /index.html` for SPA routing, proxy `/api/` to backend.

Verify: login redirects to Keycloak, answer displays with sources collapsed, lock icon for restricted users, admin panel hidden from non-admins.

---

### M6 — Admin Panel, Audit & Hardening (2–3 days)
**Goal:** Complete admin CRUD for restrictions, rate limiting, security headers.

Add to Spring Boot:
- `admin/AdminController.java` — restriction CRUD endpoints; protected by `@PreAuthorize("hasRole('admin')")`
- Keycloak Admin REST API integration (service account via client credentials) for role enumeration
- `@RateLimiter` on `/api/chat`: 20 req/min per `sub` claim (Bucket4j or similar)
- `Helmet`-equivalent security headers via `WebMvcConfigurer`

Add to Angular admin panel: restriction matrix table, add/remove forms calling admin endpoints.

Verify: add restriction via UI → immediate effect on next chat; audit log accumulates entries; 25th rapid chat request returns 429.

---

### M7 — Tests, Documentation & Polish (2–3 days)
**Goal:** Tests at each layer, README, operational runbook.

Tests:
- **Spring Boot** (`JUnit 5` + `Testcontainers`): `FgaServiceTest` — verify `buildQdrantFilter` for various restriction trees; `RagServiceTest` — mock Qdrant + Claude, verify orchestration; auth e2e — valid/invalid/expired JWT scenarios
- **Python** (`pytest`): each parser with known input → expected chunk output; ingestion idempotency; Presidio custom recognizer patterns
- **Angular** (`Jest` + Angular Testing Library): token interceptor adds header; chat component renders answer + sources

README sections: Prerequisites, Quick Start, Architecture, Configuration Reference, Adding Documents, Managing Restrictions, Development Guide.

---

## Verification — End-to-End Test

1. `docker compose up -d`
2. `docker compose run ingestion python -m src.main --manifest manifests/example-manifest.yaml`
3. Open `http://localhost:4200` → Keycloak login page
4. Login as `finance-analyst` → ask "What are the Q3 payroll figures?" → answer contains `[REDACTED]` for amounts, lock icon shown
5. Login as `admin` → same question → full answer with sources, no restrictions
6. Add restriction via admin panel → immediately affects next query

---

## Critical Files

| File | Why it's critical |
|------|------------------|
| `infra/docker-compose.yml` | Backbone — everything depends on it (no postgres service, uses Neon) |
| `infra/migrations/init.sql` | Defines FGA + conversation schemas — applied once in Neon SQL Editor |
| `docs/spec.md` | Technical spec — canonical reference for schemas, API contracts, security model |
| `docs/cloud.md` | Cloud setup guide — Neon, Anthropic API, step-by-step env configuration |
| `backend/…/fga/FgaService.java` | Core security logic: restriction lookup + Qdrant filter builder |
| `backend/…/rag/RagService.java` | Full request pipeline orchestration |
| `ingestion/src/qdrant_writer.py` | Establishes `ancestor_paths` payload schema — must match what `FgaService` filters against |
| `dlp-service/src/analyzer.py` | DLP engine — custom recognizers define what gets redacted |
