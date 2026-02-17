package com.jclaw.tool.builtin;

import com.jclaw.channel.ChannelRouter;
import com.jclaw.channel.ChannelAdapter;
import com.jclaw.channel.OutboundMessage;
import com.jclaw.tool.JclawTool;
import com.jclaw.tool.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final ChannelRouter channelRouter;

    public ChannelSendTool(@Lazy ChannelRouter channelRouter) {
        this.channelRouter = channelRouter;
    }

    @Override
    public String call(String toolInput) {
        String channel = extractField(toolInput, "channel");
        String conversationId = extractField(toolInput, "conversationId");
        String message = extractField(toolInput, "message");

        if (channel == null || conversationId == null || message == null) {
            return "{\"error\": \"channel, conversationId, and message are required\"}";
        }

        ChannelAdapter adapter = channelRouter.getAdapter(channel);
        if (adapter == null) {
            return "{\"error\": \"Channel not available: " + channel + "\"}";
        }

        try {
            adapter.sendMessage(new OutboundMessage(channel, conversationId, message)).block();
            log.info("Message sent via tool: channel={} conversation={}", channel, conversationId);
            return String.format("{\"status\":\"sent\",\"channel\":\"%s\",\"conversationId\":\"%s\"}",
                    channel, conversationId);
        } catch (Exception e) {
            log.error("Failed to send message via tool", e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "'") + "\"}";
        }
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
