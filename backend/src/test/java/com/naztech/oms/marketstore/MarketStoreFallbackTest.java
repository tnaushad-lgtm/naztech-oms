package com.naztech.oms.marketstore;

import com.naztech.oms.api.Dtos.Depth;
import com.naztech.oms.api.Dtos.DepthLevel;
import com.naztech.oms.exchange.config.HotStoreProperties;
import com.naztech.oms.exchange.config.TickStoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The market-data plane is a cache and a history — never a system of record. So the property that
 * matters most is not that it works when the servers are up, but that <em>the OMS keeps trading when
 * they are down</em>. A tick store or a hot store that throws into a fill path would take the
 * exchange down with it, which is exactly the trade a broker must never make.
 */
class MarketStoreFallbackTest {

    @Test
    @DisplayName("an unreachable QuestDB drops ticks quietly — it never throws at the fill path")
    void tickStoreSurvivesAnUnreachableServer() {
        TickStoreProperties props = new TickStoreProperties();
        props.setEnabled(true);
        props.setHost("127.0.0.1");
        props.setHttpPort(9);          // discard port: nothing is listening
        props.setIlpPort(9);

        QuestDbTickStore store = new QuestDbTickStore(props);
        try {
            assertThat(store.isLive()).isFalse();

            assertThatCode(() -> {
                for (int i = 0; i < 1000; i++) {
                    store.tick(7L, "BRACBANK", new BigDecimal("47.80"), 100, System.currentTimeMillis());
                }
            }).as("a dead tick store must never throw into the caller").doesNotThrowAnyException();

            assertThat(store.candles(7L, 60, 100))
                    .as("no history → empty, so the caller falls back")
                    .isEmpty();
        } finally {
            store.stop();
        }
    }

    @Test
    @DisplayName("an unreachable Valkey degrades to empty reads — it never throws at the fill path")
    void hotStoreSurvivesAnUnreachableServer() {
        HotStoreProperties props = new HotStoreProperties();
        props.setEnabled(true);
        props.setHost("127.0.0.1");
        props.setPort(9);              // nothing is listening
        props.setTimeoutMs(200);

        ValkeyHotStore store = new ValkeyHotStore(props);
        try {
            assertThat(store.isLive()).isFalse();

            assertThatCode(() -> {
                store.putQuote(7L, new HotStore.Quote(new BigDecimal("47.80"), null, null, 100, 1));
                store.putDepth(7L, depth());
                store.publish("oms:ticks", "{}");
            }).as("a dead hot store must never throw into the caller").doesNotThrowAnyException();

            assertThat(store.quote(7L)).isEmpty();
            assertThat(store.depth(7L)).isEmpty();
        } finally {
            store.close();
        }
    }

    @Test
    @DisplayName("with no stores configured the OMS behaves exactly as it did before they existed")
    void noStoresConfiguredIsTheOldBehaviour() {
        TickStore ticks = new NoopTickStore();
        HotStore hot = new InMemoryHotStore();

        ticks.tick(7L, "BRACBANK", new BigDecimal("47.80"), 100, System.currentTimeMillis());
        assertThat(ticks.candles(7L, 60, 100)).isEmpty();     // → caller falls back to the trade table
        assertThat(ticks.isLive()).isFalse();

        hot.putQuote(7L, new HotStore.Quote(new BigDecimal("47.80"), null, null, 100, 1));
        assertThat(hot.quote(7L)).isPresent();                 // kept locally, but not shared
        assertThat(hot.isLive()).isFalse();
    }

    private static Depth depth() {
        return new Depth("BRACBANK", new BigDecimal("47.80"),
                List.of(new DepthLevel(new BigDecimal("47.70"), 1000L, 2)),
                List.of(new DepthLevel(new BigDecimal("47.90"), 800L, 1)));
    }
}
