---
description: Deploy one or more services to GCP Cloud Run (backend, dlp, ingestion) or the Angular frontend to Firebase Hosting. Follows the runbook in DEPLOYMENT.md.
---

Deploy services for this project to GCP / Firebase. Follow the runbook in `DEPLOYMENT.md`.

First, ask the user what they want to deploy:
1. **All services** (full deployment)
2. **Backend only** (`securechat-backend` Cloud Run)
3. **DLP service only** (`securechat-dlp` Cloud Run)
4. **Ingestion service only** (`securechat-ingestion` Cloud Run)
5. **Frontend only** (Firebase Hosting)
6. **Crawler job only** (`anp-crawler-job` Cloud Run Job)

Then confirm the GCP project variables are set in the shell (from DEPLOYMENT.md Section 0):
```bash
echo "PROJECT_ID=$PROJECT_ID  REGION=$REGION  REGISTRY=$REGISTRY"
```
If any are empty, prompt the user to set them before continuing.

For **backend/dlp/ingestion** deploys:
- Build with `gcloud builds submit` to Artifact Registry
- Deploy with `gcloud run deploy` using the flags in DEPLOYMENT.md Phase 3/4
- Run the verification checklist from DEPLOYMENT.md Section 5 after deploy

For **frontend** deploys:
- `cd frontend && npm run build && cd ..`
- `firebase deploy --only hosting`

For **crawler job** deploys:
- Edit `infra/crawler-job.yaml` with the current `$ING_URL` value
- `gcloud run jobs replace infra/crawler-job.yaml --region=$REGION`

After any deploy, verify the service is healthy:
```bash
gcloud run services describe <SERVICE_NAME> --region=$REGION \
  --format="value(status.conditions[0].status,status.conditions[0].message)"
```
Must return `True`. If `False`, check logs immediately.
