package com.naztech.oms.exchange.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@code exchange.mode} is a real DSE environment ({@code dse-cert} or {@code dse-prod}).
 * Used to activate the FIX/ITCH adapters instead of the simulator.
 */
public class FixModeCondition implements Condition {
    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) {
        String mode = ctx.getEnvironment().getProperty("exchange.mode", "simulator");
        return "dse-cert".equalsIgnoreCase(mode) || "dse-prod".equalsIgnoreCase(mode);
    }
}
