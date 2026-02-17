package com.jclaw.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralized Micrometer metrics for jclaw subsystems.
 * All metric names and tag dimensions follow the tech spec.
 */
@Component
public class JclawMetrics {

    private final MeterRegistry registry;
    private final AtomicLong activeSessions = new AtomicLong(0);

    public JclawMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("jclaw.sessions.active", activeSessions);
    }

    // --- Channel metrics ---

    public void recordMessageReceived(String channel, String agent) {
        Counter.builder("jclaw.messages.received")
                .tag("channel", channel)
                .tag("agent", agent)
                .register(registry).increment();
    }

    // --- LLM metrics ---

    public void recordLlmRequest(String model, String agent) {
        Counter.builder("jclaw.llm.requests")
                .tag("model", model)
                .tag("agent", agent)
                .register(registry).increment();
    }

    public void recordLlmTokensInput(String model, String agent, long tokens) {
        Counter.builder("jclaw.llm.tokens.input")
                .tag("model", model)
                .tag("agent", agent)
                .register(registry).increment(tokens);
    }

    public void recordLlmTokensOutput(String model, String agent, long tokens) {
        Counter.builder("jclaw.llm.tokens.output")
                .tag("model", model)
                .tag("agent", agent)
                .register(registry).increment(tokens);
    }

    public Timer.Sample startLlmTimer() {
        return Timer.start(registry);
    }

    public void stopLlmTimer(Timer.Sample sample, String model, String agent) {
        sample.stop(Timer.builder("jclaw.llm.latency")
                .tag("model", model)
                .tag("agent", agent)
                .register(registry));
    }

    // --- Tool metrics ---

    public void recordToolInvocation(String tool, String agent, String outcome) {
        Counter.builder("jclaw.tools.invocations")
                .tag("tool", tool)
                .tag("agent", agent)
                .tag("outcome", outcome)
                .register(registry).increment();
    }

    // --- Content filter metrics ---

    public void recordContentFilterTriggered(String filter, String action) {
        Counter.builder("jclaw.content_filter.triggered")
                .tag("filter", filter)
                .tag("action", action)
                .register(registry).increment();
    }

    // --- Session metrics ---

    public void sessionOpened() {
        activeSessions.incrementAndGet();
    }

    public void sessionClosed() {
        activeSessions.decrementAndGet();
    }

    public MeterRegistry getRegistry() {
        return registry;
    }
}
