# Backend — Spring Boot 3.3 / Java 21

## Dev Commands

```bash
mvn spring-boot:run       # dev server on :3000
mvn package -DskipTests   # build JAR
mvn test                  # run all JUnit/Mockito tests
```

## Key Packages

```
src/main/java/com/enterprise/securechat/
├── audit/          RestrictionAuditLog entity + AuditService (SHA-256 hashing)
├── config/         RestClientConfig (5 typed RestClient beans + SSE ThreadPoolTaskExecutor), SecurityConfig
├── conversation/   Conversation + Message entities, ConversationService/Controller
├── fga/            FgaService — restriction lookup + Qdrant filter builder
├── health/         HealthController
├── rag/            RagController, RagService, ParseClient, EmbedClient, IngestClient,
│                   DocumentController, QdrantSearchClient, ClaudeService, DlpClient,
│                   SentenceBoundaryDetector, dto/
└── security/       OgRolesAndGroupExtractor (reads `https://enpsecurechat.com/roles` claim)
```

## Security-Critical Classes

- **`FgaService.buildQdrantFilter()`** — constructs the Qdrant `must_not` filter from role restrictions + classification level. Always call the overload that appends a `classification_level` clause. Never filter search results in Java — constraint #3 in root CLAUDE.md.
- **`AuditService.log()`** — stores `SHA-256(prompt)` only. Never write raw prompt text anywhere — constraint #4.
- **`OgRolesAndGroupExtractor`** — maps Auth0 JWT roles claim to Spring `ROLE_` authorities and `GROUP_BU_xxx` granted authorities.
- **`DocumentController.extractBuPath()`** — derives BU path server-side from `GROUP_BU_xxx`. Never accept a `bu_path` from the client — constraint #12.

## `RestClientConfig` Bean Summary

| Bean | Target | Timeout |
|------|--------|---------|
| `embedRestClient` | `http://ingestion:8001` | connect 2 s / read 90 s |
| `qdrantRestClient` | `${QDRANT_URL}` | connect 2 s / read 10 s |
| `claudeRestClient` | `https://api.anthropic.com` | connect 2 s / read 120 s |
| `dlpRestClient` | `http://dlp-service:8000` | connect 2 s / read 60 s |
| `ingestRestClient` | `http://ingestion:8001` | connect 2 s / read 300 s |

## `ClaudeService` Methods

| Method / overload | Max tokens | Used by |
|-------------------|-----------|---------|
| `complete(system, messages)` | 1024 | `/api/chat` (legacy blocking) |
| `complete(system, messages, 2048)` | 2048 | `/api/chat/verify` (document verification) |
| `complete(system, messages, 1536)` | 1536 | `/api/chat` structured suggestions |
| `complete(system, messages, 512)` | 512 | `/api/chat/stream` suggestion generation (after stream ends) |
| `streamComplete(system, messages, 1024, onToken)` | 1024 | `/api/chat/stream` answer tokens |

Do not raise the 1024 default — constraint #10 in root CLAUDE.md. Do not reduce the suggestions budget below 512 — Claude may output preamble before the JSON `[` that eats into the token budget.

## Test Classes

| Class | What it covers |
|-------|---------------|
| `FgaServiceTest` | Restriction lookup, Qdrant filter building, classification tier checks (25 cases) |
| `RagServiceTest` | Mock Qdrant + Claude + DLP; orchestration, DLP call counts, structured JSON suggestions, malformed JSON fallback; streaming pipeline: user-final suggestion messages, DLP on suggestions, emitter completion, emitter resilience when suggestions fail |
| `SentenceBoundaryDetectorTest` | ICU4J pt_BR sentence splits; decimal numbers, abbreviations, token-by-token streaming, flush behaviour (16 cases) |
| `ConversationServiceTest` | Delete success, 403 (wrong owner), 404 |
| `AdminControllerTest` | `@WebMvcTest`; 401 unauthenticated, 403 employee, 200 admin |

## Rate Limiting

Bucket4j in-memory token bucket: 20 requests/min per `sub` JWT claim, shared across `/api/chat`, `/api/chat/stream`, and `/api/chat/verify`. Returns HTTP 429 when exceeded. Keyed to authenticated identity, not IP address.
