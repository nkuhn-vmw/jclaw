package com.jclaw.channel.discord;

import com.jclaw.channel.ChannelAdapter;
import com.jclaw.channel.InboundMessage;
import com.jclaw.channel.OutboundMessage;
import com.jclaw.config.SecretsConfig;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "vcap.services.jclaw-secrets.credentials.discord-bot-token", matchIfMissing = false)
public class DiscordChannelAdapter extends ListenerAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(DiscordChannelAdapter.class);

    private final SecretsConfig secretsConfig;
    private final Sinks.Many<InboundMessage> messageSink =
            Sinks.many().multicast().onBackpressureBuffer();
    private JDA jda;

    public DiscordChannelAdapter(SecretsConfig secretsConfig) {
        this.secretsConfig = secretsConfig;
    }

    @PostConstruct
    public void init() {
        try {
            String token = secretsConfig.getDiscordBotToken();
            if (token == null || token.isEmpty()) {
                log.warn("Discord bot token not configured, adapter disabled");
                return;
            }

            jda = JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES)
                    .addEventListeners(this)
                    .build();
            log.info("Discord channel adapter initialized");
        } catch (Exception e) {
            log.error("Failed to initialize Discord adapter", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (jda != null) jda.shutdown();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        InboundMessage msg = new InboundMessage(
                "discord",
                event.getAuthor().getId(),
                event.getChannel().getId(),
                null,
                event.getMessage().getContentRaw(),
                Map.of("guild", event.isFromGuild() ? event.getGuild().getId() : "dm"),
                java.time.Instant.now());
        messageSink.tryEmitNext(msg);
    }

    @Override
    public String channelType() { return "discord"; }

    @Override
    public Flux<InboundMessage> receiveMessages() {
        return messageSink.asFlux();
    }

    @Override
    public Mono<Void> sendMessage(OutboundMessage msg) {
        return Mono.fromRunnable(() -> {
            if (jda == null) return;
            MessageChannel channel = jda.getTextChannelById(msg.conversationId());
            if (channel != null) {
                channel.sendMessage(msg.content()).queue();
            }
        });
    }

    @Override
    public Mono<Void> sendTypingIndicator(String conversationId) {
        return Mono.fromRunnable(() -> {
            if (jda == null) return;
            MessageChannel channel = jda.getTextChannelById(conversationId);
            if (channel != null) {
                channel.sendTyping().queue();
            }
        });
    }

    @Override
    public boolean supportsThreading() { return true; }

    @Override
    public boolean supportsReactions() { return true; }

    @Override
    public int maxMessageLength() { return 2000; }
}
