package com.jclaw.channel.teams;

import com.jclaw.channel.ChannelAdapter;
import com.jclaw.channel.InboundMessage;
import com.jclaw.channel.OutboundMessage;
import com.jclaw.config.SecretsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(name = "vcap.services.jclaw-secrets.credentials.teams-app-password", matchIfMissing = false)
public class TeamsChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(TeamsChannelAdapter.class);
    private static final String BOT_FRAMEWORK_API = "https://smba.trafficmanager.net/teams";
    private static final String LOGIN_URL = "https://login.microsoftonline.com/botframework.com/oauth2/v2.0/token";
    private static final Duration TOKEN_REFRESH_BUFFER = Duration.ofMinutes(5);

    private final Sinks.Many<InboundMessage> messageSink =
            Sinks.many().multicast().onBackpressureBuffer();
    private final WebClient webClient;
    private final WebClient tokenClient;
    private final SecretsConfig secretsConfig;
    private final String appId;

    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    public TeamsChannelAdapter(SecretsConfig secretsConfig,
                              @Value("${vcap.services.jclaw-secrets.credentials.teams-app-id:}") String appId) {
        this.secretsConfig = secretsConfig;
        this.appId = appId;
        this.webClient = WebClient.builder()
                .baseUrl(BOT_FRAMEWORK_API)
                .build();
        this.tokenClient = WebClient.builder().build();
    }

    @Override
    public String channelType() { return "teams"; }

    @Override
    public Flux<InboundMessage> receiveMessages() {
        return messageSink.asFlux();
    }

    private static final java.util.Set<String> ALLOWED_SERVICE_URL_HOSTS = java.util.Set.of(
            "smba.trafficmanager.net", "teams.microsoft.com",
            "botframework.com", "api.botframework.com");

    @Override
    public Mono<Void> sendMessage(OutboundMessage msg) {
        String serviceUrl = msg.metadata() != null
                ? (String) msg.metadata().getOrDefault("serviceUrl", BOT_FRAMEWORK_API)
                : BOT_FRAMEWORK_API;

        // Validate serviceUrl against Bot Framework domain allowlist (prevent SSRF)
        if (!isAllowedServiceUrl(serviceUrl)) {
            log.warn("Teams serviceUrl rejected (not in allowlist): {}", serviceUrl);
            return Mono.empty();
        }

        Map<String, Object> activity = Map.of(
                "type", "message",
                "text", msg.content(),
                "conversation", Map.of("id", msg.conversationId())
        );

        return getAccessToken()
                .flatMap(token -> webClient.post()
                        .uri(serviceUrl + "/v3/conversations/{conversationId}/activities",
                                msg.conversationId())
                        .header("Authorization", "Bearer " + token)
                        .bodyValue(activity)
                        .retrieve()
                        .bodyToMono(Void.class))
                .doOnSuccess(v -> log.debug("Teams message sent to {}", msg.conversationId()))
                .doOnError(e -> log.error("Failed to send Teams message to {}: {}",
                        msg.conversationId(), e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    @Override
    public Mono<Void> sendTypingIndicator(String conversationId) {
        // serviceUrl will be provided via the outbound message's metadata for actual sends;
        // for typing indicators we use the default since we only have conversationId
        Map<String, Object> activity = Map.of(
                "type", "typing",
                "conversation", Map.of("id", conversationId)
        );

        return getAccessToken()
                .flatMap(token -> webClient.post()
                        .uri("/v3/conversations/{conversationId}/activities",
                                conversationId)
                        .header("Authorization", "Bearer " + token)
                        .bodyValue(activity)
                        .retrieve()
                        .bodyToMono(Void.class))
                .onErrorResume(e -> Mono.empty());
    }

    @Override
    public boolean supportsThreading() { return true; }

    @Override
    public boolean supportsReactions() { return true; }

    @Override
    public int maxMessageLength() { return 28000; }

    @Override
    public boolean isConnected() { return appId != null && !appId.isEmpty(); }

    public void processActivity(String userId, String conversationId,
                               String text, Map<String, Object> metadata) {
        InboundMessage msg = new InboundMessage("teams", userId, conversationId,
                null, text, metadata, java.time.Instant.now());
        messageSink.tryEmitNext(msg);
    }

    private Mono<String> getAccessToken() {
        CachedToken current = cachedToken.get();
        if (current != null && current.isValid()) {
            return Mono.just(current.token());
        }

        return tokenClient.post()
                .uri(LOGIN_URL)
                .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                        .with("client_id", appId)
                        .with("client_secret", secretsConfig.getTeamsAppPassword())
                        .with("scope", "https://api.botframework.com/.default"))
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> {
                    String token = (String) response.get("access_token");
                    int expiresIn = (int) response.getOrDefault("expires_in", 3600);
                    Instant expiresAt = Instant.now().plusSeconds(expiresIn).minus(TOKEN_REFRESH_BUFFER);
                    CachedToken newToken = new CachedToken(token, expiresAt);
                    cachedToken.set(newToken);
                    log.debug("Teams OAuth token acquired, expires at {}", expiresAt);
                    return token;
                });
    }

    private boolean isAllowedServiceUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null) return false;
            return ALLOWED_SERVICE_URL_HOSTS.stream()
                    .anyMatch(allowed -> host.equals(allowed) || host.endsWith("." + allowed));
        } catch (Exception e) {
            return false;
        }
    }

    private record CachedToken(String token, Instant expiresAt) {
        boolean isValid() {
            return token != null && Instant.now().isBefore(expiresAt);
        }
    }
}
