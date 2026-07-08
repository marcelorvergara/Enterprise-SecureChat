# Enterprise SecureChat — Cloud Services Setup Guide

This guide walks through every external service this project depends on. Complete these steps before running `docker compose up` for the first time.

---

## 1. Neon PostgreSQL

Neon provides serverless PostgreSQL. The project uses **one database** inside a Neon project.

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

### 1.3 Apply the Schema
1. In the Neon dashboard, open the **SQL Editor**
2. Select the `fga_registry` database
3. Paste the contents of [infra/migrations/init.sql](../infra/migrations/init.sql) and run it
4. Verify by running: `SELECT table_name FROM information_schema.tables WHERE table_schema = 'public';`
   You should see: `roles`, `role_restrictions`, `restriction_audit_log`, `conversations`, `messages`

### 1.4 Free Tier Limits
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

## 3. Auth0 (Identity — Free Tier Cloud)

Auth0 handles authentication. No Docker container or self-hosted service required.

### 3.1 Create an Account and Tenant
1. Go to [auth0.com](https://auth0.com) and sign up (free tier is sufficient)
2. A tenant is created automatically — note the domain (e.g. `dev-xxx.us.auth0.com`)

### 3.2 Create the SPA Application
1. **Applications → Create Application → Single Page Application**
2. Name it `securechat-frontend`
3. Under **Settings**, add to:
   - **Allowed Callback URLs**: `http://localhost:4200, https://enpsecurechat.com`
   - **Allowed Logout URLs**: `http://localhost:4200, https://enpsecurechat.com`
   - **Allowed Web Origins**: `http://localhost:4200, https://enpsecurechat.com`
4. Note the **Client ID** — set it in the Angular `environment.ts` files

### 3.3 Create the API
1. **Applications → APIs → Create API**
2. Name: `EnP SecureChat API`, Identifier (audience): `api.enpsecurechat.com`
3. Under **Settings**: enable **Allow Offline Access** (required for refresh tokens)
4. Under **Settings → User Consent**: enable **Allow Skipping User Consent**
5. Set `AUTH0_AUDIENCE=api.enpsecurechat.com` in your `.env`

### 3.4 Deploy the Post-Login Action (roles in JWT)
1. **Actions → Triggers → post-login → + → Build from scratch**
2. Name: `Add Roles to Token`, paste:
```javascript
exports.onExecutePostLogin = async (event, api) => {
  const ns = 'https://enpsecurechat.com/roles';
  const roles = event.authorization?.roles ?? [];
  api.idToken.setCustomClaim(ns, roles);
  api.accessToken.setCustomClaim(ns, roles);
};
```
3. **Deploy**, then drag it into the flow and **Apply**

### 3.5 Create Roles and Test Users
1. **User Management → Roles → Create Role** — create: `admin`, `employee`, `bu-user`, `reserves-management`, `reserves-coordination`, `reservoir-team`
2. **User Management → Users → Create User** — suggested test users:

| Email | Role | Purpose |
|-------|------|---------|
| `admin-user@enpsecurechat.com` | `admin` | Full access, admin panel |
| `employee@enpsecurechat.com` | `employee` | General access |
| `bu-santos@enpsecurechat.com` | `bu-user` | Sees only `bu/santos/*` |
| `reservoir@enpsecurechat.com` | `reservoir-team` | Blocked from `bar-questions` |

3. For each user: go to **Users → [user] → Roles** and assign their role

### 3.6 OIDC Discovery URL
`https://<your-tenant>.us.auth0.com/.well-known/openid-configuration`

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

## 5. Firestore (llm-metrics status-page read path)

Firestore backs a second, independent projection of LLM telemetry — not a Neon replacement. `monitoring-links` polls `GET /internal/llm-metrics` on `securechat-backend` for the public status page, and Cloud Run's JVM cold start (~10–30 s at `min-instances=0`) made that poll slow/unreliable. Rather than pay to keep the whole backend warm (`min-instances=1`) just for a cheap read, LLM telemetry is dual-written to Firestore, and a separate Gen2 Cloud Function (`functions/llm-metrics`, negligible cold start) serves the identical JSON contract from there instead.

### 5.1 Setup (one-time, per GCP project)

```bash
gcloud services enable firestore.googleapis.com cloudfunctions.googleapis.com eventarc.googleapis.com --project=$PROJECT_ID
gcloud firestore databases create --project=$PROJECT_ID --location=us-east4 --type=firestore-native
```

Uses the `(default)` Native-mode database — nothing else in this project touches Firestore, so a named database adds a flag everywhere for no isolation benefit. Firestore's mode and location are **irreversible** once set for a project; run `gcloud firestore locations list` to confirm the target region is valid before creating.

### 5.2 What's there

A single `llm_telemetry` collection, written by `backend`'s `LlmTelemetryService.record()` (see root `CLAUDE.md` constraint #14) and read by `functions/llm-metrics`. No retention policy is enforced yet — see that constraint for the planned TTL mitigation.

---

## 6. Environment Variables Reference

Full list of variables for your `.env` file (see [infra/.env.example](../infra/.env.example) for the template):

| Variable | Required | Description |
|----------|----------|-------------|
| `NEON_APP_URL` | Yes | PostgreSQL JDBC/connection URL for app data (Neon `fga_registry` DB) |
| `AUTH0_ISSUER_URI` | Yes | Auth0 tenant URL, e.g. `https://dev-xxx.us.auth0.com/` |
| `AUTH0_AUDIENCE` | Yes | Auth0 API identifier, e.g. `api.enpsecurechat.com` |
| `QDRANT_API_KEY` | Yes | API key for local Qdrant instance |
| `ANTHROPIC_API_KEY` | Yes | Anthropic API key for Claude access |
| `INTERNAL_METRICS_KEY` | Yes | Shared secret (not a third-party credential) for `X-Internal-Key` on `GET /internal/llm-metrics` (ADR-002 telemetry) and `llm-metrics-fn`. Generate with `openssl rand -hex 32`; the same value must be configured on the monitoring-links poller. |
| `GCP_PROJECT_ID` | No | Defaults to `enp-securechat`. Explicit Firestore project id for the telemetry dual-write (§5) — only needed locally if you're running against a different GCP project. |

---

## 7. Startup Order

When bringing up the full stack for the first time:

```bash
# Step 1 — Start infrastructure services (no Keycloak — Auth0 is cloud)
docker compose up qdrant dlp-service -d

# Step 2 — Ingest documents
docker compose run --rm ingestion python -m src.main --manifest manifests/og-manifest.yaml

# Step 3 — Start backend and frontend
docker compose up backend frontend -d

# Step 4 — Open the app
# http://localhost:4200
```

For subsequent starts: `docker compose up -d`.
