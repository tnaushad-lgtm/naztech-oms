package com.naztech.oms.exchange.itch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Local DSE ITCH v2.2 simulator. Produces a *self-consistent* stream of real ITCH messages —
 * an opening sequence (Timestamp/SystemEvent/Orderbook-Directory/reference price) followed by
 * continuous order-book flow (Add/Execute/Delete/Replace/Trade/BBO/Index) — so the ITCH client,
 * order book and market-data pipeline can be built and demoed without the live DSE feed.
 *
 * <p>Deterministic given a seed (executes/deletes only reference orders it actually added), which
 * makes the whole pipeline unit-testable. Byte framing (SoupBinTCP/MoldUDP64) and DB wiring are
 * layered on in Phase 4; this class emits {@link Itch.Msg} objects (encode via {@link ItchCodec}).
 */
public class ItchSimulator {

    public record Instrument(long orderbook, String secCode, String name, long refPrice, int priceDecimals, long tickSize) {}

    private final List<Instrument> instruments;
    private final Random rnd;
    private long clockNanos = 0;
    private long nextOrderNo = 1;
    private long nextMatchNo = 1;
    private final Map<Long, List<Resting>> book = new HashMap<>();   // orderbook → live orders we've added

    private static final class Resting { final long id; final char verb; final long price; long qty;
        Resting(long id, char verb, long price, long qty) { this.id = id; this.verb = verb; this.price = price; this.qty = qty; } }

    public ItchSimulator(List<Instrument> instruments, long seed) {
        this.instruments = List.copyOf(instruments);
        this.rnd = new Random(seed);
        for (Instrument i : instruments) book.put(i.orderbook(), new ArrayList<>());
    }

    private long ts() { clockNanos += 1 + rnd.nextInt(1_000_000); return clockNanos; }

    /** Session start: Timestamp, System 'O'/'S', then each instrument's directory + reference price + a little depth. */
    public List<Itch.Msg> openingSequence() {
        List<Itch.Msg> out = new ArrayList<>();
        out.add(new Itch.Timestamp(34_200));                             // 09:30:00 in seconds since midnight
        out.add(new Itch.SystemEvent(ts(), "", 'O', 0));                 // start of messages
        out.add(new Itch.SystemEvent(ts(), "", 'S', 0));                 // start of system hours
        for (Instrument i : instruments) {
            out.add(directory(i));
            out.add(new Itch.TradingAction(ts(), i.orderbook(), 'T', ' '));                 // trading
            out.add(new Itch.AddOrder(ts(), 0, ' ', 0, i.orderbook(), i.refPrice(), 0));    // reference price (special case)
            // seed a few levels of depth each side
            for (int lvl = 1; lvl <= 3; lvl++) {
                out.add(newOrder(i, 'B', i.refPrice() - lvl * i.tickSize(), (rnd.nextInt(20) + 5) * 100L));
                out.add(newOrder(i, 'S', i.refPrice() + lvl * i.tickSize(), (rnd.nextInt(20) + 5) * 100L));
            }
        }
        out.add(new Itch.SystemEvent(ts(), "", 'Q', 0));                 // start of market hours
        return out;
    }

    /** A burst of continuous market activity across random instruments. */
    public List<Itch.Msg> burst(int n) {
        List<Itch.Msg> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            Instrument i = instruments.get(rnd.nextInt(instruments.size()));
            double r = rnd.nextDouble();
            if (r < 0.45) {                                              // add a new order near the reference
                char verb = rnd.nextBoolean() ? 'B' : 'S';
                long off = (rnd.nextInt(3) + 1) * i.tickSize();
                long px = verb == 'B' ? i.refPrice() - off : i.refPrice() + off;
                out.add(newOrder(i, verb, px, (rnd.nextInt(20) + 1) * 100L));
            } else if (r < 0.75) {                                       // (partial) execution + trade print + BBO
                out.addAll(executeSome(i));
            } else if (r < 0.90) {                                       // cancel a resting order
                Resting o = pick(i);
                if (o != null) { book.get(i.orderbook()).remove(o); out.add(new Itch.OrderDelete(ts(), o.id)); }
            } else {                                                     // replace (amend) a resting order
                Resting o = pick(i);
                if (o != null) {
                    book.get(i.orderbook()).remove(o);
                    long newId = nextOrderNo++;
                    long newQty = (rnd.nextInt(20) + 1) * 100L;
                    long newPx = o.price + (rnd.nextInt(3) - 1) * i.tickSize();
                    book.get(i.orderbook()).add(new Resting(newId, o.verb, newPx, newQty));
                    out.add(new Itch.OrderReplace(ts(), o.id, newId, newQty, newPx, 0));
                }
            }
            // occasional index tick
            if (rnd.nextDouble() < 0.1) out.add(new Itch.IndexValue(ts(), 900000 + rnd.nextInt(3), 6000_00000000L + rnd.nextInt(1000)));
        }
        return out;
    }

    private Itch.Msg directory(Instrument i) {
        return new Itch.OrderbookDirectory(ts(), i.orderbook(), 'U', "BD000" + i.orderbook(), i.secCode(),
                "BDT", "MAIN", 1, 2, 1, i.priceDecimals(), 0, 0, 'P', i.orderbook(), 'A',
                "SECTOR", "EQTY", i.name(), 0, 4);
    }

    private Itch.Msg newOrder(Instrument i, char verb, long price, long qty) {
        long id = nextOrderNo++;
        book.get(i.orderbook()).add(new Resting(id, verb, price, qty));
        return new Itch.AddOrder(ts(), id, verb, qty, i.orderbook(), price, 0);
    }

    /** Execute part/all of a random resting order → Order Executed [E] + Trade [Q] + updated BBO [O]. */
    private List<Itch.Msg> executeSome(Instrument i) {
        List<Itch.Msg> out = new ArrayList<>();
        Resting o = pick(i);
        if (o == null) return out;
        long fill = Math.max(100, Math.min(o.qty, (rnd.nextInt(10) + 1) * 100L));
        o.qty -= fill;
        long match = nextMatchNo++;
        out.add(new Itch.OrderExecuted(ts(), o.id, fill, match));
        out.add(new Itch.Trade(ts(), fill, i.orderbook(), 'Y', o.price, 0, match));         // tape print
        if (o.qty <= 0) book.get(i.orderbook()).remove(o);
        out.add(bbo(i));
        return out;
    }

    private Itch.Msg bbo(Instrument i) {
        long bestBid = -1, bestBidSz = 0, bestAsk = -1, bestAskSz = 0;
        for (Resting o : book.get(i.orderbook())) {
            if (o.qty <= 0) continue;
            if (o.verb == 'B' && (bestBid < 0 || o.price > bestBid)) { bestBid = o.price; bestBidSz = o.qty; }
            if (o.verb == 'S' && (bestAsk < 0 || o.price < bestAsk)) { bestAsk = o.price; bestAskSz = o.qty; }
        }
        return new Itch.BestBidOffer(ts(), i.orderbook(),
                bestBid < 0 ? Itch.MARKET : bestBid, bestBidSz, bestAsk < 0 ? Itch.MARKET : bestAsk, bestAskSz);
    }

    private Resting pick(Instrument i) {
        List<Resting> live = book.get(i.orderbook());
        return live.isEmpty() ? null : live.get(rnd.nextInt(live.size()));
    }

    public List<Instrument> instruments() { return instruments; }
}
