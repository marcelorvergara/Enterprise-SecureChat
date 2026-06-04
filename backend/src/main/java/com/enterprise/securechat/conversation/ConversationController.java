package com.enterprise.securechat.conversation;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ConversationController {

    private final ConversationRepository conversationRepository;

    public ConversationController(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    @GetMapping("/conversations")
    public ResponseEntity<List<ConversationView>> getConversations(Authentication auth) {
        var jwt = (Jwt) auth.getPrincipal();
        var views = conversationRepository
                .findByUserSubOrderByCreatedAtDesc(jwt.getSubject())
                .stream()
                .map(c -> new ConversationView(c.getId(), c.getCreatedAt()))
                .toList();
        return ResponseEntity.ok(views);
    }

    record ConversationView(UUID id, OffsetDateTime createdAt) {}
}
