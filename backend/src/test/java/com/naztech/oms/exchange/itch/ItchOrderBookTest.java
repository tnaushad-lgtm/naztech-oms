package com.naztech.oms.exchange.itch;

import com.naztech.oms.api.Dtos.Depth;
import com.naztech.oms.exchange.itch.Itch.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the ITCH order book builds correct, scaled depth from the order-flow messages, and that
 * a full simulator stream (opening sequence + continuous flow) decodes and rebuilds consistently.
 */
class ItchOrderBookTest {

    @Test
    void builds_scaled_depth_and_aggregates_levels() {
        ItchOrderBook b = new ItchOrderBook();
        b.apply(new AddOrder(1, 101, 'B', 1_000, 5, 30_000, 0));   // bid 300.00 x1000
        b.apply(new AddOrder(1, 102, 'B', 500, 5, 29_900, 0));     // bid 299.00 x500
        b.apply(new AddOrder(1, 103, 'S', 800, 5, 30_100, 0));     // ask 301.00 x800
        b.apply(new AddOrder(1, 104, 'S', 400, 5, 30_100, 0));     // ask 301.00 x400 → same level

        Depth d = b.depth("GP", new BigDecimal("300.50"), 2, 5);   // priceDecimals = 2
        assertThat(d.bids()).hasSize(2);
        assertThat(d.bids().get(0).price()).isEqualByComparingTo("300.00");  // best bid on top
        assertThat(d.bids().get(0).quantity()).isEqualTo(1_000L);
        assertThat(d.asks()).hasSize(1);                                     // two orders, one price level
        assertThat(d.asks().get(0).price()).isEqualByComparingTo("301.00");
        assertThat(d.asks().get(0).quantity()).isEqualTo(1_200L);
        assertThat(d.asks().get(0).orders()).isEqualTo(2);
    }

    @Test
    void execute_delete_replace_update_the_book() {
        ItchOrderBook b = new ItchOrderBook();
        b.apply(new AddOrder(1, 101, 'B', 1_000, 5, 30_000, 0));
        b.apply(new AddOrder(1, 102, 'B', 500, 5, 29_900, 0));
        b.apply(new AddOrder(1, 103, 'S', 800, 5, 30_100, 0));

        b.apply(new OrderExecuted(1, 101, 400, 9_001));            // 1000 → 600
        assertThat(b.depth("GP", BigDecimal.ZERO, 2, 5).bids().get(0).quantity()).isEqualTo(600L);

        b.apply(new OrderDelete(1, 102));                          // one bid left
        assertThat(b.depth("GP", BigDecimal.ZERO, 2, 5).bids()).hasSize(1);

        b.apply(new OrderReplace(1, 103, 201, 900, 30_200, 0));    // ask moves 301.00 → 302.00, qty 900
        Depth d = b.depth("GP", BigDecimal.ZERO, 2, 5);
        assertThat(d.asks()).hasSize(1);
        assertThat(d.asks().get(0).price()).isEqualByComparingTo("302.00");
        assertThat(d.asks().get(0).quantity()).isEqualTo(900L);
    }

    @Test
    void priceOf_gives_the_resting_price_so_an_E_execution_can_be_priced() {
        // An [E] Order-Executed carries no price — the trade prints at the resting order's price. The
        // gateway must read that price BEFORE applying the fill, because a full fill removes the order.
        // Regression: [E]/[C] were updating the book but never the last-traded price ("price 30 min old").
        ItchOrderBook b = new ItchOrderBook();
        b.apply(new AddOrder(1, 101, 'S', 1_000, 5, 30_100, 0));   // ask 301.00 x1000
        assertThat(b.priceOf(101)).isEqualTo(30_100L);            // resting price, ready to price an [E]

        b.apply(new OrderExecuted(1, 101, 400, 9_001));           // partial fill: order stays, price unchanged
        assertThat(b.priceOf(101)).isEqualTo(30_100L);

        b.apply(new OrderExecuted(1, 101, 600, 9_002));           // full fill: order gone
        assertThat(b.priceOf(101)).isEqualTo(-1L);                // -1 → gateway records nothing for it
        assertThat(b.priceOf(999)).isEqualTo(-1L);                // never-seen order
    }

    @Test
    void reference_price_and_market_orders_do_not_rest() {
        ItchOrderBook b = new ItchOrderBook();
        b.apply(new AddOrder(1, 0, ' ', 0, 5, 30_000, 0));         // reference-price update (special case)
        b.apply(new AddOrder(1, 500, 'B', 100, 5, Itch.MARKET, 0)); // market order — not on the book
        Depth d = b.depth("GP", BigDecimal.ZERO, 2, 5);
        assertThat(d.bids()).isEmpty();
        assertThat(d.asks()).isEmpty();
        assertThat(b.restingOrderCount()).isZero();
    }

    @Test
    void full_simulator_stream_decodes_and_rebuilds_consistently() {
        // one instrument → one book; order numbers are globally unique so every E/D/U applies cleanly
        ItchSimulator sim = new ItchSimulator(List.of(
                new ItchSimulator.Instrument(5, "GP", "Grameenphone Ltd", 30_000, 2, 10)), 42L);

        ItchOrderBook book = new ItchOrderBook();
        int decoded = 0;
        for (List<Itch.Msg> batch : List.of(sim.openingSequence(), sim.burst(400), sim.burst(400))) {
            for (Itch.Msg m : batch) {
                Itch.Msg back = ItchCodec.decode(ItchCodec.encode(m));   // prove every emitted msg is wire-valid
                assertThat(back).isEqualTo(m);
                decoded++;
                book.apply(back);   // apply() ignores non-book-affecting messages (T/S/R/H/Q/O/Z/I)
            }
        }
        assertThat(decoded).isGreaterThan(500);
        // A consistent stream never drives the book negative and yields a valid, ordered depth ladder.
        Depth d = book.depth("GP", BigDecimal.ZERO, 2, 10);
        assertThat(d.bids()).allSatisfy(l -> assertThat(l.quantity()).isPositive());
        assertThat(d.asks()).allSatisfy(l -> assertThat(l.quantity()).isPositive());
        if (d.bids().size() >= 2) {
            assertThat(d.bids().get(0).price()).isGreaterThan(d.bids().get(1).price());  // bids descending
        }
        if (d.asks().size() >= 2) {
            assertThat(d.asks().get(0).price()).isLessThan(d.asks().get(1).price());     // asks ascending
        }
    }
}
