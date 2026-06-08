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
     * Builds a Qdrant filter map that excludes any document whose ancestor_paths array
     * contains any of the restricted paths.
     *
     * Restricting "bu/santos" automatically blocks bu/santos/reserves, bu/santos/bar-questions,
     * etc. because every descendant stores all ancestor paths in ancestor_paths[].
     *
     * Returns an empty map when there are no restrictions (all docs visible).
     */
    public Map<String, Object> buildQdrantFilter(List<String> restrictedPaths) {
        if (restrictedPaths == null || restrictedPaths.isEmpty()) {
            return Collections.emptyMap();
        }
        var mustNot = restrictedPaths.stream()
            .map(path -> (Object) Map.of(
                "key", "ancestor_paths",
                "match", Map.of("any", List.of(path))
            ))
            .toList();
        return Map.of("must_not", mustNot);
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
