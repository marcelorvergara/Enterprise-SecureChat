# Enterprise SecureChat — Technical Specification

## 1. System Overview

Enterprise SecureChat is an AI-powered company knowledge assistant with built-in information security. Employees ask questions in natural language and receive answers synthesised from company documents (PDFs, Excel files, images). Every response is filtered through two independent security layers:

- **FGA (Fine-Grained Authorization):** documents the user is not allowed to see are excluded from the search before the AI ever reads them — making information leakage physically impossible, not just policy-dependent.
- **DLP (Data Loss Prevention):** sensitive values (prices, salaries, PII) in any answer are automatically replaced with `[REDACTED]` before reaching the browser.

Architecture diagram: see [mermaid.txt](mermaid.txt).

---

## 2. Goals and Non-Goals

**Goals**
- Answer questions using company knowledge stored in a vector database
- Enforce hierarchical role-based document restrictions at the database retrieval layer
- Redact sensitive data from answers regardless of which documents were retrieved
- Authenticate users via an enterprise identity provider (Keycloak / OIDC)
- Provide an admin panel to manage role restrictions without redeploying

**Non-Goals (Phase 1)**
- Real-time streaming responses (blocked by DLP requirement — see §8)
- Multi-tenant isolation (single company deployment)
- Permanent document ingestion via the chat UI (documents for the knowledge base are indexed via the CLI pipeline; the chat UI supports ephemeral one-time verification only — see `POST /api/chat/verify`)
- Fine-grained per-document ACLs (restrictions are path-prefix based)

---

## 3. Component Responsibilities

| Component | Technology | Responsibility |
|-----------|-----------|----------------|
| **Frontend** | Angular 17+ | Chat UI, Keycloak OIDC login, source citations, admin panel |
| **Backend** | Spring Boot 3.x (Java 21) | JWT validation, FGA lookup, RAG orchestration, Claude API, DLP proxy |
| **Identity** | Keycloak 24 (Docker) | OIDC token issuer, user/role management, AD emulation |
| **FGA Registry** | Neon PostgreSQL | Stores role → subject_path restriction mappings and audit logs |
| **Vector DB** | Qdrant 1.9 (Docker) | Stores document chunk embeddings with FGA metadata for filtered search |
| **Ingestion** | Python 3.11 | Parses PDFs/Excel/images/text, chunks, embeds, upserts into Qdrant with FGA metadata; exposes `/parse` for ephemeral document text extraction and `/ingest` for crawler-driven indexing; `crawler.py` auto-discovers ANP regulatory documents |
| **DLP Service** | Python FastAPI + Presidio | Scans LLM answers for PII and sensitive values, replaces with `[REDACTED]` |
| **LLM** | Claude API (claude-sonnet-4-6) | Generates natural language answers from retrieved context |

---

## 4. Data Schemas

### 4.1 PostgreSQL (Neon — `fga_registry` database)

```sql
-- Roles known to the system
roles(id UUID, role_name TEXT UNIQUE, created_at TIMESTAMPTZ)

-- Hierarchical path-prefix restrictions
-- A row means: this role cannot see documents whose subject_path starts with this value
role_restrictions(
  id UUID, role_name TEXT → roles, subject_path TEXT,
  reason TEXT, created_by TEXT, created_at TIMESTAMPTZ
)

-- Audit trail — one row per chat request
restriction_audit_log(
  id UUID, user_sub TEXT, role_names TEXT[], restricted_paths TEXT[],
  query_hash TEXT,   -- SHA-256 of the user's prompt, never raw text
  accessed_at TIMESTAMPTZ
)

-- Chat history
conversations(id UUID, user_sub TEXT, title TEXT, created_at TIMESTAMPTZ)
messages(
  id UUID, conversation_id UUID → conversations,
  role TEXT CHECK IN ('user','assistant'),
  content TEXT, sources JSONB, dlp_redacted INTEGER, created_at TIMESTAMPTZ
)
```

Full DDL: [infra/migrations/init.sql](../infra/migrations/init.sql)

### 4.2 Qdrant Collection (`enterprise_knowledge`)

Each document chunk is stored as a vector point with this payload:

```json
{
  "subject_path":    "finance/payroll",
  "ancestor_paths":  ["finance", "finance/payroll"],
  "source_file":     "payroll-2026.xlsx",
  "source_type":     "excel",
  "chunk_text":      "...",
  "chunk_index":     3,
  "page_number":     null,
  "sheet_name":      "Sheet1",
  "ingested_at":     "2026-06-04T00:00:00Z",
  "doc_id":          "uuid5-of-filepath"
}
```

**Vector config:** 384 dimensions, Cosine distance (model: `sentence-transformers/all-MiniLM-L6-v2`).

**The `ancestor_paths` field** is the FGA enforcement key. For a document at `finance/payroll/q3`, it stores `["finance", "finance/payroll", "finance/payroll/q3"]`. Restricting any ancestor excludes the document without recursive logic.

---

## 5. API Contracts

### Public Endpoints (backend port 3000)

#### `GET /api/health`
No auth required.
```json
{ "status": "ok", "keycloak": "reachable", "qdrant": "reachable" }
```

#### `POST /api/chat`
Auth: Bearer JWT (Keycloak). **Blocking — not streaming (see §8).**
```json
// Request
{ "prompt": "What are the Q3 drilling plans?", "conversation_id": "uuid | null" }

// Response
{
  "answer": "The Q3 drilling plans focus on...",
  "conversation_id": "uuid",
  "sources": [
    { "source_file": "drilling-plans.pdf", "subject_path": "operations/drilling", "chunk_index": 2, "score": 0.91 }
  ],
  "fga_applied": true,
  "restricted_paths_count": 2,
  "dlp_entities_redacted": 0
}
```

#### `POST /api/chat/verify`
Auth: Bearer JWT (Keycloak). **Multipart form-data.** Accepts a file alongside a question and cross-references it against the knowledge base.
```
// Request (multipart/form-data)
message:        "Is the IP address in this runbook correct?"
conversationId: "uuid"  (optional)
file:           <binary>  // .pdf, .xlsx, .xls, .png, .jpg, .jpeg, .tiff, .tif, .txt, .md, .csv

// Response (same shape as /api/chat)
{
  "answer": "The runbook lists 10.0.0.99 but the knowledge base shows 192.168.1.50...",
  "conversationId": "uuid",
  "sources": [...],
  "fgaApplied": true,
  "dlpEntitiesRedacted": 2
}
```
The uploaded file is parsed ephemerally via `ingestion:8001/parse` and injected into the Claude system prompt. It is **never written to the database** — only `message + "[Attached: filename]"` is persisted. FGA and DLP apply identically to regular chat. Max tokens: 2048.

#### `GET /api/conversations`
Returns list of conversations for the authenticated user.

#### `GET /api/conversations/{id}/messages`
Returns message history for a conversation.

#### `GET /api/admin/roles` _(admin role required)_
Returns all roles and their restriction lists.

#### `POST /api/admin/roles/{role}/restrictions` _(admin role required)_
```json
{ "subject_path": "finance/payroll", "reason": "Need-to-know only" }
```

#### `DELETE /api/admin/roles/{role}/restrictions/{encodedPath}` _(admin role required)_

### Internal Endpoints (Docker `internal` network only)

#### `POST /dlp/analyze` (dlp-service:8000)
```json
// Request
{ "text": "John earns $125,000 per year.", "language": "en" }

// Response
{ "cleaned_text": "[REDACTED] earns [REDACTED] per year.", "entities_found": [...] }
```

#### `POST /embed` (ingestion:8001)
```json
// Request
{ "text": "What are the Q3 drilling plans?" }

// Response
{ "vector": [0.123, ...] }   // 384-dimensional float array
```

#### `POST /parse` (ingestion:8001)
Accepts a multipart file upload. Dispatches to the appropriate parser by extension and returns the extracted plain text. Used exclusively by `ParseClient` to support `/api/chat/verify`. The file never touches Qdrant.
```
// Request (multipart/form-data)
file: <binary>   // .pdf, .xlsx, .xls, .png, .jpg, .jpeg, .tiff, .tif, .txt, .md, .csv

// Response
{ "text": "...", "filename": "runbook.pdf" }
```

#### `POST /ingest` (ingestion:8001)
Accepts a file and a `bu_path` form field. Parses, chunks, embeds, and upserts the document into Qdrant under the given `subject_path`. Called by the ANP crawler for every discovered document. Idempotent — calls `delete_by_doc_id` before upserting. The `doc_id` is derived as `uuid5(NAMESPACE_URL, f"{bu_path}/{filename}")`.
```
// Request (multipart/form-data)
file:    <binary>        // .pdf, .xlsx, .xls, .png, .jpg, .jpeg
bu_path: "corporate-answers"   // subject_path to assign to all chunks

// Response
{ "doc_id": "uuid", "chunks": 14, "filename": "relatorio.pdf" }
```

---

## 6. Security Model

### 6.1 Authentication
Keycloak issues JWTs (OIDC). Spring Boot validates tokens against Keycloak's JWKS endpoint:
`http://keycloak:8080/realms/enterprise-securechat/protocol/openid-connect/certs`

Role claims are read from `realm_access.roles` in the JWT payload.

### 6.2 FGA — Hierarchical Document Restriction

Restriction logic is enforced inside Qdrant, not in the LLM:

1. User's JWT roles → `FgaService.getRestrictedPaths(roles)` → list of forbidden path prefixes from Postgres
2. Each forbidden path `p` generates a Qdrant `must_not` condition:
   ```json
   { "key": "ancestor_paths", "match": { "any": ["p"] } }
   ```
3. Qdrant executes the search with this filter. Restricted documents are **never retrieved** — the LLM cannot leak what it never sees.

**Tree inheritance:** A restriction on `"finance"` automatically blocks `"finance/payroll"`, `"finance/budgets"`, etc., because all descendants store `"finance"` in their `ancestor_paths` array.

### 6.3 DLP — Output Redaction
After the LLM generates its answer, the full text is sent to Presidio (DLP service) before returning to the client. Presidio detects and replaces:

| Entity type | Example | Replaced with |
|-------------|---------|---------------|
| `PERSON` | "John Smith" | `[REDACTED]` |
| `EMAIL_ADDRESS` | john@company.com | `[REDACTED]` |
| `PHONE_NUMBER` | +55 11 99999-9999 | `[REDACTED]` |
| `CREDIT_CARD` | 4111 1111 1111 1111 | `[REDACTED]` |
| `FINANCIAL_FIGURE` (custom) | $125,000 / 78.50 USD | `[REDACTED]` |

---

## 7. Ingestion Pipeline

### Document Parsers
- **PDF** — PyMuPDF: extracts text per page; falls back to Tesseract OCR (`por+eng`) for scanned pages with no embedded text
- **Excel `.xlsx`** — openpyxl (read-only, lazy iterator): first 30 data rows per sheet as row-level chunks with headers prepended; lazy iteration prevents memory overflow on large operational spreadsheets
- **Excel `.xls`** — xlrd: same 30-row cap and header-prefix format for legacy Excel 97-2003 binary files
- **Image** — Pillow + pytesseract OCR (`por+eng`): entire image OCR'd as a single chunk
- **Plain text** (`.txt`, `.md`, `.csv`) — read as UTF-8; used only by `POST /parse` for ephemeral verification, never chunked or indexed

### Chunking
- Chunk size: 512 tokens, overlap: 64 tokens
- Each chunk gets the full `ancestor_paths` array computed from its `subject_path`

### Idempotency
Point IDs are deterministic: `uuid5(NAMESPACE_URL, f"{doc_id}:{chunk_index}")`. Re-ingesting the same file overwrites existing vectors. The `/ingest` endpoint calls `delete_by_doc_id(doc_id)` before every upsert.

### Vector Lifecycle (path moves)
When a document is moved to a new `subject_path`, old vectors retain stale `ancestor_paths`. The ingestion CLI must call `delete_by_doc_id(doc_id)` before re-ingesting. The manifest supports `previous_path` to trigger this automatically.

### Ingestion Manifest Format
```yaml
collection: enterprise_knowledge
documents:
  - path: data/finance/q3-report.pdf
    subject_path: finance/reports
  - path: data/operations/drill-plan.xlsx
    subject_path: operations/drilling
  - path: data/hr/org-chart.png
    subject_path: hr/org
```

### ANP Regulatory Crawler

`ingestion/src/crawler.py` is a standalone BFS scraper that automatically discovers and indexes regulatory documents from the ANP Exploração e Produção portal (`gov.br/anp/…/exploracao-e-producao-de-oleo-e-gas`).

**Crawl strategy:** BFS from the root URL up to depth 2, staying inside the ANP E&P URL prefix. On each page it collects all `.pdf`, `.xlsx`, and `.xls` hrefs. All file URLs are de-duplicated across pages before downloading.

**Subject-path routing** (by URL keyword matching):

| URL contains | `subject_path` assigned |
|---|---|
| `reserva`, `recursos`, or `bar` | `bar-questions` |
| anything else | `corporate-answers` |

**State tracking:** `ingestion/data/.crawler_state.json` stores `{filename → SHA-256}`. Files whose hash matches are skipped. Failed or interrupted files are not written to state and are retried on the next run.

**Operational limits:**
- 3-second pause between HTTP requests (gov.br CDN rate limiting)
- 50 MB max per file — oversized files are skipped with a warning
- 300-second POST timeout to `/ingest` (allows Tesseract OCR on long scanned PDFs)

**Key constraint:** The crawler posts to the persistent `ingestion` service container via `POST /ingest`. If the container image is rebuilt (e.g., after updating a parser), the persistent container must be restarted (`docker compose up -d --no-deps ingestion`) before the crawler is re-run, otherwise the crawler container sees the new image but the ingestion container still runs the old code.

---

## 8. Design Constraints

### Non-Streaming (Phase 1)
`/api/chat` is **blocking**. The backend waits for Claude to complete the full answer, then sends the entire text to Presidio for NER-based redaction. Presidio requires complete sentence context to accurately detect entity boundaries — streaming token-by-token breaks detection.

Future streaming path: sentence-buffer accumulation (hold tokens until punctuation → flush window to DLP → stream redacted chunk to client). Not implemented in Phase 1.

### Neon Scale-to-Zero
Neon free tier suspends the database after 5 minutes of inactivity (~500ms cold-start on next connection). This is acceptable for a side project. Spring Boot's JDBC connection pool handles reconnection transparently.

### Embed Service Latency Budget
The Spring Boot backend calls `ingestion:8001/embed` on every chat request. Config:
- Connect timeout: 2000ms
- Read timeout: 5000ms
- Uvicorn workers: 2 (model pre-loaded, no cold-start per request)

### DLP Latency Budget
Presidio with `en_core_web_lg` runs in ~100–300ms per analysis on typical answers. Target: < 500ms.

---

## 9. Performance Targets

| Operation | Target |
|-----------|--------|
| Embed user prompt (ingestion sidecar) | < 200ms |
| Qdrant filtered search (top-5) | < 100ms |
| Claude API response (blocking) | 2–8s |
| DLP redaction pass | < 500ms |
| Total `/api/chat` end-to-end | < 12s |
