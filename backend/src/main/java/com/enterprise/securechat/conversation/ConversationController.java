package com.enterprise.securechat.conversation;

import com.enterprise.securechat.rag.dto.SourceCitation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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

    public ConversationController(ConversationRepository conversationRepository,
                                  MessageRepository messageRepository,
                                  ObjectMapper objectMapper,
                                  ConversationService conversationService) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
        this.conversationService = conversationService;
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
