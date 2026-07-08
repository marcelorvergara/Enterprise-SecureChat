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
        LlmTel[LlmTelemetryService<br/>fire-and-forget, dual-write]:::backend
    end

    subgraph Storage [Storage & AI]
        VDB[(Qdrant · Vector DB)]:::data
        LLM[Claude claude-sonnet-4-6]:::ai
    end

    subgraph DLPService [DLP Service · internal only]
        Presidio[Presidio · spaCy NER<br/>PII + Financial redaction]:::backend
    end

    subgraph Telemetry [LLM Telemetry · ADR-002]
        Firestore[(Firestore · llm_telemetry)]:::data
        MetricsFn[llm-metrics-fn<br/>Gen2 Cloud Function]:::backend
    end

    MonitoringLinks[monitoring-links<br/>status-page poller]:::external

    %% --- Ingestion paths ---
    ANP -->|scrapes PDF / XLSX / XLS| Crawler
    Crawler -->|POST /ingest · subject_path routing| Ingest
    Ingest -->|upsert chunks + ancestor_paths| VDB

    ManifestIngest([One-shot manifest<br/>src/main.py]):::data
    ManifestIngest -.->|static BU documents| Ingest

    %% --- User auth ---
    User -->|1. Login| Auth0
    Auth0 -->|2. Issues JWT| UI

    %% --- Chat request ---
    User -->|3a. Question| UI
    User -->|3b. Question + File| UI

    UI -->|4a. Prompt + JWT| API
    UI -->|4b. Multipart + JWT| API

    API --> Filter
    Filter <-->|5. Restricted paths| Postgres
    Filter -->|6. Validated request| RAG

    RAG -->|7a. Embed prompt| Embed
    Embed -->|7b. Query vector| RAG

    RAG -->|8. Semantic search + FGA must_not filter| VDB
    VDB -->|9. Permitted chunks| RAG

    RAG -.->|10. Parse file · verify only| Parse
    Parse -.->|11. Raw text · ephemeral, never stored| RAG

    RAG -->|12. Prompt + context| LLM
    LLM -->|13. Draft answer| RAG

    RAG -->|14. Scan for PII / financials| DLPClient
    DLPClient <-->|Presidio analysis| Presidio
    DLPClient -->|15. Clean answer| UI
    UI -->|16. Display| User

    %% --- LLM telemetry (async, off the request thread; see ADR-002 / CLAUDE.md #14) ---
    RAG -.->|17. record, never awaited| LlmTel
    LlmTel -->|dual-write| Postgres
    LlmTel -->|dual-write, best-effort, independent of Postgres write| Firestore
    MetricsFn -->|trailing-24h read| Firestore
    MonitoringLinks -->|poll · avoids Cloud Run JVM cold start| MetricsFn
    MonitoringLinks -.->|GET /internal/llm-metrics<br/>X-Internal-Key, other consumers| API
