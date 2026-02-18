package com.jclaw.channel;

import com.jclaw.agent.AgentRuntime;
import com.jclaw.agent.AgentContext;
import com.jclaw.audit.AuditService;
import com.jclaw.config.JclawProperties;
import com.jclaw.observability.JclawMetrics;
import com.jclaw.security.IdentityMappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
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
    private final AuditService auditService;
    private final JclawProperties properties;
    private final JclawMetrics metrics;

    public ChannelRouter(List<ChannelAdapter> adapterList,
                        AgentRuntime agentRuntime,
                        IdentityMappingService identityMappingService,
                        AuditService auditService,
                        JclawProperties properties,
                        JclawMetrics metrics) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(ChannelAdapter::channelType, Function.identity()));
        this.agentRuntime = agentRuntime;
        this.identityMappingService = identityMappingService;
        this.auditService = auditService;
        this.properties = properties;
        this.metrics = metrics;
    }

    @PostConstruct
    public void startRouting() {
        adapters.values().forEach(adapter ->
            adapter.receiveMessages()
                .publishOn(Schedulers.boundedElastic())
                .flatMap(msg -> routeMessage(msg)
                    .onErrorResume(e -> {
                        log.error("Error routing message from channel={} user={}",
                                msg.channelType(), msg.channelUserId(), e);
                        return Mono.empty();
                    }))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
                        .maxBackoff(Duration.ofMinutes(1)))
                .subscribe(
                    result -> log.debug("Message routed: {}", result),
                    error -> log.error("Fatal error in routing for adapter={}", adapter.channelType(), error)
                )
        );
        log.info("Channel routing started for {} adapters", adapters.size());
    }

    private Mono<Void> routeMessage(InboundMessage message) {
        String agentId = resolveAgent(message);

        if (!isActivationSatisfied(message, agentId)) {
            log.debug("Message from channel={} does not satisfy activation mode for agent={}",
                    message.channelType(), agentId);
            return Mono.empty();
        }

        // Bypass identity mapping for WebChat users (already SSO-authenticated)
        Mono<String> principalMono;
        if ("webchat".equals(message.channelType())) {
            // WebChat users provide their principal directly via channelUserId (SSO identity)
            principalMono = Mono.just(message.channelUserId());
        } else {
            principalMono = identityMappingService.resolvePrincipal(
                    message.channelType(), message.channelUserId());
        }

        return principalMono
            .flatMap(principal -> {
                AgentContext context = new AgentContext(agentId, principal, message.channelType());

                // Content filtering is performed in AgentRuntime.processMessage()
                // to avoid duplicate filtering and double audit events

                auditService.logSessionEvent("MESSAGE_ROUTED", principal, agentId, null,
                        "Message routed from " + message.channelType());

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

                            // Chunk messages for channels with size limits
                            int maxLen = adapter.maxMessageLength();
                            if (maxLen > 0 && combined.length() > maxLen) {
                                return sendChunked(adapter, message, combined, maxLen);
                            }

                            // Propagate threadId and metadata from inbound to outbound
                            return deliverWithRetry(adapter, new OutboundMessage(
                                    message.channelType(),
                                    message.conversationId(),
                                    message.threadId(),
                                    combined,
                                    message.metadata() != null ? message.metadata() : Map.of()));
                        })
                );
            })
            .onErrorResume(IdentityMappingService.UnmappedIdentityException.class, e -> {
                log.warn("Unmapped identity: channel={} user={}", message.channelType(),
                        message.channelUserId());

                // Queue unmapped identity for later approval instead of dropping
                identityMappingService.createMapping(
                        message.channelType(),
                        message.channelUserId(),
                        message.channelUserId(),
                        null);

                auditService.logSessionEvent("UNMAPPED_IDENTITY_QUEUED", message.channelUserId(),
                        null, null, "Unmapped identity queued for approval: "
                                + message.channelType() + ":" + message.channelUserId());

                return Mono.empty();
            })
            .onErrorResume(e -> {
                log.error("Failed to route message from channel={} user={}",
                        message.channelType(), message.channelUserId(), e);
                return Mono.empty();
            });
    }

    private String resolveAgent(InboundMessage message) {
        List<JclawProperties.AgentProperties> agents = properties.getAgents();
        if (agents == null || agents.isEmpty()) return "default";

        String workspace = message.metadata() != null
                ? (String) message.metadata().get("team") : null;

        return agents.stream()
                .filter(a -> a.getChannels() != null && a.getChannels().stream()
                        .anyMatch(ch -> {
                            if (!ch.getType().equals(message.channelType())) return false;
                            // If binding specifies a workspace, message must match it
                            if (ch.getWorkspace() != null && !ch.getWorkspace().isEmpty()) {
                                if (workspace == null || !ch.getWorkspace().equals(workspace)) {
                                    return false;
                                }
                            }
                            if (ch.getChannels() != null && !ch.getChannels().isEmpty()) {
                                return ch.getChannels().contains(message.conversationId());
                            }
                            return true;
                        }))
                .map(JclawProperties.AgentProperties::getId)
                .findFirst()
                .orElse("default");
    }

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
                        return message.metadata() != null
                                && Boolean.TRUE.equals(message.metadata().get("mentioned"));
                    }
                    if ("DM".equalsIgnoreCase(activation)) {
                        return message.metadata() != null
                                && Boolean.TRUE.equals(message.metadata().get("isDm"));
                    }
                    return true;
                })
                .orElse(true);
    }

    /**
     * Splits a long message into chunks at word boundaries and sends them sequentially.
     */
    private Mono<Void> sendChunked(ChannelAdapter adapter, InboundMessage message,
                                   String content, int maxLen) {
        List<String> chunks = new java.util.ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + maxLen, content.length());
            if (end < content.length()) {
                // Try to break at a newline or space
                int breakAt = content.lastIndexOf('\n', end);
                if (breakAt <= start) breakAt = content.lastIndexOf(' ', end);
                if (breakAt > start) end = breakAt + 1;
            }
            chunks.add(content.substring(start, end));
            start = end;
        }

        Mono<Void> chain = Mono.empty();
        for (String chunk : chunks) {
            chain = chain.then(deliverWithRetry(adapter, new OutboundMessage(
                    message.channelType(),
                    message.conversationId(),
                    message.threadId(),
                    chunk,
                    message.metadata() != null ? message.metadata() : Map.of())));
        }
        return chain;
    }

    private Mono<Void> deliverWithRetry(ChannelAdapter adapter, OutboundMessage msg) {
        return adapter.sendMessage(msg)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(10)))
                .doOnError(e -> {
                    log.error("Message delivery failed after retries for channel={}: {}",
                            msg.channelType(), e.getMessage());
                    metrics.recordDeliveryFailed(msg.channelType());
                    auditService.logSessionEvent("DELIVERY_FAILED", null, null, null,
                            "Delivery failed to " + msg.channelType() + ": " + e.getMessage());
                })
                .onErrorResume(e -> Mono.empty());
    }

    public ChannelAdapter getAdapter(String channelType) {
        return adapters.get(channelType);
    }
}
