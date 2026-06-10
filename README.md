# E&P SecureChat — Exploration & Production Intelligence

> An enterprise-grade AI assistant purpose-built for the Oil & Gas sector. Every answer is drawn from indexed company documents, filtered at the vector-database layer by the caller's exact role, and scrubbed of financial figures and PII before it leaves the backend — with zero raw prompt data ever touching disk.

---

## Product Overview

E&P SecureChat transforms how Exploration & Production teams interact with their institutional knowledge. Analysts, reservoir engineers, BU managers, and compliance officers all share a single conversational interface — yet each sees precisely what their role permits, enforced not by application logic that can be bypassed, but by a cryptographic path filter applied inside the vector database before the language model is ever invoked.

### Key Capabilities

**Local-First to Serverless RAG**
Documents are parsed, chunked into 512-token segments with 64-token overlap, embedded with `all-MiniLM-L6-v2` (384-dim), and written to Qdrant with a hierarchical `ancestor_paths` payload. The same pipeline runs identically on Docker Compose locally or across four Cloud Run services at zero idle cost. No external embedding API is required — the ingestion service owns the full vector lifecycle.

**Ephemeral Document Verification**
Analysts can attach a PDF, Excel sheet, or scanned image directly to a query. The backend routes the multipart upload to `POST /api/chat/verify`, which parses the file ephemerally via the ingestion service, injects the raw text into the Claude system prompt alongside the indexed knowledge base, and discards the document text after the response is generated. Nothing from the uploaded file is persisted in conversation history, the audit log, or the vector store.

**Automated ANP Regulatory Crawling**
A built-in BFS crawler targets the ANP Exploração e Produção portal (`gov.br/anp`) up to depth 2. It downloads PDF, XLSX, and XLS files and can optionally extract editorial text directly from ANP HTML pages using Plone CMS selectors. Crawl state is maintained as a SHA-256 map — subsequent runs skip unchanged content via HEAD-only size probes in ~0.5 s per file. On GCP, the crawler runs as a scheduled Cloud Run Job every Monday at 03:00 BRT.

---

## Visual Product Tour

### The Workspace — BU-Scoped Intelligence

![](<docs/print bu-user.png>)

A BU analyst authenticated as `erica` queries the Santos basin asset valuation. The Angular 17 Material interface presents a persistent sidebar with full conversation history on the left and a clean chat workspace on the right. The response is rendered as structured Markdown — numbered sections for Reserves Volumes, Investment Projections, Recovery Improvements, and Key Observations — sourced from the document `BU-SANTOS-SAN-RESERVES-2026 (Classification: RESTRICTED / SANTOS RESERVES MANAGEMENT)`.

Notice the orange-highlighted spans throughout the answer: those are **live DLP redactions**. The raw Claude output contained explicit financial figures and reserve volumes; the Presidio post-processor intercepted them before the response reached the browser, replacing each entity with a highlighted `[REDACTED]` marker. The BU user receives the structural insight without exposure to the numerical data their role does not permit.

---

### Context-Aware Insights — Reserves Coordination View

![](<docs/print reserves-coorditation.png>)

The same query issued by a `reserves-coordination` user surfaces the full unredacted analysis: **Santos Basin Asset Valuation — Merkuza Extraction Data Analysis**. Because this role carries cross-BU read access, the FGA filter returns richer context from the Qdrant search — and because the DLP entity rules are calibrated per authority level, the financial figures pass through.

The response demonstrates the RAG pipeline's citation fidelity: specific document references are embedded inline (e.g., `BI-SANTOS SAN RESERVES 2026`), investment expenditure is structured as `USD 140 million` with a named investment plan, and recovery improvements are grounded in a specific extraction data set. Section 4 surfaces a compliance note directly from the source document: *"This document is classified as RESTRICTED // SANTOS RESERVES MANAGEMENT — cross-BU production data may only appear in a comprehensive full-field valuation."*

---

### Access Control Administration

![](<docs/print admin page.png>)

The Admin panel — accessible only to users with the `admin` role via `@PreAuthorize("hasRole('admin')")` on every endpoint — exposes two panels. The **Add Restriction** form on the left allows an administrator to bind any role to any subject path with an optional reason that is persisted to the audit log. The **Restriction Matrix** on the right lists all active restrictions with their role, subject path, and creation date, and provides a per-row delete action.

The **Restriction Audit Log** at the bottom is the compliance backbone: every query that triggered an FGA restriction is recorded with its timestamp, user ID (hashed), blocked path, and a query hash — the SHA-256 of the raw prompt. No plaintext query text is ever stored. The audit log satisfies the requirement to prove a query occurred while ensuring the prompt content itself cannot be reconstructed from the database.

---

### Document Verification with Role-Based Redaction

![](<docs/print admin answer.png>)

An admin user runs a document verification query with four attached reserve reports (visible as file chips at the bottom of the input bar: `san-field-update.pdf`, `alb-campo-valuation.pdf`, `sol-producao-anual.pdf`, and a fourth). The response synthesizes the uploaded documents against the indexed knowledge base and presents a focused summary of the numerical reserve data.

Even for an admin, the DLP pipeline is active: financial figures in the response appear as `[REDACTED]` spans. The system architecture enforces DLP universally — there is no role that bypasses the Presidio post-processor. The `max_tokens=2048` budget for the verify endpoint ensures full compliance reports are never truncated mid-analysis.

---

## The Multi-Layered Security Mesh

E&P SecureChat enforces data protection at three independent layers. Disabling any one of them would require a deliberate code change — there is no configuration flag that removes a layer.

### Fine-Grained Authorization (FGA)

> **How it works:** When a document at `bu/santos/reserves/field-update.pdf` is ingested, the pipeline writes `ancestor_paths: ["bu", "bu/santos", "bu/santos/reserves"]` into the Qdrant payload. When `FgaService.buildQdrantFilter()` runs for a user whose role is restricted from `bu/santos`, it produces a `must_not` filter passed directly to `QdrantSearchClient.search()` — before the embedding is even ranked. Every document at `bu/santos/*` is excluded **mathematically** by the vector database, not by post-filtering in Java. Restricting a parent path silently blocks all descendants with no recursive logic required.
>
> **Why it cannot be bypassed:** The filter is constructed from the live restriction table at request time (not cached) and is the only argument passed to Qdrant. There is no secondary search path to `/api/chat`. Spring Security's `@PreAuthorize` blocks non-admin users from modifying the restriction table.

### Role-Aware Data Loss Prevention (DLP)

> **How it works:** After Claude generates its draft answer, the backend sends the full response text to `http://dlp-service:8000/dlp/analyze`. The DLP microservice runs Microsoft Presidio with a Portuguese spaCy NER model (`pt_core_news_lg`) and eight custom O&G recognizers covering financial figures, reserve volumes, ANP process numbers, reserve variation percentages, investment years, contract terms, commodity prices, and document dates. Any detected entity is replaced with `[REDACTED]` before the response is returned to the Angular client.
>
> **Why it is blocking (non-streaming):** Presidio's NER models require complete sentence context. A person's name or a financial clause split across two streamed chunks may not be detected. The `/api/chat` endpoint intentionally waits for the full Claude response before calling DLP. When streaming is added in a future milestone, a sentence-buffer flush approach is required.
>
> **Why `pt_core_news_lg` and not the English model:** Brazilian geological basin and field names (Pré-Sal, Campos, Santos) are classified as `LOC`/`GPE` by the Portuguese model. The English `en_core_web_lg` misclassifies them as `PERSON`, producing false positives that redact core O&G terminology.

### Zero-Trace Compliance Logging

> **How it works:** `AuditService.log()` computes `SHA-256(rawPrompt)` and stores only the hash in `restriction_audit_log.query_hash`. The raw prompt string is passed in, hashed, and immediately discarded — it is never written to any database column, log stream, or audit record. The hash is sufficient to prove a specific query was submitted (by providing the plaintext for hash verification) without exposing the prompt content at rest.
>
> **Rate limiting:** `POST /api/chat` and `POST /api/chat/verify` share a Bucket4j in-memory token bucket of 20 requests per minute per `sub` JWT claim. Exceeding the limit returns HTTP 429. The rate limiter is keyed to the authenticated identity, not the IP address.

---

## Under-the-Hood Architecture

The diagram below traces a complete user request from browser to response, including both the fast path (text-only chat) and the ephemeral document verification path.

```mermaid
flowchart TD
    classDef user fill:#e1f5fe,stroke:#0288d1,stroke-width:2px;
    classDef frontend fill:#fff3e0,stroke:#f57c00,stroke-width:2px;
    classDef backend fill:#e8f5e9,stroke:#388e3c,stroke-width:2px;
    classDef identity fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px;
    classDef data fill:#fffde7,stroke:#fbc02d,stroke-width:2px;
    classDef ai fill:#e8eaf6,stroke:#3f51b5,stroke-width:2px;
    classDef external fill:#fce4ec,stroke:#c62828,stroke-width:2px;

    User((User)):::user
    UI[Angular Frontend]:::frontend

    ANP[ANP E&P Portal<br/>gov.br]:::external
    Crawler[ANP Crawler<br/>src/crawler.py<br/>BFS depth 2 · SHA-256 state]:::data

    subgraph Identity [Identity Layer]
        Auth0[Auth0 OIDC<br/>cloud · free tier]:::identity
        Postgres[(PostgreSQL · Neon)]:::identity
    end

    subgraph Ingestion [Ingestion Service · :8001]
        Embed[POST /embed<br/>all-MiniLM-L6-v2]:::data
        Parse[POST /parse<br/>PDF · Excel · Image · Text]:::data
        Ingest[POST /ingest<br/>Parse → Chunk → Embed → Upsert]:::data
    end

    subgraph Backend [Spring Boot · :3000]
        API[API Gateway]:::backend
        Filter[JWT + Rate Limit Filter]:::backend
        RAG[RAG Orchestrator]:::backend
        DLPClient[DLP Post-Processor]:::backend
    end

    subgraph Storage [Storage & AI]
        VDB[(Qdrant · Vector DB)]:::data
        LLM[Claude claude-sonnet-4-6]:::ai
    end

    subgraph DLPService [DLP Service · internal only]
        Presidio[Presidio · spaCy NER<br/>PII + Financial redaction]:::backend
    end

    ANP -->|scrapes PDF / XLSX / XLS| Crawler
    Crawler -->|POST /ingest · subject_path routing| Ingest
    Ingest -->|upsert chunks + ancestor_paths| VDB

    ManifestIngest([One-shot manifest<br/>src/main.py]):::data
    ManifestIngest -.->|static BU documents| Ingest

    User -->|1. Login| Auth0
    Auth0 -->|2. Issues JWT| UI

    User -->|3a. Question| UI
    User -->|3b. Question + File| UI

    UI -->|4a. Prompt + JWT| API
    UI -->|4b. Multipart + JWT| API

    API --> Filter
    Filter <-->|5. Restricted paths| Postgres
    Filter -->|6. Validated request| RAG

    RAG -->|7a. Embed prompt| Embed
    Embed -->|7b. Query vector| RAG

    RAG -->|8. Semantic search + FGA filter| VDB
    VDB -->|9. Permitted chunks| RAG

    RAG -.->|10. Parse file · verify only| Parse
    Parse -.->|11. Raw text · ephemeral, never stored| RAG

    RAG -->|12. Prompt + context| LLM
    LLM -->|13. Draft answer| RAG

    RAG -->|14. Scan for PII/financials| DLPClient
    DLPClient <-->|Presidio analysis| Presidio
    DLPClient -->|15. Clean answer| UI
    UI -->|16. Display| User
```

---

## Technical Stack & Specifications

| Layer | Technology | Notes |
|---|---|---|
| **Frontend** | Angular 17 (standalone) + Angular Material 17 | Auth0 OIDC via `@auth0/auth0-angular`; Markdown rendered through `marked` + DOMPurify (`SafeMarkdownPipe`) |
| **Backend** | Spring Boot 3.3 / Java 21 | `RagService.chat()` is deliberately non-`@Transactional` — HikariCP pool size 5 (Neon free tier); all external HTTP calls run outside any transaction boundary |
| **Identity** | Auth0 (cloud, free tier) | Post-Login Action injects O&G roles into `https://enpsecurechat.com/roles` JWT claim; `OgRolesAndGroupExtractor` maps to Spring `ROLE_` authorities |
| **Vector DB** | Qdrant 1.9 | FGA enforced via `must_not` filter on `ancestor_paths` payload at query time; 384-dim collection; Docker locally, Qdrant Cloud on GCP |
| **Relational Store** | Neon (serverless PostgreSQL) | Hosts `fga_registry` DB: FGA restriction table, conversation/message history, SHA-256 audit log |
| **LLM Engine** | Claude `claude-sonnet-4-6` via Anthropic Messages API | 1024 `max_tokens` for chat; 2048 for document verification; model configurable via `CLAUDE_MODEL` env var |
| **Ingestion Pipeline** | Python 3.11 · FastAPI · sentence-transformers | `all-MiniLM-L6-v2` embedder; LangChain `RecursiveCharacterTextSplitter` (512 tok / 64 overlap); parsers: pdfminer + pytesseract (OCR), openpyxl/xlrd, Pillow |
| **Compliance / DLP** | Python 3.11 · FastAPI · Microsoft Presidio · spaCy `pt_core_news_lg` | 8 custom O&G recognizers; internal Docker network only — no public port; Portuguese NLP model to correctly classify Brazilian basin/field names |
| **Rate Limiting** | Bucket4j 8.10 | 20 req/min per `sub` claim; in-memory token buckets; shared across `/api/chat` and `/api/chat/verify` |
| **Infrastructure** | Docker Compose (local) · GCP Cloud Run (production) | Scale-to-zero on Cloud Run; ANP crawler runs as a scheduled Cloud Run Job |

---

## Quick Start

### 1. Configure credentials

```bash
git clone <repo-url>
cd Enterprise-SecureChat
cp infra/.env.example infra/.env
```

Edit `infra/.env` with your Neon JDBC URL, Auth0 tenant settings, Anthropic API key, and Qdrant credentials:

```env
SPRING_DATASOURCE_URL=jdbc:postgresql://ep-xxxx.region.aws.neon.tech/fga_registry?sslmode=require&user=xxx&password=xxx
AUTH0_ISSUER_URI=https://dev-xxx.us.auth0.com/
AUTH0_AUDIENCE=api.enpsecurechat.com
ANTHROPIC_API_KEY=sk-ant-api03-...
QDRANT_API_KEY=your-qdrant-key
```

### 2. Apply the database schema

Open the Neon SQL Editor for your `fga_registry` database and paste the contents of `infra/migrations/init.sql`. This creates the FGA registry tables, conversation history, SHA-256 audit log, and seeds the five default O&G roles.

### 3. Start all services

```bash
cd infra
docker compose up -d
```

| Service | Endpoint | Role |
|---|---|---|
| Frontend | http://localhost:4200 | Angular SPA (nginx) |
| Backend | http://localhost:3000 | Spring Boot API gateway |
| Qdrant | http://localhost:6333 | Vector DB + dashboard |
| DLP | internal only | `dlp-service:8000` on backend network |

### 4. Index the baseline document corpus

```bash
# Runs one-shot against the persistent ingestion service started in step 3
cd infra
docker compose run --rm ingestion \
  python -m src.main --manifest manifests/og-manifest.yaml
```

This indexes the BU reserves documents under `bu/<name>/reserves` and regulatory content under `bar-questions`. The operation is fully idempotent — re-running never creates duplicate vectors.

### 5. Populate the ANP regulatory knowledge base (optional)

```bash
# Index PDF/XLSX/XLS files from the ANP E&P portal
docker compose run --rm \
  -e INGEST_URL=http://ingestion:8001/ingest \
  ingestion python -m src.crawler

# Also index HTML page text (recommended for complete regulatory coverage)
docker compose run --rm \
  -e INGEST_URL=http://ingestion:8001/ingest \
  ingestion python -m src.crawler --mode all
```

The crawler maintains a SHA-256 state file — subsequent runs skip unchanged content in ~0.5 s per file via HEAD-only size probes.

### 6. Open the application

Navigate to http://localhost:4200. Auth0 Universal Login handles authentication. After login, role-based FGA restrictions are active immediately — no restart required. Add or modify restrictions via the **Admin** panel (requires the `admin` role).

---

## Role Reference

| Role | Access Scope | Can Upload Documents |
|---|---|---|
| `admin` | Unrestricted + admin panel | — |
| `employee` | General company knowledge base | No |
| `bu-user` | Own BU path only (`bu/<name>/reserves`) | Yes |
| `reserves-management` | Cross-BU reserves access | Yes |
| `reserves-coordination` | Cross-BU reserves + `bar-questions` regulatory | Yes |
| `reservoir-team` | Reservoir engineering read-only; blocked from `bar-questions` | No |
