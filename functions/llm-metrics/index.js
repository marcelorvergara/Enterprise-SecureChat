const { Firestore, Timestamp } = require('@google-cloud/firestore');
const functions = require('@google-cloud/functions-framework');
const crypto = require('crypto');

const db = new Firestore(); // ADC via the function's runtime service account — no key file
const COLLECTION = 'llm_telemetry';

if (!process.env.INTERNAL_METRICS_KEY) {
  // Fail loud at cold start rather than silently 401-ing every request forever with no signal.
  throw new Error('INTERNAL_METRICS_KEY must be set');
}

functions.http('llmMetrics', async (req, res) => {
  if (!isValidKey(req.get('X-Internal-Key'), process.env.INTERNAL_METRICS_KEY)) {
    res.status(401).send();
    return;
  }

  const since = Timestamp.fromMillis(Date.now() - 24 * 60 * 60 * 1000);
  const snapshot = await db.collection(COLLECTION).where('occurred_at', '>=', since).get();

  let requests = snapshot.size;
  let totalLatencyMs = 0;
  let tokens = 0;
  let costUsd = 0;
  let errorCount = 0;

  snapshot.forEach((doc) => {
    const d = doc.data();
    totalLatencyMs += d.latency_ms;
    tokens += d.input_tokens + d.output_tokens;
    costUsd += d.cost_usd;
    if (!d.success) errorCount += 1;
  });

  // Field names are a cross-repo contract with monitoring-links, identical to
  // InternalMetricsController's LlmMetricsResponse on the Java backend — do not rename.
  //
  // avg_latency_ms / error_rate_pct: an average/rate over zero requests is undefined,
  // not zero. null here (not 0) so the status dashboard reads "no data this window"
  // instead of "responds instantly, never fails." requests_24h/tokens_24h/cost_usd_24h
  // stay 0 — those are genuinely zero with no activity.
  res.status(200).json({
    requests_24h: requests,
    avg_latency_ms: requests > 0 ? round(totalLatencyMs / requests, 1) : null,
    tokens_24h: tokens,
    cost_usd_24h: round(costUsd, 4),
    error_rate_pct: requests > 0 ? round((errorCount / requests) * 100, 2) : null,
  });
});

// Ported deliberately from InternalMetricsController.isValidKey() on the Java backend — same
// fail-closed, constant-time-when-lengths-match property, and the same blank-key guard (so a
// misconfigured empty INTERNAL_METRICS_KEY can never authenticate an empty header). Unlike
// Java's MessageDigest.isEqual, Node's crypto.timingSafeEqual THROWS on mismatched buffer
// lengths rather than returning false, so the length check below is required, not optional —
// a naive line-for-line port would crash on every wrong-length key instead of returning 401.
function isValidKey(provided, expected) {
  if (!provided || !expected) return false;
  const a = Buffer.from(provided, 'utf8');
  const b = Buffer.from(expected, 'utf8');
  if (a.length !== b.length) return false;
  return crypto.timingSafeEqual(a, b);
}

function round(value, decimals) {
  const factor = 10 ** decimals;
  return Math.round(value * factor) / factor;
}
