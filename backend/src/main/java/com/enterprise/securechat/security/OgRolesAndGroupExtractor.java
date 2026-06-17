package com.enterprise.securechat.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import org.springframework.lang.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class OgRolesAndGroupExtractor implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String ROLES_CLAIM  = "https://enpsecurechat.com/roles";
    private static final String GROUPS_CLAIM = "https://enpsecurechat.com/groups";

    @Override
    public Collection<GrantedAuthority> convert(@NonNull Jwt jwt) {
        var authorities = new ArrayList<GrantedAuthority>();

        List<String> roles = jwt.getClaim(ROLES_CLAIM);
        if (roles != null) {
            roles.stream()
                .map(r -> new SimpleGrantedAuthority("ROLE_" + r))
                .forEach(authorities::add);
        }

        // Maps e.g. "bu-santos" → GROUP_BU_SANTOS (consumed by FgaService and DocumentController)
        List<String> groups = jwt.getClaim(GROUPS_CLAIM);
        if (groups != null) {
            groups.stream()
                .map(g -> new SimpleGrantedAuthority(
                        "GROUP_BU_" + g.replace("bu-", "").toUpperCase()))
                .forEach(authorities::add);
        }

        return authorities;
    }
}
