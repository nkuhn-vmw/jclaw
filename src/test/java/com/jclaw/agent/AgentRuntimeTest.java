package com.jclaw.agent;

import com.jclaw.audit.AuditService;
import com.jclaw.channel.InboundMessage;
import com.jclaw.content.ContentFilterChain;
import com.jclaw.observability.JclawMetrics;
import com.jclaw.session.*;
import com.jclaw.tool.ToolRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRuntimeTest {

    @Mock private ModelRouter modelRouter;
    @Mock private ToolRegistry toolRegistry;
    @Mock private SessionManager sessionManager;
    @Mock private PromptTemplateService promptService;
    @Mock private ContentFilterChain contentFilterChain;
    @Mock private AgentConfigService agentConfigService;
    @Mock private AuditService auditService;
    @Mock private ChatModel chatModel;

    private final JclawMetrics metrics = new JclawMetrics(new SimpleMeterRegistry());

    private AgentRuntime agentRuntime;

    @BeforeEach
    void setUp() {
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        agentRuntime = new AgentRuntime(
                modelRouter, toolRegistry, sessionManager, promptService,
                contentFilterChain, agentConfigService, auditService, metrics, builder);
    }

    @Test
    void processMessageResolvesSession() {
        AgentContext context = new AgentContext("agent1", "user@test.com", "rest-api");
        InboundMessage message = new InboundMessage("rest-api", "user1", "conv1", "Hello");
        Session mockSession = new Session("agent1", "rest-api", "user@test.com", SessionScope.API);
        mockSession.setId(UUID.randomUUID());

        when(contentFilterChain.filterInbound(message, context)).thenReturn(message);
        when(sessionManager.resolveSession(any(), any())).thenReturn(mockSession);
        when(agentConfigService.getOrCreateDefault("agent1")).thenReturn(new AgentConfig("agent1", "Test"));
        when(toolRegistry.resolveTools(any())).thenReturn(List.of());

        // The LLM call will fail in test since no real model, but session resolution should work
        var responses = agentRuntime.processMessage(context, message).collectList().block();
        assertNotNull(responses);
        assertFalse(responses.isEmpty());

        verify(sessionManager).resolveSession(context, message);
    }

    @Test
    void processMessageCallsContentFilter() {
        AgentContext context = new AgentContext("agent1", "user@test.com", "rest-api");
        InboundMessage message = new InboundMessage("rest-api", "user1", "conv1", "Hello");
        Session mockSession = new Session("agent1", "rest-api", "user@test.com", SessionScope.API);
        mockSession.setId(UUID.randomUUID());

        when(contentFilterChain.filterInbound(message, context)).thenReturn(message);
        when(sessionManager.resolveSession(any(), any())).thenReturn(mockSession);
        when(agentConfigService.getOrCreateDefault("agent1")).thenReturn(new AgentConfig("agent1", "Test"));
        when(toolRegistry.resolveTools(any())).thenReturn(List.of());

        agentRuntime.processMessage(context, message).collectList().block();

        verify(contentFilterChain).filterInbound(message, context);
    }
}
