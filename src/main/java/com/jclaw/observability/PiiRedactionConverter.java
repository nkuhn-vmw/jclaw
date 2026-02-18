package com.jclaw.observability;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Logback converter that redacts PII patterns from log messages.
 * Default patterns cover SSN, credit card, and email. Additional patterns
 * can be configured via jclaw.security.pii.redact-patterns and pushed
 * through LoggingConfig at startup.
 */
public class PiiRedactionConverter extends ClassicConverter {

    private static final List<Pattern> DEFAULT_PATTERNS = List.of(
            Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b"),         // SSN
            Pattern.compile("\\b\\d{16}\\b"),                       // Credit card
            Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}") // Email
    );

    private static volatile List<Pattern> configuredPatterns = null;

    /**
     * Called by LoggingConfig to push configured PII patterns from application properties.
     */
    public static void setConfiguredPatterns(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>(DEFAULT_PATTERNS);
        if (patterns != null) {
            for (String p : patterns) {
                compiled.add(Pattern.compile(p));
            }
        }
        configuredPatterns = compiled;
    }

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message == null) return "";

        List<Pattern> patterns = configuredPatterns != null ? configuredPatterns : DEFAULT_PATTERNS;
        for (Pattern pattern : patterns) {
            message = pattern.matcher(message).replaceAll("[REDACTED]");
        }
        return message;
    }
}
