package com.jclaw.security;

import com.jclaw.audit.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AuditLogFilter extends OncePerRequestFilter {

    private final AuditService auditService;

    public AuditLogFilter(AuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        filterChain.doFilter(request, response);

        int status = response.getStatus();
        String action = request.getMethod() + " " + request.getRequestURI();
        String sourceIp = request.getRemoteAddr();

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal())) {
            String principal = auth.getName();
            String outcome = status < 400 ? "SUCCESS" : (status == 403 ? "DENIED" : "FAILURE");
            auditService.logAuth(principal, action, outcome, sourceIp);
        } else if (status == 401 || status == 403) {
            // Audit failed authentication attempts (SEC-002)
            String principal = auth != null ? auth.getName() : "anonymous";
            String outcome = status == 401 ? "AUTH_FAILED" : "DENIED";
            auditService.logAuth(principal, action, outcome, sourceIp);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/") || path.startsWith("/webhooks/");
    }
}
