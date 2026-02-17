package com.jclaw.content;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class ContentFilterPolicy {

    @Column(name = "max_message_length")
    private int maxMessageLength = 50000;

    @Column(name = "enable_pattern_detection")
    private boolean enablePatternDetection = true;

    @Column(name = "enable_instruction_detection")
    private boolean enableInstructionDetection = true;

    @Column(name = "enable_egress_guard")
    private boolean enableEgressGuard = true;

    public ContentFilterPolicy() {}

    public int getMaxMessageLength() { return maxMessageLength; }
    public void setMaxMessageLength(int maxMessageLength) { this.maxMessageLength = maxMessageLength; }

    public boolean isEnablePatternDetection() { return enablePatternDetection; }
    public void setEnablePatternDetection(boolean enablePatternDetection) { this.enablePatternDetection = enablePatternDetection; }

    public boolean isEnableInstructionDetection() { return enableInstructionDetection; }
    public void setEnableInstructionDetection(boolean enableInstructionDetection) { this.enableInstructionDetection = enableInstructionDetection; }

    public boolean isEnableEgressGuard() { return enableEgressGuard; }
    public void setEnableEgressGuard(boolean enableEgressGuard) { this.enableEgressGuard = enableEgressGuard; }
}
