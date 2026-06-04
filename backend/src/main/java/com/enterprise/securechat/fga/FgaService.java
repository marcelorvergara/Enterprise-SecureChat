package com.enterprise.securechat.fga;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class FgaService {

    private final RoleRestrictionRepository restrictionRepository;

    public FgaService(RoleRestrictionRepository restrictionRepository) {
        this.restrictionRepository = restrictionRepository;
    }

    /**
     * Returns all subject_path values that are forbidden for the given roles.
     * A single role can have multiple restrictions; this unions across all provided roles.
     */
    @Transactional(readOnly = true)
    public List<String> getRestrictedPaths(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Collections.emptyList();
        }
        return restrictionRepository.findSubjectPathsByRoleNames(roles);
    }

    /**
     * Builds a Qdrant filter map that excludes any document whose ancestor_paths array
     * contains any of the restricted paths.
     *
     * Each restricted path produces one must_not condition:
     *   { "key": "ancestor_paths", "match": { "any": ["finance"] } }
     *
     * Restricting "finance" automatically blocks finance/payroll, finance/budgets, etc.
     * because every descendant stores all ancestor paths in ancestor_paths[].
     *
     * Returns an empty map when there are no restrictions (no filter needed — all docs visible).
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
}
