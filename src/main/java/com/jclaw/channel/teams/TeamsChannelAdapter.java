package com.jclaw.channel.teams;

import com.jclaw.channel.ChannelAdapter;
import com.jclaw.channel.InboundMessage;
import com.jclaw.channel.OutboundMessage;
import com.jclaw.config.SecretsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "vcap.services.jclaw-secrets.credentials.teams-app-password", matchIfMissing = false)
public class TeamsChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(TeamsChannelAdapter.class);
    private static final String BOT_FRAMEWORK_API = "https://smba.trafficmanager.net/teams";

    private final Sinks.Many<InboundMessage> messageSink =
            Sinks.many().multicast().onBackpressureBuffer();
    private final WebClient webClient;
    private final SecretsConfig secretsConfig;

    public TeamsChannelAdapter(SecretsConfig secretsConfig) {
        this.secretsConfig = secretsConfig;
        this.webClient = WebClient.builder()
                .baseUrl(BOT_FRAMEWORK_API)
                .build();
    }

    @Override
    public String channelType() { return "teams"; }

    @Override
    public Flux<InboundMessage> receiveMessages() {
        return messageSink.asFlux();
    }

    @Override
    public Mono<Void> sendMessage(OutboundMessage msg) {
        // Use Bot Framework REST API to send messages
        String serviceUrl = msg.metadata() != null
                ? (String) msg.metadata().getOrDefault("serviceUrl", BOT_FRAMEWORK_API)
                : BOT_FRAMEWORK_API;

        Map<String, Object> activity = Map.of(
                "type", "message",
                "text", msg.content(),
                "conversation", Map.of("id", msg.conversationId())
        );

        return webClient.post()
                .uri(serviceUrl + "/v3/conversations/{conversationId}/activities",
                        msg.conversationId())
                .header("Authorization", "Bearer " + secretsConfig.getTeamsAppPassword())
                .bodyValue(activity)
                .retrieve()
                .bodyToMono(Void.class)
                .doOnSuccess(v -> log.debug("Teams message sent to {}", msg.conversationId()))
                .doOnError(e -> log.error("Failed to send Teams message to {}: {}",
                        msg.conversationId(), e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    @Override
    public Mono<Void> sendTypingIndicator(String conversationId) {
        Map<String, Object> activity = Map.of(
                "type", "typing",
                "conversation", Map.of("id", conversationId)
        );

        return webClient.post()
                .uri(BOT_FRAMEWORK_API + "/v3/conversations/{conversationId}/activities",
                        conversationId)
                .header("Authorization", "Bearer " + secretsConfig.getTeamsAppPassword())
                .bodyValue(activity)
                .retrieve()
                .bodyToMono(Void.class)
                .onErrorResume(e -> Mono.empty());
    }

    @Override
    public boolean supportsThreading() { return true; }

    @Override
    public boolean supportsReactions() { return true; }

    /**
     * Called by the Teams webhook controller to process incoming activities.
     */
    public void processActivity(String userId, String conversationId,
                               String text, Map<String, Object> metadata) {
        InboundMessage msg = new InboundMessage("teams", userId, conversationId,
                null, text, metadata, java.time.Instant.now());
        messageSink.tryEmitNext(msg);
    }
}
