package com.jclaw.tool.builtin;

import com.jclaw.agent.AgentContext;
import com.jclaw.content.ContentFilterChain;
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
    private static final int MAX_MESSAGE_LENGTH = 10_000;

    private final SessionManager sessionManager;
    private final ContentFilterChain contentFilterChain;

    public SessionSendTool(SessionManager sessionManager,
                           ContentFilterChain contentFilterChain) {
        this.sessionManager = sessionManager;
        this.contentFilterChain = contentFilterChain;
    }

    @Override
    public String call(String toolInput) {
        String sessionIdStr = com.jclaw.tool.ToolInputParser.getString(toolInput, "sessionId");
        String message = com.jclaw.tool.ToolInputParser.getString(toolInput, "message");

        if (sessionIdStr == null || message == null) {
            return "{\"error\": \"sessionId and message are required\"}";
        }

        if (message.length() > MAX_MESSAGE_LENGTH) {
            return "{\"error\": \"Message exceeds maximum length of " + MAX_MESSAGE_LENGTH + " characters\"}";
        }

        try {
            UUID sessionId = UUID.fromString(sessionIdStr);

            // Verify target session exists and belongs to the same principal
            com.jclaw.session.Session targetSession = sessionManager.getSession(sessionId);
            if (targetSession == null) {
                return "{\"error\": \"Target session not found: " + sessionIdStr + "\"}";
            }
            // Enforce principal ownership: deny if calling or target principal is unknown/null,
            // or mismatched, to prevent writes to unowned or other users' sessions
            String callingPrincipal = MDC.get("principal");
            if (callingPrincipal == null || !callingPrincipal.equals(targetSession.getPrincipal())) {
                return "{\"error\": \"Access denied: target session belongs to a different user\"}";
            }

            String senderAgent = MDC.get("agentId");
            String labeledMessage = "[Cross-session from agent: " + (senderAgent != null ? senderAgent : "unknown") + "] " + message;

            // Egress guard: prevent injecting content that would be blocked by content filters
            // Always run regardless of MDC state to prevent bypass when agentId is absent
            try {
                AgentContext ctx = new AgentContext(
                        senderAgent != null ? senderAgent : "unknown",
                        callingPrincipal,
                        targetSession.getChannelType());
                contentFilterChain.filterOutbound(labeledMessage, ctx);
            } catch (ContentFilterChain.ContentFilterException e) {
                log.warn("Egress guard blocked cross-session message from agent={}: {}",
                        senderAgent, e.getMessage());
                return "{\"error\": \"Message blocked by content filter\"}";
            }

            int tokens = labeledMessage.length() / 4;
            sessionManager.addMessage(sessionId, MessageRole.USER, labeledMessage, tokens);
            log.info("Cross-session message sent to session={} from agent={}", sessionId, senderAgent);
            return String.format("{\"status\":\"delivered\",\"sessionId\":\"%s\"}", sessionId);
        } catch (IllegalArgumentException e) {
            return "{\"error\": \"Invalid sessionId format\"}";
        } catch (Exception e) {
            String errMsg = e.getMessage() != null ? e.getMessage() : "send failed";
            return "{\"error\": \"" + escapeJson(errMsg) + "\"}";
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
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
