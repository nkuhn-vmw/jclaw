package com.jclaw.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean
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
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus").permitAll()
                        .requestMatchers("/webhooks/**").permitAll()
                        .requestMatchers("/admin/**").hasAuthority("SCOPE_jclaw.admin")
                        .requestMatchers("/operator/**").hasAnyAuthority(
                                "SCOPE_jclaw.admin", "SCOPE_jclaw.operator")
                        .requestMatchers("/api/admin/**").hasAuthority("SCOPE_jclaw.admin")
                        .requestMatchers("/api/agents/*/sessions/**").hasAnyAuthority(
                                "SCOPE_jclaw.operator", "SCOPE_jclaw.admin")
                        .requestMatchers("/api/chat/**").hasAnyAuthority(
                                "SCOPE_jclaw.user", "SCOPE_jclaw.admin", "SCOPE_jclaw.service")
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

}
