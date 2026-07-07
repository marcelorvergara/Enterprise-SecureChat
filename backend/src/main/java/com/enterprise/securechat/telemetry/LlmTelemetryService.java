package com.enterprise.securechat.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

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

    private final LlmTelemetryRepository repository;

    public LlmTelemetryService(LlmTelemetryRepository repository) {
        this.repository = repository;
    }

    /**
     * Runs on the bounded llmTelemetryExecutor, never on a request thread. Failures are
     * logged, not rethrown — telemetry must never affect the user-facing chat response.
     */
    public void record(String endpoint, String model, long latencyMs, int inputTokens,
                        int outputTokens, double costUsd, boolean success, String errorMessage) {
        try {
            repository.save(new LlmTelemetry(
                    endpoint, model, (int) latencyMs, inputTokens, outputTokens,
                    BigDecimal.valueOf(costUsd), success, errorMessage));
        } catch (Exception e) {
            log.warn("Failed to persist LLM telemetry for endpoint {}: {}", endpoint, e.getMessage());
        }
    }
}
