package com.enterprise.securechat.telemetry;

import com.google.api.core.ApiFutureCallback;
import com.google.api.core.ApiFutures;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.Firestore;
import com.google.common.util.concurrent.MoreExecutors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

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
                // Non-blocking: a fresh Cloud Run instance's first gRPC call to Firestore can
                // exceed several seconds establishing the channel (TLS + token exchange), which
                // a blocking .get(timeout) would eat directly out of the bounded
                // llmTelemetryExecutor pool (core 2/max 4). Observed in production — a cold
                // instance's first write timed out at 3s while Postgres succeeded normally.
                // addCallback logs the outcome without ever occupying a pool thread waiting.
                ApiFutures.addCallback(
                        firestore.collection(FIRESTORE_COLLECTION).add(doc),
                        new ApiFutureCallback<DocumentReference>() {
                            @Override
                            public void onSuccess(DocumentReference result) {
                                // best-effort write succeeded — nothing to do
                            }

                            @Override
                            public void onFailure(Throwable t) {
                                log.warn("Failed to persist LLM telemetry to Firestore for endpoint {}: {}",
                                        endpoint, t.getMessage());
                            }
                        },
                        MoreExecutors.directExecutor());
            } catch (Exception e) {
                log.warn("Failed to persist LLM telemetry to Firestore for endpoint {}: {}", endpoint, e.getMessage());
            }
        }
    }
}
