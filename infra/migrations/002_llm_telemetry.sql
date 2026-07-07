-- Enterprise SecureChat — LLM Telemetry (ADR-002: bespoke async telemetry logging)
-- Apply once via Neon SQL Editor, after init.sql.
-- Written by the backend's LlmTelemetryService — never read from the request thread.

CREATE TABLE IF NOT EXISTS llm_telemetry (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  endpoint       TEXT NOT NULL,          -- e.g. '/api/chat', '/api/chat/stream', '/api/chat/verify'
  model          TEXT NOT NULL,
  latency_ms     INTEGER NOT NULL,       -- Claude call latency only, not full RAG pipeline latency
  input_tokens   INTEGER NOT NULL,
  output_tokens  INTEGER NOT NULL,
  cost_usd       NUMERIC(10, 6) NOT NULL,
  success        BOOLEAN NOT NULL,
  error_message  TEXT
);

-- Supports the trailing-24h aggregate query behind GET /internal/llm-metrics
CREATE INDEX IF NOT EXISTS idx_llm_telemetry_occurred_at ON llm_telemetry(occurred_at DESC);
