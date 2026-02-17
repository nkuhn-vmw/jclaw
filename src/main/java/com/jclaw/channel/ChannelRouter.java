package com.jclaw.channel;

import com.jclaw.agent.AgentConfig;
import com.jclaw.agent.AgentRuntime;
import com.jclaw.agent.AgentContext;
import com.jclaw.config.JclawProperties;
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
    private final JclawProperties properties;

    public ChannelRouter(List<ChannelAdapter> adapterList,
                        AgentRuntime agentRuntime,
                        IdentityMappingService identityMappingService,
                        JclawProperties properties) {
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(ChannelAdapter::channelType, Function.identity()));
        this.agentRuntime = agentRuntime;
        this.identityMappingService = identityMappingService;
        this.properties = properties;
    }

    @PostConstruct
    public void startRouting() {
        adapters.values().forEach(adapter ->
            adapter.receiveMessages()
                .flatMap(this::routeMessage)
                .subscribe(
                    result -> log.debug("Message routed: {}", result),
                    error -> log.error("Error routing message", error)
                )
        );
        log.info("Channel routing started for {} adapters", adapters.size());
    }

    private Mono<Void> routeMessage(InboundMessage message) {
        return identityMappingService.resolvePrincipal(
                message.channelType(), message.channelUserId())
            .flatMap(principal -> {
                String agentId = resolveAgent(message);
                AgentContext context = new AgentContext(agentId, principal, message.channelType());
                return agentRuntime.processMessage(context, message)
                    .collectList()
                    .flatMap(responses -> {
                        if (responses.isEmpty()) return Mono.empty();
                        String combined = responses.stream()
                                .map(r -> r.content())
                                .collect(Collectors.joining("\n"));
                        ChannelAdapter adapter = adapters.get(message.channelType());
                        if (adapter == null) return Mono.empty();
                        return adapter.sendMessage(
                                new OutboundMessage(message.channelType(),
                                        message.conversationId(), combined));
                    });
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

        return agents.stream()
                .filter(a -> a.getChannels() != null && a.getChannels().stream()
                        .anyMatch(ch -> ch.getType().equals(message.channelType())))
                .map(JclawProperties.AgentProperties::getId)
                .findFirst()
                .orElse("default");
    }

    public ChannelAdapter getAdapter(String channelType) {
        return adapters.get(channelType);
    }
}
