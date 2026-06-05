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

    // Legacy HR/Finance paths that must be invisible in the O&G deployment.
    // These remain in Qdrant until the collection is purged; blocking them here
    // prevents accidental retrieval while the purge is pending.
    private static final List<String> LEGACY_ROOTS = List.of("finance", "hr", "it-ops");

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
        }

        paths.addAll(computeBuRestrictedPaths(groups));
        paths.addAll(LEGACY_ROOTS);

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
     * Derives the BU paths to block based on the user's group memberships.
     * A user in GROUP_BU_CAMPOS can see bu/campos/** but not bu/santos/** or bu/solimoes/**.
     * Users with no BU group (e.g. admin, reserves-coordination) receive no BU restrictions.
     */
    private List<String> computeBuRestrictedPaths(List<String> groups) {
        if (groups == null || groups.isEmpty()) {
            return List.of();
        }

        Set<String> userBus = groups.stream()
            .filter(g -> g.startsWith("GROUP_BU_"))
            .map(g -> g.substring("GROUP_BU_".length()).toLowerCase())
            .collect(Collectors.toSet());

        if (userBus.isEmpty()) {
            return List.of();
        }

        return java.util.Arrays.stream(knownBus)
            .filter(bu -> !userBus.contains(bu.toLowerCase()))
            .map(bu -> "bu/" + bu.toLowerCase())
            .toList();
    }
}
