package com.enterprise.securechat.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class DlpClient {

    // Entities that hr-manager users are permitted to see unredacted.
    // FGA already controls which documents they can access; DLP should not
    // re-redact financial figures that the role is explicitly authorised to view.
    private static final List<String> HR_MANAGER_ALLOW = List.of("FINANCIAL_FIGURE");

    private final RestClient restClient;

    public DlpClient(@Qualifier("dlpRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public DlpResult analyze(String text, List<String> roles) {
        var allow = roles.contains("hr-manager") ? HR_MANAGER_ALLOW : List.<String>of();
        var response = restClient.post()
                .uri("/dlp/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new DlpRequest(text, allow))
                .retrieve()
                .body(DlpResponse.class);
        if (response == null) {
            return new DlpResult(text, 0);
        }
        return new DlpResult(response.cleanedText(), response.entitiesRedacted());
    }

    record DlpRequest(
            String text,
            @JsonProperty("allow_entities") List<String> allowEntities
    ) {}

    record DlpResponse(
            @com.fasterxml.jackson.annotation.JsonProperty("cleaned_text") String cleanedText,
            @com.fasterxml.jackson.annotation.JsonProperty("entities_redacted") int entitiesRedacted
    ) {}

    public record DlpResult(String cleanedText, int entitiesRedacted) {}
}
