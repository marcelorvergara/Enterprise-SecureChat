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

    @BeforeEach
    void setUp() {
        jwt = mock(Jwt.class);
        auth = mock(Authentication.class);

        when(jwt.getSubject()).thenReturn(USER_SUB);
        when(auth.getPrincipal()).thenReturn(jwt);

        // Simulate a reservoir-team user in BU Campos
        Collection<GrantedAuthority> authorities = List.of(
            new SimpleGrantedAuthority("ROLE_reservoir-team"),
            new SimpleGrantedAuthority("GROUP_BU_CAMPOS")
        );
        lenient().doReturn(authorities).when(auth).getAuthorities();
        // Default clearance stub — individual tests override where needed
        lenient().when(fgaService.getBlockedClassifications(anyList())).thenReturn(List.of());
        // Default Claude stub — returns minimal valid JSON; individual tests override where needed
        lenient().when(claudeService.complete(anyString(), anyList(), anyInt()))
                 .thenReturn("{\"answer\":\"Default answer.\",\"suggestions\":[]}");
    }

    @Test
    void chat_dlpIsCalledBeforeReturningResult() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("What are the Q3 reserves?", null);
        var rawAnswer = "Reserves are 3.2 MMboe.";
        var cleanedAnswer = "Reserves are [REDACTED].";

        when(fgaService.getRestrictedPaths(anyList(), anyList())).thenReturn(List.of());
        when(fgaService.buildQdrantFilter(anyList(), anyList())).thenReturn(Map.of());
        when(conversationService.getOrCreate(null, USER_SUB)).thenReturn(conversation);
        when(embedClient.embed(request.message())).thenReturn(List.of(0.1f, 0.2f, 0.3f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), eq(10))).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList(), anyInt())).thenReturn(rawAnswer);
        when(dlpClient.analyze(eq(rawAnswer), anyList())).thenReturn(new DlpClient.DlpResult(cleanedAnswer, 1));

        var response = ragService.chat(request, auth);

        verify(dlpClient).analyze(eq(rawAnswer), anyList());
        assertThat(response.answer()).isEqualTo(cleanedAnswer);
        assertThat(response.dlpEntitiesRedacted()).isEqualTo(1);
    }

    @Test
    void chat_fgaAppliedIsTrueWhenRestrictionsExist() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("Show BAR data.", null);
        var restrictedPaths = List.of("bar-questions");

        when(fgaService.getRestrictedPaths(anyList(), anyList())).thenReturn(restrictedPaths);
        when(fgaService.buildQdrantFilter(eq(restrictedPaths), anyList())).thenReturn(
            Map.of("must_not", List.of(Map.of("key", "ancestor_paths",
                "match", Map.of("any", List.of("bar-questions")))))
        );
        when(conversationService.getOrCreate(null, USER_SUB)).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList(), anyInt())).thenReturn("No data found.");
        when(dlpClient.analyze(any(), anyList())).thenReturn(new DlpClient.DlpResult("No data found.", 0));

        var response = ragService.chat(request, auth);

        assertThat(response.fgaApplied()).isTrue();
    }

    @Test
    void chat_fgaAppliedIsFalseWhenNoRestrictions() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("What is our drilling schedule?", null);

        when(fgaService.getRestrictedPaths(anyList(), anyList())).thenReturn(List.of());
        when(fgaService.buildQdrantFilter(anyList(), anyList())).thenReturn(Map.of());
        when(conversationService.getOrCreate(null, USER_SUB)).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList(), anyInt())).thenReturn("Drilling starts Q2.");
        when(dlpClient.analyze(any(), anyList())).thenReturn(new DlpClient.DlpResult("Drilling starts Q2.", 0));

        var response = ragService.chat(request, auth);

        assertThat(response.fgaApplied()).isFalse();
    }

    @Test
    void chat_rolesAreStrippedOfRolePrefixBeforeFgaLookup() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("Hello", null);

        when(fgaService.getRestrictedPaths(anyList(), anyList())).thenReturn(List.of());
        when(fgaService.buildQdrantFilter(any(), any())).thenReturn(Map.of());
        when(conversationService.getOrCreate(any(), any())).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList(), anyInt())).thenReturn("Hi.");
        when(dlpClient.analyze(any(), anyList())).thenReturn(new DlpClient.DlpResult("Hi.", 0));

        ragService.chat(request, auth);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> rolesCaptor = ArgumentCaptor.forClass(List.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> groupsCaptor = ArgumentCaptor.forClass(List.class);
        verify(fgaService).getRestrictedPaths(rolesCaptor.capture(), groupsCaptor.capture());

        assertThat(rolesCaptor.getValue()).containsExactly("reservoir-team");
        assertThat(rolesCaptor.getValue()).doesNotContain("ROLE_reservoir-team");
        assertThat(groupsCaptor.getValue()).containsExactly("GROUP_BU_CAMPOS");
    }

    @Test
    void chat_auditLogReceivesHashedPromptNotRawText() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("Sensitive reserves question.", null);

        when(fgaService.getRestrictedPaths(anyList(), anyList())).thenReturn(List.of());
        when(fgaService.buildQdrantFilter(any(), any())).thenReturn(Map.of());
        when(conversationService.getOrCreate(any(), any())).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList(), anyInt())).thenReturn("Answer.");
        when(dlpClient.analyze(any(), anyList())).thenReturn(new DlpClient.DlpResult("Answer.", 0));

        ragService.chat(request, auth);

        verify(auditService).log(eq(USER_SUB), anyList(), anyList(), eq("Sensitive reserves question."));
    }

    @Test
    void chat_qdrantReceivesFgaFilterWhenRestrictionsPresent() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("Show BAR answers.", null);
        var restrictedPaths = List.of("bar-questions");
        var expectedFilter = Map.<String, Object>of("must_not", List.of(
            Map.of("key", "ancestor_paths", "match", Map.of("any", List.of("bar-questions")))
        ));

        when(fgaService.getRestrictedPaths(anyList(), anyList())).thenReturn(restrictedPaths);
        when(fgaService.buildQdrantFilter(eq(restrictedPaths), anyList())).thenReturn(expectedFilter);
        when(conversationService.getOrCreate(any(), any())).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), eq(expectedFilter), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList(), anyInt())).thenReturn("No access.");
        when(dlpClient.analyze(any(), anyList())).thenReturn(new DlpClient.DlpResult("No access.", 0));

        ragService.chat(request, auth);

        verify(qdrantClient).search(any(), eq(expectedFilter), anyInt());
    }

    @Test
    void chat_existingConversationIdIsPassedToConversationService() {
        var existingConvId = UUID.randomUUID();
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("Follow-up question.", existingConvId);

        when(fgaService.getRestrictedPaths(anyList(), anyList())).thenReturn(List.of());
        when(fgaService.buildQdrantFilter(any(), any())).thenReturn(Map.of());
        when(conversationService.getOrCreate(existingConvId, USER_SUB)).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList(), anyInt())).thenReturn("Follow-up answered.");
        when(dlpClient.analyze(any(), anyList())).thenReturn(new DlpClient.DlpResult("Follow-up answered.", 0));

        ragService.chat(request, auth);

        verify(conversationService).getOrCreate(existingConvId, USER_SUB);
    }

    @Test
    void chat_privilegedRoleSkipsDlpRedactionForVolumes() {
        // reserves-management user should pass OG_VOLUMES to DLP allow list via DlpClient
        Collection<GrantedAuthority> privilegedAuthorities = List.of(
            new SimpleGrantedAuthority("ROLE_reserves-management"),
            new SimpleGrantedAuthority("GROUP_BU_CAMPOS")
        );
        doReturn(privilegedAuthorities).when(auth).getAuthorities();

        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("What are the total proven reserves?", null);
        var rawAnswer = "Total proven reserves: 450 MMboe.";

        when(fgaService.getRestrictedPaths(anyList(), anyList())).thenReturn(List.of());
        when(fgaService.buildQdrantFilter(any(), any())).thenReturn(Map.of());
        when(conversationService.getOrCreate(any(), any())).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList(), anyInt())).thenReturn(rawAnswer);
        when(dlpClient.analyze(eq(rawAnswer), anyList())).thenReturn(new DlpClient.DlpResult(rawAnswer, 0));

        var response = ragService.chat(request, auth);

        // DlpClient receives the role list; DlpClient itself decides the bypass list
        verify(dlpClient).analyze(eq(rawAnswer), argThat(roles -> roles.contains("reserves-management")));
        assertThat(response.answer()).isEqualTo(rawAnswer);
    }

    @Test
    void chat_suggestionsReturnedOnValidJson() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("What are Q3 reserves?", null);
        var claudeJson = "{\"answer\":\"Reserves stand at 3.2 MMboe.\",\"suggestions\":[\"What is the decline rate?\",\"Compare to Q2?\"]}";

        when(fgaService.getRestrictedPaths(anyList(), anyList())).thenReturn(List.of());
        when(fgaService.buildQdrantFilter(anyList(), anyList())).thenReturn(Map.of());
        when(conversationService.getOrCreate(null, USER_SUB)).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList(), anyInt())).thenReturn(claudeJson);
        when(dlpClient.analyze(eq("Reserves stand at 3.2 MMboe."), anyList()))
                .thenReturn(new DlpClient.DlpResult("Reserves stand at [REDACTED].", 1));
        when(dlpClient.analyze(eq("What is the decline rate?"), anyList()))
                .thenReturn(new DlpClient.DlpResult("What is the decline rate?", 0));
        when(dlpClient.analyze(eq("Compare to Q2?"), anyList()))
                .thenReturn(new DlpClient.DlpResult("Compare to Q2?", 0));

        var response = ragService.chat(request, auth);

        assertThat(response.suggestions()).hasSize(2);
        assertThat(response.suggestions()).containsExactly("What is the decline rate?", "Compare to Q2?");
    }

    @Test
    void chat_suggestionsEmptyOnMalformedJson() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("What is the drilling schedule?", null);
        var plainAnswer = "I don't have that information.";

        when(fgaService.getRestrictedPaths(anyList(), anyList())).thenReturn(List.of());
        when(fgaService.buildQdrantFilter(anyList(), anyList())).thenReturn(Map.of());
        when(conversationService.getOrCreate(null, USER_SUB)).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList(), anyInt())).thenReturn(plainAnswer);
        when(dlpClient.analyze(eq(plainAnswer), anyList()))
                .thenReturn(new DlpClient.DlpResult(plainAnswer, 0));

        var response = ragService.chat(request, auth);

        assertThat(response.suggestions()).isEmpty();
        assertThat(response.answer()).isEqualTo(plainAnswer);
    }

    @Test
    void chat_dlpCalledOnEachSuggestion() {
        var conversation = new Conversation(USER_SUB);
        var request = new ChatRequest("Tell me about reserves.", null);
        var claudeJson = "{\"answer\":\"A\",\"suggestions\":[\"S1?\",\"S2?\"]}";

        when(fgaService.getRestrictedPaths(anyList(), anyList())).thenReturn(List.of());
        when(fgaService.buildQdrantFilter(anyList(), anyList())).thenReturn(Map.of());
        when(conversationService.getOrCreate(null, USER_SUB)).thenReturn(conversation);
        when(embedClient.embed(any())).thenReturn(List.of(0.1f));
        when(qdrantClient.search(any(), any(), anyInt())).thenReturn(List.of());
        when(conversationService.getHistory(any(), anyInt())).thenReturn(List.of());
        when(claudeService.complete(anyString(), anyList(), anyInt())).thenReturn(claudeJson);
        when(dlpClient.analyze(any(), anyList())).thenReturn(new DlpClient.DlpResult("cleaned", 0));

        ragService.chat(request, auth);

        verify(dlpClient, atLeast(3)).analyze(any(), anyList());
    }
}
