package com.enterprise.securechat.fga;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

    private FgaService fgaService;

    @BeforeEach
    void setUp() {
        fgaService = new FgaService(
            restrictionRepository,
            new String[]{"campos", "santos", "solimoes"}
        );
    }

    // ── DB-backed role restrictions ──────────────────────────────────────────

    @Test
    void getRestrictedPaths_returnsDbPathsForMatchingRoles() {
        when(restrictionRepository.findSubjectPathsByRoleNames(List.of("reservoir-team")))
            .thenReturn(List.of("bar-questions"));

        var paths = fgaService.getRestrictedPaths(List.of("reservoir-team"), List.of());

        assertThat(paths).contains("bar-questions");
    }

    @Test
    void getRestrictedPaths_reservoirTeamAlwaysBlockedFromBarQuestionsEvenWithEmptyDb() {
        when(restrictionRepository.findSubjectPathsByRoleNames(List.of("reservoir-team")))
            .thenReturn(List.of());

        var paths = fgaService.getRestrictedPaths(List.of("reservoir-team"), List.of());

        assertThat(paths).contains("bar-questions");
    }

    // ── Dynamic BU isolation ─────────────────────────────────────────────────

    @Test
    void getRestrictedPaths_buUserInCamposIsBlockedFromOtherBus() {
        when(restrictionRepository.findSubjectPathsByRoleNames(List.of("bu-user")))
            .thenReturn(List.of());

        var paths = fgaService.getRestrictedPaths(
            List.of("bu-user"),
            List.of("GROUP_BU_CAMPOS")
        );

        assertThat(paths).contains("bu/santos", "bu/solimoes");
        assertThat(paths).doesNotContain("bu/campos");
    }

    @Test
    void getRestrictedPaths_crossBuRoleWithNoGroupSeesAllBus() {
        when(restrictionRepository.findSubjectPathsByRoleNames(List.of("reserves-coordination")))
            .thenReturn(List.of());

        var paths = fgaService.getRestrictedPaths(
            List.of("reserves-coordination"),
            List.of()
        );

        assertThat(paths).doesNotContain("bu/campos", "bu/santos", "bu/solimoes");
    }

    @Test
    void getRestrictedPaths_nonCrossBuRoleWithNoGroupIsBlockedFromAllBus() {
        when(restrictionRepository.findSubjectPathsByRoleNames(List.of("reservoir-team")))
            .thenReturn(List.of());

        var paths = fgaService.getRestrictedPaths(
            List.of("reservoir-team"),
            List.of()
        );

        assertThat(paths).contains("bu/campos", "bu/santos", "bu/solimoes");
    }

    @Test
    void getRestrictedPaths_employeeWithNoGroupIsBlockedFromAllBus() {
        when(restrictionRepository.findSubjectPathsByRoleNames(List.of("employee")))
            .thenReturn(List.of());

        var paths = fgaService.getRestrictedPaths(
            List.of("employee"),
            List.of()
        );

        assertThat(paths).contains("bu/campos", "bu/santos", "bu/solimoes");
    }

    @Test
    void getRestrictedPaths_dbRestrictionsAndBuIsolationAreMerged() {
        when(restrictionRepository.findSubjectPathsByRoleNames(List.of("reservoir-team")))
            .thenReturn(List.of("bar-questions"));

        var paths = fgaService.getRestrictedPaths(
            List.of("reservoir-team"),
            List.of("GROUP_BU_SANTOS")
        );

        assertThat(paths).contains("bar-questions", "bu/campos", "bu/solimoes");
        assertThat(paths).doesNotContain("bu/santos");
    }

    // ── Qdrant filter building ───────────────────────────────────────────────

    @Test
    void buildQdrantFilter_emptyPathsReturnsEmptyMap() {
        assertThat(fgaService.buildQdrantFilter(List.of())).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildQdrantFilter_singlePathBuildsCorrectMustNotCondition() {
        Map<String, Object> filter = fgaService.buildQdrantFilter(List.of("bar-questions"));

        var mustNot = (List<Map<String, Object>>) filter.get("must_not");
        assertThat(mustNot).hasSize(1);

        var condition = mustNot.get(0);
        assertThat(condition.get("key")).isEqualTo("ancestor_paths");

        var match = (Map<String, Object>) condition.get("match");
        assertThat((List<String>) match.get("any")).containsExactly("bar-questions");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildQdrantFilter_multiplePathsProduceOneMustNotPerPath() {
        var filter = fgaService.buildQdrantFilter(List.of("bar-questions", "bu/santos"));

        var mustNot = (List<Map<String, Object>>) filter.get("must_not");
        assertThat(mustNot).hasSize(2);

        var second = mustNot.get(1);
        var match = (Map<String, Object>) second.get("match");
        assertThat((List<String>) match.get("any")).containsExactly("bu/santos");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildQdrantFilter_parentPathBlocksDescendantsViaAncestorPaths() {
        var filter = fgaService.buildQdrantFilter(List.of("bu/santos"));

        var mustNot = (List<Map<String, Object>>) filter.get("must_not");
        var match = (Map<String, Object>) mustNot.get(0).get("match");

        assertThat((List<String>) match.get("any")).contains("bu/santos");
    }

    // ── Clearance: getBlockedClassifications ────────────────────────────────

    @Test
    void getBlockedClassifications_employeeIsBlockedFromConfidential() {
        var blocked = fgaService.getBlockedClassifications(List.of("employee"));
        assertThat(blocked).containsExactly("Confidential");
    }

    @Test
    void getBlockedClassifications_reservesCoordinationSeesAllLevels() {
        var blocked = fgaService.getBlockedClassifications(List.of("reserves-coordination"));
        assertThat(blocked).isEmpty();
    }

    @Test
    void getBlockedClassifications_noRolesIsPublicBlockedFromInternalAndConfidential() {
        var blocked = fgaService.getBlockedClassifications(List.of());
        assertThat(blocked).containsExactlyInAnyOrder("Internal", "Confidential");
    }

    // ── Qdrant filter: combined path + classification ────────────────────────

    @Test
    @SuppressWarnings("unchecked")
    void buildQdrantFilter_classificationAddsSecondMustNotCondition() {
        var filter = fgaService.buildQdrantFilter(
                List.of("bu/santos"), List.of("Confidential"));

        var mustNot = (List<Map<String, Object>>) filter.get("must_not");
        assertThat(mustNot).hasSize(2);

        var clCondition = mustNot.get(1);
        assertThat(clCondition.get("key")).isEqualTo("classification_level");
        var match = (Map<String, Object>) clCondition.get("match");
        assertThat((List<String>) match.get("any")).containsExactly("Confidential");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildQdrantFilter_oldOverloadProducesOnlyAncestorPathsCondition() {
        var filter = fgaService.buildQdrantFilter(List.of("bar-questions"));

        var mustNot = (List<Map<String, Object>>) filter.get("must_not");
        assertThat(mustNot).hasSize(1);
        assertThat(mustNot.get(0).get("key")).isEqualTo("ancestor_paths");
    }
}
