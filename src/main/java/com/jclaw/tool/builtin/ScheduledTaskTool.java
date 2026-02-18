package com.jclaw.tool.builtin;

import com.jclaw.agent.AgentContext;
import com.jclaw.agent.AgentRuntime;
import com.jclaw.channel.InboundMessage;
import com.jclaw.tool.JclawTool;
import com.jclaw.tool.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.MDC;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Component
@JclawTool(
        name = "scheduled_task",
        description = "Create and manage scheduled tasks for recurring operations.",
        riskLevel = RiskLevel.LOW,
        requiresApproval = false
)
public class ScheduledTaskTool implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskTool.class);

    private final ScheduledTaskRepository taskRepository;
    private final AgentRuntime agentRuntime;

    public ScheduledTaskTool(ScheduledTaskRepository taskRepository,
                             @Lazy AgentRuntime agentRuntime) {
        this.taskRepository = taskRepository;
        this.agentRuntime = agentRuntime;
    }

    @Override
    public String call(String toolInput) {
        String action = com.jclaw.tool.ToolInputParser.getString(toolInput, "action");
        if (action == null) action = "list";

        return switch (action) {
            case "create" -> createTask(toolInput);
            case "cancel" -> cancelTask(toolInput);
            case "list" -> listTasks();
            default -> "{\"error\": \"Unknown action: " + action + ". Use create, cancel, or list.\"}";
        };
    }

    private String createTask(String toolInput) {
        String name = com.jclaw.tool.ToolInputParser.getString(toolInput, "name");
        String cron = com.jclaw.tool.ToolInputParser.getString(toolInput, "cron");
        String message = com.jclaw.tool.ToolInputParser.getString(toolInput, "message");

        if (name == null || cron == null) {
            return "{\"error\": \"name and cron are required for task creation\"}";
        }

        try {
            CronExpression cronExpr = CronExpression.parse(cron);
            Instant nextFire = cronExpr.next(Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime())
                    .atZone(ZoneOffset.UTC).toInstant();

            String agentId = MDC.get("agentId");
            String principal = MDC.get("principal");
            ScheduledTask task = new ScheduledTask(name, cron, message, agentId, principal);
            task.setNextFireAt(nextFire);
            ScheduledTask saved = taskRepository.save(task);

            log.info("Scheduled task created: id={} name={} cron={}", saved.getId(), name, cron);
            return String.format(
                    "{\"taskId\":\"%s\",\"name\":\"%s\",\"cron\":\"%s\",\"nextFireAt\":\"%s\",\"status\":\"scheduled\"}",
                    saved.getId(), name, cron, nextFire);
        } catch (IllegalArgumentException e) {
            return "{\"error\": \"Invalid cron expression: " + cron + "\"}";
        }
    }

    @Transactional
    private String cancelTask(String toolInput) {
        String taskId = com.jclaw.tool.ToolInputParser.getString(toolInput, "taskId");
        if (taskId == null) return "{\"error\": \"taskId required\"}";

        try {
            UUID id = UUID.fromString(taskId);
            return taskRepository.findById(id)
                    .map(task -> {
                        task.setStatus(ScheduledTask.TaskStatus.CANCELLED);
                        taskRepository.save(task);
                        return String.format("{\"taskId\":\"%s\",\"status\":\"cancelled\"}", taskId);
                    })
                    .orElse("{\"error\": \"Task not found: " + taskId + "\"}");
        } catch (IllegalArgumentException e) {
            return "{\"error\": \"Invalid task ID: " + taskId + "\"}";
        }
    }

    private String listTasks() {
        List<ScheduledTask> tasks = taskRepository.findByStatus(ScheduledTask.TaskStatus.ACTIVE);
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (ScheduledTask task : tasks) {
            if (!first) sb.append(",");
            first = false;
            sb.append(String.format(
                    "{\"taskId\":\"%s\",\"name\":\"%s\",\"cron\":\"%s\",\"nextFireAt\":\"%s\"}",
                    task.getId(), task.getName(), task.getCronExpression(),
                    task.getNextFireAt() != null ? task.getNextFireAt() : ""));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Polls for due tasks every minute and executes them.
     */
    @Scheduled(fixedRate = 60000)
    @Transactional
    public void pollDueTasks() {
        List<ScheduledTask> dueTasks = taskRepository
                .findByStatusAndNextFireAtBefore(ScheduledTask.TaskStatus.ACTIVE, Instant.now());

        for (ScheduledTask task : dueTasks) {
            log.info("Scheduled task fired: id={} name={} message={}",
                    task.getId(), task.getName(), task.getMessage());
            task.setLastFiredAt(Instant.now());

            // Dispatch the task message to the agent for processing
            try {
                String agentId = task.getAgentId() != null ? task.getAgentId() : "default";
                String principal = task.getPrincipal() != null ? task.getPrincipal() : "scheduler";
                InboundMessage inbound = new InboundMessage(
                        "scheduled-task", principal, null, null,
                        task.getMessage(), null, Instant.now());
                AgentContext context = new AgentContext(agentId, principal, "scheduled-task");
                agentRuntime.processMessage(context, inbound)
                        .collectList()
                        .subscribe(
                                responses -> log.info("Scheduled task {} dispatched, got {} responses",
                                        task.getId(), responses.size()),
                                error -> log.error("Scheduled task {} dispatch failed: {}",
                                        task.getId(), error.getMessage()));
            } catch (Exception e) {
                log.error("Failed to dispatch scheduled task {}: {}", task.getId(), e.getMessage());
            }

            // Calculate next fire time
            try {
                CronExpression cronExpr = CronExpression.parse(task.getCronExpression());
                Instant nextFire = cronExpr.next(Instant.now().atZone(ZoneOffset.UTC).toLocalDateTime())
                        .atZone(ZoneOffset.UTC).toInstant();
                task.setNextFireAt(nextFire);
            } catch (IllegalArgumentException e) {
                log.error("Invalid cron expression for task {}: {}", task.getId(), task.getCronExpression());
                task.setStatus(ScheduledTask.TaskStatus.CANCELLED);
            }

            taskRepository.save(task);
        }
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("scheduled_task")
                .description("Create and manage scheduled tasks")
                .inputSchema("""
                    {"type":"object","properties":{
                      "action":{"type":"string","enum":["create","cancel","list"],"description":"Operation to perform"},
                      "name":{"type":"string","description":"Task name (for create)"},
                      "cron":{"type":"string","description":"Cron expression (for create)"},
                      "message":{"type":"string","description":"Message to log when task fires"},
                      "taskId":{"type":"string","description":"Task ID (for cancel)"}
                    },"required":["action"]}""")
                .build();
    }

}
