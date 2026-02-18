package com.jclaw.security;

import com.jclaw.config.JclawProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Per-user rate limiting with per-minute and per-hour caps.
 * Uses Redis for distributed rate limiting across CF instances.
 * Limits are configurable via jclaw.security.rate-limit properties.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    // Lua script for atomic increment-and-check with TTL
    private static final String RATE_LIMIT_SCRIPT = """
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """;

    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final Counter rateLimitExceeded;
    private final DefaultRedisScript<Long> rateLimitRedisScript;
    private final JclawProperties.RateLimitProperties rateLimitProperties;

    public RateLimitFilter(ReactiveRedisTemplate<String, String> redisTemplate,
                           MeterRegistry meterRegistry,
                           JclawProperties jclawProperties) {
        this.redisTemplate = redisTemplate;
        this.rateLimitExceeded = Counter.builder("jclaw.rate_limit.exceeded")
                .description("Rate limit exceeded events")
                .register(meterRegistry);
        this.rateLimitRedisScript = new DefaultRedisScript<>(RATE_LIMIT_SCRIPT, Long.class);
        this.rateLimitProperties = jclawProperties.getSecurity().getRateLimit();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isAdmin(auth)) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = auth.getName();
        boolean isService = isService(auth);
        int minuteLimit = isService ? rateLimitProperties.getServicePerMinute()
                : rateLimitProperties.getUserPerMinute();
        int hourLimit = isService ? rateLimitProperties.getServicePerHour()
                : rateLimitProperties.getUserPerHour();

        // Check per-minute limit via Redis
        Long minuteCount = checkRateLimit("jclaw:rate:" + key + ":min", 60_000);
        if (minuteCount != null && minuteCount > minuteLimit) {
            rejectRequest(response, key, "per-minute", 60);
            return;
        }

        // Check per-hour limit via Redis
        Long hourCount = checkRateLimit("jclaw:rate:" + key + ":hour", 3_600_000);
        if (hourCount != null && hourCount > hourLimit) {
            rejectRequest(response, key, "per-hour", 3600);
            return;
        }

        response.setHeader("X-RateLimit-Remaining-Minute",
                String.valueOf(Math.max(0, minuteLimit - (minuteCount != null ? minuteCount : 0))));
        response.setHeader("X-RateLimit-Remaining-Hour",
                String.valueOf(Math.max(0, hourLimit - (hourCount != null ? hourCount : 0))));
        filterChain.doFilter(request, response);
    }

    private Long checkRateLimit(String key, long windowMs) {
        try {
            return redisTemplate.execute(
                    rateLimitRedisScript,
                    List.of(key),
                    List.of(String.valueOf(windowMs))
            ).blockFirst(Duration.ofSeconds(1));
        } catch (Exception e) {
            log.warn("Redis rate limit check failed, allowing request: {}", e.getMessage());
            return null; // fail open if Redis is unavailable
        }
    }

    private void rejectRequest(HttpServletResponse response, String key,
                               String window, int retryAfter) throws IOException {
        log.warn("Rate limit exceeded for principal={} window={}", key, window);
        rateLimitExceeded.increment();
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfter));
        response.getWriter().write("{\"error\":\"rate_limit_exceeded\",\"window\":\"" + window + "\"}");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/") || path.startsWith("/webhooks/");
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("SCOPE_jclaw.admin"));
    }

    private boolean isService(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(a -> a.equals("SCOPE_jclaw.service"));
    }
}
