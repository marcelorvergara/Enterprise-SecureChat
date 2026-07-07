package com.enterprise.securechat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.lang.NonNull;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class RestClientConfig {

    @Bean("qdrantRestClient")
    public RestClient qdrantRestClient(
            @Value("${qdrant.url}") @NonNull String qdrantUrl,
            @Value("${qdrant.api-key}") @NonNull String qdrantApiKey) {
        return RestClient.builder()
                .requestFactory(factory(5_000, 10_000))
                .baseUrl(qdrantUrl)
                .defaultHeader("api-key", qdrantApiKey)
                .build();
    }

    @Bean("embedRestClient")
    public RestClient embedRestClient(
            @Value("${embed-service.url}") @NonNull String embedUrl,
            @Value("${embed-service.connect-timeout}") int connectTimeout,
            @Value("${embed-service.read-timeout}") int readTimeout) {
        return RestClient.builder()
                .requestFactory(factory(connectTimeout, readTimeout))
                .baseUrl(embedUrl)
                .build();
    }

    @Bean("anthropicRestClient")
    public RestClient anthropicRestClient(
            @Value("${anthropic.api-key}") @NonNull String apiKey) {
        return RestClient.builder()
                .requestFactory(factory(5_000, 60_000))
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    @Bean("dlpRestClient")
    public RestClient dlpRestClient(
            @Value("${dlp-service.url}") @NonNull String dlpUrl,
            @Value("${dlp-service.connect-timeout}") int connectTimeout,
            @Value("${dlp-service.read-timeout}") int readTimeout) {
        return RestClient.builder()
                .requestFactory(factory(connectTimeout, readTimeout))
                .baseUrl(dlpUrl)
                .build();
    }

    @Bean("parseRestClient")
    public RestClient parseRestClient(
            @Value("${embed-service.url}") @NonNull String embedUrl) {
        return RestClient.builder()
                .requestFactory(factory(2_000, 30_000))
                .baseUrl(embedUrl)
                .build();
    }

    @Bean("ingestRestClient")
    public RestClient ingestRestClient(
            @Value("${embed-service.url}") @NonNull String embedUrl) {
        return RestClient.builder()
                .requestFactory(factory(2_000, 120_000))
                .baseUrl(embedUrl)
                .build();
    }

    /**
     * Executor for SSE streaming requests. Each concurrent /api/chat/stream call
     * runs on a dedicated thread (blocking I/O to Claude + DLP), so the Tomcat
     * request thread is released immediately after the SseEmitter is returned.
     */
    @Bean("sseExecutor")
    public ThreadPoolTaskExecutor sseExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("sse-");
        executor.initialize();
        return executor;
    }

    /**
     * Executor for fire-and-forget LLM telemetry writes (ADR-002). Bounded core/max/queue
     * so a Neon hiccup can't pile up unbounded work; DiscardPolicy means a full queue drops
     * the telemetry event instead of throwing RejectedExecutionException back onto whichever
     * request thread called CompletableFuture.runAsync(...) — telemetry is best-effort and
     * must never fail a chat response.
     */
    @Bean("llmTelemetryExecutor")
    public ThreadPoolTaskExecutor llmTelemetryExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("llm-telemetry-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.DiscardPolicy());
        executor.initialize();
        return executor;
    }

    @NonNull
    private ClientHttpRequestFactory factory(int connectMs, int readMs) {
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectMs);
        f.setReadTimeout(readMs);
        return f;
    }
}
