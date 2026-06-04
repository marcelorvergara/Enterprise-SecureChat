flowchart TD
    %% Styling
    classDef user fill:#e1f5fe,stroke:#0288d1,stroke-width:2px;
    classDef frontend fill:#fff3e0,stroke:#f57c00,stroke-width:2px;
    classDef backend fill:#e8f5e9,stroke:#388e3c,stroke-width:2px;
    classDef identity fill:#f3e5f5,stroke:#7b1fa2,stroke-width:2px;
    classDef data fill:#fffde7,stroke:#fbc02d,stroke-width:2px;
    classDef ai fill:#e8eaf6,stroke:#3f51b5,stroke-width:2px;

    %% Nodes
    User((User)):::user
    UI[Angular Frontend]:::frontend
    
    subgraph Identity [Identity Layer]
        Keycloak[Keycloak OIDC]:::identity
        Postgres[(PostgreSQL FGA Registry)]:::identity
    end
    
    Ingestion[Data Ingestion Pipeline<br/>PDFs -> Chunks -> Metadata Vectors]:::data
    
    subgraph Backend [The Missing Bridge: Spring Boot / Node]
        API[API Gateway]:::backend
        Filter[Security Filter]:::backend
        RAG[RAG Orchestrator]:::backend
        DLP[Post-Processing DLP]:::backend
    end
    
    subgraph Knowledge Base [Storage & AI Layer]
        VDB[(Vector Database)]:::data
        LLM[Enterprise LLM]:::ai
    end

    %% The Flow
    Ingestion -->|Populates Knowledge| VDB
    
    User -->|1. Login| Keycloak
    Keycloak -->|2. Issues JWT| UI
    Keycloak -.->|Syncs User Roles| Postgres

    User -->|3. Asks Question| UI
    UI -->|4. Prompt + JWT| API
    
    API --> Filter
    Filter <-->|5. Get Restricted Paths| Postgres
    Filter -->|6. Validated Request| RAG
    
    %% THE CRITICAL CONNECTIONS YOU ASKED ABOUT
    RAG -->|7. Semantic Search + FGA Restrictions| VDB
    VDB -->|8. Returns Permitted Knowledge| RAG
    
    RAG -->|9. Prompt + Allowed Knowledge| LLM
    LLM -->|10. Drafts Answer| RAG
    
    RAG -->|11. Scans for Sensitive Data| DLP
    DLP -->|12. Returns Clean Answer| UI
    UI -->|13. Displays to User| User