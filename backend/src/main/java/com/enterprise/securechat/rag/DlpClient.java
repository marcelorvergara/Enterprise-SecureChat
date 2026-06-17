package com.enterprise.securechat.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Component
public class DlpClient {

    // Roles cleared to see O&G business figures (volumes, financials, variations).
    // FGA already restricts which documents they can reach; DLP must not re-redact data
    // that these roles are explicitly cleared to view.
    private static final List<String> PRIVILEGED_ROLES = List.of(
            "reserves-coordination",
            "reserves-management"
    );

    // Admin is top clearance — sees all O&G domain entities unredacted.
    // PII entities (PERSON, EMAIL_ADDRESS, PHONE_NUMBER, CREDIT_CARD) are still redacted.
    private static final List<String> ADMIN_ROLES = List.of("admin");

    private static final List<String> PRIVILEGED_ALLOW = List.of(
            "OG_VOLUMES",
            "RESERVES_VARIATION",
            "FINANCIAL_FIGURE"
    );

    private static final List<String> ADMIN_ALLOW = List.of(
            "OG_VOLUMES",
            "RESERVES_VARIATION",
            "FINANCIAL_FIGURE",
            "INVESTMENT_YEAR",
            "OG_CONTRACT",
            "COMMODITY_PRICE",
            "ANP_PROCESS"
    );

    private final RestClient restClient;

    public DlpClient(@Qualifier("dlpRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    public DlpResult analyze(String text, List<String> roles) {
        var allow = new ArrayList<String>();
        if (roles.stream().anyMatch(ADMIN_ROLES::contains)) {
            allow.addAll(ADMIN_ALLOW);
        } else if (roles.stream().anyMatch(PRIVILEGED_ROLES::contains)) {
            allow.addAll(PRIVILEGED_ALLOW);
        }
        var response = restClient.post()
                .uri("/dlp/analyze")
                .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
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
            @JsonProperty("cleaned_text") String cleanedText,
            @JsonProperty("entities_redacted") int entitiesRedacted
    ) {}

    public record DlpResult(String cleanedText, int entitiesRedacted) {}
}
