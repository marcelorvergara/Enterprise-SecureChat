package com.enterprise.securechat.telemetry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Polled server-to-server by monitoring-links, not by a browser — never add CORS config
 * for this path. Gated by a shared-secret header instead of the Auth0 JWT filter chain
 * used everywhere else under /api, because the caller is another backend, not a logged-in
 * user. SecurityConfig permits /internal/** so this class owns the entire auth decision.
 */
@RestController
@RequestMapping("/internal")
public class InternalMetricsController {

    private final LlmTelemetryRepository repository;
    private final String internalKey;

    public InternalMetricsController(LlmTelemetryRepository repository,
                                      @Value("${internal.metrics-key}") String internalKey) {
        // Fail fast and loud on boot rather than silently authenticating an empty
        // X-Internal-Key header at request time — see isValidKey()'s blank check below,
        // which is the actual runtime guard; this is a second, earlier tripwire.
        if (internalKey == null || internalKey.isBlank()) {
            throw new IllegalStateException(
                    "internal.metrics-key (INTERNAL_METRICS_KEY) must be set to a non-blank value");
        }
        this.repository = repository;
        this.internalKey = internalKey;
    }

    @GetMapping("/llm-metrics")
    public ResponseEntity<LlmMetricsResponse> getLlmMetrics(
            @RequestHeader(value = "X-Internal-Key", required = false) String providedKey) {
        if (!isValidKey(providedKey)) {
            return ResponseEntity.status(401).build();
        }

        var agg = repository.findTrailing24hAggregate();
        return ResponseEntity.ok(new LlmMetricsResponse(
                agg.getRequests(),
                roundToOrNull(agg.getAvgLatencyMs(), 1),
                agg.getTokens() != null ? agg.getTokens() : 0L,
                roundToZero(agg.getCostUsd(), 4),
                roundToOrNull(agg.getErrorRatePct(), 2)
        ));
    }

    // Constant-time comparison — this is a bearer credential shared with another backend,
    // not a user-facing check, so timing-attack resistance is cheap insurance to keep.
    //
    // The blank checks are not redundant with the constructor's fail-fast: MessageDigest.isEqual
    // on two empty byte arrays returns true, so without this, a misconfigured empty secret would
    // authenticate ANY request that sends an empty (but present) X-Internal-Key header — a silent
    // fail-open. The constructor guard should already prevent internalKey from ever being blank,
    // but this keeps the request path safe on its own if that invariant is ever weakened.
    private boolean isValidKey(String providedKey) {
        if (providedKey == null || providedKey.isBlank() || internalKey.isBlank()) return false;
        return MessageDigest.isEqual(
                providedKey.getBytes(StandardCharsets.UTF_8),
                internalKey.getBytes(StandardCharsets.UTF_8));
    }

    // avgLatencyMs / errorRatePct: null means "no requests in this window," not zero.
    // Must stay null through rounding so the response can't be misread as "instant, error-free."
    private Double roundToOrNull(Double value, int decimals) {
        if (value == null) return null;
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    // costUsd is genuinely 0 with no activity (SQL already COALESCEs it), so this one
    // stays a zero-defaulting primitive rather than nullable.
    private double roundToZero(Double value, int decimals) {
        if (value == null) return 0;
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
