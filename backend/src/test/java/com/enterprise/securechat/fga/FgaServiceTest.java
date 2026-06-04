package com.enterprise.securechat.fga;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FgaServiceTest {

    @Mock
    private RoleRestrictionRepository restrictionRepository;

    @InjectMocks
    private FgaService fgaService;

    @Test
    void getRestrictedPaths_returnsPathsForMatchingRoles() {
        when(restrictionRepository.findSubjectPathsByRoleNames(List.of("finance-analyst")))
            .thenReturn(List.of("finance/payroll", "finance/budgets"));

        List<String> paths = fgaService.getRestrictedPaths(List.of("finance-analyst"));

        assertThat(paths).containsExactly("finance/payroll", "finance/budgets");
    }

    @Test
    void getRestrictedPaths_emptyRolesReturnsEmpty() {
        assertThat(fgaService.getRestrictedPaths(List.of())).isEmpty();
    }

    @Test
    void getRestrictedPaths_nullRolesReturnsEmpty() {
        assertThat(fgaService.getRestrictedPaths(null)).isEmpty();
    }

    @Test
    void buildQdrantFilter_emptyPathsReturnsEmptyMap() {
        assertThat(fgaService.buildQdrantFilter(List.of())).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildQdrantFilter_singlePathBuildsCorrectMustNotCondition() {
        Map<String, Object> filter = fgaService.buildQdrantFilter(List.of("finance"));

        List<Map<String, Object>> mustNot = (List<Map<String, Object>>) filter.get("must_not");
        assertThat(mustNot).hasSize(1);

        Map<String, Object> condition = mustNot.get(0);
        assertThat(condition.get("key")).isEqualTo("ancestor_paths");

        Map<String, Object> match = (Map<String, Object>) condition.get("match");
        assertThat((List<String>) match.get("any")).containsExactly("finance");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildQdrantFilter_multiplePathsProduceOneMustNotPerPath() {
        Map<String, Object> filter = fgaService.buildQdrantFilter(
            List.of("finance", "hr/compensation")
        );

        List<Map<String, Object>> mustNot = (List<Map<String, Object>>) filter.get("must_not");
        assertThat(mustNot).hasSize(2);

        // Verify second path is also correctly mapped
        Map<String, Object> second = mustNot.get(1);
        Map<String, Object> match = (Map<String, Object>) second.get("match");
        assertThat((List<String>) match.get("any")).containsExactly("hr/compensation");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildQdrantFilter_parentPathBlocksDescendantsViaAncestorPaths() {
        // Restricting "finance" should exclude finance/payroll and finance/budgets
        // because those documents store "finance" in their ancestor_paths[] field.
        // This test verifies the filter structure that makes hierarchy work.
        Map<String, Object> filter = fgaService.buildQdrantFilter(List.of("finance"));

        List<Map<String, Object>> mustNot = (List<Map<String, Object>>) filter.get("must_not");
        Map<String, Object> match = (Map<String, Object>) mustNot.get(0).get("match");

        // The "any" list contains "finance" — Qdrant will exclude every document
        // whose ancestor_paths array contains this value, which includes all children.
        assertThat((List<String>) match.get("any")).contains("finance");
    }
}
