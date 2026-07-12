# llm-metrics Cloud Function — Node.js / Gen2

Serves the same JSON contract as `backend`'s `GET /internal/llm-metrics`, from Firestore instead of Postgres, so `monitoring-links`' status-page poll doesn't depend on a JVM Cloud Run service waking from a cold start. See root `CLAUDE.md` constraint #14.

## Dev Commands

```bash
npm install
npm start                    # functions-framework --target=llmMetrics, local :8080
```

## Source Layout

```
index.js       HTTP handler — X-Internal-Key auth + trailing-24h Firestore aggregate
package.json   @google-cloud/firestore, @google-cloud/functions-framework
```

There is no Postgres/Neon awareness here at all — this function only ever reads Firestore. It has no relationship to `backend`'s `LlmTelemetryRepository`.

## Auth Contract

Identical shared-secret model to `InternalMetricsController` on the Java backend: `X-Internal-Key` header, compared against `INTERNAL_METRICS_KEY` (same Secret Manager secret, no separate value to distribute) via `crypto.timingSafeEqual`. Unlike Java's `MessageDigest.isEqual`, `timingSafeEqual` **throws** on mismatched buffer lengths rather than returning `false` — `isValidKey()`'s length guard exists specifically to avoid that crash, not as a redundant check. Missing/blank `INTERNAL_METRICS_KEY` fails the function at module load (cold start), not per-request.

## JSON Contract

Byte-identical to `LlmMetricsResponse` on the backend — do not rename these fields, `monitoring-links` relays them verbatim:

```json
{
  "requests_24h": 42,
  "avg_latency_ms": 812.3,
  "tokens_24h": 18344,
  "cost_usd_24h": 0.1122,
  "error_rate_pct": 2.38
}
```

`avg_latency_ms` and `error_rate_pct` are `null` (not `0`) when `requests_24h` is `0` for that window — an average/rate over zero samples is undefined, and `0` would render on the public status dashboard as "responds instantly, never fails" instead of "no data this window." `requests_24h`/`tokens_24h`/`cost_usd_24h` stay `0` in that case since those genuinely are zero with no activity. See the `requests > 0 ? ... : null` ternaries in `index.js`.

**This is a hand-maintained duplicate of `LlmTelemetryRepository`'s aggregation on the Java backend, not a shared library** — see root `CLAUDE.md` constraint #15. Any change to the aggregation logic or response shape on either side (this file's `index.js`, or `backend`'s `LlmTelemetryRepository.java`/`LlmMetricsResponse.java`) should prompt checking whether the other needs the identical change. There is no test coverage here to catch drift (see below) and no coverage on the Java side for this function either.

## Firestore

Reads the `llm_telemetry` collection in the `(default)` Native-mode database (`enp-securechat`, `us-east4`), written by `backend`'s `LlmTelemetryService.record()` dual-write. Runs under the dedicated `llm-metrics-fn` service account (`roles/datastore.viewer` + `roles/secretmanager.secretAccessor` on `INTERNAL_METRICS_KEY` only) — not the default compute SA.

## Deploy

```bash
gcloud functions deploy llm-metrics-fn \
  --gen2 --project=enp-securechat --region=us-east4 --runtime=nodejs22 \
  --source=functions/llm-metrics --entry-point=llmMetrics --trigger-http \
  --allow-unauthenticated \
  --service-account=llm-metrics-fn@enp-securechat.iam.gserviceaccount.com \
  --set-secrets=INTERNAL_METRICS_KEY=INTERNAL_METRICS_KEY:latest \
  --memory=256Mi --timeout=30s --min-instances=0 --max-instances=5
```

`--allow-unauthenticated` mirrors `InternalMetricsController`'s rationale — auth is the shared-secret header, not GCP IAM invoker identity. **After every deploy, actually run the auth test** (`curl` with no header / wrong key / correct key) rather than trusting a clean `gcloud functions deploy` exit code — an org policy silently blocking `--allow-unauthenticated` would surface as a `403` from IAM before the request ever reaches this code, which looks superficially like a different failure.

## Testing

There is no test framework or test file in this package (`package.json` has only a `start` script) — logic changes here are verified by deploying and `curl`-ing the live function directly (see Deploy above), not by an automated suite. If this function grows enough logic to warrant one, add it deliberately rather than bolting a harness onto a single fix.
