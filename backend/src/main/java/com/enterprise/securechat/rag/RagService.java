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

    // Top-K chunks passed to Claude as context
    private static final int TOP_K = 5;
    // Last N conversation turns (user+assistant pairs) sent to Claude
    private static final int HISTORY_TURNS = 10;

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

        // ── 1. FGA — compute Qdrant must_not filter ──────────────────────────
        List<String> restrictedPaths = fgaService.getRestrictedPaths(roles);
        Map<String, Object> qdrantFilter = fgaService.buildQdrantFilter(restrictedPaths);

        // ── 2. Conversation — get or create ──────────────────────────────────
        var conversation = conversationService.getOrCreate(request.conversationId(), userSub);

        // ── 3. Embed the user prompt ─────────────────────────────────────────
        var vector = embedClient.embed(request.message());

        // ── 4. FGA-filtered semantic search ──────────────────────────────────
        var hits = qdrantClient.search(vector, qdrantFilter, TOP_K);

        // ── 5. Build source citations and chunk context ───────────────────────
        var sources = hits.stream()
                .map(hit -> new SourceCitation(
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
        var rawAnswer = claudeService.complete(systemPrompt, claudeMessages);

        // ── 8. DLP — redact PII and financial figures before returning ────────
        var dlpResult = dlpClient.analyze(rawAnswer);

        // ── 9. Persist messages (store the redacted answer, not the raw one) ──
        conversationService.saveUserMessage(conversation.getId(), request.message());
        conversationService.saveAssistantMessage(conversation.getId(), dlpResult.cleanedText(), sources);

        // ── 10. Audit log — SHA-256 of prompt, never raw text ────────────────
        auditService.log(userSub, roles, restrictedPaths, request.message());

        return new ChatResponse(
                dlpResult.cleanedText(),
                conversation.getId(),
                sources,
                !restrictedPaths.isEmpty(),
                dlpResult.entitiesRedacted()
        );
    }

    private String buildSystemPrompt(String contextChunks) {
        if (contextChunks == null || contextChunks.isBlank()) {
            return """
                    You are an enterprise knowledge assistant. \
                    Answer questions accurately and professionally. \
                    No relevant documents were found for this query — \
                    say so clearly rather than inventing information.""";
        }
        return "You are an enterprise knowledge assistant. " +
               "Answer using ONLY the provided context. " +
               "Do not invent facts not present in the context. " +
               "If the context is insufficient, say so clearly.\n\n" +
               "Context:\n" + contextChunks;
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
