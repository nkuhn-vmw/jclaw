package com.jclaw.tool.builtin;

import com.jclaw.session.SessionManager;
import com.jclaw.session.MessageRole;
import com.jclaw.tool.JclawTool;
import com.jclaw.tool.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@JclawTool(
        name = "session_send",
        description = "Send a message to another agent's session for cross-agent coordination.",
        riskLevel = RiskLevel.MEDIUM,
        requiresApproval = false
)
public class SessionSendTool implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(SessionSendTool.class);

    private final SessionManager sessionManager;

    public SessionSendTool(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public String call(String toolInput) {
        String sessionIdStr = extractField(toolInput, "sessionId");
        String message = extractField(toolInput, "message");

        if (sessionIdStr == null || message == null) {
            return "{\"error\": \"sessionId and message are required\"}";
        }

        try {
            UUID sessionId = UUID.fromString(sessionIdStr);
            int tokens = message.length() / 4;
            sessionManager.addMessage(sessionId, MessageRole.SYSTEM, message, tokens);
            log.info("Cross-session message sent to session={}", sessionId);
            return String.format("{\"status\":\"delivered\",\"sessionId\":\"%s\"}", sessionId);
        } catch (IllegalArgumentException e) {
            return "{\"error\": \"Invalid sessionId format\"}";
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("session_send")
                .description("Cross-session agent messaging")
                .inputSchema("""
                    {"type":"object","properties":{
                      "sessionId":{"type":"string","description":"Target session UUID"},
                      "message":{"type":"string","description":"Message to inject into the target session"}
                    },"required":["sessionId","message"]}""")
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
