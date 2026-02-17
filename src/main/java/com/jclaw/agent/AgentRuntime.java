package com.jclaw.agent;

import com.jclaw.audit.AuditService;
import com.jclaw.channel.InboundMessage;
import com.jclaw.content.ContentFilterChain;
import com.jclaw.session.MessageRole;
import com.jclaw.session.Session;
import com.jclaw.session.SessionManager;
import com.jclaw.tool.ToolRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final ModelRouter modelRouter;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final PromptTemplateService promptService;
    private final ContentFilterChain contentFilterChain;
    private final AgentConfigService agentConfigService;
    private final AuditService auditService;
    private final Timer llmLatencyTimer;
    private final Counter messagesProcessed;
    private final Counter messagesErrored;

    public AgentRuntime(ModelRouter modelRouter,
                       ToolRegistry toolRegistry,
                       SessionManager sessionManager,
                       PromptTemplateService promptService,
                       ContentFilterChain contentFilterChain,
                       AgentConfigService agentConfigService,
                       AuditService auditService,
                       MeterRegistry meterRegistry) {
        this.modelRouter = modelRouter;
        this.toolRegistry = toolRegistry;
        this.sessionManager = sessionManager;
        this.promptService = promptService;
        this.contentFilterChain = contentFilterChain;
        this.agentConfigService = agentConfigService;
        this.auditService = auditService;
        this.llmLatencyTimer = Timer.builder("jclaw.llm.latency")
                .description("LLM call latency")
                .register(meterRegistry);
        this.messagesProcessed = Counter.builder("jclaw.messages.processed")
                .description("Messages processed successfully")
                .register(meterRegistry);
        this.messagesErrored = Counter.builder("jclaw.messages.errored")
                .description("Messages that encountered errors")
                .register(meterRegistry);
    }

    public Flux<AgentResponse> processMessage(AgentContext context, InboundMessage message) {
        return Mono.fromCallable(() -> {
            MDC.put("agentId", context.agentId());
            MDC.put("principal", context.principal());
            MDC.put("channelType", context.channelType());

            try {
                // 1. Resolve session
                Session session = sessionManager.resolveSession(context, message);
                MDC.put("sessionId", session.getId().toString());

                // 2. Content filtering
                contentFilterChain.filterInbound(message, context);

                // 3. Store user message
                sessionManager.addMessage(session.getId(), MessageRole.USER,
                        message.content(), estimateTokens(message.content()));

                // 4. Build prompt
                Prompt prompt = promptService.buildPrompt(context, session, message);

                // 5. Resolve agent config, model, and tools
                AgentConfig config = agentConfigService.getOrCreateDefault(context.agentId());
                ChatModel model = modelRouter.resolveModel(context.agentId(), config);
                List<ToolCallback> tools = toolRegistry.resolveTools(context);

                // 6. Execute LLM call with tools
                ChatClient chatClient = ChatClient.builder(model).build();
                ChatResponse response = llmLatencyTimer.record(() -> {
                    ChatClient.ChatClientRequestSpec spec = chatClient.prompt(prompt);
                    if (!tools.isEmpty()) {
                        spec = spec.tools(tools);
                    }
                    return spec.call().chatResponse();
                });

                String responseContent = response.getResult().getOutput().getText();

                // 7. Store assistant response
                sessionManager.addMessage(session.getId(), MessageRole.ASSISTANT,
                        responseContent, estimateTokens(responseContent));

                // 8. Audit
                auditService.logSessionEvent("MESSAGE_PROCESSED", context.principal(),
                        context.agentId(), session.getId(), "Message processed");

                messagesProcessed.increment();
                return new AgentResponse(responseContent);
            } finally {
                MDC.clear();
            }
        }).flux()
        .subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(e -> {
            log.error("Error processing message for agent={} principal={}",
                    context.agentId(), context.principal(), e);
            messagesErrored.increment();
            return Flux.just(new AgentResponse(
                    "I encountered an error processing your request. Please try again."));
        });
    }

    private int estimateTokens(String text) {
        return text != null ? text.length() / 4 : 0;
    }
}
