package com.enterprise.securechat.rag;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

@Component
public class ClaudeService {

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper;

    public ClaudeService(
            @Qualifier("anthropicRestClient") RestClient restClient,
            @Value("${anthropic.model}") String model,
            ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.model = model;
        this.objectMapper = objectMapper;
    }

    /** Exposes the configured model name for telemetry — avoids duplicating the @Value binding elsewhere. */
    public String getModel() {
        return model;
    }

    /**
     * Sends a blocking request to the Anthropic Messages API and returns the text response.
     * Blocking (non-streaming) is intentional — required for DLP post-processing in M4.
     *
     * @param systemPrompt assembled RAG context + instructions
     * @param messages     conversation history ending with the current user message
     */
    public String complete(String systemPrompt, List<ConversationMessage> messages) {
        return complete(systemPrompt, messages, 1024);
    }

    public String complete(String systemPrompt, List<ConversationMessage> messages, int maxTokens) {
        var request = new MessagesRequest(model, maxTokens, systemPrompt, messages, false);
        var response = restClient.post()
                .uri("/v1/messages")
                .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
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

    /**
     * Streams tokens from the Anthropic Messages API, invoking {@code onToken} for each
     * text delta. Returns only after the stream is fully consumed.
     * <p>
     * Sentence buffering and DLP flushing are the caller's responsibility.
     * Do NOT annotate callers with @Transactional — the stream can last 30-60 s.
     */
    public void streamComplete(
            String systemPrompt,
            List<ConversationMessage> messages,
            int maxTokens,
            Consumer<String> onToken) {

        var request = new MessagesRequest(model, maxTokens, systemPrompt, messages, true);

        restClient.post()
                .uri("/v1/messages")
                .contentType(Objects.requireNonNull(MediaType.APPLICATION_JSON))
                .body(request)
                .exchange((req, resp) -> {
                    try (var reader = new BufferedReader(
                            new InputStreamReader(resp.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (!line.startsWith("data:")) continue;
                            String json = line.substring(5).trim();
                            if ("[DONE]".equals(json)) break;
                            dispatchTextDelta(json, onToken);
                        }
                    }
                    return null;
                });
    }

    private void dispatchTextDelta(String json, Consumer<String> onToken) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!"content_block_delta".equals(root.path("type").asText())) return;
            JsonNode delta = root.path("delta");
            if (!"text_delta".equals(delta.path("type").asText())) return;
            String text = delta.path("text").asText();
            if (!text.isEmpty()) {
                onToken.accept(text);
            }
        } catch (Exception ignored) {
            // Malformed SSE line — skip silently; stream continues
        }
    }

    public record ConversationMessage(String role, String content) {}

    record MessagesRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            String system,
            List<ConversationMessage> messages,
            boolean stream
    ) {}

    record MessagesResponse(List<ContentBlock> content) {}

    record ContentBlock(String type, String text) {}
}
