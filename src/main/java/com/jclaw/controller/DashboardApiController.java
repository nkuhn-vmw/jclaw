package com.jclaw.controller;

import com.jclaw.agent.AgentConfig;
import com.jclaw.agent.AgentConfigService;
import com.jclaw.agent.AgentContext;
import com.jclaw.agent.AgentRuntime;
import com.jclaw.audit.AuditEvent;
import com.jclaw.audit.AuditService;
import com.jclaw.channel.InboundMessage;
import com.jclaw.security.IdentityMapping;
import com.jclaw.security.IdentityMappingService;
import com.jclaw.session.Session;
import com.jclaw.session.SessionManager;
import com.jclaw.tool.RiskLevel;
import com.jclaw.tool.ToolRegistry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/api")
@PreAuthorize("hasAuthority('SCOPE_jclaw.admin')")
public class DashboardApiController {

    private final AgentRuntime agentRuntime;
    private final AgentConfigService agentConfigService;
    private final IdentityMappingService identityMappingService;
    private final SessionManager sessionManager;
    private final AuditService auditService;
    private final ToolRegistry toolRegistry;

    public DashboardApiController(AgentRuntime agentRuntime,
                                  AgentConfigService agentConfigService,
                                  IdentityMappingService identityMappingService,
                                  SessionManager sessionManager,
                                  AuditService auditService,
                                  ToolRegistry toolRegistry) {
        this.agentRuntime = agentRuntime;
        this.agentConfigService = agentConfigService;
        this.identityMappingService = identityMappingService;
        this.sessionManager = sessionManager;
        this.auditService = auditService;
        this.toolRegistry = toolRegistry;
    }

    // --- User Info ---

    @GetMapping("/userinfo")
    public UserInfoResponse getUserInfo(Authentication auth) {
        List<String> authorities = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
        return new UserInfoResponse(auth.getName(), authorities);
    }

    // --- Chat ---

    @PostMapping("/chat/send")
    public ChatResponse sendMessage(@RequestBody ChatRequest request, Authentication auth) {
        String principal = auth.getName();
        String agentId = request.agentId() != null ? request.agentId() : "default";

        InboundMessage message = new InboundMessage(
                "dashboard", principal, request.conversationId(), request.message());

        AgentContext context = new AgentContext(agentId, principal, "dashboard");

        var response = agentRuntime.callMessage(context, message).block();
        return new ChatResponse(response != null ? response.content() : "", agentId);
    }

    // --- Skills ---

    @GetMapping("/skills")
    public List<SkillDto> getSkills() {
        return toolRegistry.getAllTools().stream()
                .map(entry -> new SkillDto(
                        entry.name(),
                        entry.description(),
                        entry.riskLevel(),
                        entry.requiresApproval()))
                .collect(Collectors.toList());
    }

    // --- Agents ---

    @GetMapping("/agents")
    public List<AgentConfig> listAgents() {
        return agentConfigService.getAllConfigs();
    }

    @GetMapping("/agents/{agentId}")
    public AgentConfig getAgent(@PathVariable String agentId) {
        return agentConfigService.getAgentConfig(agentId);
    }

    @PutMapping("/agents/{agentId}")
    public AgentConfig updateAgent(@PathVariable String agentId, @RequestBody AgentConfig config) {
        config.setAgentId(agentId);
        return agentConfigService.updateAgentConfig(agentId, config);
    }

    @DeleteMapping("/agents/{agentId}")
    public void deleteAgent(@PathVariable String agentId) {
        agentConfigService.deleteAgentConfig(agentId);
    }

    // --- Identity Mappings ---

    @GetMapping("/identity-mappings/pending")
    public List<IdentityMapping> getPendingMappings() {
        return identityMappingService.getPendingMappings();
    }

    @PostMapping("/identity-mappings/{id}/approve")
    public IdentityMapping approveMapping(@PathVariable UUID id,
                                          @RequestBody Map<String, String> body,
                                          Authentication auth) {
        String jclawPrincipal = body != null ? body.get("jclawPrincipal") : null;
        if (jclawPrincipal == null || jclawPrincipal.isBlank()) {
            throw new IllegalArgumentException("jclawPrincipal is required for approval");
        }
        return identityMappingService.approveMapping(id, auth.getName(), jclawPrincipal);
    }

    // --- Sessions ---

    @GetMapping("/sessions")
    public List<Session> getSessions(@RequestParam(required = false) String agentId) {
        if (agentId != null && !agentId.isBlank()) {
            return sessionManager.getActiveSessionsForAgent(agentId);
        }
        return sessionManager.getAllActiveSessions();
    }

    @PostMapping("/sessions/{sessionId}/archive")
    public void archiveSession(@PathVariable UUID sessionId) {
        sessionManager.archiveSession(sessionId);
    }

    // --- Audit Log ---

    private static final int MAX_PAGE_SIZE = 200;

    @GetMapping("/audit")
    public Page<AuditEvent> getAuditLog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String principal,
            @RequestParam(required = false) String eventType) {
        if (page < 0) page = 0;
        if (size < 1) size = 1;
        if (size > MAX_PAGE_SIZE) size = MAX_PAGE_SIZE;
        if (principal != null) {
            return auditService.findByPrincipal(principal, PageRequest.of(page, size));
        }
        if (eventType != null) {
            return auditService.findByEventType(eventType, PageRequest.of(page, size));
        }
        return auditService.findAll(PageRequest.of(page, size));
    }

    // --- DTOs ---

    record UserInfoResponse(String name, List<String> authorities) {}

    record ChatRequest(String message, String agentId, String conversationId) {}

    record ChatResponse(String response, String agentId) {}

    record SkillDto(String name, String description, RiskLevel riskLevel, boolean requiresApproval) {}
}
