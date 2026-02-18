package com.jclaw.channel.restapi;

import com.jclaw.agent.AgentConfig;
import com.jclaw.agent.AgentConfigService;
import com.jclaw.audit.AuditEvent;
import com.jclaw.audit.AuditService;
import com.jclaw.security.IdentityMapping;
import com.jclaw.security.IdentityMappingService;
import com.jclaw.session.Session;
import com.jclaw.session.SessionManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class AdminApiController {

    private final AgentConfigService agentConfigService;
    private final IdentityMappingService identityMappingService;
    private final SessionManager sessionManager;
    private final AuditService auditService;

    public AdminApiController(AgentConfigService agentConfigService,
                             IdentityMappingService identityMappingService,
                             SessionManager sessionManager,
                             AuditService auditService) {
        this.agentConfigService = agentConfigService;
        this.identityMappingService = identityMappingService;
        this.sessionManager = sessionManager;
        this.auditService = auditService;
    }

    // --- Agent Config ---
    @GetMapping("/agents")
    @PreAuthorize("hasAuthority('SCOPE_jclaw.admin')")
    public List<AgentConfig> listAgents() {
        return agentConfigService.getAllConfigs();
    }

    @GetMapping("/agents/{agentId}")
    @PreAuthorize("hasAuthority('SCOPE_jclaw.admin')")
    public AgentConfig getAgent(@PathVariable String agentId) {
        return agentConfigService.getAgentConfig(agentId);
    }

    @PutMapping("/agents/{agentId}")
    @PreAuthorize("hasAuthority('SCOPE_jclaw.admin')")
    public AgentConfig updateAgent(@PathVariable String agentId,
                                  @RequestBody AgentConfig config) {
        // Enforce path/body consistency: override body agentId with path parameter
        config.setAgentId(agentId);
        return agentConfigService.updateAgentConfig(agentId, config);
    }

    @DeleteMapping("/agents/{agentId}")
    @PreAuthorize("hasAuthority('SCOPE_jclaw.admin')")
    public void deleteAgent(@PathVariable String agentId) {
        agentConfigService.deleteAgentConfig(agentId);
    }

    // --- Identity Mappings ---
    @GetMapping("/identity-mappings/pending")
    @PreAuthorize("hasAnyAuthority('SCOPE_jclaw.operator', 'SCOPE_jclaw.admin')")
    public List<IdentityMapping> getPendingMappings() {
        return identityMappingService.getPendingMappings();
    }

    @PostMapping("/identity-mappings/{id}/approve")
    @PreAuthorize("hasAnyAuthority('SCOPE_jclaw.operator', 'SCOPE_jclaw.admin')")
    public IdentityMapping approveMapping(@PathVariable UUID id,
                                          @RequestBody java.util.Map<String, String> body,
                                          Authentication auth) {
        String jclawPrincipal = body != null ? body.get("jclawPrincipal") : null;
        if (jclawPrincipal == null || jclawPrincipal.isBlank()) {
            throw new IllegalArgumentException("jclawPrincipal is required for approval");
        }
        return identityMappingService.approveMapping(id, auth.getName(), jclawPrincipal);
    }

    // --- Sessions ---
    @GetMapping("/agents/{agentId}/sessions")
    @PreAuthorize("hasAnyAuthority('SCOPE_jclaw.operator', 'SCOPE_jclaw.admin')")
    public List<Session> getAgentSessions(@PathVariable String agentId) {
        return sessionManager.getActiveSessionsForAgent(agentId);
    }

    @PostMapping("/sessions/{sessionId}/archive")
    @PreAuthorize("hasAnyAuthority('SCOPE_jclaw.operator', 'SCOPE_jclaw.admin')")
    public void archiveSession(@PathVariable UUID sessionId) {
        sessionManager.archiveSession(sessionId);
    }

    // --- Audit Log ---
    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('SCOPE_jclaw.admin')")
    public Page<AuditEvent> getAuditLog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String principal,
            @RequestParam(required = false) String eventType) {
        if (principal != null) {
            return auditService.findByPrincipal(principal, PageRequest.of(page, size));
        }
        if (eventType != null) {
            return auditService.findByEventType(eventType, PageRequest.of(page, size));
        }
        return auditService.findAll(PageRequest.of(page, size));
    }
}
