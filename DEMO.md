# Demo Performance Runbook (temporary infra tuning)

Companion to [DEPLOYMENT.md](DEPLOYMENT.md). This document covers a **temporary, reversible**
override of the production Cloud Run topology to eliminate cold-start lag before showing the app
to a prospect. It does not change any application code.

Use the `/demo-start` and `/demo-stop` commands to apply/revert this. This file is the source of
truth they follow.

---

## 1. The problem

`DEPLOYMENT.md` already documents the cold-start cost of this topology (Section 1):

> `securechat-ingestion` (sentence-transformers) takes ~60s to cold-start; `securechat-dlp`
> (spaCy `pt_core_news_lg` + Presidio) takes ~30s.

Because `securechat-backend`, `securechat-ingestion`, and `securechat-dlp` all run with
`--min-instances=0` (the project's deliberate "zero idle cost" baseline), a chat request that
arrives after a period of inactivity pays a **cascade**, not a single cold start:

1. `securechat-backend` cold-starts (Spring Boot/Java 21, several seconds).
2. It calls `securechat-ingestion` to embed the prompt — cold-starts if idle, **~60s**.
3. It calls Claude, streams the answer.
4. It calls `securechat-dlp` to redact each sentence — cold-starts if idle, **~30s**.

Worst case (all three cold) is on the order of 90–100+ seconds before a demo viewer sees a
useful response — not acceptable for a live pitch.

## 2. Root cause is bigger than "cold start" — but check live config, not just IaC

Neither the manual `gcloud run deploy` commands in `DEPLOYMENT.md` (Phase 3/4) nor any of the
three GitHub Actions workflows (`backend.yml`, `dlp.yml`, `ingestion.yml`) ever pass `--memory` or
`--cpu`. **This does not mean the services are running on Cloud Run platform defaults** — an
earlier version of this doc claimed that and was wrong. The live revisions had already been
hand-tuned at some point outside of any script in this repo (confirmed 2026-06-16 via
`gcloud run revisions describe`):

| Service | Actual live config before any demo tuning |
|---|---|
| `securechat-backend` | `cpu=1, memory=1Gi` |
| `securechat-ingestion` | `cpu=1, memory=4Gi` |
| `securechat-dlp` | `cpu=1, memory=2Gi` |

None of this is captured in `DEPLOYMENT.md` or the workflows — **always run
`gcloud run revisions describe $(gcloud run services describe SERVICE --region=$REGION --format='value(status.latestReadyRevisionName)') --region=$REGION --format='value(spec.containers[0].resources.limits)'`
before changing memory/CPU on any of these services.** Lowering `securechat-ingestion` from its
real baseline of 4Gi to 2Gi during testing on 2026-06-16 caused an immediate, real failure: the
container OOMed during startup ("Memory limit of 2048 MiB exceeded with 2058 MiB used") and the
new revision never went live — Cloud Run correctly kept serving the prior good revision, so there
was no outage, but the deploy failed. Root cause: `ingestion/Dockerfile` hardcodes
`--workers ${UVICORN_WORKERS:-2}` (2 pre-forked Uvicorn workers "so the model stays warm", per
`ingestion/CLAUDE.md`) — each worker loads its own independent copy of the sentence-transformers
model, roughly doubling the real memory floor versus a single-worker assumption. This is fixed,
independent of CPU count.

## 3. Two independent levers — keep this distinction

| Tier | Change | Idle cost? | Revert after demo? |
|---|---|---|---|
| **1. Right-sizing** | `--memory` / `--cpu` per service | No — only affects the active-processing billing rate, not idle | No, safe to keep permanently |
| **2. Keep-warm** | `--min-instances=1` + `--cpu-boost` | **Yes** — `min-instances` bills idle memory/CPU 24/7 | **Yes — mandatory** |

Do not set `--no-cpu-throttling` alongside `min-instances=1` — that bills full active-CPU rate
around the clock instead of the discounted idle rate, multiplying the cost of this whole exercise.
Not used here.

## 4. Commands

Variables from `DEPLOYMENT.md` Section 0 (`PROJECT_ID`, `REGION`) must already be set in the shell.

### Go live (apply before the demo)

```bash
# Tier 1 — right-size memory/CPU (safe to leave on permanently)
# backend was already 1Gi/1cpu — this is a no-op, kept for completeness
gcloud run services update securechat-backend    --region=$REGION --memory=1Gi --cpu=1
# ingestion needs >2Gi to fit 2 model-loading workers — 4Gi is the proven-safe floor, do not lower it
gcloud run services update securechat-ingestion  --region=$REGION --memory=4Gi --cpu=2
gcloud run services update securechat-dlp        --region=$REGION --memory=4Gi --cpu=2

# Tier 2 — keep-warm (temporary, costs idle $, must be reverted)
gcloud run services update securechat-backend    --region=$REGION --min-instances=1 --cpu-boost
gcloud run services update securechat-ingestion  --region=$REGION --min-instances=1 --cpu-boost
gcloud run services update securechat-dlp        --region=$REGION --min-instances=1 --cpu-boost
```

### Revert (run as soon as the demo window ends)

```bash
gcloud run services update securechat-backend    --region=$REGION --min-instances=0
gcloud run services update securechat-ingestion  --region=$REGION --min-instances=0
gcloud run services update securechat-dlp        --region=$REGION --min-instances=0
```

This restores the documented "zero idle cost" baseline from `DEPLOYMENT.md` Section 1. Tier 1
memory/CPU sizing is intentionally left in place — it has no idle-cost downside.

## 5. Preheat the database tier

Neon's Free and Launch plans both suspend the compute endpoint after **5 minutes of inactivity**
— this is a fixed timeout on both plans (confirmed from the Neon "Change plan" pricing page); only
the Scale plan ("configurable scale to zero") lets you change or disable it. A single preheat just
before the demo is **not enough** if there's a natural pause longer than ~5 minutes mid-demo (e.g.
live Q&A) — Neon will suspend again and the next query pays the resume delay.

~5 minutes before the demo, log in and send one chat message to wake Neon's compute and populate
the backend's HikariCP pool. If a gap longer than ~4 minutes is likely during the demo itself, send
one more trivial query beforehand to keep the compute endpoint resumed. There is no `gcloud`
command for this — it's an app-level warmup, not infrastructure tuning.

**Do not upgrade the Neon plan to solve this.** Free → Launch does not change the 5-minute
scale-to-zero behavior (it's identical on both) — only Free → Scale does, and Scale's
$0.222/CU-hour pricing plus SOC2/HIPAA/SLA features are aimed at production multi-tenant load, not
a one-off demo. The Free tier's 2 CU ceiling is also the documented reason
`hikari.maximum-pool-size: 5` was chosen (root `CLAUDE.md`) — raising the compute ceiling without
revisiting that pool size wouldn't help a demo anyway. Revisit this only if Free's usage caps
(0.5 GB storage/project, 100 compute-hours/month) are actually being hit from crawler ingestion
volume — that's a capacity question, not a cold-start fix.

## 6. Verify before walking into the room

```bash
# All three services should report status True
for s in securechat-backend securechat-ingestion securechat-dlp; do
  gcloud run services describe $s --region=$REGION \
    --format="value(metadata.name,status.conditions[0].status)"
done

# End-to-end smoke test
curl -s https://api.$APP_DOMAIN/api/health
```

## 7. Cost expectations

Idle `min-instances=1` is billed at Cloud Run's discounted idle rate, not the active rate — but it
is not free. With the Tier 1 sizes above (7 GiB combined across the three services), expect low
single-digit dollars for a multi-day demo window if left running continuously. Check the
[GCP pricing calculator](https://cloud.google.com/products/calculator) for an exact figure in
`us-east4` before a longer window. The real risk isn't the per-day cost — it's **forgetting to
revert**, which turns a one-time demo expense into a silent recurring charge. Run `/demo-stop`
the moment the demo is over.
