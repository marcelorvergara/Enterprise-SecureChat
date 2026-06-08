package com.enterprise.securechat.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class OgRolesAndGroupExtractor implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String ROLES_CLAIM = "https://enpsecurechat.com/roles";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        var authorities = new ArrayList<GrantedAuthority>();

        List<String> roles = jwt.getClaim(ROLES_CLAIM);
        if (roles != null) {
            roles.stream()
                .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                .forEach(authorities::add);
        }

        return authorities;
    }
}
