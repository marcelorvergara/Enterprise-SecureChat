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
- Authenticate users via an OIDC identity provider (Auth0)
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
| **Frontend** | Angular 17+ | Chat UI, Auth0 OIDC login (`@auth0/auth0-angular`), source citations, admin panel |
| **Backend** | Spring Boot 3.x (Java 21) | JWT validation, FGA lookup, RAG orchestration, Claude API, DLP proxy |
| **Identity** | Auth0 (free tier, cloud) | OIDC token issuer, user/role management |
| **FGA Registry** | Neon PostgreSQL | Stores role → subject_path restriction mappings and audit logs |
| **Vector DB** | Qdrant 1.9 (Docker) | Stores document chunk embeddings with FGA metadata for filtered search |
| **Ingestion** | Python 3.11 | Parses PDFs/Excel/images/text, chunks, embeds, upserts into Qdrant with FGA metadata; exposes `/parse` for ephemeral document text extraction and `/ingest` for crawler-driven indexing; `crawler.py` auto-discovers ANP regulatory documents and can extract HTML page text (`--mode html/all`) |
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
Auth: Bearer JWT (Auth0). **Blocking — not streaming (see §8).**
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
Auth: Bearer JWT (Auth0). **Multipart form-data.** Accepts a file alongside a question and cross-references it against the knowledge base.
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

#### `DELETE /api/conversations/{id}`
Auth: Bearer JWT (Auth0). Deletes the conversation and all its messages. Returns **204 No Content**.

- Returns **403 Forbidden** if the authenticated user does not own the conversation.
- Returns **404 Not Found** if the conversation does not exist.
- Message deletion is handled by the database `ON DELETE CASCADE` constraint — no explicit message deletion in application code.

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
Auth0 issues JWTs (OIDC). Spring Boot validates tokens via the issuer's OpenID Connect discovery document (`/.well-known/openid-configuration`) and enforces the `api.enpsecurechat.com` audience claim.

Role claims are read from `https://enpsecurechat.com/roles` in the JWT payload, injected by a Post-Login Action.

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
After the LLM generates its answer, the full text is sent to Presidio (DLP service) before returning to the client. The NLP engine is `pt_core_news_lg` (Portuguese spaCy model) so Brazilian geological names are classified as LOC/GPE rather than PERSON. Industry acronyms (FPSO, PETROBRAS, etc.) are allowlisted. Presidio detects and replaces:

| Entity type | Source | Example | Replaced with |
|---|---|---|---|
| `PERSON` | SpacyRecognizer (PT NER) | "João Silva" | `[REDACTED]` |
| `EMAIL_ADDRESS` | EmailRecognizer | joao@empresa.com | `[REDACTED]` |
| `PHONE_NUMBER` | PhoneRecognizer | +55 11 99999-9999 | `[REDACTED]` |
| `CREDIT_CARD` | CreditCardRecognizer | 4111 1111 1111 1111 | `[REDACTED]` |
| `DATE_TIME` | SpacyRecognizer (PT NER) | 31/12/2035, 15 de junho de 2026 | `[REDACTED]` |
| `FINANCIAL_FIGURE` | custom — `financial_figures.py` | R$125.000, 450,000, 45 million USD | `[REDACTED]` |
| `OG_VOLUMES` | custom — `og_rules.py` | 450 MMboe, 3.2 bbl/d, 1,200 bbl | `[REDACTED]` |
| `ANP_PROCESS` | custom — `og_rules.py` | Ofício Nº 402/2026, Processo 48500.0012/2025-31 | `[REDACTED]` |
| `RESERVES_VARIATION` | custom — `og_rules.py` | +4.2% variação, fator de recuperação: 28% | `[REDACTED]` |
| `INVESTMENT_YEAR` | custom — `og_rules.py` | investimento em 2027, CAPEX 2028, 2025 a 2031 | `[REDACTED]` |
| `OG_CONTRACT` | custom — `og_rules.py` | prazo do contrato: 31/12/2035, limite econômico: 150 bbl/d | `[REDACTED]` |
| `COMMODITY_PRICE` | custom — `og_rules.py` | 70 USD/bbl, preço do barril: 65, $2.50/MMBtu | `[REDACTED]` |

---

## 7. Ingestion Pipeline

### Document Parsers
- **PDF** — PyMuPDF: extracts text per page; falls back to Tesseract OCR (`por+eng`) for scanned pages with no embedded text
- **Excel `.xlsx`** — openpyxl (read-only, lazy iterator): first 30 data rows per sheet as row-level chunks with headers prepended; lazy iteration prevents memory overflow on large operational spreadsheets
- **Excel `.xls`** — xlrd: same 30-row cap and header-prefix format for legacy Excel 97-2003 binary files
- **Image** — Pillow + pytesseract OCR (`por+eng`): entire image OCR'd as a single chunk
- **Plain text** (`.txt`, `.md`, `.csv`) — read as UTF-8; used by `POST /parse` for ephemeral verification and by the crawler HTML mode (pages are converted to `.txt` before POSTing to `/ingest`)

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

`ingestion/src/crawler.py` is a standalone BFS scraper that automatically discovers and indexes content from the ANP Exploração e Produção portal (`gov.br/anp/…/exploracao-e-producao-de-oleo-e-gas`).

**Mode flag (`--mode`):**

| Mode | Behavior |
|---|---|
| `files` (default) | Downloads PDF, XLSX, XLS file links found on pages |
| `html` | Extracts editorial text from each crawled HTML page |
| `all` | Both files and HTML page text |

When triggered via the `POST /crawl` API endpoint, the mode is read from the `CRAWLER_MODE` environment variable (defaults to `files`).

**Crawl strategy:** BFS from the root URL up to depth 2, staying inside the ANP E&P URL prefix. Depth-2 pages contain the richest regulatory/procedural content. All discovered pages are visited once regardless of mode.

**File mode — subject-path routing** (by URL keyword matching):

| URL contains | `subject_path` assigned |
|---|---|
| `reserva`, `recursos`, or `bar` | `bar-questions` |
| anything else | `corporate-answers` |

**HTML mode — content extraction:**

The ANP portal runs Plone CMS. Content is extracted from the first matching selector: `#content-core` → `.documentContent` → `#region-content` → `main`. Before extracting text, boilerplate elements (nav, header, footer, share buttons, portlet navigation) are removed in-place. Each page's breadcrumb trail is extracted from `#portal-breadcrumbs` and prepended to the text as `Categoria: X > Y > Z` — this gives every Qdrant chunk its hierarchical site context, improving retrieval precision for regulatory topic queries. Pages with fewer than 80 characters of body text (thin pointer pages, JS-rendered accordions without static content) are skipped. All HTML pages go to `subject_path: corporate-answers` regardless of URL keywords.

**State tracking:** `ingestion/data/.crawler_state.json` stores:
- File entries: `{filename → {"sha": SHA-256(bytes), "size": Content-Length}}`
- HTML entries: `{html::{slug} → SHA-256(extracted_text)}`

The separate key namespace prevents collisions between file and HTML entries. For HTML, hashing the extracted text (not raw HTML) means gov.br nav-chrome updates do not trigger spurious re-ingests. File entries written by older crawler versions (`{filename: sha_str}` without a size) are migrated on the first run via a HEAD request — no re-download required.

**Skip optimisation:** For file entries already in state, the crawler sends a HEAD request and compares `Content-Length` against the stored size. A size match skips the file immediately (0.5 s pause). A size mismatch or missing `Content-Length` falls through to a full download and SHA comparison. This reduces a full all-skip pass from hours to minutes. HTML pages do not benefit from HEAD probing — they are fetched in full to extract and hash the editorial text, but unchanged pages do not sleep an extra 3 seconds after the skip decision.

**Operational limits:**
- 3-second pause between full file downloads and page fetches (gov.br CDN rate limiting)
- 0.5-second pause between HEAD-only size probes (already-indexed files)
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
Presidio with `pt_core_news_lg` runs in ~100–300ms per analysis on typical answers. Target: < 500ms.

---

## 9. Performance Targets

| Operation | Target |
|-----------|--------|
| Embed user prompt (ingestion sidecar) | < 200ms |
| Qdrant filtered search (top-5) | < 100ms |
| Claude API response (blocking) | 2–8s |
| DLP redaction pass | < 500ms |
| Total `/api/chat` end-to-end | < 12s |
