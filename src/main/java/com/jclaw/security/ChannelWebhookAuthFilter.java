package com.jclaw.security;

import com.jclaw.config.SecretsConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Authenticates inbound channel webhook requests using channel-specific
 * signature verification (e.g., Slack signing secret, Teams HMAC).
 * Non-webhook requests pass through.
 */
public class ChannelWebhookAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ChannelWebhookAuthFilter.class);

    private final SecretsConfig secretsConfig;

    public ChannelWebhookAuthFilter(SecretsConfig secretsConfig) {
        this.secretsConfig = secretsConfig;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/webhooks/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String channelType = extractChannelType(path);
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);

        // Read the body first so it's cached
        wrappedRequest.getInputStream().readAllBytes();
        byte[] body = wrappedRequest.getContentAsByteArray();

        boolean verified = switch (channelType) {
            case "slack" -> verifySlackSignature(wrappedRequest, body);
            case "teams" -> verifyTeamsToken(wrappedRequest);
            case "google-chat" -> verifyGoogleChatToken(wrappedRequest);
            case "discord" -> verifyDiscordSignature(wrappedRequest, body);
            default -> {
                log.warn("Unknown webhook channel type: {}", channelType);
                yield false;
            }
        };

        if (!verified) {
            log.warn("Webhook signature verification failed for channel={} ip={}",
                    channelType, request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"error\":\"webhook_auth_failed\"}");
            return;
        }

        log.debug("Webhook authenticated for channel: {}", channelType);
        filterChain.doFilter(wrappedRequest, response);
    }

    private boolean verifySlackSignature(HttpServletRequest request, byte[] body) {
        String signingSecret = secretsConfig.getSlackSigningSecret();
        if (signingSecret == null || signingSecret.isEmpty()) {
            log.warn("Slack signing secret not configured, rejecting webhook");
            return false;
        }

        String timestamp = request.getHeader("X-Slack-Request-Timestamp");
        String signature = request.getHeader("X-Slack-Signature");
        if (timestamp == null || signature == null) return false;

        // Prevent replay attacks (reject if timestamp > 5 minutes old)
        try {
            long ts = Long.parseLong(timestamp);
            if (Math.abs(System.currentTimeMillis() / 1000 - ts) > 300) {
                log.warn("Slack webhook timestamp too old: {}", timestamp);
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }

        String baseString = "v0:" + timestamp + ":" + new String(body, StandardCharsets.UTF_8);
        String computed = "v0=" + hmacSha256(signingSecret, baseString);
        return constantTimeEquals(signature, computed);
    }

    private boolean verifyTeamsToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false;
        // Teams Bot Framework uses JWT validation against Microsoft's keys.
        // In production, validate the JWT token against Microsoft's JWKS endpoint.
        // For now, verify the token is present and non-empty.
        String token = authHeader.substring(7);
        return !token.isEmpty();
    }

    private boolean verifyGoogleChatToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false;
        // Google Chat uses JWT tokens signed by Google's service account.
        // In production, validate against Google's public keys.
        String token = authHeader.substring(7);
        return !token.isEmpty();
    }

    private boolean verifyDiscordSignature(HttpServletRequest request, byte[] body) {
        String publicKey = secretsConfig.getDiscordPublicKey();
        if (publicKey == null || publicKey.isEmpty()) {
            log.warn("Discord public key not configured, rejecting webhook");
            return false;
        }

        String signature = request.getHeader("X-Signature-Ed25519");
        String timestamp = request.getHeader("X-Signature-Timestamp");
        if (signature == null || timestamp == null) return false;

        // Discord uses Ed25519 signature verification.
        // For now verify headers are present; full Ed25519 requires a crypto library.
        return !signature.isEmpty() && !timestamp.isEmpty();
    }

    private String hmacSha256(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.error("HMAC computation failed", e);
            return "";
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private String extractChannelType(String path) {
        String[] parts = path.split("/");
        return parts.length > 2 ? parts[2] : "unknown";
    }
}
