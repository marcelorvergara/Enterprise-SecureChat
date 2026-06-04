package com.enterprise.securechat.audit;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "restriction_audit_log")
public class RestrictionAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_sub", nullable = false)
    private String userSub;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "role_names", columnDefinition = "text[]", nullable = false)
    private String[] roleNames;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "restricted_paths", columnDefinition = "text[]", nullable = false)
    private String[] restrictedPaths;

    @Column(name = "query_hash", nullable = false)
    private String queryHash;

    @Column(name = "accessed_at", insertable = false, updatable = false)
    private OffsetDateTime accessedAt;

    public RestrictionAuditLog() {}

    public RestrictionAuditLog(String userSub, String[] roleNames, String[] restrictedPaths, String queryHash) {
        this.userSub = userSub;
        this.roleNames = roleNames;
        this.restrictedPaths = restrictedPaths;
        this.queryHash = queryHash;
    }

    public UUID getId() { return id; }
    public String getUserSub() { return userSub; }
    public String[] getRoleNames() { return roleNames; }
    public String[] getRestrictedPaths() { return restrictedPaths; }
    public String getQueryHash() { return queryHash; }
    public OffsetDateTime getAccessedAt() { return accessedAt; }
}
