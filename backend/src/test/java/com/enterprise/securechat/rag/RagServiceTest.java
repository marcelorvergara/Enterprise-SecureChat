package com.enterprise.securechat.rag;

import com.enterprise.securechat.audit.AuditService;
import com.enterprise.securechat.conversation.Conversation;
import com.enterprise.securechat.conversation.ConversationService;
import com.enterprise.securechat.fga.FgaService;
import com.enterprise.securechat.rag.dto.ChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock private FgaService fgaService;
    @Mock private EmbedClient embedClient;
    @Mock private QdrantSearchClient qdrantClient;
    @Mock private ClaudeService claudeService;
    @Mock private DlpClient dlpClient;
    @Mock private ConversationService conversationService;
    @Mock private AuditService auditService;

    @InjectMocks
    private RagService ragService;

    private Authentication auth;
    private Jwt jwt;
    private static final String USER_SUB = "user-sub-123";
    private static final UUID CONV_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jwt = mock(Jwt.class);
        auth = mock(Authentication.class);

        when(jwt.getSubject()).thenReturn(USER_SUB);
        when(auth.getPrincipal()).thenReturn(jwt);

        // Simulate a finance-analyst user
        Collection<GrantedAuthority> authorities = List.of(
            new SimpleGrantedAuthority("ROLE_finance-analyst"),
            new SimpleGrantedAuthority("ROLE_employee")
        );
        doReturn(authorities).when(auth).getAuthorities();
    }

    @Test
    void chat_dlpIsCalledBeforeReturningResult() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("What are the Q3 figures?", null);
        var vector = List.of(0.1f, 0.2f, 0.3f);
        var rawAnswer = "The Q3 figures are $125,000.";
        var cleanedAnswer = "The Q3 figures are [REDACTED].";

        when(fgaService.getRestrictedPaths(anyList())).thenReturn(List.of());
        when(fgaService.buildQdrantFilter(anyList())).thenReturn(Map.of());
        when(conversationService.getOrCreate(null, USER_SUB)).thenReturn(conversation);
        when(embedClient.embed(request.message())).thenReturn(vector);
        when(qdrantClient.search(eq(vector), any(), eq(5))).thenReturn(List.of());
        when(conversationService.getHistory(any(), eq(10))).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList())).thenReturn(rawAnswer);
        when(dlpClient.analyze(eq(rawAnswer), anyList())).thenReturn(new DlpClient.DlpResult(cleanedAnswer, 1));

        var response = ragService.chat(request, auth);

        // DLP must be called with the raw Claude answer
        verify(dlpClient).analyze(eq(rawAnswer), anyList());
        // Response must contain the cleaned (DLP-processed) text, not raw
        assertThat(response.answer()).isEqualTo(cleanedAnswer);
        assertThat(response.dlpEntitiesRedacted()).isEqualTo(1);
    }

    @Test
    void chat_fgaAppliedIsTrueWhenRestrictionsExist() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("Show me payroll data.", null);
        var restrictedPaths = List.of("finance/payroll");

        when(fgaService.getRestrictedPaths(anyList())).thenReturn(restrictedPaths);
        when(fgaService.buildQdrantFilter(restrictedPaths)).thenReturn(
            Map.of("must_not", List.of(Map.of("key", "ancestor_paths",
                "match", Map.of("any", List.of("finance/payroll")))))
        );
        when(conversationService.getOrCreate(null, USER_SUB)).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList())).thenReturn("No data found.");
        when(dlpClient.analyze(any(), anyList())).thenReturn(new DlpClient.DlpResult("No data found.", 0));

        var response = ragService.chat(request, auth);

        assertThat(response.fgaApplied()).isTrue();
    }

    @Test
    void chat_fgaAppliedIsFalseWhenNoRestrictions() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("What is our onboarding process?", null);

        when(fgaService.getRestrictedPaths(anyList())).thenReturn(List.of());
        when(fgaService.buildQdrantFilter(List.of())).thenReturn(Map.of());
        when(conversationService.getOrCreate(null, USER_SUB)).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList())).thenReturn("Onboarding takes 2 weeks.");
        when(dlpClient.analyze(any(), anyList())).thenReturn(new DlpClient.DlpResult("Onboarding takes 2 weeks.", 0));

        var response = ragService.chat(request, auth);

        assertThat(response.fgaApplied()).isFalse();
    }

    @Test
    void chat_rolesAreStrippedOfRolePrefixBeforeFgaLookup() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("Hello", null);

        when(fgaService.getRestrictedPaths(anyList())).thenReturn(List.of());
        when(fgaService.buildQdrantFilter(any())).thenReturn(Map.of());
        when(conversationService.getOrCreate(any(), any())).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList())).thenReturn("Hi.");
        when(dlpClient.analyze(any(), anyList())).thenReturn(new DlpClient.DlpResult("Hi.", 0));

        ragService.chat(request, auth);

        // FGA lookup must receive plain Keycloak role names, not Spring's "ROLE_" prefixed ones
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> rolesCaptor = ArgumentCaptor.forClass(List.class);
        verify(fgaService).getRestrictedPaths(rolesCaptor.capture());
        assertThat(rolesCaptor.getValue()).containsExactlyInAnyOrder("finance-analyst", "employee");
        assertThat(rolesCaptor.getValue()).doesNotContain("ROLE_finance-analyst");
    }

    @Test
    void chat_auditLogReceivesHashedPromptNotRawText() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("Sensitive question here.", null);

        when(fgaService.getRestrictedPaths(anyList())).thenReturn(List.of());
        when(fgaService.buildQdrantFilter(any())).thenReturn(Map.of());
        when(conversationService.getOrCreate(any(), any())).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList())).thenReturn("Answer.");
        when(dlpClient.analyze(any(), anyList())).thenReturn(new DlpClient.DlpResult("Answer.", 0));

        ragService.chat(request, auth);

        // AuditService.log receives the raw prompt so it can hash it internally
        verify(auditService).log(eq(USER_SUB), anyList(), anyList(), eq("Sensitive question here."));
    }

    @Test
    void chat_qdrantReceivesFgaFilterWhenRestrictionsPresent() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("Show me finance data.", null);
        var restrictedPaths = List.of("finance");
        var expectedFilter = Map.<String, Object>of("must_not", List.of(
            Map.of("key", "ancestor_paths", "match", Map.of("any", List.of("finance")))
        ));

        when(fgaService.getRestrictedPaths(anyList())).thenReturn(restrictedPaths);
        when(fgaService.buildQdrantFilter(restrictedPaths)).thenReturn(expectedFilter);
        when(conversationService.getOrCreate(any(), any())).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), eq(expectedFilter), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList())).thenReturn("No access.");
        when(dlpClient.analyze(any(), anyList())).thenReturn(new DlpClient.DlpResult("No access.", 0));

        ragService.chat(request, auth);

        verify(qdrantClient).search(any(), eq(expectedFilter), anyInt());
    }

    @Test
    void chat_existingConversationIdIsPassedToConversationService() {
        var existingConvId = UUID.randomUUID();
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("Follow-up question.", existingConvId);

        when(fgaService.getRestrictedPaths(anyList())).thenReturn(List.of());
        when(fgaService.buildQdrantFilter(any())).thenReturn(Map.of());
        when(conversationService.getOrCreate(existingConvId, USER_SUB)).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList())).thenReturn("Follow-up answered.");
        when(dlpClient.analyze(any(), anyList())).thenReturn(new DlpClient.DlpResult("Follow-up answered.", 0));

        ragService.chat(request, auth);

        verify(conversationService).getOrCreate(existingConvId, USER_SUB);
    }
}
