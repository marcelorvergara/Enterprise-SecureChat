package com.enterprise.securechat.audit;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

@Service
public class AuditService {

    private final RestrictionAuditLogRepository repository;

    public AuditService(RestrictionAuditLogRepository repository) {
        this.repository = repository;
    }

    public void log(String userSub, List<String> roles, List<String> restrictedPaths, String prompt) {
        repository.save(new RestrictionAuditLog(
                userSub,
                roles.toArray(String[]::new),
                restrictedPaths.toArray(String[]::new),
                sha256(prompt)
        ));
    }

    private String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
