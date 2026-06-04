# Enterprise SecureChat — Cloud Services Setup Guide

This guide walks through every external service this project depends on. Complete these steps before running `docker compose up` for the first time.

---

## 1. Neon PostgreSQL

Neon provides serverless PostgreSQL. The project uses **two databases** inside a single Neon project — one for the app and one for Keycloak.

### 1.1 Create a Neon Account
1. Go to [neon.tech](https://neon.tech) and sign up (free tier is sufficient)
2. Create a new **Project** — name it `enterprise-securechat`
3. Choose the region closest to you

### 1.2 Create the App Database
1. In the Neon dashboard, open your project and click **Databases**
2. Create a database named `fga_registry`
3. Click **Connect** → copy the connection string. It looks like:
   ```
   postgresql://user:password@ep-xxxx-xxxxxx.region.aws.neon.tech/fga_registry?sslmode=require
   ```
4. Set this as `NEON_APP_URL` in your `.env` file

### 1.3 Create the Keycloak Database
1. Create a second database named `keycloak` (same project, same branch)
2. Copy the connection string and convert it to JDBC format:
   ```
   jdbc:postgresql://ep-xxxx-xxxxxx.region.aws.neon.tech/keycloak?sslmode=require&user=YOUR_USER&password=YOUR_PASSWORD
   ```
3. Set this as `NEON_KEYCLOAK_URL` in your `.env` file

### 1.4 Apply the Schema
1. In the Neon dashboard, open the **SQL Editor**
2. Select the `fga_registry` database
3. Paste the contents of [infra/migrations/init.sql](../infra/migrations/init.sql) and run it
4. Verify by running: `SELECT table_name FROM information_schema.tables WHERE table_schema = 'public';`
   You should see: `roles`, `role_restrictions`, `restriction_audit_log`, `conversations`, `messages`

### 1.5 Free Tier Limits
| Resource | Free Tier Limit |
|----------|----------------|
| Storage | 0.5 GB |
| Compute | 191.9 compute-hours/month |
| Branches | 10 |
| Databases | Unlimited |
| Idle timeout | 5 minutes (cold-start ~500ms) |

The idle cold-start is transparent to the application — Spring Boot's connection pool reconnects automatically.

---

## 2. Anthropic API (Claude)

### 2.1 Get an API Key
1. Go to [console.anthropic.com](https://console.anthropic.com)
2. Sign in or create an account
3. Navigate to **Settings → API Keys**
4. Click **Create Key**, name it `enterprise-securechat-dev`
5. Copy the key (starts with `sk-ant-api03-...`)
6. Set it as `ANTHROPIC_API_KEY` in your `.env` file

### 2.2 Model Used
This project uses **`claude-sonnet-4-6`** — the current Claude 4.x Sonnet generation.

Do not change this to `claude-3-5-sonnet-latest` — that is the previous generation (Claude 3.5) and less capable.

### 2.3 Estimated Cost (Side Project)
The `/api/chat` endpoint is non-streaming and uses a context window that includes:
- System prompt: ~200 tokens
- Retrieved chunks (top 5 × ~512 tokens): ~2,560 tokens
- Conversation history (last 10 messages): ~2,000 tokens
- User prompt: ~50 tokens
- Answer: ~500 tokens

**Approximate cost per chat message:** $0.003–$0.008 USD at claude-sonnet-4-6 pricing.

For a development/side project with light usage, expect to stay well within the free $5 API credit.

### 2.4 Rate Limits
| Tier | Requests/min | Tokens/min |
|------|-------------|-----------|
| Free (Tier 1) | 50 | 40,000 |

If you hit rate limits during testing, add a small delay between rapid test requests.

---

## 3. Keycloak (Docker — Backed by Neon)

Keycloak runs locally in Docker but stores all its data in Neon (the `keycloak` database).

### 3.1 First Start
After setting `NEON_KEYCLOAK_URL` in `.env`:
```bash
docker compose up keycloak
```
Keycloak will create its own schema in the `keycloak` database automatically on first boot. This takes ~60–90 seconds.

### 3.2 Import the Realm
The realm configuration is imported automatically from [infra/keycloak/realm-export.json](../infra/keycloak/realm-export.json) via the `--import-realm` flag in docker-compose.

### 3.3 Create Test Users
1. Open the Keycloak Admin Console: [http://localhost:8080](http://localhost:8080)
2. Login with `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` from your `.env`
3. Select the `enterprise-securechat` realm
4. Navigate to **Users → Add User**
5. Create these test users:

| Username | Role to assign | Purpose |
|----------|---------------|---------|
| `admin-user` | `admin` | Full access, admin panel |
| `employee-one` | `employee` | General access, no restrictions |
| `finance-analyst` | `finance-analyst` | Restricted from finance paths (add via admin panel) |
| `hr-manager` | `hr-manager` | Restricted from HR paths (add via admin panel) |

6. For each user, set a password under **Credentials** and assign their role under **Role Mappings → Realm Roles**

### 3.4 Admin Console URL
- Local: [http://localhost:8080](http://localhost:8080)
- Realm: `enterprise-securechat`
- OIDC discovery: [http://localhost:8080/realms/enterprise-securechat/.well-known/openid-configuration](http://localhost:8080/realms/enterprise-securechat/.well-known/openid-configuration)

---

## 4. Qdrant (Docker — Local)

Qdrant runs entirely in Docker and stores data in a local volume. No external account needed.

### 4.1 Dashboard
Available at [http://localhost:6333/dashboard](http://localhost:6333/dashboard) after `docker compose up qdrant`.

### 4.2 API Key
Set `QDRANT_API_KEY` in `.env` to any string. The same value must be used by the backend (`QDRANT_API_KEY`) and the ingestion pipeline.

### 4.3 Collection Setup
The collection `enterprise_knowledge` is created automatically by the ingestion pipeline on first run. No manual setup required.

### 4.4 Future: Qdrant Cloud
For production deployment, Qdrant offers a managed cloud service at [cloud.qdrant.io](https://cloud.qdrant.io) with a free tier (1 GB storage). To migrate: update `QDRANT_URL` in `.env` to your cloud cluster URL.

---

## 5. Environment Variables Reference

Full list of variables for your `.env` file (see [infra/.env.example](../infra/.env.example) for the template):

| Variable | Required | Description |
|----------|----------|-------------|
| `NEON_APP_URL` | Yes | PostgreSQL JDBC/connection URL for app data (Neon `fga_registry` DB) |
| `NEON_KEYCLOAK_URL` | Yes | JDBC URL for Keycloak's backing database (Neon `keycloak` DB) |
| `KEYCLOAK_ADMIN` | Yes | Keycloak admin username (default: `admin`) |
| `KEYCLOAK_ADMIN_PASSWORD` | Yes | Keycloak admin password |
| `QDRANT_API_KEY` | Yes | API key for local Qdrant instance |
| `ANTHROPIC_API_KEY` | Yes | Anthropic API key for Claude access |
| `APP_JWT_SECRET` | Yes | Internal secret for any app-level token signing |

---

## 6. Startup Order

When bringing up the full stack for the first time:

```bash
# Step 1 — Start infrastructure services
docker compose up keycloak qdrant dlp-service -d

# Step 2 — Wait for Keycloak to finish importing realm (~90s)
docker compose logs -f keycloak   # look for "Listening on: http://0.0.0.0:8080"

# Step 3 — Ingest documents
docker compose --profile ingestion run --rm ingestion python -m src.main --manifest manifests/example-manifest.yaml

# Step 4 — Start the ingestion embed sidecar (keeps model warm for backend)
docker compose --profile ingestion up ingestion -d

# Step 5 — Start backend and frontend
docker compose up backend frontend -d

# Step 6 — Open the app
# http://localhost:4200
```

For subsequent starts (after first setup): `docker compose up -d` (and `docker compose --profile ingestion up ingestion -d` to keep the embed service warm).
