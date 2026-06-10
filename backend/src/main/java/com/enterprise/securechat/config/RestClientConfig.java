package com.enterprise.securechat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean("qdrantRestClient")
    public RestClient qdrantRestClient(
            @Value("${qdrant.url}") String qdrantUrl,
            @Value("${qdrant.api-key}") String qdrantApiKey) {
        return RestClient.builder()
                .requestFactory(factory(5_000, 10_000))
                .baseUrl(qdrantUrl)
                .defaultHeader("api-key", qdrantApiKey)
                .build();
    }

    @Bean("embedRestClient")
    public RestClient embedRestClient(
            @Value("${embed-service.url}") String embedUrl,
            @Value("${embed-service.connect-timeout}") int connectTimeout,
            @Value("${embed-service.read-timeout}") int readTimeout) {
        return RestClient.builder()
                .requestFactory(factory(connectTimeout, readTimeout))
                .baseUrl(embedUrl)
                .build();
    }

    @Bean("anthropicRestClient")
    public RestClient anthropicRestClient(
            @Value("${anthropic.api-key}") String apiKey) {
        return RestClient.builder()
                .requestFactory(factory(5_000, 60_000))
                .baseUrl("https://api.anthropic.com")
                .defaultHeader("x-api-key", apiKey)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
    }

    @Bean("dlpRestClient")
    public RestClient dlpRestClient(
            @Value("${dlp-service.url}") String dlpUrl,
            @Value("${dlp-service.connect-timeout}") int connectTimeout,
            @Value("${dlp-service.read-timeout}") int readTimeout) {
        return RestClient.builder()
                .requestFactory(factory(connectTimeout, readTimeout))
                .baseUrl(dlpUrl)
                .build();
    }

    @Bean("parseRestClient")
    public RestClient parseRestClient(
            @Value("${embed-service.url}") String embedUrl) {
        return RestClient.builder()
                .requestFactory(factory(2_000, 30_000))
                .baseUrl(embedUrl)
                .build();
    }

    @Bean("ingestRestClient")
    public RestClient ingestRestClient(
            @Value("${embed-service.url}") String embedUrl) {
        return RestClient.builder()
                .requestFactory(factory(2_000, 120_000))
                .baseUrl(embedUrl)
                .build();
    }

    private SimpleClientHttpRequestFactory factory(int connectMs, int readMs) {
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connectMs);
        f.setReadTimeout(readMs);
        return f;
    }
}
