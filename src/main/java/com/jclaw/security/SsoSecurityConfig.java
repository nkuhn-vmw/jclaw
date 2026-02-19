package com.jclaw.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import com.jclaw.config.JclawProperties;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SsoSecurityConfig {

    private final AuditLogFilter auditLogFilter;
    private final RateLimitFilter rateLimitFilter;
    private final ChannelWebhookAuthFilter channelWebhookAuthFilter;
    private final JwtAuthenticationConverter jwtConverter;
    private final JclawProperties properties;

    public SsoSecurityConfig(AuditLogFilter auditLogFilter, RateLimitFilter rateLimitFilter,
                             ChannelWebhookAuthFilter channelWebhookAuthFilter,
                             JwtAuthenticationConverter jwtConverter,
                             JclawProperties properties) {
        this.auditLogFilter = auditLogFilter;
        this.rateLimitFilter = rateLimitFilter;
        this.channelWebhookAuthFilter = channelWebhookAuthFilter;
        this.jwtConverter = jwtConverter;
        this.properties = properties;
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
                        .userInfoEndpoint(userInfo -> userInfo
                                .userService(scopeMappingOAuth2UserService())
                        )
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/css/**", "/admin/js/**").permitAll()
                        .requestMatchers("/admin/**").hasAuthority("SCOPE_jclaw.admin")
                        .requestMatchers("/operator/**").hasAnyAuthority(
                                "SCOPE_jclaw.admin", "SCOPE_jclaw.operator")
                        .anyRequest().authenticated()
                )
                .addFilterAfter(auditLogFilter, AuthorizationFilter.class)
                .addFilterAfter(rateLimitFilter, AuditLogFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), UsernamePasswordAuthenticationFilter.class)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                )
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                )
                .build();
    }

    /**
     * Custom OAuth2 user service that grants SCOPE_jclaw.* authorities based on the
     * jclaw.dashboard.admin-users property. UAA's P-Identity SSO plan only grants
     * the "openid" scope via authorization_code flow and does not return an id_token,
     * so Spring Security uses the plain OAuth2 path (not OIDC). This service maps
     * SSO usernames to the authorities the rest of the app expects.
     */
    private OAuth2UserService<OAuth2UserRequest, OAuth2User> scopeMappingOAuth2UserService() {
        DefaultOAuth2UserService delegate = new DefaultOAuth2UserService();
        return userRequest -> {
            OAuth2User oauth2User = delegate.loadUser(userRequest);
            Set<GrantedAuthority> authorities = new HashSet<>(oauth2User.getAuthorities());

            // Grant jclaw authorities based on configured admin users list
            String userName = (String) oauth2User.getAttributes().get("user_name");
            List<String> adminUsers = properties.getDashboard().getAdminUserList();
            if (userName != null && adminUsers.contains(userName)) {
                authorities.add(new SimpleGrantedAuthority("SCOPE_jclaw.admin"));
                authorities.add(new SimpleGrantedAuthority("SCOPE_jclaw.operator"));
                authorities.add(new SimpleGrantedAuthority("SCOPE_jclaw.user"));
            }

            // Preserve the original name attribute key
            String nameAttr = "sub";
            if (oauth2User.getAttributes().containsKey("user_name")) {
                nameAttr = "user_name";
            }
            return new DefaultOAuth2User(authorities, oauth2User.getAttributes(), nameAttr);
        };
    }

    /**
     * Forces the deferred CSRF token to be loaded on every request so the XSRF-TOKEN
     * cookie is always present for JavaScript to read. Spring Security 6 defers token
     * generation by default, so without this filter the cookie won't appear until a
     * form-based POST triggers it.
     */
    private static class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken(); // Force token generation
            }
            filterChain.doFilter(request, response);
        }
    }

}
