package com.enterprise.securechat.telemetry;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface LlmTelemetryRepository extends JpaRepository<LlmTelemetry, UUID> {

    /**
     * Projection backing GET /internal/llm-metrics. Column aliases below are matched
     * to these getters by Spring Data's native-query projection support.
     */
    interface TrailingAggregate {
        long getRequests();
        Double getAvgLatencyMs();
        Long getTokens();
        Double getCostUsd();
        Double getErrorRatePct();
    }

    @Query(value = """
            SELECT
              COUNT(*)                                        AS requests,
              COALESCE(AVG(latency_ms), 0)                     AS avgLatencyMs,
              COALESCE(SUM(input_tokens + output_tokens), 0)   AS tokens,
              COALESCE(SUM(cost_usd), 0)                       AS costUsd,
              CASE WHEN COUNT(*) = 0 THEN 0
                   ELSE 100.0 * COUNT(*) FILTER (WHERE NOT success) / COUNT(*)
              END                                               AS errorRatePct
            FROM llm_telemetry
            WHERE occurred_at >= now() - interval '24 hours'
            """, nativeQuery = true)
    TrailingAggregate findTrailing24hAggregate();
}
