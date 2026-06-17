package com.enterprise.securechat.rag;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Objects;

@Component
public class EmbedClient {

    private final RestClient restClient;

    public EmbedClient(@Qualifier("embedRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public List<Float> embed(String text) {
        var response = restClient.post()
                .uri("/embed")
                .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                .body(new EmbedRequest(text))
                .retrieve()
                .body(EmbedResponse.class);
        if (response == null || response.vector() == null) {
            throw new IllegalStateException("Embed service returned empty response");
        }
        return response.vector();
    }

    record EmbedRequest(String text) {}

    record EmbedResponse(List<Float> vector) {}
}
