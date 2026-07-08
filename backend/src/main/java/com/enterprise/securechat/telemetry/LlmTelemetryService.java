package com.enterprise.securechat.telemetry;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Firestore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Deliberately its own bean rather than a method on RagService: @Async only works through
 * Spring's CGLIB proxy, so a self-invoked method (this.record(...)) inside RagService would
 * silently run synchronously on the request thread instead of being dispatched to an executor.
 * Keeping this as a separate @Component means callers always go through the proxy, and the
 * explicit CompletableFuture.runAsync(..., llmTelemetryExecutor) in RagService makes the
 * dispatch obvious rather than implicit.
 */
@Component
public class LlmTelemetryService {

    private static final Logger log = LoggerFactory.getLogger(LlmTelemetryService.class);
    private static final String FIRESTORE_COLLECTION = "llm_telemetry";

    private final LlmTelemetryRepository repository;
    private final Firestore firestore; // nullable — see FirestoreConfig

    public LlmTelemetryService(LlmTelemetryRepository repository, Firestore firestore) {
        this.repository = repository;
        this.firestore = firestore;
    }

    /**
     * Runs on the bounded llmTelemetryExecutor, never on a request thread. Both writes below
     * are independent and best-effort — failures are logged, never rethrown, and a failure in
     * one must never affect the other or the caller. See root CLAUDE.md constraint #14: the
     * Firestore write is an additive projection for the status-page read path, not a
     * replacement for Postgres, and the two are allowed to drift on partial failure.
     */
    public void record(String endpoint, String model, long latencyMs, int inputTokens,
                        int outputTokens, double costUsd, boolean success, String errorMessage) {
        try {
            repository.save(new LlmTelemetry(
                    endpoint, model, (int) latencyMs, inputTokens, outputTokens,
                    BigDecimal.valueOf(costUsd), success, errorMessage));
        } catch (Exception e) {
            log.warn("Failed to persist LLM telemetry to Postgres for endpoint {}: {}", endpoint, e.getMessage());
        }

        if (firestore != null) {
            try {
                Map<String, Object> doc = new HashMap<>();
                // Must be a Firestore Timestamp, not a string — llm-metrics-fn's trailing-24h
                // range query depends on the native type, not lexicographic string comparison.
                doc.put("occurred_at", Timestamp.now());
                doc.put("endpoint", endpoint);
                doc.put("model", model);
                doc.put("latency_ms", (int) latencyMs);
                doc.put("input_tokens", inputTokens);
                doc.put("output_tokens", outputTokens);
                doc.put("cost_usd", costUsd);
                doc.put("success", success);
                doc.put("error_message", errorMessage);
                firestore.collection(FIRESTORE_COLLECTION).add(doc).get(3, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Failed to persist LLM telemetry to Firestore for endpoint {}: {}", endpoint, e.getMessage());
            }
        }
    }
}
