package com.enterprise.securechat.fga;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FgaService {

    // Hard-coded role restrictions that hold even when the DB row is absent.
    // Mirrors the most critical role_restrictions entries as a defence-in-depth
    // guarantee against misconfigured or empty tables.
    private static final Map<String, List<String>> STATIC_ROLE_RESTRICTIONS = Map.of(
        "reservoir-team", List.of("bar-questions")
    );

    // Roles that may query across all BUs without belonging to a specific BU group.
    // Every other role without a GROUP_BU_* authority is blocked from all bu/* paths.
    private static final Set<String> CROSS_BU_ROLES = Set.of(
        "admin", "reserves-management", "reserves-coordination"
    );

    // ── Clearance hierarchy ──────────────────────────────────────────────────
    // Tier values are ordinal: a user with tier N can see levels 0…N.
    static final int TIER_PUBLIC       = 0;
    static final int TIER_INTERNAL     = 1;
    static final int TIER_CONFIDENTIAL = 2;

    private static final Map<String, Integer> ROLE_CLEARANCE = Map.of(
        "admin",                 TIER_CONFIDENTIAL,
        "reserves-coordination", TIER_CONFIDENTIAL,
        "reserves-management",   TIER_CONFIDENTIAL,
        "reservoir-team",        TIER_INTERNAL,
        "bu-user",               TIER_INTERNAL,
        "employee",              TIER_INTERNAL
    );

    private static final Map<String, Integer> LEVEL_TIER = Map.of(
        "Public",       TIER_PUBLIC,
        "Internal",     TIER_INTERNAL,
        "Confidential", TIER_CONFIDENTIAL
    );

    private final RoleRestrictionRepository restrictionRepository;
    private final String[] knownBus;

    public FgaService(
            RoleRestrictionRepository restrictionRepository,
            @Value("${og.known-bus:campos,santos,solimoes}") String[] knownBus) {
        this.restrictionRepository = restrictionRepository;
        this.knownBus = knownBus;
    }

    /**
     * Returns all subject_path values that are forbidden for the given roles and BU groups.
     *
     * Two restriction layers are merged:
     * 1. DB-backed role restrictions — managed via AdminController (e.g. reservoir-team → bar-questions).
     * 2. Dynamic BU isolation — users in GROUP_BU_CAMPOS are blocked from all other BU paths.
     */
    @Transactional(readOnly = true)
    public List<String> getRestrictedPaths(List<String> roles, List<String> groups) {
        var paths = new ArrayList<String>();

        if (roles != null && !roles.isEmpty()) {
            paths.addAll(restrictionRepository.findSubjectPathsByRoleNames(roles));
            roles.forEach(role ->
                paths.addAll(STATIC_ROLE_RESTRICTIONS.getOrDefault(role, List.of()))
            );
        }

        paths.addAll(computeBuRestrictedPaths(roles, groups));

        return paths;
    }

    /**
     * Returns the classification levels that are blocked for the given roles.
     *
     * A role's clearance tier is looked up from ROLE_CLEARANCE (unrecognised roles
     * default to TIER_PUBLIC). The user's effective clearance is the maximum tier
     * across all their roles. Any classification level with a tier above that maximum
     * is blocked.
     *
     * Examples:
     *   ["employee"]              → ["Confidential"]         (Internal tier — blocks only Confidential)
     *   ["reserves-coordination"] → []                       (Confidential tier — nothing blocked)
     *   []                        → ["Internal","Confidential"] (no roles — Public tier only)
     */
    public List<String> getBlockedClassifications(List<String> roles) {
        int maxTier = (roles == null || roles.isEmpty()) ? TIER_PUBLIC :
            roles.stream()
                 .mapToInt(r -> ROLE_CLEARANCE.getOrDefault(r, TIER_PUBLIC))
                 .max()
                 .orElse(TIER_PUBLIC);
        return LEVEL_TIER.entrySet().stream()
                .filter(e -> e.getValue() > maxTier)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Builds a Qdrant filter map combining subject-path restrictions (FGA hierarchy)
     * with classification-level clearance restrictions (clearance hierarchy).
     *
     * Both dimensions are expressed as must_not conditions applied at the Qdrant
     * search layer — never in Java application code (Constraint #3).
     *
     * Returns an empty map when there are no restrictions of either kind.
     */
    public Map<String, Object> buildQdrantFilter(
            List<String> restrictedPaths,
            List<String> blockedClassifications) {
        var mustNot = new ArrayList<>();
        if (restrictedPaths != null) {
            restrictedPaths.forEach(path ->
                mustNot.add(Map.of(
                    "key", "ancestor_paths",
                    "match", Map.of("any", List.of(path))
                )));
        }
        if (blockedClassifications != null && !blockedClassifications.isEmpty()) {
            mustNot.add(Map.of(
                "key",   "classification_level",
                "match", Map.of("any", blockedClassifications)
            ));
        }
        if (mustNot.isEmpty()) return Collections.emptyMap();
        return Map.of("must_not", List.copyOf(mustNot));
    }

    /**
     * Single-argument overload for backward compatibility with existing callers
     * and tests that predate the classification clearance layer.
     *
     * Delegates to the two-argument form with no classification restrictions.
     */
    public Map<String, Object> buildQdrantFilter(List<String> restrictedPaths) {
        return buildQdrantFilter(restrictedPaths, List.of());
    }

    /**
     * Derives the BU paths to block based on roles and group memberships.
     *
     * - Has GROUP_BU_X → can see bu/X/**, blocked from all other bu/* paths.
     * - No BU group + CROSS_BU_ROLES → unrestricted across all BUs.
     * - No BU group + any other role → blocked from every bu/* path (default deny).
     */
    private List<String> computeBuRestrictedPaths(List<String> roles, List<String> groups) {
        Set<String> userBus = (groups == null ? List.<String>of() : groups).stream()
            .filter(g -> g.startsWith("GROUP_BU_"))
            .map(g -> g.substring("GROUP_BU_".length()).toLowerCase())
            .collect(Collectors.toSet());

        if (!userBus.isEmpty()) {
            return java.util.Arrays.stream(knownBus)
                .filter(bu -> !userBus.contains(bu.toLowerCase()))
                .map(bu -> "bu/" + bu.toLowerCase())
                .toList();
        }

        boolean hasCrossBuRole = roles != null && roles.stream().anyMatch(CROSS_BU_ROLES::contains);
        if (hasCrossBuRole) {
            return List.of();
        }

        return java.util.Arrays.stream(knownBus)
            .map(bu -> "bu/" + bu.toLowerCase())
            .toList();
    }
}
