package com.enterprise.securechat.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Extracts both Keycloak realm roles and BU group memberships from the JWT.
 *
 * Realm roles  → ROLE_reserves-coordination, ROLE_reserves-management, etc.
 * Keycloak groups → GROUP_BU_CAMPOS, GROUP_BU_SANTOS, etc.
 *
 * The groups claim is emitted by the "groups" client scope protocol mapper
 * (oidc-group-membership-mapper, full.path=false) configured in the realm export.
 * Group name is upper-cased so FgaService can compare it case-insensitively.
 */
public class OgRolesAndGroupExtractor implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        var authorities = new ArrayList<GrantedAuthority>();

        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess != null && realmAccess.containsKey("roles")) {
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) realmAccess.get("roles");
            roles.stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .forEach(authorities::add);
        }

        List<String> groups = jwt.getClaim("groups");
        if (groups != null) {
            groups.stream()
                .map(g -> (GrantedAuthority) new SimpleGrantedAuthority("GROUP_" + g.toUpperCase()))
                .forEach(authorities::add);
        }

        return authorities;
    }
}
