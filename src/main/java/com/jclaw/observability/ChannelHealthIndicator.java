package com.jclaw.observability;

import com.jclaw.channel.ChannelAdapter;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ChannelHealthIndicator implements HealthIndicator {

    private final List<ChannelAdapter> adapters;

    public ChannelHealthIndicator(List<ChannelAdapter> adapters) {
        this.adapters = adapters;
    }

    @Override
    public Health health() {
        if (adapters.isEmpty()) {
            return Health.unknown().withDetail("reason", "No channel adapters registered").build();
        }

        Map<String, String> channelStatus = new LinkedHashMap<>();
        boolean anyDown = false;
        for (ChannelAdapter adapter : adapters) {
            boolean connected = adapter.isConnected();
            channelStatus.put(adapter.channelType(), connected ? "connected" : "disconnected");
            if (!connected) anyDown = true;
        }

        Health.Builder builder = anyDown ? Health.down() : Health.up();
        return builder
                .withDetail("channels", channelStatus)
                .withDetail("count", adapters.size())
                .build();
    }
}
