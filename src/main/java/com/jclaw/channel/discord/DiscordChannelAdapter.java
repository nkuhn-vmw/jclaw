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

        // Propagate thread ID for threaded messages
        String threadId = event.isFromThread()
                ? event.getChannel().getId() : null;
        // Use parent channel ID as conversationId for threads
        String conversationId = event.isFromThread()
                ? event.getMessage().getChannel().asThreadChannel()
                        .getParentChannel().getId()
                : event.getChannel().getId();

        Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("guild", event.isFromGuild() ? event.getGuild().getId() : "dm");
        if (!event.isFromGuild()) {
            metadata.put("isDm", true);
        }

        InboundMessage msg = new InboundMessage(
                "discord",
                event.getAuthor().getId(),
                conversationId,
                threadId,
                event.getMessage().getContentRaw(),
                metadata,
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
            // If threadId is present, send to the thread channel
            if (msg.threadId() != null) {
                MessageChannel thread = jda.getThreadChannelById(msg.threadId());
                if (thread != null) {
                    thread.sendMessage(msg.content()).queue();
                    return;
                }
            }
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

    @Override
    public boolean isConnected() {
        return jda != null && jda.getStatus() == JDA.Status.CONNECTED;
    }
}
