package com.jclaw.channel.googlechat;

import com.jclaw.channel.ChannelAdapter;
import com.jclaw.channel.InboundMessage;
import com.jclaw.channel.OutboundMessage;
import com.jclaw.config.SecretsConfig;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "vcap.services.jclaw-secrets.credentials.google-chat-credentials", matchIfMissing = false)
public class GoogleChatChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(GoogleChatChannelAdapter.class);
    private static final String GOOGLE_CHAT_API = "https://chat.googleapis.com/v1";

    private final Sinks.Many<InboundMessage> messageSink =
            Sinks.many().multicast().onBackpressureBuffer();
    private final WebClient webClient;
    private final SecretsConfig secretsConfig;
    private GoogleCredentials credentials;

    public GoogleChatChannelAdapter(SecretsConfig secretsConfig) {
        this.secretsConfig = secretsConfig;
        this.webClient = WebClient.builder()
                .baseUrl(GOOGLE_CHAT_API)
                .build();
    }

    @PostConstruct
    public void init() {
        try {
            String credentialsJson = secretsConfig.getGoogleChatCredentials();
            if (credentialsJson != null && !credentialsJson.isBlank()) {
                credentials = ServiceAccountCredentials.fromStream(
                        new ByteArrayInputStream(credentialsJson.getBytes(StandardCharsets.UTF_8)))
                        .createScoped(List.of("https://www.googleapis.com/auth/chat.bot"));
                log.info("Google Chat service account credentials loaded");
            } else {
                log.warn("Google Chat credentials not configured");
            }
        } catch (Exception e) {
            log.error("Failed to load Google Chat credentials", e);
        }
    }

    @Override
    public String channelType() { return "google-chat"; }

    @Override
    public Flux<InboundMessage> receiveMessages() {
        return messageSink.asFlux();
    }

    @Override
    public Mono<Void> sendMessage(OutboundMessage msg) {
        String spaceId = msg.conversationId();
        String threadId = msg.threadId();

        Map<String, Object> message = Map.of("text", msg.content());

        if (threadId != null) {
            message = Map.of(
                    "text", msg.content(),
                    "thread", Map.of("name", threadId)
            );
        }

        Map<String, Object> finalMessage = message;
        return getAuthToken()
                .flatMap(token -> {
                    WebClient.RequestBodySpec requestSpec = webClient.post()
                            .uri(uriBuilder -> {
                                uriBuilder.path("/{spaceId}/messages");
                                if (threadId != null) {
                                    uriBuilder.queryParam("messageReplyOption",
                                            "REPLY_MESSAGE_FALLBACK_TO_NEW_THREAD");
                                }
                                return uriBuilder.build(spaceId);
                            });

                    return requestSpec
                            .header("Authorization", "Bearer " + token)
                            .bodyValue(finalMessage)
                            .retrieve()
                            .bodyToMono(Void.class);
                })
                .doOnSuccess(v -> log.debug("Google Chat message sent to {}", spaceId))
                .doOnError(e -> log.error("Failed to send Google Chat message to {}: {}",
                        spaceId, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    @Override
    public Mono<Void> sendTypingIndicator(String conversationId) {
        return Mono.empty();
    }

    @Override
    public boolean supportsThreading() { return true; }

    @Override
    public boolean supportsReactions() { return false; }

    @Override
    public int maxMessageLength() { return 4096; }

    @Override
    public boolean isConnected() { return credentials != null; }

    public void processEvent(String userId, String spaceId, String text,
                            String threadName, Map<String, Object> metadata) {
        InboundMessage msg = new InboundMessage("google-chat", userId, spaceId,
                threadName, text, metadata, java.time.Instant.now());
        messageSink.tryEmitNext(msg);
    }

    private Mono<String> getAuthToken() {
        if (credentials == null) {
            return Mono.error(new IllegalStateException("Google Chat credentials not configured"));
        }
        return Mono.fromCallable(() -> {
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        }).subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }
}
