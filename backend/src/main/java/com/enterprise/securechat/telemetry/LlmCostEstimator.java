package com.enterprise.securechat.telemetry;

import java.util.Map;

/**
 * Token counts here are a chars/4 heuristic, not the Anthropic API's real "usage" field —
 * getting exact counts would mean changing ClaudeService's return type on every call site
 * (complete/streamComplete), which several tests stub directly. The heuristic is good enough
 * for the trend-level cost tracking ADR-002 asks for; the resulting cost_usd is inherently
 * an estimate, matching how it's labeled downstream on the mvergara.net dashboard.
 */
public final class LlmCostEstimator {

    private LlmCostEstimator() {}

    private record Pricing(double inputPer1k, double outputPer1k) {}

    // USD per 1K tokens, approximate public pricing as of model release. Update if the
    // configured anthropic.model changes tier.
    private static final Pricing DEFAULT_PRICING = new Pricing(0.003, 0.015); // Sonnet tier
    private static final Map<String, Pricing> PRICING_BY_MODEL_PREFIX = Map.of(
            "claude-haiku", new Pricing(0.0008, 0.004),
            "claude-sonnet", new Pricing(0.003, 0.015),
            "claude-opus", new Pricing(0.015, 0.075)
    );

    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return Math.max(1, text.length() / 4);
    }

    public static double estimateCostUsd(String model, int inputTokens, int outputTokens) {
        var pricing = PRICING_BY_MODEL_PREFIX.entrySet().stream()
                .filter(e -> model != null && model.startsWith(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(DEFAULT_PRICING);
        return (inputTokens / 1000.0) * pricing.inputPer1k() + (outputTokens / 1000.0) * pricing.outputPer1k();
    }
}
