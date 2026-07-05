package com.naztech.oms.exchange.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Top-level exchange-connectivity settings. Switching venue/environment is CONFIG-ONLY.
 *
 * <p>{@code exchange.mode} selects which adapters are active:
 * <ul>
 *   <li>{@code simulator} — in-process {@code SimulatedMatchingEngine} (default; dev/demo)</li>
 *   <li>{@code dse-cert}  — real DSE via FIX/ITCH against the certification environment</li>
 *   <li>{@code dse-prod}  — real DSE via FIX/ITCH against production</li>
 * </ul>
 * No DSE-specific value is hard-coded anywhere; everything is externalised here / in {@code fix.*}/{@code itch.*}.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "exchange")
public class ExchangeProperties {

    /** simulator | dse-cert | dse-prod */
    private String mode = "simulator";
}
