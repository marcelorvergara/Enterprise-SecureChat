package com.enterprise.securechat.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface RestrictionAuditLogRepository extends JpaRepository<RestrictionAuditLog, UUID> {

    Page<RestrictionAuditLog> findAllByOrderByAccessedAtDesc(Pageable pageable);
}
