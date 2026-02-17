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
        String principal = extractField(toolInput, "principal");
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
