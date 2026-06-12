package com.enterprise.securechat.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationServiceTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private ConversationService conversationService;

    private static final String OWNER_SUB = "auth0|owner-sub-123";
    private static final String OTHER_SUB  = "auth0|other-sub-456";

    @Test
    void delete_removesConversationWhenUserIsOwner() {
        var id = UUID.randomUUID();
        var conv = new Conversation(OWNER_SUB);
        when(conversationRepository.findById(id)).thenReturn(Optional.of(conv));

        conversationService.delete(id, OWNER_SUB);

        verify(conversationRepository).deleteById(id);
    }

    @Test
    void delete_throwsForbiddenWhenUserDoesNotOwnConversation() {
        var id = UUID.randomUUID();
        var conv = new Conversation(OWNER_SUB);
        when(conversationRepository.findById(id)).thenReturn(Optional.of(conv));

        assertThatThrownBy(() -> conversationService.delete(id, OTHER_SUB))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(conversationRepository, never()).deleteById(any());
    }

    @Test
    void delete_throwsNotFoundWhenConversationDoesNotExist() {
        var id = UUID.randomUUID();
        when(conversationRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.delete(id, OWNER_SUB))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(conversationRepository, never()).deleteById(any());
    }
}
