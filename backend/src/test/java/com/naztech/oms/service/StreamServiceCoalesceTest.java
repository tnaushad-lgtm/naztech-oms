package com.naztech.oms.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 7: proves the SSE coalescer protects the UI from a &gt;10k msg/sec feed — market/indices
 * collapse to last-value-wins, trades are rate-capped per flush, and order updates are never buffered.
 */
class StreamServiceCoalesceTest {

    @Test
    void collapses_market_and_indices_and_caps_trades() {
        StreamService s = new StreamService();
        s.setCoalesceForTest(true);
        for (int i = 0; i < 10_000; i++) {
            s.publish("market", Map.of("n", i));
            s.publish("indices", Map.of("ts", i));
            s.publish("trade", Map.of("p", i));
        }
        List<String[]> flush = s.drain();
        long market = flush.stream().filter(x -> x[0].equals("market")).count();
        long indices = flush.stream().filter(x -> x[0].equals("indices")).count();
        long trades = flush.stream().filter(x -> x[0].equals("trade")).count();

        assertThat(market).isEqualTo(1);      // 10k market ticks → 1 (latest wins)
        assertThat(indices).isEqualTo(1);
        assertThat(trades).isEqualTo(25);     // rate-capped per flush
        assertThat(s.tradeBacklog()).isLessThanOrEqualTo(500);  // bounded backlog, no unbounded growth
    }

    @Test
    void order_updates_are_never_buffered() {
        StreamService s = new StreamService();
        s.setCoalesceForTest(true);
        s.publish("order", Map.of("id", 1));   // immediate path
        assertThat(s.drain()).isEmpty();
    }

    @Test
    void coalescing_off_by_default_keeps_immediate_behaviour() {
        StreamService s = new StreamService();   // coalesce defaults false
        s.publish("market", Map.of("n", 1));
        s.publish("trade", Map.of("p", 1));
        assertThat(s.drain()).isEmpty();          // nothing buffered → behaves exactly as before
    }
}
