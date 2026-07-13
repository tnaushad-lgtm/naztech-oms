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
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "local-exchange-matcher");
                t.setDaemon(true);
                return t;
            });

    private final long ackDelayMs;
    private final long partialFillDelayMs;
    private final long finalFillDelayMs;

    public LocalExchange(long ackDelayMs, long partialFillDelayMs, long finalFillDelayMs) {
        this.ackDelayMs = ackDelayMs;
        this.partialFillDelayMs = partialFillDelayMs;
        this.finalFillDelayMs = finalFillDelayMs;
    }

    /** A working order the exchange is holding on behalf of the OMS. */
    private static final class Resting {
        final String clOrdId;
        final String exchangeOrderId;
        final String symbol;
        final char side;
        final long qty;
        final BigDecimal price;
        long cumQty;
        BigDecimal notional = BigDecimal.ZERO;
        volatile boolean done;

        Resting(String clOrdId, String exchangeOrderId, String symbol, char side, long qty, BigDecimal price) {
            this.clOrdId = clOrdId;
            this.exchangeOrderId = exchangeOrderId;
            this.symbol = symbol;
            this.side = side;
            this.qty = qty;
            this.price = price;
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

    @Override
    public void onLogon(SessionID sessionId) {
        log.info("LOGON  <-- {} is connected. Orders will be accepted and filled.", sessionId.getTargetCompID());
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
                : ReferencePrices.of(symbol);

        Resting order = new Resting(clOrdId, "EX-" + orderSeq.getAndIncrement(), symbol, side, qty, price);
        book.put(clOrdId, order);

        schedule(ackDelayMs, () -> send(ack(order), sid));

        // Would this order actually trade? A real venue only fills a limit order that crosses the
        // market — a BUY at 23.90 when the stock is 47.80 simply rests on the book until the market
        // comes to it. Filling everything regardless (as this did originally) is not just unrealistic:
        // it means a "resting" order still books a trade, moves a position and marks the tape, which
        // quietly triples the write load of every order placed.
        BigDecimal reference = ReferencePrices.of(symbol);
        boolean marketable = ordType == OrdType.MARKET
                || (side == Side.BUY ? price.compareTo(reference) >= 0 : price.compareTo(reference) <= 0);

        // Per-order logging is DEBUG: this runs on the session's receive thread, and writing a line
        // to a Windows console per order is slow enough to throttle the whole venue — at which point
        // a throughput test measures how fast a cmd window scrolls, not how fast the OMS trades.
        if (!marketable) {
            log.debug("REST   {} {} {} x {} @ {} — away from the market ({}), resting on the book",
                    clOrdId, sideName(side), qty, symbol, price, reference);
            return;                                   // acked and working; it will sit here until cancelled
        }

        log.debug("NEW    {} {} {} x {} @ {} (tif={})", clOrdId, sideName(side), qty, symbol, price, tif);

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
