package com.enterprise.securechat.conversation;

import com.enterprise.securechat.rag.dto.SourceCitation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    public ConversationService(ConversationRepository conversationRepository,
                               MessageRepository messageRepository,
                               ObjectMapper objectMapper) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the existing conversation if conversationId is provided and belongs to the
     * user, or creates a new one. Throws 404 if the ID exists but belongs to someone else.
     */
    @Transactional
    public Conversation getOrCreate(UUID conversationId, String userSub) {
        if (conversationId != null) {
            var conv = conversationRepository.findById(conversationId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
            if (!conv.getUserSub().equals(userSub)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
            }
            return conv;
        }
        return conversationRepository.save(new Conversation(userSub));
    }

    /**
     * Returns the last maxTurns pairs (user + assistant) in chronological order.
     * The repository returns DESC; we reverse before returning.
     */
    @Transactional(readOnly = true)
    public List<Message> getHistory(UUID conversationId, int maxTurns) {
        var page = PageRequest.of(0, maxTurns * 2);
        var descending = messageRepository.findLatestByConversationId(conversationId, page);
        var ordered = new ArrayList<>(descending);
        Collections.reverse(ordered);
        return ordered;
    }

    @Transactional
    public void setTitle(UUID conversationId, String firstMessage) {
        conversationRepository.findById(conversationId).ifPresent(conv -> {
            var cleaned = firstMessage.replaceAll("[\\r\\n]+", " ").trim();
            conv.setTitle(cleaned.length() > 72 ? cleaned.substring(0, 72) + "…" : cleaned);
            conversationRepository.save(conv);
        });
    }

    @Transactional
    public void saveUserMessage(UUID conversationId, String content) {
        messageRepository.save(new Message(conversationId, "user", content, null));
    }

    @Transactional
    public void delete(UUID conversationId, String userSub) {
        var conv = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (!conv.getUserSub().equals(userSub)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied");
        }
        conversationRepository.deleteById(conversationId);
        // ON DELETE CASCADE in the DB handles messages automatically
    }

    @Transactional
    public void saveAssistantMessage(UUID conversationId, String content, List<SourceCitation> sources) {
        String sourcesJson = null;
        if (sources != null && !sources.isEmpty()) {
            try {
                sourcesJson = objectMapper.writeValueAsString(sources);
            } catch (JsonProcessingException e) {
                sourcesJson = "[]";
            }
        }
        messageRepository.save(new Message(conversationId, "assistant", content, sourcesJson));
    }
}
