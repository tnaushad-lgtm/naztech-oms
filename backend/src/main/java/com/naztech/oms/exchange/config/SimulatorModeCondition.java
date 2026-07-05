package com.naztech.oms.exchange.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when {@code exchange.mode} is {@code simulator} (or unset).
 * Used to activate the in-process {@code SimulatedMatchingEngine} for dev/demo.
 */
public class SimulatorModeCondition implements Condition {
    @Override
    public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata md) {
        String mode = ctx.getEnvironment().getProperty("exchange.mode", "simulator");
        return mode == null || mode.isBlank() || mode.equalsIgnoreCase("simulator");
    }
}
