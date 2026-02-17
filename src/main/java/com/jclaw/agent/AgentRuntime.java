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
import java.util.concurrent.atomic.AtomicLong;

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
    private final MeterRegistry meterRegistry;

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
        this.meterRegistry = meterRegistry;
    }

    public Flux<AgentResponse> processMessage(AgentContext context, InboundMessage message) {
        return Mono.fromCallable(() -> {
            MDC.put("agentId", context.agentId());
            MDC.put("principal", context.principal());
            MDC.put("channelType", context.channelType());

            // 1. Resolve session
            Session session = sessionManager.resolveSession(context, message);
            MDC.put("sessionId", session.getId().toString());

            // 2. Content filtering (throws ContentFilterException if rejected)
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

            return new LlmCallContext(session, prompt, model, tools, config);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(ctx -> {
            Timer.Sample sample = Timer.start(meterRegistry);

            // 6. Execute streaming LLM call
            ChatClient chatClient = ChatClient.builder(ctx.model()).build();
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt(ctx.prompt());
            if (!ctx.tools().isEmpty()) {
                spec = spec.tools(ctx.tools());
            }

            return spec.stream().chatResponse()
                .map(chatResponse -> {
                    String text = chatResponse.getResult() != null
                            && chatResponse.getResult().getOutput() != null
                            ? chatResponse.getResult().getOutput().getText()
                            : "";
                    return new AgentResponse(text != null ? text : "");
                })
                .filter(response -> response.content() != null && !response.content().isEmpty())
                .doOnComplete(() -> {
                    sample.stop(Timer.builder("jclaw.llm.latency")
                            .tag("agent", context.agentId())
                            .tag("model", ctx.config().getModel() != null ? ctx.config().getModel() : "default")
                            .register(meterRegistry));
                    Counter.builder("jclaw.messages.processed")
                            .tag("channel", context.channelType())
                            .tag("agent", context.agentId())
                            .tag("outcome", "success")
                            .register(meterRegistry).increment();

                    auditService.logSessionEvent("MESSAGE_PROCESSED", context.principal(),
                            context.agentId(), ctx.session().getId(), "Message processed");
                })
                .doFinally(signal -> MDC.clear());
        })
        .onErrorResume(e -> {
            log.error("Error processing message for agent={} principal={}",
                    context.agentId(), context.principal(), e);
            Counter.builder("jclaw.messages.processed")
                    .tag("channel", context.channelType())
                    .tag("agent", context.agentId())
                    .tag("outcome", "error")
                    .register(meterRegistry).increment();
            MDC.clear();
            return Flux.just(new AgentResponse(
                    "I encountered an error processing your request. Please try again."));
        });
    }

    private int estimateTokens(String text) {
        return text != null ? text.length() / 4 : 0;
    }

    private record LlmCallContext(
            Session session,
            Prompt prompt,
            ChatModel model,
            List<ToolCallback> tools,
            AgentConfig config
    ) {}
}
