package com.enterprise.securechat.telemetry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "llm_telemetry")
public class LlmTelemetry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "occurred_at", insertable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "endpoint", nullable = false)
    private String endpoint;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "input_tokens", nullable = false)
    private int inputTokens;

    @Column(name = "output_tokens", nullable = false)
    private int outputTokens;

    @Column(name = "cost_usd", nullable = false)
    private BigDecimal costUsd;

    @Column(name = "success", nullable = false)
    private boolean success;

    @Column(name = "error_message")
    private String errorMessage;

    public LlmTelemetry() {}

    public LlmTelemetry(String endpoint, String model, int latencyMs, int inputTokens,
                         int outputTokens, BigDecimal costUsd, boolean success, String errorMessage) {
        this.endpoint = endpoint;
        this.model = model;
        this.latencyMs = latencyMs;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.costUsd = costUsd;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public UUID getId() { return id; }
    public OffsetDateTime getOccurredAt() { return occurredAt; }
    public String getEndpoint() { return endpoint; }
    public String getModel() { return model; }
    public int getLatencyMs() { return latencyMs; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public BigDecimal getCostUsd() { return costUsd; }
    public boolean isSuccess() { return success; }
    public String getErrorMessage() { return errorMessage; }
}
