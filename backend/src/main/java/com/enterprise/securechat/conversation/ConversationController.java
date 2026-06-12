package com.enterprise.securechat.conversation;

import com.enterprise.securechat.fga.FgaService;
import com.enterprise.securechat.rag.QdrantSearchClient;
import com.enterprise.securechat.rag.dto.SourceCitation;
import com.enterprise.securechat.rag.dto.SourcePreviewResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ConversationController {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    private final ConversationService conversationService;
    private final QdrantSearchClient qdrantSearchClient;
    private final FgaService fgaService;

    public ConversationController(ConversationRepository conversationRepository,
                                  MessageRepository messageRepository,
                                  ObjectMapper objectMapper,
                                  ConversationService conversationService,
                                  QdrantSearchClient qdrantSearchClient,
                                  FgaService fgaService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.conversationService = conversationService;
        this.qdrantSearchClient = qdrantSearchClient;
        this.fgaService = fgaService;
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationView>> getConversations(Authentication auth) {
        var jwt = (Jwt) auth.getPrincipal();
        var views = conversationRepository
                .findByUserSubOrderByCreatedAtDesc(jwt.getSubject())
                .stream()
                .map(c -> new ConversationView(c.getId(), c.getCreatedAt(), c.getTitle()))
                .toList();
        return ResponseEntity.ok(views);
    }

    @GetMapping("/conversations/{id}")
    public ResponseEntity<ConversationView> getConversation(
            @PathVariable UUID id,
            Authentication auth) {
        var jwt = (Jwt) auth.getPrincipal();
        var conv = conversationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (!conv.getUserSub().equals(jwt.getSubject())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return ResponseEntity.ok(new ConversationView(conv.getId(), conv.getCreatedAt(), conv.getTitle()));
    }

    @GetMapping("/conversations/{id}/messages")
    public ResponseEntity<List<MessageView>> getMessages(
            @PathVariable UUID id,
            Authentication auth) {
        var jwt = (Jwt) auth.getPrincipal();
        var conv = conversationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (!conv.getUserSub().equals(jwt.getSubject())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        var views = messageRepository.findAllByConversationIdOrderByCreatedAtAsc(id)
                .stream()
                .map(m -> new MessageView(
                        m.getId(),
                        m.getRole(),
                        m.getContent(),
                        parseSources(m.getSources()),
                        m.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(views);
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> deleteConversation(
            @PathVariable UUID id,
            Authentication auth) {
        var jwt = (Jwt) auth.getPrincipal();
        conversationService.delete(id, jwt.getSubject());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations/{id}/sources/{chunkId}")
    public ResponseEntity<SourcePreviewResponse> getSourcePreview(
            @PathVariable UUID id,
            @PathVariable String chunkId,
            Authentication auth) {
        var jwt = (Jwt) auth.getPrincipal();

        // Ownership check — user must own the conversation
        var conv = conversationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (!conv.getUserSub().equals(jwt.getSubject())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }

        // Fetch the Qdrant point
        var hit = qdrantSearchClient.getPoint(chunkId);
        if (hit == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chunk not found");
        }

        // FGA check — verify the chunk's ancestor_paths are accessible to this user
        var roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring(5))
                .toList();
        var groups = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("GROUP_"))
                .toList();

        // ── FGA path check: ancestor_paths hierarchy ──────────────────────────
        var restrictedPaths = fgaService.getRestrictedPaths(roles, groups);
        if (!restrictedPaths.isEmpty()) {
            @SuppressWarnings("unchecked")
            var ancestorPaths = (List<String>) hit.payload().getOrDefault("ancestor_paths", List.of());
            boolean blocked = ancestorPaths.stream()
                    .anyMatch(ap -> restrictedPaths.stream().anyMatch(ap::startsWith));
            if (blocked) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }
        }

        // ── Clearance check: classification_level ─────────────────────────────
        var blockedClassifications = fgaService.getBlockedClassifications(roles);
        if (!blockedClassifications.isEmpty()) {
            var chunkClassification = (String) hit.payload().get("classification_level");
            if (chunkClassification != null && blockedClassifications.contains(chunkClassification)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }
        }

        var chunkText = (String) hit.payload().getOrDefault("chunk_text", "");
        var sourceFile = (String) hit.payload().get("source_file");
        var subjectPath = (String) hit.payload().get("subject_path");
        var pageNum = hit.payload().get("page_number") instanceof Number n ? n.intValue() : null;
        var sheetName = (String) hit.payload().get("sheet_name");

        return ResponseEntity.ok(new SourcePreviewResponse(chunkId, chunkText, sourceFile, subjectPath, pageNum, sheetName));
    }

    private List<SourceCitation> parseSources(String sourcesJson) {
        if (sourcesJson == null) return null;
        try {
            return objectMapper.readValue(sourcesJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, SourceCitation.class));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    record ConversationView(UUID id, OffsetDateTime createdAt, String title) {}

    record MessageView(UUID id, String role, String content, List<SourceCitation> sources, OffsetDateTime createdAt) {}
}
