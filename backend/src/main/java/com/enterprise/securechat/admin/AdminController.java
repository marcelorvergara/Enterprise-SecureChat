package com.enterprise.securechat.admin;

import com.enterprise.securechat.audit.RestrictionAuditLog;
import com.enterprise.securechat.audit.RestrictionAuditLogRepository;
import com.enterprise.securechat.conversation.MessageRepository;
import com.enterprise.securechat.fga.Role;
import com.enterprise.securechat.fga.RoleRepository;
import com.enterprise.securechat.fga.RoleRestriction;
import com.enterprise.securechat.fga.RoleRestrictionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('admin')")
public class AdminController {

    private final RoleRepository roleRepository;
    private final RoleRestrictionRepository restrictionRepository;
    private final RestrictionAuditLogRepository auditLogRepository;
    private final MessageRepository messageRepository;

    public AdminController(RoleRepository roleRepository,
                           RoleRestrictionRepository restrictionRepository,
                           RestrictionAuditLogRepository auditLogRepository,
                           MessageRepository messageRepository) {
        this.roleRepository = roleRepository;
        this.restrictionRepository = restrictionRepository;
        this.auditLogRepository = auditLogRepository;
        this.messageRepository = messageRepository;
    }

    // ── GET /api/admin/roles ────────────────────────────────────────────────────
    // Returns every role (from the roles table) with its current restriction list.
    @GetMapping("/roles")
    public ResponseEntity<List<RoleView>> getRoles() {
        var views = roleRepository.findAllByOrderByRoleNameAsc().stream()
                .map(role -> {
                    var restrictions = restrictionRepository.findByRoleName(role.getRoleName())
                            .stream()
                            .map(r -> new RestrictionView(r.getId(), r.getSubjectPath(),
                                    r.getReason(), r.getCreatedAt()))
                            .toList();
                    return new RoleView(role.getRoleName(), restrictions);
                })
                .toList();
        return ResponseEntity.ok(views);
    }

    // ── POST /api/admin/roles/{role}/restrictions ───────────────────────────────
    @PostMapping("/roles/{role}/restrictions")
    public ResponseEntity<RestrictionView> addRestriction(
            @PathVariable String role,
            @RequestBody @Valid AddRestrictionRequest req,
            Authentication auth) {
        var jwt = (Jwt) auth.getPrincipal();
        var restriction = new RoleRestriction();
        restriction.setRoleName(role);
        restriction.setSubjectPath(req.subjectPath());
        restriction.setReason(req.reason());
        restriction.setCreatedBy(jwt.getSubject());
        var saved = restrictionRepository.save(restriction);
        // Re-read to pick up the DB-generated created_at timestamp
        var refreshed = restrictionRepository.findById(saved.getId()).orElseThrow();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RestrictionView(refreshed.getId(), refreshed.getSubjectPath(),
                        refreshed.getReason(), refreshed.getCreatedAt()));
    }

    // ── DELETE /api/admin/roles/{role}/restrictions?subjectPath=finance/payroll ─
    @DeleteMapping("/roles/{role}/restrictions")
    public ResponseEntity<Void> removeRestriction(
            @PathVariable String role,
            @RequestParam String subjectPath) {
        restrictionRepository.deleteByRoleNameAndSubjectPath(role, subjectPath);
        return ResponseEntity.noContent().build();
    }

    // ── GET /api/admin/audit-log?page=0&size=20 ─────────────────────────────────
    @GetMapping("/audit-log")
    public ResponseEntity<Page<AuditLogView>> getAuditLog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        var pageable = PageRequest.of(page, size, Sort.by("accessedAt").descending());
        var result = auditLogRepository.findAllByOrderByAccessedAtDesc(pageable)
                .map(e -> new AuditLogView(
                        e.getId(),
                        e.getUserSub(),
                        Arrays.asList(e.getRoleNames()),
                        Arrays.asList(e.getRestrictedPaths()),
                        e.getQueryHash(),
                        e.getAccessedAt()));
        return ResponseEntity.ok(result);
    }

    // ── GET /api/admin/metrics/security-heatmap ─────────────────────────────────
    @GetMapping("/metrics/security-heatmap")
    public ResponseEntity<SecurityHeatmapResponse> getSecurityHeatmap() {
        var paths = auditLogRepository.findTopRestrictedPaths().stream()
                .map(row -> new SecurityHeatmapResponse.PathHitCount(
                        (String) row[0], ((Number) row[1]).longValue()))
                .toList();
        var dlpDensity = messageRepository.findDlpDensityByDay().stream()
                .map(row -> new SecurityHeatmapResponse.DlpDensityPoint(
                        (String) row[0], ((Number) row[1]).longValue()))
                .toList();
        return ResponseEntity.ok(new SecurityHeatmapResponse(paths, dlpDensity));
    }

    // ── DTOs ────────────────────────────────────────────────────────────────────
    record RoleView(String roleName, List<RestrictionView> restrictions) {}

    record RestrictionView(UUID id, String subjectPath, String reason, OffsetDateTime createdAt) {}

    record AddRestrictionRequest(@NotBlank String subjectPath, String reason) {}

    record AuditLogView(
            UUID id,
            String userSub,
            List<String> roleNames,
            List<String> restrictedPaths,
            String queryHash,
            OffsetDateTime accessedAt) {}
}
