package com.enterprise.securechat.fga;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface RoleRestrictionRepository extends JpaRepository<RoleRestriction, UUID> {

    @Query("SELECT r.subjectPath FROM RoleRestriction r WHERE r.roleName IN :roleNames")
    List<String> findSubjectPathsByRoleNames(@Param("roleNames") List<String> roleNames);

    List<RoleRestriction> findByRoleName(String roleName);

    @Modifying
    @Transactional
    @Query("DELETE FROM RoleRestriction r WHERE r.roleName = :roleName AND r.subjectPath = :subjectPath")
    void deleteByRoleNameAndSubjectPath(@Param("roleName") String roleName,
                                        @Param("subjectPath") String subjectPath);
}
