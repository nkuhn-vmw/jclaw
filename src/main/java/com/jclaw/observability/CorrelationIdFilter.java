package com.jclaw.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Sets MDC correlation IDs for every HTTP request for structured logging.
 * Propagates: requestId, sessionId, agentId, channelType, channelTraceId.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String SESSION_ID_HEADER = "X-Session-ID";
    private static final String AGENT_ID_HEADER = "X-Agent-ID";
    private static final String CHANNEL_TYPE_HEADER = "X-Channel-Type";
    private static final String CHANNEL_TRACE_ID_HEADER = "X-Channel-Trace-ID";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        String requestId = request.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }

        MDC.put("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        // Propagate optional context headers to MDC
        putIfPresent(request, SESSION_ID_HEADER, "sessionId");
        putIfPresent(request, AGENT_ID_HEADER, "agentId");
        putIfPresent(request, CHANNEL_TYPE_HEADER, "channelType");
        putIfPresent(request, CHANNEL_TRACE_ID_HEADER, "channelTraceId");

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
            MDC.remove("sessionId");
            MDC.remove("agentId");
            MDC.remove("channelType");
            MDC.remove("channelTraceId");
        }
    }

    private void putIfPresent(HttpServletRequest request, String header, String mdcKey) {
        String value = request.getHeader(header);
        if (value != null && !value.isBlank()) {
            MDC.put(mdcKey, value);
        }
    }
}
