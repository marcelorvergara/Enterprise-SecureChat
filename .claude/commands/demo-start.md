---
description: Apply temporary performance tuning (memory/CPU sizing, min-instances=1, cpu-boost) to the production Cloud Run services ahead of a live demo, and preheat Neon. Follows the runbook in DEMO.md.
---

Prepare the production topology for a live demo. Follow the runbook in `DEMO.md`.

First, confirm the GCP project variables are set in the shell (from `DEPLOYMENT.md` Section 0):
```bash
echo "PROJECT_ID=$PROJECT_ID  REGION=$REGION"
```
If either is empty, prompt the user to set them before continuing.

Tell the user this will start an idle Cloud Run cost (low single-digit $/day per `DEMO.md`
Section 7) until they run `/demo-stop`, and confirm before proceeding.

Then run, in order:

0. **Check live config before changing anything.** The deploy scripts in this repo never set
   `--memory`/`--cpu`, but the live revisions may already be hand-tuned beyond what's in any
   script — confirm before assuming defaults:
   ```bash
   for s in securechat-backend securechat-ingestion securechat-dlp; do
     rev=$(gcloud run services describe $s --region=$REGION --format='value(status.latestReadyRevisionName)')
     echo "$s ($rev):"
     gcloud run revisions describe $rev --region=$REGION --format='value(spec.containers[0].resources.limits)'
   done
   ```
   Compare against the table in `DEMO.md` Section 2. If a service is already at or above the
   Tier 1 target below, skip it — don't lower a value that's already higher.

1. **Tier 1 — right-size memory/CPU** (safe to leave on permanently, no idle-cost impact):
   ```bash
   gcloud run services update securechat-backend    --region=$REGION --memory=1Gi --cpu=1
   gcloud run services update securechat-ingestion  --region=$REGION --memory=4Gi --cpu=2
   gcloud run services update securechat-dlp        --region=$REGION --memory=4Gi --cpu=2
   ```
   `securechat-ingestion` needs at least 4Gi — it runs 2 pre-forked Uvicorn workers
   (`ingestion/Dockerfile`), each loading its own copy of the embedding model. 2Gi was tried on
   2026-06-16 and OOMed during startup. Do not lower it.

2. **Tier 2 — keep-warm** (temporary, this is what costs idle $ — `/demo-stop` must be run after):
   ```bash
   gcloud run services update securechat-backend    --region=$REGION --min-instances=1 --cpu-boost
   gcloud run services update securechat-ingestion  --region=$REGION --min-instances=1 --cpu-boost
   gcloud run services update securechat-dlp        --region=$REGION --min-instances=1 --cpu-boost
   ```
   Do not add `--no-cpu-throttling` — combined with `min-instances=1` it bills full active-CPU
   rate 24/7 instead of the discounted idle rate.

3. **Verify** all three services report `status.conditions[0].status == True`:
   ```bash
   for s in securechat-backend securechat-ingestion securechat-dlp; do
     gcloud run services describe $s --region=$REGION \
       --format="value(metadata.name,status.conditions[0].status)"
   done
   ```
   If any return `False`, check logs immediately — do not proceed to the demo.

4. **Preheat Neon**: tell the user to log into the app and send one chat message now, a few
   minutes before the demo starts, to wake Neon's compute endpoint and populate the backend's
   connection pool. This step has no `gcloud` equivalent — it must be done by using the app.

5. Run the smoke test from `DEMO.md` Section 6:
   ```bash
   curl -s https://api.$APP_DOMAIN/api/health
   ```

Finish by reminding the user: this configuration is now accruing idle cost. Run `/demo-stop` the
moment the demo is over.
