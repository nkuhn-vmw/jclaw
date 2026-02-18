package com.jclaw.session;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sessions")
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    @Column(name = "channel_type", nullable = false, length = 32)
    private String channelType;

    @Column(name = "channel_conversation_id", length = 256)
    private String channelConversationId;

    @Column(nullable = false, length = 256)
    private String principal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SessionScope scope = SessionScope.MAIN;

    @Column(columnDefinition = "jsonb")
    private String metadata = "{}";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_active_at", nullable = false)
    private Instant lastActiveAt = Instant.now();

    @Column(name = "message_count", nullable = false)
    private int messageCount = 0;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens = 0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private SessionStatus status = SessionStatus.ACTIVE;

    public Session() {}

    public Session(String agentId, String channelType, String principal, SessionScope scope) {
        this.agentId = agentId;
        this.channelType = channelType;
        this.principal = principal;
        this.scope = scope;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }

    public String getChannelConversationId() { return channelConversationId; }
    public void setChannelConversationId(String channelConversationId) { this.channelConversationId = channelConversationId; }

    public String getPrincipal() { return principal; }
    public void setPrincipal(String principal) { this.principal = principal; }

    public SessionScope getScope() { return scope; }
    public void setScope(SessionScope scope) { this.scope = scope; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public Instant getCreatedAt() { return createdAt; }

    public Instant getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; }

    public int getMessageCount() { return messageCount; }
    public void setMessageCount(int messageCount) { this.messageCount = messageCount; }

    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int totalTokens) { this.totalTokens = totalTokens; }

    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; }

    public void incrementMessageCount() { this.messageCount++; }
    public void addTokens(int tokens) { this.totalTokens += tokens; }
    public void touch() { this.lastActiveAt = Instant.now(); }
}
