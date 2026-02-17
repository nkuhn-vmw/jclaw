package com.jclaw.tool;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface JclawTool {
    String name();
    String description();
    RiskLevel riskLevel() default RiskLevel.LOW;
    boolean requiresApproval() default false;
}
