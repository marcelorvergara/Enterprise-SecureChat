package com.enterprise.securechat.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Component
public class ClaudeService {

    private final RestClient restClient;
    private final String model;

    public ClaudeService(
            @Qualifier("anthropicRestClient") RestClient restClient,
            @Value("${anthropic.model}") String model) {
        this.restClient = restClient;
        this.model = model;
    }

    /**
     * Sends a blocking request to the Anthropic Messages API and returns the text response.
     * Blocking (non-streaming) is intentional — required for DLP post-processing in M4.
     *
     * @param systemPrompt assembled RAG context + instructions
     * @param messages     conversation history ending with the current user message
     */
    public String complete(String systemPrompt, List<ConversationMessage> messages) {
        var request = new MessagesRequest(model, 1024, systemPrompt, messages);
        var response = restClient.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(MessagesResponse.class);

        if (response == null || response.content() == null || response.content().isEmpty()) {
            throw new IllegalStateException("Claude API returned an empty response");
        }
        return response.content().stream()
                .filter(b -> "text".equals(b.type()))
                .map(ContentBlock::text)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No text block in Claude response"));
    }

    public record ConversationMessage(String role, String content) {}

    record MessagesRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            String system,
            List<ConversationMessage> messages
    ) {}

    record MessagesResponse(List<ContentBlock> content) {}

    record ContentBlock(String type, String text) {}
}
