---
description: Revert the temporary demo performance tuning — sets min-instances back to 0 on all three Cloud Run services to stop the recurring idle cost. Follows the runbook in DEMO.md.
---

Revert the demo keep-warm tuning applied by `/demo-start`. Follow the runbook in `DEMO.md`.

First, confirm the GCP project variables are set in the shell (from `DEPLOYMENT.md` Section 0):
```bash
echo "PROJECT_ID=$PROJECT_ID  REGION=$REGION"
```
If either is empty, prompt the user to set them before continuing.

Then run:

1. **Scale back to zero idle cost** (this is the only step that matters for billing):
   ```bash
   gcloud run services update securechat-backend    --region=$REGION --min-instances=0
   gcloud run services update securechat-ingestion  --region=$REGION --min-instances=0
   gcloud run services update securechat-dlp        --region=$REGION --min-instances=0
   ```
   This restores the "zero idle cost" baseline documented in `DEPLOYMENT.md` Section 1. Do not
   touch the Tier 1 `--memory`/`--cpu` sizing applied by `/demo-start` — those have no idle-cost
   downside and are meant to stay permanently (they likely fix a real OOM/GC risk, not just a
   demo nicety — see `DEMO.md` Section 2).

2. **Verify** `min-instances` is back to `0` on all three services:
   ```bash
   for s in securechat-backend securechat-ingestion securechat-dlp; do
     gcloud run services describe $s --region=$REGION \
       --format="value(metadata.name,spec.template.metadata.annotations['autoscaling.knative.dev/minScale'])"
   done
   ```
   Each line should show the service name with an empty or `0` minScale value.

3. Report back to the user that idle billing has stopped and the services will scale to zero again
   after their next idle period.
