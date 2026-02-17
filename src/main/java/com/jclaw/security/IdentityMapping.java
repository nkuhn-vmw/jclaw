package com.jclaw.security;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "identity_mappings")
public class IdentityMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "channel_type", nullable = false, length = 32)
    private String channelType;

    @Column(name = "channel_user_id", nullable = false, length = 256)
    private String channelUserId;

    @Column(name = "jclaw_principal", nullable = false, length = 256)
    private String jclawPrincipal;

    @Column(name = "display_name", length = 256)
    private String displayName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(nullable = false)
    private boolean approved = false;

    @Column(name = "approved_by", length = 256)
    private String approvedBy;

    public IdentityMapping() {}

    public IdentityMapping(String channelType, String channelUserId, String jclawPrincipal) {
        this.channelType = channelType;
        this.channelUserId = channelUserId;
        this.jclawPrincipal = jclawPrincipal;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getChannelType() { return channelType; }
    public void setChannelType(String channelType) { this.channelType = channelType; }
    public String getChannelUserId() { return channelUserId; }
    public void setChannelUserId(String channelUserId) { this.channelUserId = channelUserId; }
    public String getJclawPrincipal() { return jclawPrincipal; }
    public void setJclawPrincipal(String jclawPrincipal) { this.jclawPrincipal = jclawPrincipal; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public boolean isApproved() { return approved; }
    public void setApproved(boolean approved) { this.approved = approved; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }
}
