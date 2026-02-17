package com.jclaw.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-user rate limiting with per-minute and per-hour caps.
 * In production with Redis, replace with Bucket4j Redis-backed buckets.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final int USER_LIMIT_PER_MINUTE = 20;
    private static final int USER_LIMIT_PER_HOUR = 200;
    private static final int SERVICE_LIMIT_PER_MINUTE = 60;
    private static final int SERVICE_LIMIT_PER_HOUR = 1000;

    private final Map<String, RateBucket> minuteBuckets = new ConcurrentHashMap<>();
    private final Map<String, RateBucket> hourBuckets = new ConcurrentHashMap<>();
    private final Counter rateLimitExceeded;

    public RateLimitFilter(MeterRegistry meterRegistry) {
        this.rateLimitExceeded = Counter.builder("jclaw.rate_limit.exceeded")
                .description("Rate limit exceeded events")
                .register(meterRegistry);
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
        int minuteLimit = isService ? SERVICE_LIMIT_PER_MINUTE : USER_LIMIT_PER_MINUTE;
        int hourLimit = isService ? SERVICE_LIMIT_PER_HOUR : USER_LIMIT_PER_HOUR;

        RateBucket minuteBucket = minuteBuckets.computeIfAbsent(key,
                k -> new RateBucket(minuteLimit, 60_000));
        RateBucket hourBucket = hourBuckets.computeIfAbsent(key,
                k -> new RateBucket(hourLimit, 3_600_000));

        if (!minuteBucket.tryConsume()) {
            rejectRequest(response, key, "per-minute", 60);
            return;
        }
        if (!hourBucket.tryConsume()) {
            rejectRequest(response, key, "per-hour", 3600);
            return;
        }

        response.setHeader("X-RateLimit-Remaining-Minute",
                String.valueOf(minuteBucket.getRemaining()));
        response.setHeader("X-RateLimit-Remaining-Hour",
                String.valueOf(hourBucket.getRemaining()));
        filterChain.doFilter(request, response);
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

    private static class RateBucket {
        private final int limit;
        private final long windowMs;
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();

        RateBucket(int limit, long windowMs) {
            this.limit = limit;
            this.windowMs = windowMs;
        }

        boolean tryConsume() {
            long now = System.currentTimeMillis();
            if (now - windowStart > windowMs) {
                count.set(0);
                windowStart = now;
            }
            return count.incrementAndGet() <= limit;
        }

        int getRemaining() {
            return Math.max(0, limit - count.get());
        }
    }
}
