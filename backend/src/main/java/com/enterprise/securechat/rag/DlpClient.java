package com.enterprise.securechat.rag;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class DlpClient {

    private final RestClient restClient;

    public DlpClient(@Qualifier("dlpRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public DlpResult analyze(String text) {
        var response = restClient.post()
                .uri("/dlp/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new DlpRequest(text))
                .retrieve()
                .body(DlpResponse.class);
        if (response == null) {
            return new DlpResult(text, 0);
        }
        return new DlpResult(response.cleanedText(), response.entitiesRedacted());
    }

    record DlpRequest(String text) {}

    record DlpResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("cleaned_text") String cleanedText,
            @com.fasterxml.jackson.annotation.JsonProperty("entities_redacted") int entitiesRedacted
    ) {}

    public record DlpResult(String cleanedText, int entitiesRedacted) {}
}
