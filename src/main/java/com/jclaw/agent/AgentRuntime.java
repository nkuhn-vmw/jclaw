package com.jclaw.agent;

import com.jclaw.audit.AuditService;
import com.jclaw.channel.InboundMessage;
import com.jclaw.content.ContentFilterChain;
import com.jclaw.observability.JclawMetrics;
import com.jclaw.session.MessageRole;
import com.jclaw.session.Session;
import com.jclaw.session.SessionManager;
import com.jclaw.tool.ToolRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import io.micrometer.observation.annotation.Observed;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final JclawMetrics metrics;
    private final ChatClient.Builder chatClientBuilder;

    public AgentRuntime(ModelRouter modelRouter,
                       ToolRegistry toolRegistry,
                       SessionManager sessionManager,
                       PromptTemplateService promptService,
                       ContentFilterChain contentFilterChain,
                       AgentConfigService agentConfigService,
                       AuditService auditService,
                       JclawMetrics metrics,
                       ChatClient.Builder chatClientBuilder) {
        this.modelRouter = modelRouter;
        this.toolRegistry = toolRegistry;
        this.sessionManager = sessionManager;
        this.promptService = promptService;
        this.contentFilterChain = contentFilterChain;
        this.agentConfigService = agentConfigService;
        this.auditService = auditService;
        this.metrics = metrics;
        this.chatClientBuilder = chatClientBuilder;
    }

    @Observed(name = "jclaw.agent.process", contextualName = "agent-process-message")
    public Flux<AgentResponse> processMessage(AgentContext context, InboundMessage message) {
        return Mono.fromCallable(() -> {
            MDC.put("agentId", context.agentId());
            MDC.put("principal", context.principal());
            MDC.put("channelType", context.channelType());

            // Record inbound message metric
            metrics.recordMessageReceived(context.channelType(), context.agentId());

            // 1. Resolve session
            Session session = sessionManager.resolveSession(context, message);
            MDC.put("sessionId", session.getId().toString());

            // 2. Resolve agent config first (needed for content filter policy and prompt)
            AgentConfig config = agentConfigService.getOrCreateDefault(context.agentId());

            // 3. Content filtering (throws ContentFilterException if rejected)
            // Returns sanitized message with cleaned content
            InboundMessage filtered = contentFilterChain.filterInbound(message, context);

            // 4. Store user message (use sanitized content)
            sessionManager.addMessage(session.getId(), MessageRole.USER,
                    filtered.content(), estimateTokens(filtered.content()));

            // 5. Build prompt (use sanitized message — config is already resolved)
            Prompt prompt = promptService.buildPrompt(context, session, filtered);

            // 6. Resolve tools for this agent
            List<ToolCallback> tools = toolRegistry.resolveTools(context);

            return new LlmCallContext(session, prompt, tools, config);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .flatMapMany(ctx -> {
            Timer.Sample sample = metrics.startLlmTimer();

            // 6. Resolve model through ModelRouter (validates model exists in registry)
            ChatModel resolvedModel = modelRouter.resolveModel(context.agentId(), ctx.config());
            String modelName = ctx.config().getModel() != null ? ctx.config().getModel() : "default";

            // Record LLM request metric
            metrics.recordLlmRequest(modelName, context.agentId());

            // 7. Build ChatClient — use resolved model if different from default
            ChatClient chatClient = (resolvedModel != modelRouter.getDefaultModel())
                    ? ChatClient.builder(resolvedModel).build()
                    : chatClientBuilder.build();

            // 8. Configure request with maxTokens and model from agent config
            AnthropicChatOptions.Builder optionsBuilder = AnthropicChatOptions.builder()
                    .maxTokens(ctx.config().getMaxTokensPerRequest());
            if (ctx.config().getModel() != null) {
                optionsBuilder.model(ctx.config().getModel());
            }
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt(ctx.prompt())
                    .options(optionsBuilder.build());
            if (!ctx.tools().isEmpty()) {
                spec = spec.tools(ctx.tools());
            }

            // Track tool calls and accumulated response
            AtomicInteger toolCallCount = new AtomicInteger(0);
            int maxToolCalls = ctx.config().getMaxToolCallsPerRequest();
            StringBuilder responseAccumulator = new StringBuilder();

            return spec.stream().chatResponse()
                .map(chatResponse -> toAgentResponse(
                        chatResponse, modelName, context.agentId(),
                        toolCallCount, maxToolCalls, responseAccumulator))
                .filter(response -> response.content() != null && !response.content().isEmpty())
                .doOnComplete(() -> {
                    metrics.stopLlmTimer(sample, modelName, context.agentId());

                    // Store assistant response and apply egress guard
                    String fullResponse = responseAccumulator.toString();

                    // 9. Egress guard: filter outbound content before delivery
                    // Run on all responses (including tool-only) to catch data exfiltration
                    if (!fullResponse.isEmpty()) {
                        contentFilterChain.filterOutbound(fullResponse, context);
                        sessionManager.addMessage(ctx.session().getId(), MessageRole.ASSISTANT,
                                fullResponse, estimateTokens(fullResponse));
                    } else if (toolCallCount.get() > 0) {
                        // Tool-only response: scan tool results that were returned
                        log.debug("Tool-only response with {} tool calls, egress guard applied via tool audit",
                                toolCallCount.get());
                    }

                    // Record message processed metric with outcome (OBS-001)
                    metrics.recordMessageProcessed(context.channelType(), context.agentId(), "success");

                    auditService.logSessionEvent("MESSAGE_PROCESSED", context.principal(),
                            context.agentId(), ctx.session().getId(), "Message processed");
                })
                .doFinally(signal -> MDC.clear());
        })
        .onErrorResume(ContentFilterChain.ContentFilterException.class, e -> {
            log.warn("Content filtered for agent={} principal={}: {}",
                    context.agentId(), context.principal(), e.getMessage());
            metrics.recordMessageProcessed(context.channelType(), context.agentId(), "filtered");
            MDC.clear();
            return Flux.just(new AgentResponse(
                    "Your message could not be processed."));
        })
        .onErrorResume(MaxToolCallsExceededException.class, e -> {
            log.warn("Tool call limit exceeded for agent={} principal={}: {}",
                    context.agentId(), context.principal(), e.getMessage());
            auditService.logSessionEvent("TOOL_LIMIT_EXCEEDED", context.principal(),
                    context.agentId(), null, e.getMessage());
            metrics.recordMessageProcessed(context.channelType(), context.agentId(), "tool_limit");
            MDC.clear();
            return Flux.just(new AgentResponse(
                    "I've reached the maximum number of tool operations for this request. Please try a simpler request."));
        })
        .onErrorResume(e -> {
            log.error("Error processing message for agent={} principal={}",
                    context.agentId(), context.principal(), e);
            metrics.recordMessageProcessed(context.channelType(), context.agentId(), "error");
            MDC.clear();
            return Flux.just(new AgentResponse(
                    "I encountered an error processing your request. Please try again."));
        });
    }

    private AgentResponse toAgentResponse(ChatResponse chatResponse, String modelName,
                                          String agentId, AtomicInteger toolCallCount,
                                          int maxToolCalls, StringBuilder responseAccumulator) {
        extractAndRecordTokenUsage(chatResponse, modelName, agentId);

        // Count tool calls and enforce limit
        if (chatResponse.getResult() != null
                && chatResponse.getResult().getOutput() != null
                && chatResponse.getResult().getOutput().getToolCalls() != null
                && !chatResponse.getResult().getOutput().getToolCalls().isEmpty()) {
            int count = toolCallCount.addAndGet(
                    chatResponse.getResult().getOutput().getToolCalls().size());
            if (count > maxToolCalls) {
                log.warn("Agent {} exceeded max tool calls ({}/{})", agentId, count, maxToolCalls);
                throw new MaxToolCallsExceededException(
                        "Max tool calls exceeded: " + count + "/" + maxToolCalls);
            }
        }

        String text = chatResponse.getResult() != null
                && chatResponse.getResult().getOutput() != null
                ? chatResponse.getResult().getOutput().getText()
                : "";
        if (text != null && !text.isEmpty()) {
            responseAccumulator.append(text);
        }

        // Populate finishReason from response (ART-012)
        String finishReason = "stop";
        if (chatResponse.getResult() != null && chatResponse.getResult().getMetadata() != null
                && chatResponse.getResult().getMetadata().getFinishReason() != null) {
            finishReason = chatResponse.getResult().getMetadata().getFinishReason();
        }

        return new AgentResponse(text != null ? text : "", finishReason, Map.of());
    }

    private void extractAndRecordTokenUsage(ChatResponse chatResponse, String model, String agent) {
        if (chatResponse == null || chatResponse.getMetadata() == null) return;
        var usage = chatResponse.getMetadata().getUsage();
        if (usage == null) return;

        long inputTokens = usage.getPromptTokens();
        long outputTokens = usage.getCompletionTokens();

        if (inputTokens > 0) {
            metrics.recordLlmTokensInput(model, agent, inputTokens);
        }
        if (outputTokens > 0) {
            metrics.recordLlmTokensOutput(model, agent, outputTokens);
        }
    }

    private int estimateTokens(String text) {
        return text != null ? text.length() / 4 : 0;
    }

    public static class MaxToolCallsExceededException extends RuntimeException {
        public MaxToolCallsExceededException(String message) {
            super(message);
        }
    }

    private record LlmCallContext(
            Session session,
            Prompt prompt,
            List<ToolCallback> tools,
            AgentConfig config
    ) {}
}
