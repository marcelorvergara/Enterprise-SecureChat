package com.enterprise.securechat.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface RestrictionAuditLogRepository extends JpaRepository<RestrictionAuditLog, UUID> {

    Page<RestrictionAuditLog> findAllByOrderByAccessedAtDesc(Pageable pageable);

    @Query(value = """
            SELECT unnest(restricted_paths) AS path, COUNT(*) AS hit_count
            FROM restriction_audit_log
            WHERE restricted_paths != '{}'
            GROUP BY path
            ORDER BY hit_count DESC
            LIMIT 20
            """, nativeQuery = true)
    List<Object[]> findTopRestrictedPaths();
}
