package com.jclaw.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditRepository auditRepository;

    public AuditService(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @Transactional
    public AuditEvent log(String eventType, String action) {
        return log(AuditEvent.of(eventType, action));
    }

    @Transactional
    public AuditEvent log(AuditEvent event) {
        AuditEvent saved = auditRepository.save(event);
        log.info("audit event={} action={} principal={} outcome={}",
                saved.getEventType(), saved.getAction(),
                saved.getPrincipal(), saved.getOutcome());
        return saved;
    }

    public void logAuth(String principal, String action, String outcome, String sourceIp) {
        String eventType = "SUCCESS".equals(outcome) || "AUTH_SUCCESS".equals(outcome)
                ? AuditEvent.TYPE_AUTH_SUCCESS : AuditEvent.TYPE_AUTH_FAILURE;
        log(AuditEvent.of(eventType, action)
                .withPrincipal(principal)
                .withSourceIp(sourceIp)
                .withOutcome(outcome));
    }

    public void logToolCall(String principal, String agentId, UUID sessionId,
                           String toolName, String outcome, String details) {
        log(AuditEvent.of(AuditEvent.TYPE_TOOL_CALL, toolName)
                .withPrincipal(principal)
                .withAgentId(agentId)
                .withSessionId(sessionId)
                .withOutcome(outcome)
                .withDetails(details));
    }

    public void logSessionEvent(String eventType, String principal, String agentId,
                               UUID sessionId, String action) {
        log(AuditEvent.of(eventType, action)
                .withPrincipal(principal)
                .withAgentId(agentId)
                .withSessionId(sessionId));
    }

    public void logConfigChange(String principal, String agentId, String action, String details) {
        log(AuditEvent.of(AuditEvent.TYPE_CONFIG_CHANGE, action)
                .withPrincipal(principal)
                .withAgentId(agentId)
                .withDetails(details));
    }

    public void logContentFilter(String filterName, String action, String principal,
                                String channelType, String outcome) {
        log(AuditEvent.of(AuditEvent.TYPE_CONTENT_FILTER, action)
                .withPrincipal(principal)
                .withChannelType(channelType)
                .withResource("filter", filterName)
                .withOutcome(outcome));
    }

    public Page<AuditEvent> findAll(Pageable pageable) {
        return auditRepository.findAll(pageable);
    }

    public Page<AuditEvent> findByPrincipal(String principal, Pageable pageable) {
        return auditRepository.findByPrincipalOrderByTimestampDesc(principal, pageable);
    }

    public Page<AuditEvent> findByEventType(String eventType, Pageable pageable) {
        return auditRepository.findByEventTypeOrderByTimestampDesc(eventType, pageable);
    }
}
