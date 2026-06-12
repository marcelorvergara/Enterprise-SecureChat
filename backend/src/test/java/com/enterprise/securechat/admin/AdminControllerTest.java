package com.enterprise.securechat.admin;

import com.enterprise.securechat.audit.RestrictionAuditLogRepository;
import com.enterprise.securechat.config.SecurityConfig;
import com.enterprise.securechat.conversation.MessageRepository;
import com.enterprise.securechat.fga.RoleRepository;
import com.enterprise.securechat.fga.RoleRestrictionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
@Import(SecurityConfig.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private RoleRepository roleRepository;

    @MockBean
    private RoleRestrictionRepository restrictionRepository;

    @MockBean
    private RestrictionAuditLogRepository auditLogRepository;

    @MockBean
    private MessageRepository messageRepository;

    @Test
    void getSecurityHeatmap_unauthenticatedGets401() throws Exception {
        mvc.perform(get("/api/admin/metrics/security-heatmap"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getSecurityHeatmap_employeeGets403() throws Exception {
        mvc.perform(get("/api/admin/metrics/security-heatmap")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_employee"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void getSecurityHeatmap_adminGets200WithEmptyArrays() throws Exception {
        when(auditLogRepository.findTopRestrictedPaths()).thenReturn(List.of());
        when(messageRepository.findDlpDensityByDay()).thenReturn(List.of());

        mvc.perform(get("/api/admin/metrics/security-heatmap")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topRestrictedPaths").isArray())
                .andExpect(jsonPath("$.dlpDensityByDay").isArray());
    }
}
