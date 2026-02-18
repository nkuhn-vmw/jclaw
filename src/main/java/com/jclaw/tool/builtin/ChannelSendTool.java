package com.jclaw.tool.builtin;

import com.jclaw.agent.AgentContext;
import com.jclaw.channel.ChannelRouter;
import com.jclaw.channel.ChannelAdapter;
import com.jclaw.channel.OutboundMessage;
import com.jclaw.content.ContentFilterChain;
import com.jclaw.tool.JclawTool;
import com.jclaw.tool.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
@JclawTool(
        name = "channel_send",
        description = "Send messages to other channels or conversations.",
        riskLevel = RiskLevel.MEDIUM,
        requiresApproval = false
)
public class ChannelSendTool implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(ChannelSendTool.class);
    private static final int MAX_MESSAGE_LENGTH = 4000;

    private final ChannelRouter channelRouter;
    private final ContentFilterChain contentFilterChain;

    public ChannelSendTool(@Lazy ChannelRouter channelRouter,
                           ContentFilterChain contentFilterChain) {
        this.channelRouter = channelRouter;
        this.contentFilterChain = contentFilterChain;
    }

    @Override
    public String call(String toolInput) {
        String channel = com.jclaw.tool.ToolInputParser.getString(toolInput, "channel");
        String conversationId = com.jclaw.tool.ToolInputParser.getString(toolInput, "conversationId");
        String message = com.jclaw.tool.ToolInputParser.getString(toolInput, "message");

        if (channel == null || conversationId == null || message == null) {
            return "{\"error\": \"channel, conversationId, and message are required\"}";
        }

        if (message.length() > MAX_MESSAGE_LENGTH) {
            return "{\"error\": \"Message exceeds maximum length of " + MAX_MESSAGE_LENGTH + " characters\"}";
        }

        // EgressGuard: always filter outbound content before sending to external channel (per spec §5.4)
        String agentId = MDC.get("agentId");
        String principal = MDC.get("principal");
        String channelType = MDC.get("channelType");
        AgentContext ctx = new AgentContext(
                agentId != null ? agentId : "unknown",
                principal != null ? principal : "system",
                channelType != null ? channelType : "tool");
        try {
            contentFilterChain.filterOutbound(message, ctx);
        } catch (ContentFilterChain.ContentFilterException e) {
            log.warn("Egress guard blocked channel_send from agent={}: {}", agentId, e.getMessage());
            return "{\"error\": \"Message blocked by content filter\"}";
        }

        ChannelAdapter adapter = channelRouter.getAdapter(channel);
        if (adapter == null) {
            return "{\"error\": \"Channel not available: " + channel.replace("\"", "'") + "\"}";
        }

        try {
            adapter.sendMessage(new OutboundMessage(channel, conversationId, message)).block();
            log.info("Message sent via tool: channel={} conversation={}", channel, conversationId);
            return String.format("{\"status\":\"sent\",\"channel\":\"%s\",\"conversationId\":\"%s\"}",
                    escapeJson(channel), escapeJson(conversationId));
        } catch (Exception e) {
            log.error("Failed to send message via tool", e);
            String errMsg = e.getMessage() != null ? e.getMessage() : "send failed";
            return "{\"error\": \"" + escapeJson(errMsg) + "\"}";
        }
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name("channel_send")
                .description("Send messages to other channels or conversations")
                .inputSchema("""
                    {"type":"object","properties":{
                      "channel":{"type":"string","description":"Target channel type (slack, teams, discord, google-chat, webchat)"},
                      "conversationId":{"type":"string","description":"Target conversation/channel ID"},
                      "message":{"type":"string","description":"Message text to send"}
                    },"required":["channel","conversationId","message"]}""")
                .build();
    }

}
