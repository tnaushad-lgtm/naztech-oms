package com.naztech.oms.exchange.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves the config-only venue switch: {@code exchange.mode} selects the simulator vs the FIX
 * gateway, with no code change. Pure unit test (no Spring context).
 */
class ExchangeModeConditionTest {

    private final SimulatorModeCondition simulator = new SimulatorModeCondition();
    private final FixModeCondition fix = new FixModeCondition();
    private final AnnotatedTypeMetadata meta = mock(AnnotatedTypeMetadata.class);

    private ConditionContext ctx(String mode) {
        MockEnvironment env = new MockEnvironment();
        if (mode != null) {
            env.setProperty("exchange.mode", mode);
        }
        ConditionContext c = mock(ConditionContext.class);
        when(c.getEnvironment()).thenReturn(env);
        return c;
    }

    @Test
    void simulator_is_the_default_when_unset() {
        assertThat(simulator.matches(ctx(null), meta)).isTrue();
        assertThat(fix.matches(ctx(null), meta)).isFalse();
    }

    @Test
    void simulator_mode_selects_simulator() {
        assertThat(simulator.matches(ctx("simulator"), meta)).isTrue();
        assertThat(fix.matches(ctx("simulator"), meta)).isFalse();
    }

    @Test
    void dse_cert_selects_fix() {
        assertThat(fix.matches(ctx("dse-cert"), meta)).isTrue();
        assertThat(simulator.matches(ctx("dse-cert"), meta)).isFalse();
    }

    @Test
    void dse_prod_selects_fix() {
        assertThat(fix.matches(ctx("dse-prod"), meta)).isTrue();
        assertThat(simulator.matches(ctx("dse-prod"), meta)).isFalse();
    }
}
