package com.naztech.oms.marketstore;

import com.naztech.oms.exchange.config.HotStoreProperties;
import com.naztech.oms.exchange.config.TickStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Chooses the market-data plane: QuestDB for tick history, Valkey for the live picture — or neither,
 * in which case the OMS behaves exactly as it did before they existed.
 *
 * <p>Both are optional and both degrade rather than fail. MySQL remains the system of record for
 * orders, trades, positions and limits; nothing in this package holds anything that cannot be
 * rebuilt from it and the feed. That is the whole reason a market-data outage must never take
 * trading down with it.
 */
@Configuration
@EnableConfigurationProperties({TickStoreProperties.class, HotStoreProperties.class})
public class MarketStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(MarketStoreConfig.class);

    private ValkeyHotStore valkey;

    @Bean
    public TickStore tickStore(TickStoreProperties props) {
        if (!props.isEnabled()) {
            log.info("Tick store disabled (tickstore.enabled=false) — no tick history; candles come "
                    + "from the trade table.");
            return new NoopTickStore();
        }
        return new QuestDbTickStore(props);
    }

    @Bean
    public HotStore hotStore(HotStoreProperties props) {
        if (!props.isEnabled()) {
            log.info("Hot store disabled (hotstore.enabled=false) — quotes from MySQL, depth from the "
                    + "in-JVM book.");
            return new InMemoryHotStore();
        }
        valkey = new ValkeyHotStore(props);
        return valkey;
    }

    /** A cache that went away should come back on its own, without a restart. */
    @Scheduled(fixedDelay = 15_000)
    public void recheckHotStore() {
        if (valkey != null) {
            valkey.recheck();
        }
    }
}
