package com.jclaw.tool.builtin;

import com.jclaw.tool.JclawTool;
import com.jclaw.tool.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Component
@JclawTool(
        name = "scheduled_task",
        description = "Create and manage scheduled tasks for recurring operations.",
        riskLevel = RiskLevel.LOW,
        requiresApproval = false
)
public class ScheduledTaskTool implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskTool.class);

    private final TaskScheduler taskScheduler;
    private final Map<String, ScheduledFuture<?>> activeTasks = new ConcurrentHashMap<>();

    public ScheduledTaskTool(TaskScheduler taskScheduler) {
        this.taskScheduler = taskScheduler;
    }

    @Override
    public String call(String toolInput) {
        String action = extractField(toolInput, "action");
        if (action == null) action = "list";

        return switch (action) {
            case "create" -> createTask(toolInput);
            case "cancel" -> cancelTask(toolInput);
            case "list" -> listTasks();
            default -> "{\"error\": \"Unknown action: " + action + ". Use create, cancel, or list.\"}";
        };
    }

    private String createTask(String toolInput) {
        String name = extractField(toolInput, "name");
        String cron = extractField(toolInput, "cron");
        String message = extractField(toolInput, "message");

        if (name == null || cron == null) {
            return "{\"error\": \"name and cron are required for task creation\"}";
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        try {
            ScheduledFuture<?> future = taskScheduler.schedule(
                () -> log.info("Scheduled task fired: id={} name={} message={}", taskId, name, message),
                new CronTrigger(cron)
            );
            activeTasks.put(taskId, future);
            log.info("Scheduled task created: id={} name={} cron={}", taskId, name, cron);
            return String.format("{\"taskId\":\"%s\",\"name\":\"%s\",\"cron\":\"%s\",\"status\":\"scheduled\"}",
                    taskId, name, cron);
        } catch (IllegalArgumentException e) {
            return "{\"error\": \"Invalid cron expression: " + cron + "\"}";
        }
    }

    private String cancelTask(String toolInput) {
        String taskId = extractField(toolInput, "taskId");
        if (taskId == null) return "{\"error\": \"taskId required\"}";

        ScheduledFuture<?> future = activeTasks.remove(taskId);
        if (future == null) return "{\"error\": \"Task not found: " + taskId + "\"}";

        future.cancel(false);
        return String.format("{\"taskId\":\"%s\",\"status\":\"cancelled\"}", taskId);
    }

    private String listTasks() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Map.Entry<String, ScheduledFuture<?>> entry : activeTasks.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(String.format("{\"taskId\":\"%s\",\"cancelled\":%s}",
                    entry.getKey(), entry.getValue().isCancelled()));
        }
        sb.append("]");
        return sb.toString();
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

    private String extractField(String json, String field) {
        if (json == null) return null;
        int idx = json.indexOf("\"" + field + "\"");
        if (idx < 0) return null;
        int start = json.indexOf("\"", idx + field.length() + 2);
        if (start < 0) return null;
        int end = json.indexOf("\"", start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }
}
