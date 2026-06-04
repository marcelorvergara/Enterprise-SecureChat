package com.enterprise.securechat.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Extracts Keycloak realm roles from the JWT claim {@code realm_access.roles}
 * and maps them to Spring Security {@code ROLE_<name>} granted authorities.
 *
 * Keycloak JWT shape:
 * <pre>
 * {
 *   "realm_access": {
 *     "roles": ["admin", "employee", "finance-analyst"]
 *   }
 * }
 * </pre>
 *
 * Result: ROLE_admin, ROLE_employee, ROLE_finance-analyst
 * Used in @PreAuthorize("hasRole('admin')") — Spring strips the ROLE_ prefix automatically.
 */
public class RolesExtractor implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !realmAccess.containsKey("roles")) {
            return Collections.emptyList();
        }

        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) realmAccess.get("roles");

        return roles.stream()
            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
            .toList();
    }
}
