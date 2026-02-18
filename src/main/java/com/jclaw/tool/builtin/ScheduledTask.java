package com.jclaw.tool.builtin;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "scheduled_tasks")
public class ScheduledTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 256)
    private String name;

    @Column(name = "cron_expression", nullable = false, length = 128)
    private String cronExpression;

    @Column(length = 1000)
    private String message;

    @Column(name = "agent_id", length = 64)
    private String agentId;

    @Column(length = 256)
    private String principal;

    @Column(name = "next_fire_at")
    private Instant nextFireAt;

    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TaskStatus status = TaskStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public ScheduledTask() {}

    public ScheduledTask(String name, String cronExpression, String message,
                        String agentId, String principal) {
        this.name = name;
        this.cronExpression = cronExpression;
        this.message = message;
        this.agentId = agentId;
        this.principal = principal;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getPrincipal() { return principal; }
    public void setPrincipal(String principal) { this.principal = principal; }
    public Instant getNextFireAt() { return nextFireAt; }
    public void setNextFireAt(Instant nextFireAt) { this.nextFireAt = nextFireAt; }
    public Instant getLastFiredAt() { return lastFiredAt; }
    public void setLastFiredAt(Instant lastFiredAt) { this.lastFiredAt = lastFiredAt; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }

    public enum TaskStatus {
        ACTIVE, COMPLETED, CANCELLED
    }
}
