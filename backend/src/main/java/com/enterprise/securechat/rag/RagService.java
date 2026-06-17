package com.enterprise.securechat.rag;

import com.enterprise.securechat.audit.AuditService;
import com.enterprise.securechat.conversation.ConversationService;
import com.enterprise.securechat.fga.FgaService;
import com.enterprise.securechat.rag.dto.ChatRequest;
import com.enterprise.securechat.rag.dto.ChatResponse;
import com.enterprise.securechat.rag.dto.SourceCitation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    // Top-K chunks passed to Claude as context.
    // 10 gives BU-specific documents (which may compete with many corporate-answers
    // chunks for the same query) a fair chance to surface in Claude's context window.
    private static final int TOP_K = 10;
    // Last N conversation turns (user+assistant pairs) sent to Claude
    private static final int HISTORY_TURNS = 10;
    private static final int STRUCTURED_CHAT_MAX_TOKENS = 1536;
    // Streaming endpoint uses plain-text prompt (no JSON overhead); 1024 is the default cap
    private static final int STREAMING_MAX_TOKENS = 1024;
    // Reused across streaming helpers — ObjectMapper is thread-safe after construction
    private static final ObjectMapper STREAM_MAPPER = new ObjectMapper();

    private final FgaService fgaService;
    private final EmbedClient embedClient;
    private final QdrantSearchClient qdrantClient;
    private final ClaudeService claudeService;
    private final DlpClient dlpClient;
    private final ConversationService conversationService;
    private final AuditService auditService;

    public RagService(FgaService fgaService,
                      EmbedClient embedClient,
                      QdrantSearchClient qdrantClient,
                      ClaudeService claudeService,
                      DlpClient dlpClient,
                      ConversationService conversationService,
                      AuditService auditService) {
        this.fgaService = fgaService;
        this.embedClient = embedClient;
        this.qdrantClient = qdrantClient;
        this.claudeService = claudeService;
        this.dlpClient = dlpClient;
        this.conversationService = conversationService;
        this.auditService = auditService;
    }

    /**
     * Full RAG pipeline for a single chat turn.
     *
     * Intentionally NOT @Transactional — keeping the DB connection closed while
     * making external HTTP calls (embed service, Qdrant, Anthropic) avoids
     * exhausting HikariCP's pool under concurrent load. Each DB operation
     * executes in its own short transaction via the injected services.
     */
    public ChatResponse chat(ChatRequest request, Authentication auth) {
        var jwt = (Jwt) auth.getPrincipal();
        var userSub = jwt.getSubject();

        // Strip "ROLE_" prefix to get plain Keycloak role names for FGA lookup
        var roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .toList();

        // Collect BU group authorities (e.g. GROUP_BU_CAMPOS) for FGA BU isolation
        var groups = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("GROUP_"))
                .toList();

        // ── 1. FGA — compute Qdrant must_not filter (path + clearance) ─────────
        List<String> restrictedPaths        = fgaService.getRestrictedPaths(roles, groups);
        List<String> blockedClassifications = fgaService.getBlockedClassifications(roles);
        Map<String, Object> qdrantFilter    = fgaService.buildQdrantFilter(restrictedPaths, blockedClassifications);

        // ── 2. Conversation — get or create ──────────────────────────────────
        var conversation = conversationService.getOrCreate(request.conversationId(), userSub);
        if (conversation.getTitle() == null) {
            conversationService.setTitle(conversation.getId(), request.message());
        }

        // ── 3. Embed the user prompt ─────────────────────────────────────────
        var vector = embedClient.embed(request.message());

        // ── 4. FGA-filtered semantic search ──────────────────────────────────
        var hits = qdrantClient.search(vector, qdrantFilter, TOP_K);

        // ── 5. Build source citations and chunk context ───────────────────────
        var sources = hits.stream()
                .map(hit -> new SourceCitation(
                        hit.id(),
                        payloadString(hit.payload(), "source_file"),
                        payloadString(hit.payload(), "subject_path"),
                        payloadInt(hit.payload(), "page_number"),
                        payloadString(hit.payload(), "sheet_name"),
                        payloadString(hit.payload(), "origin_source"),
                        payloadString(hit.payload(), "jurisdiction"),
                        hit.score()
                ))
                .toList();

        var contextChunks = hits.stream()
                .map(hit -> payloadString(hit.payload(), "chunk_text"))
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n---\n\n"));

        // ── 6. Assemble prompt and history ───────────────────────────────────
        var systemPrompt = buildSystemPrompt(contextChunks);

        var history = conversationService.getHistory(conversation.getId(), HISTORY_TURNS);
        var claudeMessages = new ArrayList<ClaudeService.ConversationMessage>();
        for (var msg : history) {
            claudeMessages.add(new ClaudeService.ConversationMessage(msg.getRole(), msg.getContent()));
        }
        claudeMessages.add(new ClaudeService.ConversationMessage("user", request.message()));

            // ── 7. Call Claude (blocking — DLP requires the complete answer) ────────
        var rawResponse = claudeService.complete(systemPrompt, claudeMessages, STRUCTURED_CHAT_MAX_TOKENS);
        var parsed = parseClaudeResponse(rawResponse);

        // ── 8. DLP — answer + each suggestion (suggestions must never bypass DLP) ──
        var dlpAnswer = dlpClient.analyze(parsed.answer(), roles);
        var dlpSuggestions = parsed.suggestions().stream()
                .map(s -> dlpClient.analyze(s, roles))
                .toList();
        int totalRedacted = dlpAnswer.entitiesRedacted()
                + dlpSuggestions.stream().mapToInt(DlpClient.DlpResult::entitiesRedacted).sum();
        var cleanedSuggestions = dlpSuggestions.stream()
                .map(DlpClient.DlpResult::cleanedText)
                .toList();

        // ── 9. Persist — cleaned answer only; suggestions are ephemeral UI hints ──
        conversationService.saveUserMessage(conversation.getId(), request.message());
        conversationService.saveAssistantMessage(conversation.getId(), dlpAnswer.cleanedText(), sources, totalRedacted);

        // ── 10. Audit log — SHA-256 of prompt, never raw text ────────────────
        auditService.log(userSub, roles, restrictedPaths, request.message());

        return new ChatResponse(
                dlpAnswer.cleanedText(),
                conversation.getId(),
                sources,
                !restrictedPaths.isEmpty(),
                totalRedacted,
                cleanedSuggestions
        );
    }

    /**
     * RAG pipeline variant that injects an uploaded document into the system prompt for
     * compliance verification. Raw document text is never persisted — only a filename hint.
     * NOT @Transactional for the same reason as chat().
     */
    public ChatResponse chatWithDocument(ChatRequest request, String documentText,
                                         String documentFilename, Authentication auth) {
        var jwt = (Jwt) auth.getPrincipal();
        var userSub = jwt.getSubject();

        var roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .toList();

        var groups = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("GROUP_"))
                .toList();

        List<String> restrictedPaths        = fgaService.getRestrictedPaths(roles, groups);
        List<String> blockedClassifications = fgaService.getBlockedClassifications(roles);
        Map<String, Object> qdrantFilter    = fgaService.buildQdrantFilter(restrictedPaths, blockedClassifications);

        var conversation = conversationService.getOrCreate(request.conversationId(), userSub);
        if (conversation.getTitle() == null) {
            var titleText = request.message() + " [" + documentFilename + "]";
            conversationService.setTitle(conversation.getId(), titleText);
        }

        var vector = embedClient.embed(request.message());
        var hits = qdrantClient.search(vector, qdrantFilter, TOP_K);

        var sources = hits.stream()
                .map(hit -> new SourceCitation(
                        hit.id(),
                        payloadString(hit.payload(), "source_file"),
                        payloadString(hit.payload(), "subject_path"),
                        payloadInt(hit.payload(), "page_number"),
                        payloadString(hit.payload(), "sheet_name"),
                        payloadString(hit.payload(), "origin_source"),
                        payloadString(hit.payload(), "jurisdiction"),
                        hit.score()
                ))
                .toList();

        var contextChunks = hits.stream()
                .map(hit -> payloadString(hit.payload(), "chunk_text"))
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n---\n\n"));

        var systemPrompt = buildSystemPromptWithDocument(contextChunks, documentText, documentFilename);

        var history = conversationService.getHistory(conversation.getId(), HISTORY_TURNS);
        var claudeMessages = new ArrayList<ClaudeService.ConversationMessage>();
        for (var msg : history) {
            claudeMessages.add(new ClaudeService.ConversationMessage(msg.getRole(), msg.getContent()));
        }
        claudeMessages.add(new ClaudeService.ConversationMessage("user", request.message()));

        var rawResponse = claudeService.complete(systemPrompt, claudeMessages, 2048);
        var parsed = parseClaudeResponse(rawResponse);

        var dlpAnswer = dlpClient.analyze(parsed.answer(), roles);
        var dlpSuggestions = parsed.suggestions().stream()
                .map(s -> dlpClient.analyze(s, roles))
                .toList();
        int totalRedacted = dlpAnswer.entitiesRedacted()
                + dlpSuggestions.stream().mapToInt(DlpClient.DlpResult::entitiesRedacted).sum();
        var cleanedSuggestions = dlpSuggestions.stream()
                .map(DlpClient.DlpResult::cleanedText)
                .toList();

        var userMessageContent = request.message() + " [Attached: " + documentFilename + "]";
        conversationService.saveUserMessage(conversation.getId(), userMessageContent);
        conversationService.saveAssistantMessage(conversation.getId(), dlpAnswer.cleanedText(), sources, totalRedacted);

        auditService.log(userSub, roles, restrictedPaths, request.message());

        return new ChatResponse(
                dlpAnswer.cleanedText(),
                conversation.getId(),
                sources,
                !restrictedPaths.isEmpty(),
                totalRedacted,
                cleanedSuggestions
        );
    }

    private String buildSystemPromptWithDocument(String contextChunks, String documentText,
                                                  String documentFilename) {
        var knowledge = (contextChunks == null || contextChunks.isBlank())
                ? "No relevant documents were found in the knowledge base."
                : contextChunks;
        return OG_BASE_INSTRUCTIONS + "\n\n"
             + "ENTERPRISE KNOWLEDGE BASE (verified ground truth):\n" + knowledge + "\n\n"
             + "USER-SUBMITTED DOCUMENT FOR VERIFICATION:\nFilename: " + documentFilename + "\n"
             + documentText + "\n\n"
             + "Compare the submitted document against the knowledge base. "
             + "Identify discrepancies, errors, outdated figures, or ANP compliance issues. "
             + "If the knowledge base lacks coverage on the submitted topic, say so clearly."
             + JSON_FORMAT_INSTRUCTION;
    }

    private String buildSystemPrompt(String contextChunks) {
        if (contextChunks == null || contextChunks.isBlank()) {
            return OG_BASE_INSTRUCTIONS + "\n\n"
                 + "No relevant documents were found for this query — "
                 + "say so clearly rather than inventing information."
                 + JSON_FORMAT_INSTRUCTION;
        }
        return OG_BASE_INSTRUCTIONS + "\n\n"
             + "Answer using ONLY the provided context. "
             + "Do not invent facts not present in the context. "
             + "If the context is insufficient, say so clearly.\n\n"
             + "Context:\n" + contextChunks
             + JSON_FORMAT_INSTRUCTION;
    }

    // Base instructions shared by all system prompts.
    // Unit rule closes the bare-number DLP gap: a number like "450,000" with no unit
    // bypasses the FINANCIAL_FIGURE recognizer, but "450,000 MMboe" is caught by OG_VOLUMES.
    private static final String JSON_FORMAT_INSTRUCTION =
        "\n\n===MANDATORY RESPONSE FORMAT===\n"
      + "You MUST respond with ONLY a valid JSON object. No markdown, no code fences, no prose before or after.\n"
      + "The JSON must have exactly two keys:\n"
      + "  \"answer\": your complete answer as a plain string (use \\n for newlines, escape internal double-quotes as \\\")\n"
      + "  \"suggestions\": an array of 2-3 specific follow-up questions as strings\n"
      + "Example: {\"answer\": \"The Santos Basin contains...\", \"suggestions\": [\"What is the production rate?\", \"Which operator holds the largest stake?\"]}\n"
      + "Do NOT wrap in markdown fences. Do NOT add any text outside the JSON object.";

    private static final String OG_BASE_INSTRUCTIONS =
        "You are an Oil & Gas enterprise knowledge assistant operating under ANP compliance rules.\n"
      + "Always attach standard measurement units to every quantity you cite: "
      + "use MMboe, bbl, bbl/d, m³/d for volumes; USD/bbl or BRL/bbl for barrel prices; "
      + "USD, BRL, or EUR followed by the amount for monetary figures. "
      + "Never write a bare number without its unit or currency symbol.\n"
      + "When your answer contains information sourced from an ANP official document, "
      + "prefix that paragraph with 'ANP Response:' so it can be identified downstream.";

    private record StructuredResponse(String answer, List<String> suggestions) {}

    private StructuredResponse parseClaudeResponse(String raw) {
        try {
            int start = raw.indexOf('{');
            int end   = raw.lastIndexOf('}');
            if (start != -1 && end > start) {
                var json   = raw.substring(start, end + 1);
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var node   = mapper.readTree(json);
                var answer = node.path("answer").asText(null);
                var suggestions = new java.util.ArrayList<String>();
                var sugNode = node.path("suggestions");
                if (sugNode.isArray()) {
                    for (var s : sugNode) {
                        var text = s.asText("").strip();
                        if (!text.isBlank()) suggestions.add(text);
                    }
                }
                if (answer != null && !answer.isBlank()) {
                    return new StructuredResponse(answer, List.copyOf(suggestions));
                }
            }
        } catch (Exception ignored) {}
        return new StructuredResponse(raw, List.of());
    }

    private String payloadString(Map<String, Object> payload, String key) {
        var val = payload.get(key);
        return val instanceof String s ? s : null;
    }

    private Integer payloadInt(Map<String, Object> payload, String key) {
        var val = payload.get(key);
        return val instanceof Number n ? n.intValue() : null;
    }

    // ── Streaming RAG pipeline ─────────────────────────────────────────────────

    /**
     * Sentence-buffered streaming variant of {@link #chat}. Tokens arrive from Claude,
     * complete sentences are DLP-scanned and emitted as SSE events. A final "metadata"
     * event carries conversationId, sources, DLP count, and suggestions.
     *
     * NOT @Transactional — same reasoning as chat(). The executor thread that calls this
     * must not hold a DB connection while awaiting tokens from Claude or DLP responses.
     */
    public void chatStream(ChatRequest request, Authentication auth, SseEmitter emitter) {
        var jwt = (Jwt) auth.getPrincipal();
        var userSub = jwt.getSubject();

        var roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .toList();

        var groups = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("GROUP_"))
                .toList();

        // ── 1. FGA ───────────────────────────────────────────────────────────
        List<String> restrictedPaths        = fgaService.getRestrictedPaths(roles, groups);
        List<String> blockedClassifications = fgaService.getBlockedClassifications(roles);
        Map<String, Object> qdrantFilter    = fgaService.buildQdrantFilter(restrictedPaths, blockedClassifications);

        // ── 2. Conversation ──────────────────────────────────────────────────
        var conversation = conversationService.getOrCreate(request.conversationId(), userSub);
        if (conversation.getTitle() == null) {
            conversationService.setTitle(conversation.getId(), request.message());
        }

        // ── 3. Embed + FGA-filtered search ───────────────────────────────────
        var vector = embedClient.embed(request.message());
        var hits   = qdrantClient.search(vector, qdrantFilter, TOP_K);

        // ── 4. Sources + context ─────────────────────────────────────────────
        var sources = hits.stream()
                .map(hit -> new SourceCitation(
                        hit.id(),
                        payloadString(hit.payload(), "source_file"),
                        payloadString(hit.payload(), "subject_path"),
                        payloadInt(hit.payload(), "page_number"),
                        payloadString(hit.payload(), "sheet_name"),
                        payloadString(hit.payload(), "origin_source"),
                        payloadString(hit.payload(), "jurisdiction"),
                        hit.score()
                ))
                .toList();

        var contextChunks = hits.stream()
                .map(hit -> payloadString(hit.payload(), "chunk_text"))
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n\n---\n\n"));

        // ── 5. Build streaming prompt (plain text — no JSON format instruction) ─
        var systemPrompt = buildStreamingSystemPrompt(contextChunks);

        var history = conversationService.getHistory(conversation.getId(), HISTORY_TURNS);
        var claudeMessages = new ArrayList<ClaudeService.ConversationMessage>();
        for (var msg : history) {
            claudeMessages.add(new ClaudeService.ConversationMessage(msg.getRole(), msg.getContent()));
        }
        claudeMessages.add(new ClaudeService.ConversationMessage("user", request.message()));

        // ── 6. Persist user message + audit BEFORE streaming starts ──────────
        conversationService.saveUserMessage(conversation.getId(), request.message());
        auditService.log(userSub, roles, restrictedPaths, request.message());

        // ── 7. Stream → sentence buffer → DLP per sentence → SSE ─────────────
        var detector      = new SentenceBoundaryDetector();
        var fullAnswer    = new StringBuilder();
        int[] dlpCount    = {0};

        try {
            claudeService.streamComplete(systemPrompt, claudeMessages, STREAMING_MAX_TOKENS, token -> {
                List<String> sentences = detector.feed(token);
                for (String sentence : sentences) {
                    emitRedactedSentence(sentence, roles, emitter, fullAnswer, dlpCount);
                }
            });

            // Flush any trailing sentence fragment
            String tail = detector.flush();
            if (!tail.isBlank()) {
                emitRedactedSentence(tail, roles, emitter, fullAnswer, dlpCount);
            }

            // ── 8. Generate suggestions (non-streaming, after answer is complete) ─
            // Anthropic API requires the last message to be from "user".
            // Build a focused two-message context (Q + A) rather than reusing
            // claudeMessages (which would end on the "assistant" turn and be rejected).
            var qaContext = "User question: " + request.message() + "\n\n"
                          + "Answer provided: " + fullAnswer.toString().strip();
            var suggestionMessages = List.of(
                    new ClaudeService.ConversationMessage("user", qaContext));
            var suggestions = generateSuggestions(suggestionMessages, roles, dlpCount);

            // ── 9. Persist assembled answer ───────────────────────────────────
            var cleanedAnswer = fullAnswer.toString().strip();
            conversationService.saveAssistantMessage(conversation.getId(), cleanedAnswer, sources, dlpCount[0]);

            // ── 10. Final metadata event ──────────────────────────────────────
            var metadata = buildMetadataJson(conversation.getId(), sources,
                    !restrictedPaths.isEmpty(), dlpCount[0], suggestions);
            emitter.send(SseEmitter.event().name("metadata").data(metadata));
            emitter.complete();

        } catch (UncheckedIOException e) {
            emitter.completeWithError(e.getCause());
        } catch (Exception e) {
            try { emitter.completeWithError(e); } catch (Exception ignored) {}
        }
    }

    private void emitRedactedSentence(String sentence, List<String> roles,
                                       SseEmitter emitter, StringBuilder accumulator,
                                       int[] dlpCount) {
        var result = dlpClient.analyze(sentence, roles);
        dlpCount[0] += result.entitiesRedacted();
        String cleaned = result.cleanedText();
        accumulator.append(cleaned).append(" ");
        try {
            emitter.send(SseEmitter.event().data(cleaned));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<String> generateSuggestions(List<ClaudeService.ConversationMessage> messages,
                                              List<String> roles, int[] dlpCount) {
        var suggestionSystemPrompt =
            "You generate follow-up questions for an Oil & Gas enterprise knowledge assistant. "
          + "Respond ONLY with a JSON array of exactly 3 question strings. No prose, no markdown. "
          + "Example: [\"What is the production rate?\", \"Which operator holds the stake?\", \"What are the ANP requirements?\"]";
        try {
            var raw = claudeService.complete(suggestionSystemPrompt, messages, 512);
            int start = raw.indexOf('[');
            int end   = raw.lastIndexOf(']');
            if (start == -1 || end <= start) {
                log.warn("generateSuggestions: no JSON array found in Claude response: {}", raw);
                return List.of();
            }
            var node = STREAM_MAPPER.readTree(raw.substring(start, end + 1));
            if (!node.isArray()) return List.of();
            List<String> suggestions = new ArrayList<>();
            for (var s : node) {
                var text = s.asText("").strip();
                if (!text.isBlank()) suggestions.add(text);
            }
            return suggestions.stream()
                    .map(s -> {
                        var result = dlpClient.analyze(s, roles);
                        dlpCount[0] += result.entitiesRedacted();
                        return result.cleanedText();
                    })
                    .toList();
        } catch (Exception e) {
            log.warn("generateSuggestions failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String buildStreamingSystemPrompt(String contextChunks) {
        var base = OG_BASE_INSTRUCTIONS + "\n\n"
            + "Answer using ONLY the provided context. "
            + "Do not invent facts not present in the context. "
            + "If the context is insufficient, say so clearly. "
            + "Respond in plain prose — no JSON, no code fences.\n\n";
        if (contextChunks == null || contextChunks.isBlank()) {
            return base + "No relevant documents were found for this query — say so clearly.";
        }
        return base + "Context:\n" + contextChunks;
    }

    private String buildMetadataJson(UUID conversationId, List<SourceCitation> sources,
                                      boolean fgaApplied, int dlpEntitiesRedacted,
                                      List<String> suggestions) {
        try {
            var node = STREAM_MAPPER.createObjectNode();
            node.put("conversationId", conversationId.toString());
            node.put("fgaApplied", fgaApplied);
            node.put("dlpEntitiesRedacted", dlpEntitiesRedacted);
            node.set("sources", STREAM_MAPPER.valueToTree(sources));
            var sugArr = STREAM_MAPPER.createArrayNode();
            suggestions.forEach(sugArr::add);
            node.set("suggestions", sugArr);
            return STREAM_MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "{}";
        }
    }
}
