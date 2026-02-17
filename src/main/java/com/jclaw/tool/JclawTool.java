package com.jclaw.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface JclawTool {
    String name();
    String description();
    RiskLevel riskLevel() default RiskLevel.LOW;
    boolean requiresApproval() default false;
}
