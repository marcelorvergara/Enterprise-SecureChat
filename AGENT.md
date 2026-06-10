# Enterprise-SecureChat: GCP Deployment Guide

## 0. Pre-Deployment Variables

Set these shell variables once at the start of every session — all commands below reference them.

```bash
# GCP project targeting
export PROJECT_ID=enp-securechat          # change for a new GCP project
export REGION=us-east4
export REGISTRY=$REGION-docker.pkg.dev/$PROJECT_ID/securechat-repo
export BUCKET=$PROJECT_ID-crawler-state

# External service credentials (same for every GCP project unless you rotate them)
export NEON_DB_URL="jdbc:postgresql://ep-billowing-frost-ap4xcz7j.c-7.us-east-1.aws.neon.tech/fga_registry?sslmode=require"
export NEON_DB_USER="neondb_owner"
export QDRANT_CLOUD_URL="https://e8ab552c-8f40-4bbd-86ec-66758725942f.us-east4-0.gcp.cloud.qdrant.io:6333"
export AUTH0_ISSUER="https://dev-ll8lyragj23p2c7l.us.auth0.com/"
export AUTH0_AUDIENCE="api.enpsecurechat.com"
export APP_DOMAIN="enpsecurechat.com"
```

> **When deploying to a new GCP project:** only `PROJECT_ID` and `BUCKET` change. All external service credentials (Neon, Qdrant, Auth0, Anthropic) remain the same unless you explicitly rotate or replace them.

---

## 1. Objective & Architectural Boundaries

- **Role:** GCP Cloud Architect / Senior DevOps Engineer. Do not modify Java, Angular, Python, or TypeScript application source code.
- **Cost model:** Zero idle cost. All services run on Cloud Run with `--min-instances=0` (scale-to-zero).
- **Security mesh:** `securechat-dlp` and `securechat-ingestion` are deployed with `--ingress=all --allow-unauthenticated`. **Do NOT use `--ingress=internal`** — Cloud Run-to-Cloud Run calls via `.run.app` URLs travel over the public internet and are treated as external traffic by Google's load balancer; `--ingress=internal` silently blocks them with a Google infrastructure 404 before the container ever receives the request. The IAM `allUsers:run.invoker` binding is the access boundary, not network ingress.
- **Cold-start timeouts:** `securechat-ingestion` (sentence-transformers) takes ~60 s to cold-start; `securechat-dlp` (spaCy `pt_core_news_lg` + Presidio) takes ~30 s. The backend is already configured with matching read-timeouts (`embed-service.read-timeout: 90000`, `dlp-service.read-timeout: 60000`). The nginx frontend has `proxy_read_timeout 240s`.

---

## 2. System Topology

```mermaid
flowchart TD
    classDef internet fill:#fafafa,stroke:#616161,stroke-width:2px,stroke-dasharray: 5 5;
    classDef run fill:#e8f5e9,stroke:#388e3c,stroke-width:2px;
    classDef saas fill:#fff3e0,stroke:#f57c00,stroke-width:2px;
    classDef storage fill:#ede7f6,stroke:#5e35b1,stroke-width:2px;

    User((Corporate User<br/>Browser)):::internet
    ANP_Portal[Public ANP E&P Portal<br/>gov.br/anp]:::internet
    Anthropic[Anthropic Claude API<br/>claude-sonnet-4-6]:::internet

    subgraph Auth0_Cloud [Managed Identity Plane]
        Auth0[Auth0 Tenant<br/>Free Tier Cloud OIDC]:::saas
    end

    subgraph SaaS_Data_Tier [Serverless Data Tier]
        Neon[(Neon Serverless Postgres<br/>fga_registry DB · Pool Max: 5)]:::saas
        Qdrant_Cloud[(Qdrant Cloud Cluster<br/>Free Tier Vector DB)]:::saas
    end

    subgraph GCP_Cloud_Run [GCP Cloud Run · Scaled to Zero]
        CR_Front[Cloud Run: securechat-frontend<br/>Domain: enpsecurechat.com · Port 80]:::run
        CR_Back[Cloud Run: securechat-backend<br/>Domain: api.enpsecurechat.com · Port 3000]:::run

        CR_Dlp[Cloud Run: securechat-dlp<br/>Public Ingress · IAM Protected · Port 8000]:::run
        CR_Ing[Cloud Run: securechat-ingestion<br/>Public Ingress · IAM Protected · Port 8001]:::run

        Job_Crawler[Cloud Run Job: anp-crawler<br/>Scheduled Weekly · BRT Monday 03:00]:::run
    end

    subgraph Storage_Tier [GCS Object Store]
        Bucket_State[(GCS State Bucket<br/>$PROJECT_ID-crawler-state)]:::storage
    end

    User ===>|1. OIDC login| Auth0
    Auth0 -.->|2. JWT with role claims| User
    User ===>|3. HTTPS static assets| CR_Front
    User ===>|4. REST + JWT| CR_Back

    CR_Back ===>|Embed prompt · Port 8001| CR_Ing
    CR_Back ===>|DLP redact response · Port 8000| CR_Dlp

    CR_Back ===>|JWKS verification| Auth0
    CR_Back ===|Short transactions · SSL| Neon
    CR_Back ===>|FGA vector query| Qdrant_Cloud
    CR_Back ===>|RAG-augmented prompt| Anthropic

    CR_Ing ===>|Idempotent vector upsert| Qdrant_Cloud

    Job_Crawler ==>|Persist crawler state| Bucket_State
    Job_Crawler ==>|POST chunks to /ingest| CR_Ing
    Job_Crawler -.->|BFS scrape PDF/HTML| ANP_Portal
```

---

## 3. Local Docker Compose Development

Use this before or instead of Cloud Run for local testing. All services run in Docker; nginx proxies the Angular app to the Spring Boot backend on the Docker network.

**Prerequisites:** `infra/.env` filled in (copy from `infra/.env.example`).

```bash
cd infra
docker compose up -d
```

- The `frontend` service mounts `infra/nginx.local.conf` at runtime, overriding the Cloud Run nginx config baked into the image. This routes `/api/` to `http://backend:3000` on the Docker network instead of the Cloud Run `.run.app` URL.
- The `backend` service reads all credentials from `infra/.env` via `env_file`.
- The local `qdrant` container runs idle if `QDRANT_URL` in `.env` points to Qdrant Cloud — this is intentional so local tests use the same vector index as production.
- There is **no Keycloak** — identity is Auth0 cloud. The `keycloak` service was removed from docker-compose.

One-shot document indexing (ingestion container must be running):
```bash
docker compose run --rm ingestion python -m src.main --manifest manifests/og-manifest.yaml
```

---

## 4. Cloud Run Deployment

Run phases in order. Every command references the variables from Section 0.

### Phase 0 — Enable GCP APIs *(once per new project)*

```bash
gcloud config set project $PROJECT_ID

gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  cloudbuild.googleapis.com \
  cloudscheduler.googleapis.com \
  cloudresourcemanager.googleapis.com \
  secretmanager.googleapis.com \
  storage.googleapis.com
```

### Phase 1 — Artifact Registry + Secret Manager

Create the Docker repository:
```bash
gcloud artifacts repositories create securechat-repo \
  --repository-format=docker --location=$REGION
gcloud auth configure-docker $REGION-docker.pkg.dev
```

Create the three secrets (run interactively — paste values when prompted):
```bash
printf '%s' "$ANTHROPIC_API_KEY_VALUE"       | gcloud secrets create ANTHROPIC_API_KEY       --data-file=-
printf '%s' "$QDRANT_API_KEY_VALUE"           | gcloud secrets create QDRANT_API_KEY           --data-file=-
printf '%s' "$SPRING_DATASOURCE_PASSWORD_VAL" | gcloud secrets create SPRING_DATASOURCE_PASSWORD --data-file=-
```

Grant the default Compute SA permission to read the secrets:
```bash
PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format='value(projectNumber)')
SA="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"

for SECRET in ANTHROPIC_API_KEY QDRANT_API_KEY SPRING_DATASOURCE_PASSWORD; do
  gcloud secrets add-iam-policy-binding $SECRET \
    --member="serviceAccount:${SA}" \
    --role="roles/secretmanager.secretAccessor"
done
```

### Phase 2 — Build Images

> **Before building the frontend:** update `set $backend_host` in `frontend/nginx.conf` to the new backend's `.run.app` URL. You don't know this URL until after Phase 4's first backend deploy — deploy the backend first with a placeholder, capture its URL, update `nginx.conf`, then rebuild and redeploy the frontend.
>
> **nginx proxy_pass rule:** `proxy_pass https://$backend_host;` must have **no trailing path**. Adding a path (e.g. `/api/`) causes nginx to ignore the request URI and send every request to that static path, returning 404 for all endpoints. This is nginx behavior specific to variable-containing proxy_pass values.
>
> **Why `.run.app` and not `api.enpsecurechat.com`:** if Cloudflare proxies the `api` subdomain (orange cloud), it intercepts ACME challenges and the Cloud Run SSL certificate never provisions. Pointing nginx directly at the `.run.app` URL bypasses the domain mapping dependency.

Build each component from its own directory:
```bash
# From project root
cd backend      && gcloud builds submit --tag $REGISTRY/securechat-backend:latest     --region=$REGION . && cd ..
cd dlp-service  && gcloud builds submit --tag $REGISTRY/securechat-dlp:latest         --region=$REGION . && cd ..
cd ingestion    && gcloud builds submit --tag $REGISTRY/securechat-ingestion:latest   --region=$REGION . && cd ..
cd frontend     && gcloud builds submit --tag $REGISTRY/securechat-frontend:latest    --region=$REGION . && cd ..
```

### Phase 3 — Deploy Internal Services

Provision the crawler state bucket:
```bash
gcloud storage buckets create gs://$BUCKET --location=$REGION
```

Deploy DLP (port 8000):
```bash
gcloud run deploy securechat-dlp \
  --image $REGISTRY/securechat-dlp:latest \
  --region=$REGION --port=8000 --min-instances=0 \
  --ingress=all --allow-unauthenticated
```

Deploy Ingestion (port 8001):
```bash
gcloud run deploy securechat-ingestion \
  --image $REGISTRY/securechat-ingestion:latest \
  --region=$REGION --port=8001 --min-instances=0 \
  --ingress=all --allow-unauthenticated \
  --set-env-vars="QDRANT_URL=$QDRANT_CLOUD_URL" \
  --set-secrets="QDRANT_API_KEY=QDRANT_API_KEY:latest"
```

Capture URLs for the next phase:
```bash
DLP_URL=$(gcloud run services describe securechat-dlp    --region=$REGION --format='value(status.url)')
ING_URL=$(gcloud run services describe securechat-ingestion --region=$REGION --format='value(status.url)')
```

### Phase 4 — Deploy Backend & Frontend

Deploy the backend:
```bash
gcloud run deploy securechat-backend \
  --image $REGISTRY/securechat-backend:latest \
  --region=$REGION --port=3000 --min-instances=0 \
  --ingress=all --allow-unauthenticated \
  --set-env-vars="SPRING_DATASOURCE_URL=$NEON_DB_URL,SPRING_DATASOURCE_USERNAME=$NEON_DB_USER,QDRANT_URL=$QDRANT_CLOUD_URL,AUTH0_ISSUER_URI=$AUTH0_ISSUER,AUTH0_AUDIENCE=$AUTH0_AUDIENCE,DLP_SERVICE_URL=$DLP_URL,EMBED_SERVICE_URL=$ING_URL" \
  --set-secrets="ANTHROPIC_API_KEY=ANTHROPIC_API_KEY:latest,QDRANT_API_KEY=QDRANT_API_KEY:latest,SPRING_DATASOURCE_PASSWORD=SPRING_DATASOURCE_PASSWORD:latest"
```

Capture the backend URL and update `frontend/nginx.conf` before building the frontend:
```bash
BACKEND_URL=$(gcloud run services describe securechat-backend --region=$REGION --format='value(status.url)')
# Strip the https:// prefix — nginx.conf uses: set $backend_host "xxx.run.app";
BACKEND_HOST=$(echo $BACKEND_URL | sed 's|https://||')
echo "Update set \$backend_host \"$BACKEND_HOST\"; in frontend/nginx.conf, then rebuild the frontend image."
```

After updating `nginx.conf`, rebuild and deploy the frontend:
```bash
cd frontend && gcloud builds submit --tag $REGISTRY/securechat-frontend:latest --region=$REGION . && cd ..

gcloud run deploy securechat-frontend \
  --image $REGISTRY/securechat-frontend:latest \
  --region=$REGION --port=80 --min-instances=0 \
  --ingress=all --allow-unauthenticated
```

### Phase 5 — Crawler Job

> **Known gcloud CLI bug:** `--add-volume-mount` incorrectly rejects valid Unix absolute paths. Use the YAML manifest instead.

Edit `infra/crawler-job.yaml`: replace `INGEST_URL` with `$ING_URL/ingest` and `namespace` with your project number. Then apply:
```bash
gcloud run jobs replace infra/crawler-job.yaml --region=$REGION
```

Grant the Compute SA write access to the state bucket:
```bash
PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format='value(projectNumber)')
gcloud storage buckets add-iam-policy-binding gs://$BUCKET \
  --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --role="roles/storage.objectAdmin"
```

Create the weekly Cloud Scheduler trigger (Mondays 03:00 BRT):
```bash
gcloud scheduler jobs create http anp-crawler-schedule \
  --location=$REGION \
  --schedule="0 3 * * 1" \
  --uri="https://$REGION-run.googleapis.com/apis/run.googleapis.com/v1/namespaces/${PROJECT_NUMBER}/jobs/anp-crawler-job:run" \
  --message-body="{}" \
  --oauth-service-account-email="${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --time-zone="America/Sao_Paulo"
```

### Phase 6 — Custom Domain Mapping

> Use `gcloud beta run domain-mappings` — the GA command group errors on managed Cloud Run.

```bash
gcloud beta run domain-mappings create \
  --service=securechat-frontend --domain=$APP_DOMAIN \
  --platform=managed --region=$REGION

gcloud beta run domain-mappings create \
  --service=securechat-backend --domain=api.$APP_DOMAIN \
  --platform=managed --region=$REGION
```

Each command outputs A/AAAA records — add them at your DNS registrar. SSL certificate provisioning takes up to 24 hours. Do not loop-ping the custom domains during provisioning.

---

## 5. Verification Checklist

Run after every phase. Do not rely on clean CLI exit codes alone.

**Service readiness:**
```bash
gcloud run services describe [SERVICE_NAME] --region=$REGION \
  --format="value(status.conditions[0].status,status.conditions[0].message)"
```
Must return `True`. `False` with no message means the container crashed on startup — inspect logs immediately.

**Log inspection:**
```bash
gcloud logging read \
  "resource.type=cloud_run_revision AND resource.labels.service_name=[SERVICE_NAME]" \
  --limit=100 --format="value(textPayload)"
```

| Service | What to confirm |
|---|---|
| `securechat-frontend` | nginx starts without `[emerg] host not found in upstream` |
| `securechat-backend` | No `HikariPool-1 - Connection is not available` or `SQLException` |
| `securechat-ingestion` | Uvicorn prints startup completion; no `ModuleNotFoundError` |
| `securechat-dlp` | Uvicorn prints startup completion; no spaCy model exceptions |

**Ingress audit** (both must show `run.googleapis.com/ingress: all`):
```bash
gcloud run services describe securechat-ingestion --region=$REGION --format='value(spec.template.metadata.annotations)'
gcloud run services describe securechat-dlp        --region=$REGION --format='value(spec.template.metadata.annotations)'
```
If either shows `ingress=internal`, Cloud Run-to-Cloud Run calls will be blocked at the load balancer with a Google infrastructure 404. The container will show zero log entries at the time of the call — that is the diagnostic signal.

**End-to-end smoke test:**
```bash
# Should return 200 with a JSON health object
curl -s https://api.$APP_DOMAIN/api/health
```
