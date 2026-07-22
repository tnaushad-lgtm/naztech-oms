package com.naztech.oms.exchange.itch;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Order-book consistency validation (Phase-C item pulled forward): the validator flags crossed books and,
 * critically, the structural invariants hold at every step of a long simulated session routed through the
 * real codec — a regression guard on {@link ItchOrderBook} reconstruction / depth aggregation.
 */
class ItchBookInvariantsTest {

    @Test
    void flags_a_crossed_book() {
        ItchOrderBook b = new ItchOrderBook();
        b.add(1, 'B', 10_100, 100);   // bid 101.00
        b.add(2, 'S', 10_000, 100);   // ask 100.00  → crossed
        ItchBookInvariants.Report r = ItchBookInvariants.check(b, 2, 5);
        assertThat(r.ok()).isFalse();
        assertThat(r.violations()).anyMatch(s -> s.contains("crossed"));
    }

    /**
     * The shape of the bug this test exists to prevent.
     *
     * A venue does not delete every resting order at the close; it publishes a System Event and the
     * book is void from that point. The gateway decoded [S] and ignored it, so nothing ever emptied
     * the books — and because a reconnect replays from sequence one across several sessions, each
     * session's leftovers piled onto the last. Enough stale bids outliving the offers that traded
     * against them produces a CROSSED book, which is arithmetically impossible on a real venue.
     *
     * Observed against nFIX on 2026-07-22: 273 of 306 instruments crossed, ACI showing a 239.90 bid
     * against a 239.60 ask while the venue itself showed a clean 239.60 / 239.70.
     */
    @Test
    void a_session_boundary_must_empty_the_book_or_stale_orders_cross_it() {
        ItchOrderBook b = new ItchOrderBook();

        // yesterday: a bid that will never be deleted explicitly, because the close voids it
        b.add(1, 'B', 23_990, 100);
        assertThat(b.restingOrderCount()).isEqualTo(1);

        // the session boundary — what ItchGateway.onSystemEvent now does on O/S/C/E
        b.clear();
        assertThat(b.restingOrderCount()).isZero();

        // today: an offer BELOW yesterday's bid. Legal on a fresh book, impossible if the bid survived.
        b.add(2, 'S', 23_960, 700);
        b.add(3, 'B', 23_950, 220);
        assertThat(ItchBookInvariants.check(b, 2, 10).ok())
                .as("a book rebuilt after the boundary must not be crossed")
                .isTrue();

        // and the counter-proof: without the clear, the same two days cross.
        ItchOrderBook stale = new ItchOrderBook();
        stale.add(1, 'B', 23_990, 100);      // yesterday, never cleared
        stale.add(2, 'S', 23_960, 700);      // today
        assertThat(ItchBookInvariants.check(stale, 2, 10).ok())
                .as("this is what the feed produced before the fix")
                .isFalse();
    }

    @Test
    void clean_two_sided_book_passes() {
        ItchOrderBook b = new ItchOrderBook();
        b.add(1, 'B', 9_900, 100);
        b.add(2, 'B', 9_800, 200);
        b.add(3, 'S', 10_100, 100);
        b.add(4, 'S', 10_200, 50);
        assertThat(ItchBookInvariants.check(b, 2, 5).ok()).isTrue();
    }

    @Test
    void simulator_stream_keeps_structural_invariants() {
        List<ItchSimulator.Instrument> ins = List.of(
                new ItchSimulator.Instrument(1, "GP", "Grameenphone", 25_000, 2, 10),
                new ItchSimulator.Instrument(2, "BRACBANK", "BRAC Bank", 6_400, 2, 10));
        ItchSimulator sim = new ItchSimulator(ins, 42L);

        Map<Long, ItchOrderBook> books = new HashMap<>();
        Map<Long, Long> orderToBook = new HashMap<>();
        for (ItchSimulator.Instrument i : ins) books.put(i.orderbook(), new ItchOrderBook());

        // route each message through the real codec, exactly like ItchGateway
        Consumer<Itch.Msg> route = raw -> {
            Itch.Msg m = ItchCodec.decode(ItchCodec.encode(raw));
            switch (m.type()) {
                case 'A' -> { Itch.AddOrder a = (Itch.AddOrder) m;
                    if (!Itch.isReferencePrice(a)) { orderToBook.put(a.orderNumber(), a.orderbook()); books.get(a.orderbook()).apply(a); } }
                case 'F' -> { Itch.AddOrderParticipant f = (Itch.AddOrderParticipant) m;
                    orderToBook.put(f.orderNumber(), f.orderbook()); books.get(f.orderbook()).apply(f); }
                case 'E' -> { Itch.OrderExecuted e = (Itch.OrderExecuted) m; Long ob = orderToBook.get(e.orderNumber()); if (ob != null) books.get(ob).apply(e); }
                case 'C' -> { Itch.OrderExecutedWithPrice c = (Itch.OrderExecutedWithPrice) m; Long ob = orderToBook.get(c.orderNumber()); if (ob != null) books.get(ob).apply(c); }
                case 'D' -> { Itch.OrderDelete d = (Itch.OrderDelete) m; Long ob = orderToBook.remove(d.orderNumber()); if (ob != null) books.get(ob).apply(d); }
                case 'U' -> { Itch.OrderReplace u = (Itch.OrderReplace) m; Long ob = orderToBook.remove(u.origOrderNumber());
                    if (ob != null) { books.get(ob).apply(u); orderToBook.put(u.newOrderNumber(), ob); } }
                default -> { /* non-book message */ }
            }
        };

        for (Itch.Msg m : sim.openingSequence()) route.accept(m);
        for (int step = 0; step < 300; step++) {
            for (Itch.Msg m : sim.burst(20)) route.accept(m);
            for (Map.Entry<Long, ItchOrderBook> e : books.entrySet()) {
                ItchBookInvariants.Report r = ItchBookInvariants.checkStructural(e.getValue(), 2, 10);
                assertThat(r.ok()).as("step %d book %d: %s", step, e.getKey(), r.violations()).isTrue();
            }
        }
    }
}
