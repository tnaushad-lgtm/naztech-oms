package com.naztech.oms.exchange.fixsim;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Application;
import quickfix.DoNotSend;
import quickfix.FieldNotFound;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.CxlRejReason;
import quickfix.field.CxlRejResponseTo;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.MsgType;
import quickfix.field.OrdStatus;
import quickfix.field.OrdType;
import quickfix.field.OrderID;
import quickfix.field.OrderQty;
import quickfix.field.OrigClOrdID;
import quickfix.field.Price;
import quickfix.field.Side;
import quickfix.field.Symbol;
import quickfix.field.Text;
import quickfix.field.TimeInForce;
import quickfix.field.TransactTime;
import quickfix.fix50sp1.ExecutionReport;
import quickfix.fix50sp1.OrderCancelReject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A local FIX acceptor that plays the exchange for the OMS — the counterparty the OMS's real
 * {@code FixTradingGateway} talks to over a real QuickFIX/J session. It replaces the hosted FIXSIM
 * trial (which silently refuses logons on an unsubscribed account) with something that runs on the
 * dealer's own machine: no trial, no paywall, no expiry, works offline.
 *
 * <p>It exercises the OMS's genuine FIX path end to end — {@code FixTradingGateway} →
 * NewOrderSingle → ExecutionReport → {@code ExecutionService} → order lifecycle, trade tape,
 * portfolio. Only the party on the far end of the socket differs from a real venue.
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li>NewOrderSingle(35=D) → immediate {@code New} ack, then a partial fill, then the balance —
 *       so the OMS walks OPEN → PARTIAL → FILLED as it would against a real book. IOC/FOK fill in
 *       one shot (no resting phase).</li>
 *   <li>OrderCancelRequest(35=F) → {@code Canceled} ExecutionReport, or an OrderCancelReject(35=9)
 *       when the order is unknown or already done.</li>
 *   <li>OrderCancelReplaceRequest(35=G) → {@code Replaced} ack (the OMS amends by cancel+resubmit
 *       today, so this is here for completeness).</li>
 * </ul>
 *
 * <h2>Contract with {@code ExecutionService} — do not "tidy" these away</h2>
 * <ul>
 *   <li><b>ClOrdID(11) is echoed byte-for-byte</b>; the OMS correlates by {@code order_ref}, and
 *       tag 37 is never stored. On cancel-related reports {@code OrigClOrdID(41)} carries the
 *       original {@code ORD-…} ref, because the cancel's own {@code CXL-…} ClOrdID matches nothing
 *       in the OMS database.</li>
 *   <li><b>ExecID(17) stays ≤ 28 characters and unique</b>: the OMS stores
 *       {@code trade_ref = "FIX-" + execID} in a {@code VARCHAR(32) UNIQUE} column, so a longer or
 *       repeated ID rolls the fill back with nothing but an ERROR line to show for it.</li>
 *   <li><b>CumQty(14) and AvgPx(6) ride on every report.</b> On the FIX path the OMS treats the
 *       exchange's cumulative figures as authoritative; omit them and an order reaches FILLED with
 *       {@code filled_qty = 0}.</li>
 *   <li><b>Fills are never replayed.</b> Each LastQty(32) &gt; 0 books a fresh trade and re-applies
 *       the position — there is no de-duplication on the OMS side.</li>
 * </ul>
 */
public class LocalExchange implements Application {

    private static final Logger log = LoggerFactory.getLogger(LocalExchange.class);

    /** Prefix + epoch-seconds + counter keeps ExecID unique across restarts and well under 28 chars. */
    private final String execIdEpoch = String.valueOf(System.currentTimeMillis() / 1000);
    private final AtomicLong execSeq = new AtomicLong(1);
    private final AtomicLong orderSeq = new AtomicLong(1);

    private final Map<String, Resting> book = new ConcurrentHashMap<>();

    /**
     * What the venue believes each instrument is worth — pushed by the OMS from its live market data.
     *
     * <p>Without this the exchange priced orders off a hardcoded table of seeded LTPs, which drifts
     * away from the prices the trader is actually looking at as the feed moves. The two then disagree
     * about what is marketable: a bid that looks aggressive on the terminal rests untouched at the
     * venue, for no reason the dealer can see. An exchange must trade against the market that exists,
     * not the one it was born with.
     */
    private final Map<String, BigDecimal> livePrices = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "local-exchange-matcher");
                t.setDaemon(true);
                return t;
            });

    private final long ackDelayMs;
    private final long partialFillDelayMs;
    private final long finalFillDelayMs;

    /**
     * The venue's own trading phase — because the venue decides what trades, not the client.
     *
     * <p>The OMS pushes this on every session change (see {@code LocalExchangeMain}'s control
     * endpoint). Without it the exchange filled orders in pre-open, which no real exchange does: it
     * accepts them, queues them, and crosses the book at the opening bell.
     */
    private volatile Phase phase = Phase.OPEN;

    public enum Phase { CLOSED, PRE_OPEN, OPEN, HALTED }

    /**
     * How the venue decides each marketable order's fate — the "outcome mix" a performance run needs.
     *
     * <p>A load test where every order is accepted and fully filled measures one path through the OMS
     * and calls it the system. Real venues reject, and real orders sit half-filled; those paths write
     * different rows, take different branches in {@code ExecutionService}, and cost different money.
     * So the mix is a dial: of every 100 marketable orders, {@code rejectPct} are refused outright,
     * {@code partialPct} fill {@code partialFillPct}% and then stop (leaving the order PARTIAL), and
     * the rest fill completely.
     *
     * <p>Defaults are all-accept / all-fill, so an exchange nobody has configured behaves exactly as
     * it did before this existed.
     *
     * @param rejectPct      0–100: share of orders the venue rejects outright
     * @param partialPct     0–100: share of orders that fill partly and then stop
     * @param partialFillPct 1–99: how much of such an order fills (the minutes ask for 25 / 50 / 75)
     */
    public record OutcomeMix(int rejectPct, int partialPct, int partialFillPct) {

        public static final OutcomeMix ALL_FILL = new OutcomeMix(0, 0, 50);

        public OutcomeMix {
            rejectPct = clamp(rejectPct, 0, 100);
            partialPct = clamp(partialPct, 0, 100 - rejectPct);   // the two shares cannot exceed the whole
            partialFillPct = clamp(partialFillPct, 1, 99);        // 0 or 100 would not be a partial fill
        }

        private static int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }
    }

    private volatile OutcomeMix mix = OutcomeMix.ALL_FILL;

    public LocalExchange(long ackDelayMs, long partialFillDelayMs, long finalFillDelayMs) {
        this.ackDelayMs = ackDelayMs;
        this.partialFillDelayMs = partialFillDelayMs;
        this.finalFillDelayMs = finalFillDelayMs;
    }

    public Phase phase() {
        return phase;
    }

    public OutcomeMix outcomeMix() {
        return mix;
    }

    /** Set by the OMS before a performance run (see LocalExchangeMain's /config endpoint). */
    public void setOutcomeMix(OutcomeMix next) {
        this.mix = next == null ? OutcomeMix.ALL_FILL : next;
        log.info("OUTCOME MIX — reject {}%, partial {}% (filling {}%), full {}%",
                mix.rejectPct(), mix.partialPct(), mix.partialFillPct(),
                100 - mix.rejectPct() - mix.partialPct());
    }

    /**
     * The OMS pushes its live prices here (see LocalExchangeMain's /prices endpoint), and every push
     * is a chance for a resting order to trade: a bid left below the market fills the moment the
     * market falls to it. That sweep is what makes this a book rather than a queue.
     */
    public void setPrices(Map<String, BigDecimal> prices) {
        livePrices.putAll(prices);
        if (phase == Phase.OPEN) {
            sweep();
        }
    }

    /** Cross every resting order the market has now reached. The opening bell is just this, once. */
    private void sweep() {
        SessionID sid = activeSession;
        if (sid == null) {
            return;
        }
        for (Resting o : book.values()) {
            if (o.done || o.leaves() <= 0 || !isMarketable(o)) {
                continue;
            }
            if (!o.sweeping.compareAndSet(false, true)) {
                continue;                       // fills already scheduled; a second push must not double them
            }
            long clip = o.qty > 1 ? Math.max(1, o.leaves() / 2) : o.leaves();
            log.debug("CROSS  {} — the market reached {} @ {}", o.clOrdId, o.symbol, o.price);
            schedule(partialFillDelayMs, () -> fill(o, clip, sid));
            schedule(finalFillDelayMs, () -> fill(o, o.leaves(), sid));
        }
    }

    public int pricedInstruments() {
        return livePrices.size();
    }

    /** The live price if the OMS has told us one; otherwise the seeded reference. */
    private BigDecimal reference(String symbol) {
        BigDecimal live = symbol == null ? null : livePrices.get(symbol.toUpperCase());
        return live != null && live.signum() > 0 ? live : ReferencePrices.of(symbol);
    }

    /**
     * Would this order trade right now? Asked afresh every time, because it is a question about the
     * market as it stands — a bid below the market rests until the market falls to it, and then it
     * trades. Answering it once at order entry and remembering the answer is how a resting order ends
     * up sitting there for ever while the price walks straight through it.
     */
    private boolean isMarketable(Resting o) {
        if (o.marketOrder) {
            return true;
        }
        BigDecimal ref = reference(o.symbol);
        return o.side == Side.BUY ? o.price.compareTo(ref) >= 0 : o.price.compareTo(ref) <= 0;
    }

    /**
     * Move the venue to a new phase. Crossing into OPEN runs the opening auction: every order that
     * built up in pre-open and is now marketable trades at once, which is what the opening bell
     * actually is.
     */
    public void setPhase(Phase next, SessionID sid) {
        Phase from = phase;
        phase = next;
        log.info("EXCHANGE {} -> {}", from, next);
        if (next == Phase.OPEN && from == Phase.PRE_OPEN && sid != null) {
            uncross(sid);
        }
    }

    /** The opening bell: cross everything the pre-open book has been waiting to trade. */
    private void uncross(SessionID sid) {
        log.info("OPENING BELL — crossing the pre-open book");
        sweep();
    }

    /** A working order the exchange is holding on behalf of the OMS. */
    private static final class Resting {
        final String clOrdId;
        final String exchangeOrderId;
        final String symbol;
        final char side;
        final long qty;
        final BigDecimal price;
        /** A market order always trades; a limit order only when the market reaches its price. */
        final boolean marketOrder;
        long cumQty;
        BigDecimal notional = BigDecimal.ZERO;
        volatile boolean done;
        /** Fills already scheduled for this order — so a second price push cannot double-fill it. */
        final java.util.concurrent.atomic.AtomicBoolean sweeping = new java.util.concurrent.atomic.AtomicBoolean();

        Resting(String clOrdId, String exchangeOrderId, String symbol, char side, long qty, BigDecimal price,
                boolean marketOrder) {
            this.clOrdId = clOrdId;
            this.exchangeOrderId = exchangeOrderId;
            this.symbol = symbol;
            this.side = side;
            this.qty = qty;
            this.price = price;
            this.marketOrder = marketOrder;
        }

        long leaves() {
            return qty - cumQty;
        }

        BigDecimal avgPx() {
            return cumQty == 0
                    ? BigDecimal.ZERO
                    : notional.divide(BigDecimal.valueOf(cumQty), 4, RoundingMode.HALF_UP);
        }
    }

    // ---------------------------------------------------------------- session callbacks

    @Override
    public void onCreate(SessionID sessionId) {
        log.info("Session created: {}", sessionId);
    }

    /** The OMS's session, remembered so the control endpoint can report fills after a phase change. */
    private volatile SessionID activeSession;

    @Override
    public void onLogon(SessionID sessionId) {
        activeSession = sessionId;
        log.info("LOGON  <-- {} is connected. Market is {}.", sessionId.getTargetCompID(), phase);
    }

    /** Used by the control endpoint: the session to send the opening bell's fills on. */
    public SessionID activeSession() {
        return activeSession;
    }

    @Override
    public void onLogout(SessionID sessionId) {
        log.info("LOGOUT <-- {} disconnected.", sessionId.getTargetCompID());
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        // session-level plumbing (logon/heartbeat/resend) is QuickFIX/J's business
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId) {
        // ditto — credentials are not checked: this is a dealer-side test venue, not a real one
    }

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        log.debug("--> {}", message);
    }

    @Override
    public void fromApp(Message message, SessionID sessionId) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);
            switch (msgType) {
                case "D" -> onNewOrder(message, sessionId);
                case "F" -> onCancel(message, sessionId);
                case "G" -> onReplace(message, sessionId);
                default -> log.info("Ignoring inbound MsgType={} (this venue trades orders only)", msgType);
            }
        } catch (Exception e) {
            log.error("Failed to handle inbound message: {}", e.toString(), e);
        }
    }

    // ---------------------------------------------------------------- order handling

    private void onNewOrder(Message m, SessionID sid) throws FieldNotFound {
        String clOrdId = m.getString(ClOrdID.FIELD);
        String symbol = m.isSetField(Symbol.FIELD) ? m.getString(Symbol.FIELD) : "";
        char side = m.getChar(Side.FIELD);
        long qty = (long) m.getDouble(OrderQty.FIELD);
        char ordType = m.getChar(OrdType.FIELD);
        char tif = m.isSetField(TimeInForce.FIELD) ? m.getChar(TimeInForce.FIELD) : TimeInForce.DAY;

        // MARKET orders carry no Price(44); bond yield-basis orders carry Yield(236) instead.
        BigDecimal price = (ordType == OrdType.LIMIT && m.isSetField(Price.FIELD))
                ? BigDecimal.valueOf(m.getDouble(Price.FIELD)).setScale(4, RoundingMode.HALF_UP)
                : reference(symbol);

        // The venue's session decides what may happen to this order. A closed or halted market takes
        // no orders at all; pre-open takes them and queues them; only a trading market crosses them.
        // The venue enforces this, not the client — which is the whole reason a real exchange cannot
        // be talked into trading before the bell.
        Phase p = phase;
        if (p == Phase.CLOSED || p == Phase.HALTED) {
            log.info("REJECT {} — market is {}", clOrdId, p);
            send(rejected(clOrdId, symbol, side, qty,
                    p == Phase.CLOSED ? "Market is closed" : "Market is halted"), sid);
            return;
        }

        // The configured share of orders the venue simply refuses. Rolled before the order is booked:
        // a rejected order never existed as far as the exchange is concerned, so it must not appear on
        // the book, and it must not be cancellable.
        OutcomeMix m2 = mix;
        int roll = m2.rejectPct() + m2.partialPct() > 0 ? ThreadLocalRandom.current().nextInt(100) : 100;
        if (roll < m2.rejectPct()) {
            log.debug("REJECT {} — outcome mix ({}% rejected)", clOrdId, m2.rejectPct());
            send(rejected(clOrdId, symbol, side, qty, "Rejected by exchange"), sid);
            return;
        }

        // Would this order trade if the market were open? A real venue only fills a limit order that
        // crosses the market — a BUY at 23.90 when the stock is 47.80 simply rests on the book until
        // the market comes to it.
        Resting order = new Resting(clOrdId, "EX-" + orderSeq.getAndIncrement(), symbol, side, qty, price,
                ordType == OrdType.MARKET);
        book.put(clOrdId, order);

        BigDecimal reference = reference(symbol);
        boolean marketable = isMarketable(order);

        schedule(ackDelayMs, () -> send(ack(order), sid));

        if (p == Phase.PRE_OPEN) {
            // The book builds; nothing crosses. It trades at the opening bell — see uncross().
            log.debug("QUEUE  {} {} {} x {} @ {} — pre-open, waiting for the bell",
                    clOrdId, sideName(side), qty, symbol, price);
            return;
        }

        // Per-order logging is DEBUG: this runs on the session's receive thread, and writing a line
        // to a Windows console per order is slow enough to throttle the whole venue — at which point
        // a throughput test measures how fast a cmd window scrolls, not how fast the OMS trades.
        if (!marketable) {
            log.debug("REST   {} {} {} x {} @ {} — away from the market ({}), resting on the book",
                    clOrdId, sideName(side), qty, symbol, price, reference);
            return;                                   // acked and working; it will sit here until cancelled
        }

        log.debug("NEW    {} {} {} x {} @ {} (tif={})", clOrdId, sideName(side), qty, symbol, price, tif);

        // The configured share that fills part-way and then stops. The order stays PARTIAL and working
        // — it is not cancelled and not completed — which is the state a real book leaves an order in
        // when the liquidity at its price runs out, and the state the OMS must be able to carry.
        if (roll < m2.rejectPct() + m2.partialPct()) {
            long clip = Math.max(1, qty * m2.partialFillPct() / 100);
            if (clip < qty) {
                log.debug("PARTIAL {} — outcome mix: filling {} of {} and stopping", clOrdId, clip, qty);
                schedule(ackDelayMs + partialFillDelayMs, () -> fill(order, clip, sid));
                order.sweeping.set(true);      // and no later price push may finish it off
                return;
            }
            // a 1-share order cannot be partially filled; fall through and fill it
        }

        boolean immediate = tif == TimeInForce.IMMEDIATE_OR_CANCEL || tif == TimeInForce.FILL_OR_KILL;
        if (immediate || qty <= 1) {
            schedule(ackDelayMs + partialFillDelayMs, () -> fill(order, order.leaves(), sid));
            return;
        }

        // Marketable: fill it in two clips so the OMS walks PARTIAL then FILLED.
        long firstClip = qty / 2;
        schedule(ackDelayMs + partialFillDelayMs, () -> fill(order, firstClip, sid));
        schedule(ackDelayMs + finalFillDelayMs, () -> fill(order, order.leaves(), sid));
    }

    private void onCancel(Message m, SessionID sid) throws FieldNotFound {
        String cancelClOrdId = m.getString(ClOrdID.FIELD);
        String origClOrdId = m.getString(OrigClOrdID.FIELD);
        Resting order = book.get(origClOrdId);

        if (order == null || order.done) {
            String why = order == null ? "Unknown order" : "Order is already complete";
            log.info("CXLREJ {} ({})", origClOrdId, why);
            send(cancelReject(cancelClOrdId, origClOrdId, order, why), sid);
            return;
        }

        order.done = true;
        log.info("CANCEL {} — {} of {} filled, {} pulled", origClOrdId, order.cumQty, order.qty, order.leaves());
        send(cancelled(order, cancelClOrdId), sid);
    }

    private void onReplace(Message m, SessionID sid) throws FieldNotFound {
        String newClOrdId = m.getString(ClOrdID.FIELD);
        String origClOrdId = m.getString(OrigClOrdID.FIELD);
        Resting order = book.get(origClOrdId);

        if (order == null || order.done) {
            send(cancelReject(newClOrdId, origClOrdId, order,
                    order == null ? "Unknown order" : "Order is already complete"), sid);
            return;
        }
        log.info("REPLACE {} -> {}", origClOrdId, newClOrdId);
        send(replaced(order, newClOrdId), sid);
    }

    /**
     * Book a fill and report it. Runs on the scheduler thread; a cancel that lands first flips
     * {@code done} and this becomes a no-op, so a cancelled order can never fill afterwards.
     */
    private void fill(Resting o, long qty, SessionID sid) {
        if (o.done || qty <= 0) {
            return;
        }
        o.cumQty += qty;
        o.notional = o.notional.add(o.price.multiply(BigDecimal.valueOf(qty)));
        boolean complete = o.leaves() <= 0;
        if (complete) {
            o.done = true;
        }
        log.debug("FILL   {} {} @ {} ({}/{}){}", o.clOrdId, qty, o.price, o.cumQty, o.qty,
                complete ? " COMPLETE" : " partial");
        send(filled(o, qty, complete), sid);
    }

    // ---------------------------------------------------------------- ExecutionReport builders

    /** Every report carries the tags {@code ExecutionService} needs; see the class javadoc. */
    private ExecutionReport report(Resting o, char execType, char ordStatus) {
        ExecutionReport er = new ExecutionReport();
        er.set(new OrderID(o.exchangeOrderId));          // required by the dictionary; the OMS ignores it
        er.set(new ExecID(nextExecId()));                // ≤ 28 chars, unique — becomes trade_ref
        er.set(new ExecType(execType));
        er.set(new OrdStatus(ordStatus));
        er.set(new ClOrdID(o.clOrdId));                  // the OMS correlates on this
        er.set(new Side(o.side));
        er.set(new Symbol(o.symbol));
        er.set(new OrderQty(o.qty));
        er.set(new LeavesQty(o.leaves()));
        er.set(new CumQty(o.cumQty));                    // authoritative filled qty for the OMS
        er.set(new AvgPx(o.avgPx().doubleValue()));      // authoritative average price for the OMS
        er.set(new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
        return er;
    }

    private ExecutionReport ack(Resting o) {
        // ExecType=New + OrdStatus=New: the OMS maps OrdStatus 0 -> OPEN. Sending 'A' (PendingNew)
        // here would leave the order derived from filled qty instead, and it would never show OPEN.
        return report(o, ExecType.NEW, OrdStatus.NEW);
    }

    private ExecutionReport filled(Resting o, long lastQty, boolean complete) {
        ExecutionReport er = report(o, ExecType.TRADE,
                complete ? OrdStatus.FILLED : OrdStatus.PARTIALLY_FILLED);
        er.set(new LastQty(lastQty));                    // > 0 is what books the trade + position
        er.set(new LastPx(o.price.doubleValue()));
        return er;
    }

    private ExecutionReport cancelled(Resting o, String cancelClOrdId) {
        ExecutionReport er = report(o, ExecType.CANCELED, OrdStatus.CANCELED);
        er.set(new ClOrdID(cancelClOrdId));
        er.set(new OrigClOrdID(o.clOrdId));              // the OMS can only find the order by this
        er.set(new LeavesQty(0));
        return er;
    }

    private ExecutionReport replaced(Resting o, String newClOrdId) {
        // OrdStatus=New rather than Replaced('5'): the OMS has no REPLACED state and would
        // otherwise derive the status from filled qty.
        ExecutionReport er = report(o, ExecType.REPLACED, OrdStatus.NEW);
        er.set(new ClOrdID(newClOrdId));
        er.set(new OrigClOrdID(o.clOrdId));
        return er;
    }

    /** The venue refusing an order outright. OrdStatus 8 is what the OMS maps to REJECTED. */
    private ExecutionReport rejected(String clOrdId, String symbol, char side, long qty, String why) {
        ExecutionReport er = new ExecutionReport();
        er.set(new OrderID("NONE"));
        er.set(new ExecID(nextExecId()));
        er.set(new ExecType(ExecType.REJECTED));
        er.set(new OrdStatus(OrdStatus.REJECTED));
        er.set(new ClOrdID(clOrdId));
        er.set(new Side(side));
        er.set(new Symbol(symbol));
        er.set(new OrderQty(qty));
        er.set(new LeavesQty(0));
        er.set(new CumQty(0));
        er.set(new AvgPx(0));
        er.set(new Text(why));                     // the OMS reads Text on a reject as the reason
        er.set(new TransactTime(LocalDateTime.now(ZoneOffset.UTC)));
        return er;
    }

    private OrderCancelReject cancelReject(String clOrdId, String origClOrdId, Resting o, String reason) {
        OrderCancelReject rej = new OrderCancelReject();
        rej.set(new OrderID(o == null ? "NONE" : o.exchangeOrderId));
        rej.set(new ClOrdID(clOrdId));
        rej.set(new OrigClOrdID(origClOrdId));           // the OMS's only handle on the order
        rej.set(new OrdStatus(o == null ? OrdStatus.REJECTED : OrdStatus.FILLED));
        rej.set(new CxlRejResponseTo(CxlRejResponseTo.ORDER_CANCEL_REQUEST));
        rej.set(new CxlRejReason(o == null
                ? CxlRejReason.UNKNOWN_ORDER
                : CxlRejReason.TOO_LATE_TO_CANCEL));
        rej.set(new Text(reason));
        return rej;
    }

    /** e.g. {@code E-1752403000-7} — unique across restarts, and short enough for trade_ref. */
    private String nextExecId() {
        return "E-" + execIdEpoch + "-" + execSeq.getAndIncrement();
    }

    // ---------------------------------------------------------------- plumbing

    private void schedule(long delayMs, Runnable task) {
        scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS);
    }

    private void send(Message m, SessionID sid) {
        try {
            if (!Session.sendToTarget(m, sid)) {
                log.warn("Could not send {} — is the OMS still logged on?", m.getHeader().getString(MsgType.FIELD));
            }
        } catch (SessionNotFound e) {
            log.warn("Session {} has gone away; dropping message", sid);
        } catch (FieldNotFound e) {
            log.error("Malformed outbound message: {}", e.toString(), e);
        }
    }

    private static String sideName(char side) {
        return side == Side.SELL ? "SELL" : "BUY";
    }

    /** Stop the matcher thread. Called on shutdown; also keeps tests from leaking threads. */
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
