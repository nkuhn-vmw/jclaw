package com.jclaw.channel;

import com.jclaw.agent.AgentRuntime;
import com.jclaw.agent.AgentContext;
import com.jclaw.audit.AuditService;
import com.jclaw.config.JclawProperties;
import com.jclaw.content.ContentFilterChain;
import com.jclaw.security.IdentityMappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ChannelRouter {

    private static final Logger log = LoggerFactory.getLogger(ChannelRouter.class);

    private final Map<String, ChannelAdapter> adapters;
    private final AgentRuntime agentRuntime;
    private final IdentityMappingService identityMappingService;
    private final ContentFilterChain contentFilterChain;
    private final AuditService auditService;
    private final JclawProperties properties;

    public ChannelRouter(List<ChannelAdapter> adapterList,
                        AgentRuntime agentRuntime,
                        IdentityMappingService identityMappingService,
                        ContentFilterChain contentFilterChain,
                        AuditService auditService,
                        JclawProperties properties) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(ChannelAdapter::channelType, Function.identity()));
        this.agentRuntime = agentRuntime;
        this.identityMappingService = identityMappingService;
        this.contentFilterChain = contentFilterChain;
        this.auditService = auditService;
        this.properties = properties;
    }

    @PostConstruct
    public void startRouting() {
        adapters.values().forEach(adapter ->
            adapter.receiveMessages()
                .flatMap(msg -> routeMessage(msg)
                    .onErrorResume(e -> {
                        log.error("Error routing message from channel={} user={}",
                                msg.channelType(), msg.channelUserId(), e);
                        return Mono.empty();
                    }))
                .retry() // Resubscribe on terminal errors from the adapter stream
                .subscribe(
                    result -> log.debug("Message routed: {}", result),
                    error -> log.error("Fatal error in routing for adapter={}", adapter.channelType(), error)
                )
        );
        log.info("Channel routing started for {} adapters", adapters.size());
    }

    private Mono<Void> routeMessage(InboundMessage message) {
        // CH-2: Resolve agent with workspace/channel-ID filtering
        String agentId = resolveAgent(message);

        // CH-1: Enforce activation mode
        if (!isActivationSatisfied(message, agentId)) {
            log.debug("Message from channel={} does not satisfy activation mode for agent={}",
                    message.channelType(), agentId);
            return Mono.empty();
        }

        // CH-3: Identity mapping gate — requires approved identity
        return identityMappingService.resolvePrincipal(
                message.channelType(), message.channelUserId())
            .flatMap(principal -> {
                AgentContext context = new AgentContext(agentId, principal, message.channelType());

                // CH-4: Invoke content filter chain
                try {
                    contentFilterChain.filterInbound(message, context);
                } catch (ContentFilterChain.ContentFilterException e) {
                    log.warn("Content filter rejected message from principal={}: {}",
                            principal, e.getMessage());
                    auditService.logContentFilter(e.getFilterName(), "REJECTED",
                            principal, message.channelType(), "FILTERED");
                    return Mono.empty();
                }

                // Audit: log message routing
                auditService.logSessionEvent("MESSAGE_ROUTED", principal, agentId, null,
                        "Message routed from " + message.channelType());

                // Send typing indicator
                ChannelAdapter adapter = adapters.get(message.channelType());
                Mono<Void> typingIndicator = adapter != null
                        ? adapter.sendTypingIndicator(message.conversationId())
                            .onErrorResume(e -> Mono.empty())
                        : Mono.empty();

                return typingIndicator.then(
                    agentRuntime.processMessage(context, message)
                        .collectList()
                        .flatMap(responses -> {
                            if (responses.isEmpty()) return Mono.empty();
                            String combined = responses.stream()
                                    .map(r -> r.content())
                                    .collect(Collectors.joining(""));
                            if (adapter == null) return Mono.empty();
                            return adapter.sendMessage(
                                    new OutboundMessage(message.channelType(),
                                            message.conversationId(), combined));
                        })
                );
            })
            .onErrorResume(IdentityMappingService.UnmappedIdentityException.class, e -> {
                log.warn("Unmapped identity: channel={} user={}", message.channelType(),
                        message.channelUserId());
                return Mono.empty();
            })
            .onErrorResume(e -> {
                log.error("Failed to route message from channel={} user={}",
                        message.channelType(), message.channelUserId(), e);
                return Mono.empty();
            });
    }

    /**
     * CH-2: Resolve agent based on channel type, workspace, and channel-ID filtering.
     */
    private String resolveAgent(InboundMessage message) {
        List<JclawProperties.AgentProperties> agents = properties.getAgents();
        if (agents == null || agents.isEmpty()) return "default";

        String workspace = message.metadata() != null
                ? (String) message.metadata().get("team") : null;

        return agents.stream()
                .filter(a -> a.getChannels() != null && a.getChannels().stream()
                        .anyMatch(ch -> {
                            // Match channel type
                            if (!ch.getType().equals(message.channelType())) return false;
                            // Match workspace if specified
                            if (ch.getWorkspace() != null && !ch.getWorkspace().isEmpty()
                                    && workspace != null && !ch.getWorkspace().equals(workspace)) {
                                return false;
                            }
                            // Match specific channel IDs if specified
                            if (ch.getChannels() != null && !ch.getChannels().isEmpty()) {
                                return ch.getChannels().contains(message.conversationId());
                            }
                            return true;
                        }))
                .map(JclawProperties.AgentProperties::getId)
                .findFirst()
                .orElse("default");
    }

    /**
     * CH-1: Check if the message satisfies the activation mode for the resolved agent.
     * Modes: ALWAYS (process all), MENTION (only when @mentioned), DM (only direct messages).
     */
    private boolean isActivationSatisfied(InboundMessage message, String agentId) {
        List<JclawProperties.AgentProperties> agents = properties.getAgents();
        if (agents == null || agents.isEmpty()) return true;

        return agents.stream()
                .filter(a -> a.getId() != null && a.getId().equals(agentId))
                .flatMap(a -> a.getChannels().stream())
                .filter(ch -> ch.getType().equals(message.channelType()))
                .findFirst()
                .map(binding -> {
                    String activation = binding.getActivation();
                    if (activation == null || "ALWAYS".equalsIgnoreCase(activation)) return true;
                    if ("MENTION".equalsIgnoreCase(activation)) {
                        // Check if the message contains a mention
                        return message.metadata() != null
                                && Boolean.TRUE.equals(message.metadata().get("mentioned"));
                    }
                    if ("DM".equalsIgnoreCase(activation)) {
                        // Check if it's a direct message
                        return message.metadata() != null
                                && Boolean.TRUE.equals(message.metadata().get("isDm"));
                    }
                    return true;
                })
                .orElse(true); // Default to always if no binding found
    }

    public ChannelAdapter getAdapter(String channelType) {
        return adapters.get(channelType);
    }
}
