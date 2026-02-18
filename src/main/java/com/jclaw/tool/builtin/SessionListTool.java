package com.jclaw.tool.builtin;

import com.jclaw.session.Session;
import com.jclaw.session.SessionManager;
import com.jclaw.tool.JclawTool;
import com.jclaw.tool.RiskLevel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@JclawTool(
        name = "session_list",
        description = "List active sessions for the current user.",
        riskLevel = RiskLevel.LOW,
        requiresApproval = false
)
public class SessionListTool implements ToolCallback {

    private final SessionManager sessionManager;

    public SessionListTool(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public String call(String toolInput) {
        // toolInput expected: {"principal": "user@example.com"}
        String principal = com.jclaw.tool.ToolInputParser.getString(toolInput, "principal");
        if (principal == null) return "{\"error\": \"principal required\"}";

        List<Session> sessions = sessionManager.getActiveSessions(principal);
        String result = sessions.stream()
                .map(s -> String.format("{\"id\":\"%s\",\"agent\":\"%s\",\"channel\":\"%s\",\"messages\":%d}",
                        s.getId(), s.getAgentId(), s.getChannelType(), s.getMessageCount()))
                .collect(Collectors.joining(",", "[", "]"));
        return result;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("session_list")
                .description("List active sessions")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"principal\":{\"type\":\"string\",\"description\":\"User principal/email\"}},\"required\":[\"principal\"]}")
                .build();
    }

}
