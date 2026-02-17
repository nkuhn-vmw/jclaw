package com.jclaw.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Centralized Micrometer metrics for jclaw subsystems.
 */
@Component
public class JclawMetrics {

    private final MeterRegistry registry;

    // Channel metrics
    private final Counter messagesReceived;
    private final Counter messagesSent;
    private final Counter messagesRoutingFailed;

    // Tool metrics
    private final Counter toolCallsTotal;
    private final Counter toolCallsBlocked;
    private final Timer toolCallLatency;

    // Content filter metrics
    private final Counter contentFiltered;
    private final Counter contentBlocked;

    // Session metrics
    private final Counter sessionsCreated;

    public JclawMetrics(MeterRegistry registry) {
        this.registry = registry;

        this.messagesReceived = Counter.builder("jclaw.channel.messages.received")
                .description("Total inbound messages received")
                .register(registry);
        this.messagesSent = Counter.builder("jclaw.channel.messages.sent")
                .description("Total outbound messages sent")
                .register(registry);
        this.messagesRoutingFailed = Counter.builder("jclaw.channel.messages.routing_failed")
                .description("Messages that failed routing")
                .register(registry);

        this.toolCallsTotal = Counter.builder("jclaw.tool.calls.total")
                .description("Total tool calls executed")
                .register(registry);
        this.toolCallsBlocked = Counter.builder("jclaw.tool.calls.blocked")
                .description("Tool calls blocked by policy")
                .register(registry);
        this.toolCallLatency = Timer.builder("jclaw.tool.call.latency")
                .description("Tool call execution latency")
                .register(registry);

        this.contentFiltered = Counter.builder("jclaw.content.filtered")
                .description("Content filter events")
                .register(registry);
        this.contentBlocked = Counter.builder("jclaw.content.blocked")
                .description("Content blocked by filters")
                .register(registry);

        this.sessionsCreated = Counter.builder("jclaw.sessions.created")
                .description("Sessions created")
                .register(registry);
    }

    public void recordMessageReceived(String channel) {
        messagesReceived.increment();
        Counter.builder("jclaw.channel.messages.received")
                .tag("channel", channel)
                .register(registry).increment();
    }

    public void recordMessageSent(String channel) {
        messagesSent.increment();
    }

    public void recordRoutingFailure() {
        messagesRoutingFailed.increment();
    }

    public void recordToolCall(String toolName) {
        toolCallsTotal.increment();
    }

    public void recordToolBlocked(String toolName) {
        toolCallsBlocked.increment();
    }

    public Timer getToolCallLatencyTimer() {
        return toolCallLatency;
    }

    public void recordContentFiltered() {
        contentFiltered.increment();
    }

    public void recordContentBlocked() {
        contentBlocked.increment();
    }

    public void recordSessionCreated() {
        sessionsCreated.increment();
    }
}
