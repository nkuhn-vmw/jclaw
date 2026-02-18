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
        String path = request.getRequestURI();

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String principal = (auth != null && auth.isAuthenticated()
                && !"anonymousUser".equals(auth.getPrincipal()))
                ? auth.getName()
                : "anonymous";
        String action = request.getMethod() + " " + path;
        String sourceIp = resolveClientIp(request);

        // Log AUTH_SUCCESS for sensitive admin/operator paths (per spec §5.5)
        if (status >= 200 && status < 300 && isSensitivePath(path)
                && !"anonymous".equals(principal)) {
            auditService.logAuth(principal, action, "AUTH_SUCCESS", sourceIp);
            return;
        }

        // Log authentication/authorization failures (401/403)
        if (status != 401 && status != 403) return;

        String outcome = status == 401 ? "AUTH_FAILED" : "DENIED";
        auditService.logAuth(principal, action, outcome, sourceIp);
    }

    private boolean isSensitivePath(String path) {
        return path.startsWith("/api/admin/") || path.startsWith("/api/agents/")
                || path.startsWith("/api/identity-mappings/");
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/actuator/");
    }
}
