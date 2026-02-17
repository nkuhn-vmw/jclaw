package com.jclaw.channel.slack;

import com.jclaw.channel.ChannelAdapter;
import com.jclaw.channel.InboundMessage;
import com.jclaw.channel.OutboundMessage;
import com.jclaw.config.SecretsConfig;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.methods.SlackApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "vcap.services.jclaw-secrets.credentials.slack-bot-token", matchIfMissing = false)
public class SlackChannelAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(SlackChannelAdapter.class);

    private final SecretsConfig secretsConfig;
    private final Sinks.Many<InboundMessage> messageSink =
            Sinks.many().multicast().onBackpressureBuffer();
    private App slackApp;
    private SocketModeApp socketModeApp;

    public SlackChannelAdapter(SecretsConfig secretsConfig) {
        this.secretsConfig = secretsConfig;
    }

    @PostConstruct
    public void init() {
        try {
            String botToken = secretsConfig.getSlackBotToken();
            String appToken = secretsConfig.getSlackAppToken();

            if (botToken == null || botToken.isEmpty()) {
                log.warn("Slack bot token not configured, adapter disabled");
                return;
            }

            AppConfig config = new AppConfig();
            config.setSingleTeamBotToken(botToken);
            slackApp = new App(config);

            // Handle messages
            slackApp.message(".*", (payload, ctx) -> {
                var event = payload.getEvent();
                if (event.getBotId() != null) return ctx.ack(); // skip bot messages

                InboundMessage msg = new InboundMessage(
                        "slack", event.getUser(), event.getChannel(),
                        event.getThreadTs(), event.getText(),
                        Map.of("team", event.getTeam() != null ? event.getTeam() : ""),
                        java.time.Instant.now());
                messageSink.tryEmitNext(msg);
                return ctx.ack();
            });

            // Handle app mentions
            slackApp.event(com.slack.api.model.event.AppMentionEvent.class, (payload, ctx) -> {
                var event = payload.getEvent();
                InboundMessage msg = new InboundMessage(
                        "slack", event.getUser(), event.getChannel(),
                        event.getThreadTs(), event.getText(),
                        Map.of("mentioned", true),
                        java.time.Instant.now());
                messageSink.tryEmitNext(msg);
                return ctx.ack();
            });

            // Start Socket Mode to receive events
            if (appToken != null && !appToken.isEmpty()) {
                socketModeApp = new SocketModeApp(appToken, slackApp);
                socketModeApp.startAsync();
                log.info("Slack Socket Mode started");
            } else {
                log.warn("Slack app token not configured, Socket Mode disabled");
            }

            log.info("Slack channel adapter initialized");
        } catch (Exception e) {
            log.error("Failed to initialize Slack adapter", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        try {
            if (socketModeApp != null) {
                socketModeApp.stop();
                log.info("Slack Socket Mode stopped");
            }
        } catch (Exception e) {
            log.error("Error stopping Slack Socket Mode", e);
        }
    }

    @Override
    public String channelType() { return "slack"; }

    @Override
    public Flux<InboundMessage> receiveMessages() {
        return messageSink.asFlux();
    }

    @Override
    public Mono<Void> sendMessage(OutboundMessage msg) {
        return Mono.fromRunnable(() -> {
            try {
                if (slackApp == null) return;
                slackApp.client().chatPostMessage(r -> r
                        .channel(msg.conversationId())
                        .text(msg.content())
                        .threadTs(msg.threadId())
                );
            } catch (IOException | SlackApiException e) {
                log.error("Failed to send Slack message", e);
            }
        });
    }

    @Override
    public Mono<Void> sendTypingIndicator(String conversationId) {
        return Mono.empty(); // Slack doesn't support typing indicators for bots
    }

    @Override
    public boolean supportsThreading() { return true; }

    @Override
    public boolean supportsReactions() { return true; }
}
