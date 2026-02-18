package com.jclaw.tool.builtin;

import com.jclaw.session.Session;
import com.jclaw.session.SessionManager;
import com.jclaw.session.SessionMessage;
import com.jclaw.tool.JclawTool;
import com.jclaw.tool.RiskLevel;
import org.springframework.ai.tool.ToolCallback;
import org.slf4j.MDC;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@JclawTool(
        name = "session_history",
        description = "Retrieve session transcript history.",
        riskLevel = RiskLevel.LOW,
        requiresApproval = false
)
public class SessionHistoryTool implements ToolCallback {

    private final SessionManager sessionManager;

    public SessionHistoryTool(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public String call(String toolInput) {
        try {
            String sessionIdStr = com.jclaw.tool.ToolInputParser.getString(toolInput, "sessionId");
            if (sessionIdStr == null) return "{\"error\": \"sessionId required\"}";

            UUID sessionId = UUID.fromString(sessionIdStr);

            // Verify the calling agent and principal own this session
            Session session = sessionManager.getSession(sessionId);
            if (session == null) return "{\"error\": \"Session not found\"}";
            String callingAgent = MDC.get("agentId");
            if (callingAgent != null && !callingAgent.equals(session.getAgentId())) {
                return "{\"error\": \"Access denied: session belongs to a different agent\"}";
            }
            String callingPrincipal = MDC.get("principal");
            if (callingPrincipal != null && session.getPrincipal() != null
                    && !callingPrincipal.equals(session.getPrincipal())) {
                return "{\"error\": \"Access denied: session belongs to a different user\"}";
            }

            List<SessionMessage> history = sessionManager.getHistory(sessionId);
            String result = history.stream()
                    .map(m -> String.format("{\"role\":\"%s\",\"content\":\"%s\"}",
                            m.getRole(), escapeJson(m.getContent())))
                    .collect(Collectors.joining(",", "[", "]"));
            return result;
        } catch (Exception e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("session_history")
                .description("Retrieve session transcript")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"sessionId\":{\"type\":\"string\",\"description\":\"Session UUID to retrieve history for\"}},\"required\":[\"sessionId\"]}")
                .build();
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
