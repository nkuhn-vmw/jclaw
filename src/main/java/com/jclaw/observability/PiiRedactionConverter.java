package com.jclaw.observability;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Pattern;

/**
 * Logback converter that redacts PII patterns (SSN, credit card, email) from log messages.
 * Configure in logback-spring.xml with: &lt;conversionRule conversionWord="piiRedact" converterClass="com.jclaw.observability.PiiRedactionConverter"/&gt;
 */
public class PiiRedactionConverter extends ClassicConverter {

    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CC_PATTERN = Pattern.compile("\\b\\d{16}\\b");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");

    @Override
    public String convert(ILoggingEvent event) {
        String message = event.getFormattedMessage();
        if (message == null) return "";
        message = SSN_PATTERN.matcher(message).replaceAll("[REDACTED-SSN]");
        message = CC_PATTERN.matcher(message).replaceAll("[REDACTED-CC]");
        message = EMAIL_PATTERN.matcher(message).replaceAll("[REDACTED-EMAIL]");
        return message;
    }
}
