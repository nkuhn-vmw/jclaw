package com.jclaw.tool.builtin;

import com.jclaw.session.SessionManager;
import com.jclaw.session.MessageRole;
import com.jclaw.tool.JclawTool;
import com.jclaw.tool.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
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
        String sessionIdStr = com.jclaw.tool.ToolInputParser.getString(toolInput, "sessionId");
        String message = com.jclaw.tool.ToolInputParser.getString(toolInput, "message");

        if (sessionIdStr == null || message == null) {
            return "{\"error\": \"sessionId and message are required\"}";
        }

        try {
            UUID sessionId = UUID.fromString(sessionIdStr);
            String senderAgent = MDC.get("agentId");
            String labeledMessage = "[Cross-session from agent: " + (senderAgent != null ? senderAgent : "unknown") + "] " + message;
            int tokens = labeledMessage.length() / 4;
            sessionManager.addMessage(sessionId, MessageRole.USER, labeledMessage, tokens);
            log.info("Cross-session message sent to session={} from agent={}", sessionId, senderAgent);
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

}
