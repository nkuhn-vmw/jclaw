package com.jclaw.audit;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditEvent {

    // Event type constants matching spec section 5.5
    public static final String TYPE_AUTH_SUCCESS = "AUTH_SUCCESS";
    public static final String TYPE_AUTH_FAILURE = "AUTH_FAILURE";
    public static final String TYPE_TOOL_CALL = "TOOL_CALL";
    public static final String TYPE_SESSION_CREATE = "SESSION_CREATE";
    public static final String TYPE_SESSION_ARCHIVE = "SESSION_ARCHIVE";
    public static final String TYPE_CONFIG_CHANGE = "CONFIG_CHANGE";
    public static final String TYPE_CONTENT_FILTER = "CONTENT_FILTER";
    public static final String TYPE_MESSAGE_ROUTED = "MESSAGE_ROUTED";
    public static final String TYPE_DELIVERY_FAILED = "DELIVERY_FAILED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private Instant timestamp = Instant.now();

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(length = 256)
    private String principal;

    @Column(name = "agent_id", length = 64)
    private String agentId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "channel_type", length = 32)
    private String channelType;

    @Column(nullable = false, length = 256)
    private String action;

    @Column(name = "resource_type", length = 64)
    private String resourceType;

    @Column(name = "resource_id", length = 256)
    private String resourceId;

    @Column(columnDefinition = "jsonb")
    private String details = "{}";

    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    @Column(nullable = false, length = 16)
    private String outcome = "SUCCESS";

    public AuditEvent() {}

    public static AuditEvent of(String eventType, String action) {
        AuditEvent event = new AuditEvent();
        event.eventType = eventType;
        event.action = action;
        return event;
    }

    public AuditEvent withPrincipal(String principal) { this.principal = principal; return this; }
    public AuditEvent withAgentId(String agentId) { this.agentId = agentId; return this; }
    public AuditEvent withSessionId(UUID sessionId) { this.sessionId = sessionId; return this; }
    public AuditEvent withChannelType(String channelType) { this.channelType = channelType; return this; }
    public AuditEvent withResource(String type, String id) { this.resourceType = type; this.resourceId = id; return this; }
    public AuditEvent withDetails(String details) { this.details = details; return this; }
    public AuditEvent withSourceIp(String sourceIp) { this.sourceIp = sourceIp; return this; }
    public AuditEvent withOutcome(String outcome) { this.outcome = outcome; return this; }

    public UUID getId() { return id; }
    public Instant getTimestamp() { return timestamp; }
    public String getEventType() { return eventType; }
    public String getPrincipal() { return principal; }
    public String getAgentId() { return agentId; }
    public UUID getSessionId() { return sessionId; }
    public String getChannelType() { return channelType; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getDetails() { return details; }
    public String getSourceIp() { return sourceIp; }
    public String getOutcome() { return outcome; }
}
