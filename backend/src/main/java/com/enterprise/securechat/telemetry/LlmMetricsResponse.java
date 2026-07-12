package com.enterprise.securechat.telemetry;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Field names are snake_case to match the cross-repo contract in monitoring-links'
 * ADR-002 spec verbatim — monitoring-links relays these numbers as-is, so any drift here
 * would silently break the aggregator without a code change on either side.
 *
 * avgLatencyMs and errorRatePct are nullable: an average/rate over zero requests is
 * undefined, not zero. A 0 here would render on the public status dashboard as
 * "responds instantly, never fails" instead of "no data this window." requests24h,
 * tokens24h, and costUsd24h stay primitive — those genuinely are 0 with no activity.
 */
public record LlmMetricsResponse(
        @JsonProperty("requests_24h") long requests24h,
        @JsonProperty("avg_latency_ms") Double avgLatencyMs,
        @JsonProperty("tokens_24h") long tokens24h,
        @JsonProperty("cost_usd_24h") double costUsd24h,
        @JsonProperty("error_rate_pct") Double errorRatePct
) {}
