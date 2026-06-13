package com.enterprise.securechat.rag;

import com.enterprise.securechat.audit.AuditService;
import com.enterprise.securechat.conversation.ConversationService;
import com.enterprise.securechat.fga.FgaService;
import com.enterprise.securechat.rag.dto.ChatRequest;
import com.enterprise.securechat.rag.dto.ChatResponse;
import com.enterprise.securechat.rag.dto.SourceCitation;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RagService {

    // Top-K chunks passed to Claude as context.
    // 10 gives BU-specific documents (which may compete with many corporate-answers
    // chunks for the same query) a fair chance to surface in Claude's context window.
    private static final int TOP_K = 10;
    // Last N conversation turns (user+assistant pairs) sent to Claude
    private static final int HISTORY_TURNS = 10;
    private static final int STRUCTURED_CHAT_MAX_TOKENS = 1536;

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
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .toList();

        // Collect BU group authorities (e.g. GROUP_BU_CAMPOS) for FGA BU isolation
        var groups = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
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
        conversationService.saveAssistantMessage(conversation.getId(), dlpAnswer.cleanedText(), sources);

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
                .map(a -> a.getAuthority())
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .toList();

        var groups = auth.getAuthorities().stream()
                .map(a -> a.getAuthority())
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
        conversationService.saveAssistantMessage(conversation.getId(), dlpAnswer.cleanedText(), sources);

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
}
