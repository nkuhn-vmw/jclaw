package com.jclaw.config;

import com.jclaw.observability.PiiRedactionConverter;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configures logging infrastructure including PII redaction patterns.
 * Pushes configured patterns from application properties into the
 * Logback PiiRedactionConverter via its static holder.
 */
@Configuration
public class LoggingConfig {

    private static final Logger log = LoggerFactory.getLogger(LoggingConfig.class);

    private final JclawProperties properties;

    public LoggingConfig(JclawProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void configurePiiRedaction() {
        List<String> patterns = properties.getSecurity().getPii().getRedactPatterns();
        if (patterns != null && !patterns.isEmpty()) {
            log.info("Configuring {} additional PII redaction patterns", patterns.size());
            PiiRedactionConverter.setConfiguredPatterns(patterns);
        } else {
            log.info("Using default PII redaction patterns (SSN, CC, email)");
        }
    }
}
