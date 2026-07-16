package com.naztech.oms.exchange.itch;

import com.naztech.oms.api.Dtos.Depth;
import com.naztech.oms.api.Dtos.DepthLevel;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Rebuilds one order book from the ITCH order-flow messages (Add [A]/[F], Executed [E]/[C],
 * Delete [D], Replace [U]) and aggregates it into the OMS {@link Depth} shape — so the ITCH feed
 * drives the exact same market-depth ladder the UI already renders. Prices are kept as raw ITCH
 * integers and scaled by {@code 10^priceDecimals} only when depth is produced.
 */
public class ItchOrderBook {

    private static final class Order {
        final char verb;        // 'B' buy / 'S' sell
        final long price;       // raw ITCH price
        long qty;               // remaining quantity
        Order(char verb, long price, long qty) { this.verb = verb; this.price = price; this.qty = qty; }
    }

    private final Map<Long, Order> orders = new HashMap<>();   // orderNumber → resting order

    /** Route a book-affecting ITCH message; other message types are ignored. */
    public void apply(Itch.Msg m) {
        switch (m.type()) {
            case 'A' -> { Itch.AddOrder a = (Itch.AddOrder) m; if (!Itch.isReferencePrice(a)) add(a.orderNumber(), a.verb(), a.price(), a.quantity()); }
            case 'F' -> { Itch.AddOrderParticipant f = (Itch.AddOrderParticipant) m; add(f.orderNumber(), f.verb(), f.price(), f.quantity()); }
            case 'E' -> { Itch.OrderExecuted e = (Itch.OrderExecuted) m; execute(e.orderNumber(), e.execQty()); }
            case 'C' -> { Itch.OrderExecutedWithPrice c = (Itch.OrderExecutedWithPrice) m; execute(c.orderNumber(), c.execQty()); }
            case 'D' -> { Itch.OrderDelete d = (Itch.OrderDelete) m; orders.remove(d.orderNumber()); }
            case 'U' -> { Itch.OrderReplace u = (Itch.OrderReplace) m; replace(u.origOrderNumber(), u.newOrderNumber(), u.price(), u.quantity()); }
            default -> { /* not a book-affecting message */ }
        }
    }

    public void add(long orderNumber, char verb, long price, long qty) {
        if (qty <= 0 || Itch.isMarket(price)) return;      // ignore market/ref sentinels on the book
        orders.put(orderNumber, new Order(verb, price, qty));
    }

    public void execute(long orderNumber, long execQty) {
        Order o = orders.get(orderNumber);
        if (o == null) return;
        o.qty -= execQty;
        if (o.qty <= 0) orders.remove(orderNumber);
    }

    /**
     * The raw resting price of an order, or -1 if we don't know it. An [E] Order-Executed message carries
     * no price of its own — the trade printed at the resting order's price — so the gateway must read it
     * here <em>before</em> applying the execution, since a full fill removes the order.
     */
    public long priceOf(long orderNumber) {
        Order o = orders.get(orderNumber);
        return o == null ? -1 : o.price;
    }

    public void replace(long origOrderNumber, long newOrderNumber, long newPrice, long newQty) {
        Order old = orders.remove(origOrderNumber);
        char verb = old != null ? old.verb : 'B';
        if (newQty > 0 && !Itch.isMarket(newPrice)) orders.put(newOrderNumber, new Order(verb, newPrice, newQty));
    }

    public int restingOrderCount() { return orders.size(); }

    /** Drop every resting order — used when the venue restarts and the book must be rebuilt from the replay. */
    public void clear() { orders.clear(); }

    /** Best (highest) bid as a raw ITCH price, or -1 if none. */
    public long bestBidRaw() { return best('B', true); }
    /** Best (lowest) ask as a raw ITCH price, or -1 if none. */
    public long bestAskRaw() { return best('S', false); }

    private long best(char verb, boolean wantMax) {
        long best = -1;
        for (Order o : orders.values()) {
            if (o.verb != verb || o.qty <= 0) continue;
            if (best < 0) best = o.price;
            else best = wantMax ? Math.max(best, o.price) : Math.min(best, o.price);
        }
        return best;
    }

    /** Aggregate to the OMS depth ladder (bids high→low, asks low→high), price scaled by 10^priceDecimals. */
    public Depth depth(String symbol, BigDecimal ltp, int priceDecimals, int levels) {
        return new Depth(symbol, ltp,
                side('B', true, priceDecimals, levels),
                side('S', false, priceDecimals, levels));
    }

    private List<DepthLevel> side(char verb, boolean descending, int priceDecimals, int levels) {
        Comparator<Long> order = descending ? Comparator.<Long>reverseOrder() : Comparator.<Long>naturalOrder();
        TreeMap<Long, long[]> byPrice = new TreeMap<>(order);
        for (Order o : orders.values()) {
            if (o.verb != verb || o.qty <= 0) continue;
            long[] agg = byPrice.computeIfAbsent(o.price, k -> new long[2]);
            agg[0] += o.qty;
            agg[1] += 1;
        }
        List<DepthLevel> out = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : byPrice.entrySet()) {
            BigDecimal px = BigDecimal.valueOf(e.getKey()).movePointLeft(priceDecimals);
            out.add(new DepthLevel(px, e.getValue()[0], (int) e.getValue()[1]));
            if (out.size() >= levels) break;
        }
        return out;
    }
}
