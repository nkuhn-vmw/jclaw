package com.jclaw.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SsoSecurityConfig {

    private final AuditLogFilter auditLogFilter;
    private final RateLimitFilter rateLimitFilter;
    private final ChannelWebhookAuthFilter channelWebhookAuthFilter;
    private final JwtAuthenticationConverter jwtConverter;

    public SsoSecurityConfig(AuditLogFilter auditLogFilter, RateLimitFilter rateLimitFilter,
                             ChannelWebhookAuthFilter channelWebhookAuthFilter,
                             JwtAuthenticationConverter jwtConverter) {
        this.auditLogFilter = auditLogFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.channelWebhookAuthFilter = channelWebhookAuthFilter;
        this.jwtConverter = jwtConverter;
    }

    /**
     * API and webhook filter chain — stateless JWT auth, no HTTP sessions.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/api/**", "/webhooks/**", "/actuator/**")
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/webhooks/**").permitAll()
                        .requestMatchers("/api/admin/**").hasAuthority("SCOPE_jclaw.admin")
                        .requestMatchers("/api/agents/*/sessions/**").hasAnyAuthority(
                                "SCOPE_jclaw.operator", "SCOPE_jclaw.admin")
                        .requestMatchers("/api/chat/**").hasAnyAuthority(
                                "SCOPE_jclaw.user", "SCOPE_jclaw.admin", "SCOPE_jclaw.service",
                                "SCOPE_uaa.user")
                        .requestMatchers("/api/webchat/**").hasAnyAuthority(
                                "SCOPE_jclaw.user", "SCOPE_jclaw.admin")
                        .requestMatchers("/api/service/**").hasAuthority("SCOPE_jclaw.service")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(channelWebhookAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(auditLogFilter, AuthorizationFilter.class)
                .addFilterAfter(rateLimitFilter, AuditLogFilter.class)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .csrf(csrf -> csrf
                        .ignoringRequestMatchers("/api/**", "/webhooks/**")
                )
                .build();
    }

    /**
     * Web UI filter chain — OAuth2 login with session support for admin/operator dashboard.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webFilterChain(HttpSecurity http) throws Exception {
        return http
                .oauth2Login(oauth2 -> oauth2
                        .defaultSuccessUrl("/admin/dashboard")
                        .failureUrl("/login?error=true")
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/**").hasAuthority("SCOPE_jclaw.admin")
                        .requestMatchers("/operator/**").hasAnyAuthority(
                                "SCOPE_jclaw.admin", "SCOPE_jclaw.operator")
                        .anyRequest().authenticated()
                )
                .addFilterAfter(auditLogFilter, AuthorizationFilter.class)
                .addFilterAfter(rateLimitFilter, AuditLogFilter.class)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )
                .build();
    }

}
