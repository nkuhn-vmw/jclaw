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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.NamedParameterSpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * Authenticates inbound channel webhook requests using channel-specific
 * signature verification (Slack HMAC-SHA256, Teams JWT, Google Chat JWT, Discord Ed25519).
 * Non-webhook requests pass through.
 */
@org.springframework.stereotype.Component
public class ChannelWebhookAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ChannelWebhookAuthFilter.class);

    private static final String TEAMS_JWKS_URL = "https://login.botframework.com/v1/.well-known/keys";
    private static final String GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final String TEAMS_ISSUER = "https://api.botframework.com";
    private static final String GOOGLE_ISSUER = "chat@system.gserviceaccount.com";

    private final SecretsConfig secretsConfig;
    private final com.jclaw.audit.AuditService auditService;
    private final Map<String, CachedJwkSet> jwksCache = new ConcurrentHashMap<>();

    public ChannelWebhookAuthFilter(SecretsConfig secretsConfig,
                                    com.jclaw.audit.AuditService auditService) {
        this.secretsConfig = secretsConfig;
        this.auditService = auditService;
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
            case "teams" -> verifyTeamsJwt(wrappedRequest);
            case "google-chat" -> verifyGoogleChatJwt(wrappedRequest);
            case "discord" -> verifyDiscordSignature(wrappedRequest, body);
            default -> {
                log.warn("Unknown webhook channel type: {}", channelType);
                yield false;
            }
        };

        if (!verified) {
            log.warn("Webhook signature verification failed for channel={} ip={}",
                    channelType, request.getRemoteAddr());
            auditService.logAuth("webhook:" + channelType,
                    "WEBHOOK_AUTH " + channelType, "AUTH_FAILED", request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("{\"error\":\"webhook_auth_failed\"}");
            return;
        }

        log.debug("Webhook authenticated for channel: {}", channelType);
        auditService.logAuth("webhook:" + channelType,
                "WEBHOOK_AUTH " + channelType, "AUTH_SUCCESS", request.getRemoteAddr());
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

    /**
     * Validates Teams Bot Framework JWT token against Microsoft's JWKS endpoint.
     * Verifies signature, issuer (api.botframework.com), and expiry.
     */
    private boolean verifyTeamsJwt(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false;
        String token = authHeader.substring(7);
        if (token.isEmpty()) return false;

        try {
            JWKSet jwkSet = fetchJwkSet(TEAMS_JWKS_URL);
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                    JWSAlgorithm.RS256, new ImmutableJWKSet<>(jwkSet)));

            JWTClaimsSet claims = processor.process(token, null);

            // Verify issuer
            String issuer = claims.getIssuer();
            if (!TEAMS_ISSUER.equals(issuer)) {
                log.warn("Teams JWT issuer mismatch: expected={} actual={}", TEAMS_ISSUER, issuer);
                return false;
            }

            // Verify audience matches our Teams app ID (prevents cross-bot token replay)
            String expectedAppId = secretsConfig.getTeamsAppId();
            if (expectedAppId != null && !expectedAppId.isEmpty()) {
                java.util.List<String> audience = claims.getAudience();
                if (audience == null || !audience.contains(expectedAppId)) {
                    log.warn("Teams JWT audience mismatch: expected={} actual={}", expectedAppId, audience);
                    return false;
                }
            }

            // Verify not expired
            if (claims.getExpirationTime() != null
                    && claims.getExpirationTime().before(new java.util.Date())) {
                log.warn("Teams JWT token expired");
                return false;
            }

            return true;
        } catch (Exception e) {
            log.warn("Teams JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Validates Google Chat JWT token against Google's public keys.
     * Verifies signature, issuer (chat@system.gserviceaccount.com), audience, and expiry.
     */
    private boolean verifyGoogleChatJwt(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false;
        String token = authHeader.substring(7);
        if (token.isEmpty()) return false;

        try {
            JWKSet jwkSet = fetchJwkSet(GOOGLE_JWKS_URL);
            DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
            processor.setJWSKeySelector(new JWSVerificationKeySelector<>(
                    JWSAlgorithm.RS256, new ImmutableJWKSet<>(jwkSet)));

            JWTClaimsSet claims = processor.process(token, null);

            // Verify issuer
            String issuer = claims.getIssuer();
            if (!GOOGLE_ISSUER.equals(issuer)) {
                log.warn("Google Chat JWT issuer mismatch: expected={} actual={}", GOOGLE_ISSUER, issuer);
                return false;
            }

            // Verify audience matches our Google Chat project number (prevents cross-project token replay)
            String expectedProjectNumber = secretsConfig.getGoogleChatProjectNumber();
            if (expectedProjectNumber != null && !expectedProjectNumber.isEmpty()) {
                java.util.List<String> audience = claims.getAudience();
                if (audience == null || !audience.contains(expectedProjectNumber)) {
                    log.warn("Google Chat JWT audience mismatch: expected={} actual={}", expectedProjectNumber, audience);
                    return false;
                }
            }

            // Verify not expired
            if (claims.getExpirationTime() != null
                    && claims.getExpirationTime().before(new java.util.Date())) {
                log.warn("Google Chat JWT token expired");
                return false;
            }

            return true;
        } catch (Exception e) {
            log.warn("Google Chat JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Verifies Discord Ed25519 signature using the application's public key.
     * Uses Java 15+ native EdDSA support.
     */
    private boolean verifyDiscordSignature(HttpServletRequest request, byte[] body) {
        String publicKeyHex = secretsConfig.getDiscordPublicKey();
        if (publicKeyHex == null || publicKeyHex.isEmpty()) {
            log.warn("Discord public key not configured, rejecting webhook");
            return false;
        }

        String signatureHex = request.getHeader("X-Signature-Ed25519");
        String timestamp = request.getHeader("X-Signature-Timestamp");
        if (signatureHex == null || timestamp == null) return false;

        try {
            // Decode the public key from hex
            byte[] publicKeyBytes = HexFormat.of().parseHex(publicKeyHex);

            // Build the message to verify: timestamp + body
            byte[] timestampBytes = timestamp.getBytes(StandardCharsets.UTF_8);
            byte[] message = new byte[timestampBytes.length + body.length];
            System.arraycopy(timestampBytes, 0, message, 0, timestampBytes.length);
            System.arraycopy(body, 0, message, timestampBytes.length, body.length);

            // Decode the signature from hex
            byte[] signatureBytes = HexFormat.of().parseHex(signatureHex);

            // Verify using Java EdDSA
            KeyFactory keyFactory = KeyFactory.getInstance("EdDSA");
            java.security.spec.EdECPublicKeySpec keySpec = decodeEd25519PublicKey(publicKeyBytes);
            PublicKey pubKey = keyFactory.generatePublic(keySpec);

            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(pubKey);
            verifier.update(message);
            return verifier.verify(signatureBytes);
        } catch (Exception e) {
            log.warn("Discord Ed25519 verification failed: {}", e.getMessage());
            return false;
        }
    }

    private java.security.spec.EdECPublicKeySpec decodeEd25519PublicKey(byte[] publicKeyBytes) {
        // Ed25519 public keys are 32 bytes. Decode the y coordinate and sign bit.
        if (publicKeyBytes.length != 32) {
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes");
        }

        // The last byte's MSB is the sign bit of x
        boolean xOdd = (publicKeyBytes[31] & 0x80) != 0;

        // Clear the sign bit for y decoding
        byte[] yBytes = publicKeyBytes.clone();
        yBytes[31] &= 0x7F;

        // Reverse bytes (Ed25519 is little-endian)
        byte[] reversed = new byte[yBytes.length];
        for (int i = 0; i < yBytes.length; i++) {
            reversed[i] = yBytes[yBytes.length - 1 - i];
        }

        java.math.BigInteger y = new java.math.BigInteger(1, reversed);
        java.security.spec.EdECPoint point = new java.security.spec.EdECPoint(xOdd, y);
        return new java.security.spec.EdECPublicKeySpec(NamedParameterSpec.ED25519, point);
    }

    private JWKSet fetchJwkSet(String url) throws Exception {
        CachedJwkSet cached = jwksCache.get(url);
        long now = System.currentTimeMillis();

        // Cache JWKS for 1 hour
        if (cached != null && (now - cached.fetchedAt) < 3_600_000) {
            return cached.jwkSet;
        }

        JWKSet jwkSet = JWKSet.load(URI.create(url).toURL());
        jwksCache.put(url, new CachedJwkSet(jwkSet, now));
        return jwkSet;
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

    private record CachedJwkSet(JWKSet jwkSet, long fetchedAt) {}
}
