package com.enterprise.securechat.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalMetricsController.class)
@Import(com.enterprise.securechat.config.SecurityConfig.class)
@TestPropertySource(properties = "internal.metrics-key=test-shared-secret")
class InternalMetricsControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private LlmTelemetryRepository repository;

    @Test
    void getLlmMetrics_missingHeaderGets401() throws Exception {
        mvc.perform(get("/internal/llm-metrics"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getLlmMetrics_wrongKeyGets401() throws Exception {
        mvc.perform(get("/internal/llm-metrics").header("X-Internal-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getLlmMetrics_emptyKeyHeaderGets401() throws Exception {
        // Regression guard: MessageDigest.isEqual on two empty byte arrays returns true,
        // so without an explicit blank check an empty header would authenticate against
        // a misconfigured empty secret. The configured secret here is non-blank
        // ("test-shared-secret"), so this alone proves the empty *provided* key is
        // rejected regardless of what the configured secret is.
        mvc.perform(get("/internal/llm-metrics").header("X-Internal-Key", ""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getLlmMetrics_correctKeyGets200WithAggregate() throws Exception {
        var agg = new LlmTelemetryRepository.TrailingAggregate() {
            @Override public long getRequests() { return 42L; }
            @Override public Double getAvgLatencyMs() { return 1234.5; }
            @Override public Long getTokens() { return 98765L; }
            @Override public Double getCostUsd() { return 1.23456; }
            @Override public Double getErrorRatePct() { return 2.5; }
        };
        when(repository.findTrailing24hAggregate()).thenReturn(agg);

        mvc.perform(get("/internal/llm-metrics").header("X-Internal-Key", "test-shared-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests_24h").value(42))
                .andExpect(jsonPath("$.avg_latency_ms").value(1234.5))
                .andExpect(jsonPath("$.tokens_24h").value(98765))
                .andExpect(jsonPath("$.error_rate_pct").value(2.5));
        // Reaching 200 with only the X-Internal-Key header and no bearer token also proves
        // /internal/** bypasses the Auth0 JWT resource-server filter, as intended.
    }

    @Test
    void getLlmMetrics_zeroRequestsReturnsNullAvgLatencyAndErrorRateNotZero() throws Exception {
        // Regression guard: an average/rate over zero samples is undefined, not zero.
        // A literal 0 here would render on the public status dashboard as "responds
        // instantly, never fails" instead of "no data this window."
        var agg = new LlmTelemetryRepository.TrailingAggregate() {
            @Override public long getRequests() { return 0L; }
            @Override public Double getAvgLatencyMs() { return null; }
            @Override public Long getTokens() { return null; }
            @Override public Double getCostUsd() { return 0.0; }
            @Override public Double getErrorRatePct() { return null; }
        };
        when(repository.findTrailing24hAggregate()).thenReturn(agg);

        mvc.perform(get("/internal/llm-metrics").header("X-Internal-Key", "test-shared-secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requests_24h").value(0))
                .andExpect(jsonPath("$.avg_latency_ms").doesNotExist())
                .andExpect(jsonPath("$.tokens_24h").value(0))
                .andExpect(jsonPath("$.cost_usd_24h").value(0))
                .andExpect(jsonPath("$.error_rate_pct").doesNotExist());
    }

    @Test
    void constructor_blankInternalKeyFailsFastInsteadOfBootingWithAnOpenEndpoint() {
        // If INTERNAL_METRICS_KEY is ever unset/blank in an environment (e.g. a misconfigured
        // Cloud Run secret), this must crash the app loudly on boot rather than start up with
        // an endpoint that silently authenticates empty X-Internal-Key headers.
        assertThatThrownBy(() -> new InternalMetricsController(repository, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERNAL_METRICS_KEY");
        assertThatThrownBy(() -> new InternalMetricsController(repository, "   "))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new InternalMetricsController(repository, null))
                .isInstanceOf(IllegalStateException.class);
    }
}
