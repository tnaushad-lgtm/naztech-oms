package com.naztech.oms.exchange.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the exchange-connectivity configuration property beans
 * ({@code exchange.*}, {@code fix.*}, {@code itch.*}). Additive — touches no existing config.
 */
@Configuration
@EnableConfigurationProperties({ExchangeProperties.class, FixProperties.class, ItchProperties.class})
public class ExchangeConfig {
}
