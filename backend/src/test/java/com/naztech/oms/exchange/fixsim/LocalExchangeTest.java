package com.naztech.oms.exchange.fixsim;

import com.naztech.oms.entity.OmsOrder;
import com.naztech.oms.entity.Security;
import com.naztech.oms.exchange.fix.FixMessageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import quickfix.Acceptor;
import quickfix.Application;
import quickfix.DefaultMessageFactory;
import quickfix.Initiator;
import quickfix.MemoryStoreFactory;
import quickfix.Message;
import quickfix.ScreenLogFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;
import quickfix.field.AvgPx;
import quickfix.field.ClOrdID;
import quickfix.field.CumQty;
import quickfix.field.ExecID;
import quickfix.field.ExecType;
import quickfix.field.LastPx;
import quickfix.field.LastQty;
import quickfix.field.LeavesQty;
import quickfix.field.MsgType;
import quickfix.field.OrdStatus;
import quickfix.field.OrigClOrdID;

import java.math.BigDecimal;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the {@link LocalExchange} acceptor and a QuickFIX/J initiator standing in for the OMS, then
 * drives a real FIX session between them over a loopback socket — logon, NewOrderSingle, fills,
 * cancel. The orders are built by the OMS's own {@link FixMessageFactory}, so this covers the true
 * wire format rather than a test-only approximation.
 *
 * <p>No Spring context and no database: the exchange is a standalone counterparty by design, which
 * keeps this fast and lets it verify the FIX plumbing that {@code mvn test} otherwise never reaches.
 */
class LocalExchangeTest {

    private Acceptor acceptor;
    private Initiator initiator;
    private LocalExchange exchange;

    @AfterEach
    void tearDown() {
        if (initiator != null) {
            initiator.stop(true);
        }
        if (acceptor != null) {
            acceptor.stop(true);
        }
        if (exchange != null) {
            exchange.shutdown();
        }
    }

    @Test
    @DisplayName("a limit order is acked, partially filled, then completed — with the tags the OMS needs")
    void fillsAnOrderThroughItsWholeLifecycle() throws Exception {
        Oms oms = connect(50L, 150L, 400L);

        OmsOrder order = order("ORD-1752400000123-457", "BUY", 1000L, new BigDecimal("112.5000"));
        Session.sendToTarget(new FixMessageFactory().newOrderSingle(order, security("BEXIMCO")), oms.sessionId);

        Message ack = oms.next();
        assertThat(ack.getHeader().getString(MsgType.FIELD)).isEqualTo("8");
        assertThat(ack.getChar(ExecType.FIELD)).isEqualTo(ExecType.NEW);
        assertThat(ack.getChar(OrdStatus.FIELD)).isEqualTo(OrdStatus.NEW);       // -> OMS status OPEN
        assertThat(ack.getString(ClOrdID.FIELD)).isEqualTo(order.getOrderRef()); // correlation key
        assertThat(ack.getDouble(CumQty.FIELD)).isZero();
        assertThat(ack.getDouble(LeavesQty.FIELD)).isEqualTo(1000.0);

        Message partial = oms.next();
        assertThat(partial.getChar(ExecType.FIELD)).isEqualTo(ExecType.TRADE);
        assertThat(partial.getChar(OrdStatus.FIELD)).isEqualTo(OrdStatus.PARTIALLY_FILLED); // -> PARTIAL
        assertThat(partial.getDouble(LastQty.FIELD)).isEqualTo(500.0);   // books the trade + position
        assertThat(partial.getDouble(LastPx.FIELD)).isEqualTo(112.5);
        assertThat(partial.getDouble(CumQty.FIELD)).isEqualTo(500.0);    // authoritative filled qty
        assertThat(partial.getDouble(AvgPx.FIELD)).isEqualTo(112.5);     // authoritative avg price
        assertThat(partial.getDouble(LeavesQty.FIELD)).isEqualTo(500.0);

        Message complete = oms.next();
        assertThat(complete.getChar(OrdStatus.FIELD)).isEqualTo(OrdStatus.FILLED);  // -> FILLED
        assertThat(complete.getDouble(LastQty.FIELD)).isEqualTo(500.0);
        assertThat(complete.getDouble(CumQty.FIELD)).isEqualTo(1000.0);
        assertThat(complete.getDouble(AvgPx.FIELD)).isEqualTo(112.5);
        assertThat(complete.getDouble(LeavesQty.FIELD)).isZero();

        // trade_ref is "FIX-" + ExecID in a VARCHAR(32) UNIQUE column: too long or repeated and the
        // fill is rolled back inside the OMS with only an ERROR line to show for it.
        Set<String> execIds = new HashSet<>();
        for (Message er : List.of(ack, partial, complete)) {
            String execId = er.getString(ExecID.FIELD);
            assertThat(execId).hasSizeLessThanOrEqualTo(28);
            assertThat(execIds.add(execId)).as("ExecID %s is reused", execId).isTrue();
        }
    }

    @Test
    @DisplayName("a limit order away from the market rests: acked, but no fill until it is crossed")
    void restsANonMarketableLimitOrder() throws Exception {
        Oms oms = connect(50L, 150L, 400L);

        // BRACBANK is 47.80; a bid at 23.90 is nowhere near it, so a real venue would rest it.
        OmsOrder order = order("ORD-1752400002222-333", "BUY", 1000L, new BigDecimal("23.9000"));
        Session.sendToTarget(new FixMessageFactory().newOrderSingle(order, security("BRACBANK")), oms.sessionId);

        Message ack = oms.next();
        assertThat(ack.getChar(OrdStatus.FIELD)).isEqualTo(OrdStatus.NEW);          // -> OPEN, working
        assertThat(ack.getDouble(LeavesQty.FIELD)).isEqualTo(1000.0);

        // Nothing further: no fill, so no trade is booked, no position moves, no tape is marked.
        assertThat(oms.inbound.poll(2, TimeUnit.SECONDS))
                .as("an order priced away from the market must not fill")
                .isNull();
    }

    @Test
    @DisplayName("a market order fills at the reference price even though it carries no Price(44)")
    void fillsAMarketOrderAtTheReferencePrice() throws Exception {
        Oms oms = connect(50L, 150L, 400L);

        OmsOrder order = order("ORD-1752400000999-101", "SELL", 1L, null);
        order.setOrderType("MARKET");
        Session.sendToTarget(new FixMessageFactory().newOrderSingle(order, security("GP")), oms.sessionId);

        oms.next();                       // ack
        Message fill = oms.next();
        assertThat(fill.getChar(OrdStatus.FIELD)).isEqualTo(OrdStatus.FILLED);
        assertThat(fill.getDouble(LastPx.FIELD)).isEqualTo(305.40);   // GP's seeded LTP
        assertThat(fill.getDouble(LastQty.FIELD)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("a cancel pulls the order and reports OrigClOrdID, which is the OMS's only handle on it")
    void cancelsARestingOrder() throws Exception {
        Oms oms = connect(50L, 30_000L, 60_000L);   // fills far enough out that the cancel wins

        OmsOrder order = order("ORD-1752400001234-222", "BUY", 500L, new BigDecimal("47.8000"));
        Security sec = security("BRACBANK");
        Session.sendToTarget(new FixMessageFactory().newOrderSingle(order, sec), oms.sessionId);
        oms.next();                       // ack

        String cxlId = "CXL-1-" + order.getOrderRef();
        Session.sendToTarget(new FixMessageFactory().cancel(order, sec, cxlId), oms.sessionId);

        Message cancelled = oms.next();
        assertThat(cancelled.getChar(OrdStatus.FIELD)).isEqualTo(OrdStatus.CANCELED);   // -> CANCELLED
        assertThat(cancelled.getString(ClOrdID.FIELD)).isEqualTo(cxlId);
        // The cancel's own CXL- id matches no order_ref in the OMS, so without tag 41 the report
        // would be silently dropped.
        assertThat(cancelled.getString(OrigClOrdID.FIELD)).isEqualTo(order.getOrderRef());
        assertThat(cancelled.getDouble(LeavesQty.FIELD)).isZero();
    }

    @Test
    @DisplayName("cancelling an unknown order is rejected, not silently swallowed")
    void rejectsACancelForAnUnknownOrder() throws Exception {
        Oms oms = connect(50L, 150L, 400L);

        OmsOrder ghost = order("ORD-0000000000000-000", "BUY", 100L, new BigDecimal("10.0000"));
        Session.sendToTarget(
                new FixMessageFactory().cancel(ghost, security("GP"), "CXL-1-" + ghost.getOrderRef()),
                oms.sessionId);

        Message reject = oms.next();
        assertThat(reject.getHeader().getString(MsgType.FIELD)).isEqualTo("9");   // OrderCancelReject
        assertThat(reject.getString(OrigClOrdID.FIELD)).isEqualTo(ghost.getOrderRef());
    }

    // ---------------------------------------------------------------- harness

    /** Starts the exchange on a free port and an initiator that plays the OMS, then waits for logon. */
    private Oms connect(long ackMs, long partialMs, long finalMs) throws Exception {
        int port = freePort();
        exchange = new LocalExchange(ackMs, partialMs, finalMs);
        acceptor = LocalExchangeMain.acceptor(
                exchange,
                LocalExchangeMain.settings(port, LocalExchangeMain.DEFAULT_SENDER,
                        LocalExchangeMain.DEFAULT_TARGET, "target/exchangelog-test"));
        acceptor.start();

        Oms oms = new Oms();
        initiator = new SocketInitiator(oms, new MemoryStoreFactory(), omsSettings(port),
                new ScreenLogFactory(false, false, false), new DefaultMessageFactory());
        initiator.start();

        assertThat(oms.loggedOn.await(20, TimeUnit.SECONDS))
                .as("the OMS never logged on to the local exchange")
                .isTrue();
        return oms;
    }

    /** The OMS side of the session — mirrors FixEngineConfig, with the CompIDs the other way round. */
    private static SessionSettings omsSettings(int port) {
        SessionSettings s = new SessionSettings();
        s.setString("ConnectionType", "initiator");
        s.setString("ReconnectInterval", "1");

        SessionID sid = new SessionID("FIXT.1.1", LocalExchangeMain.DEFAULT_TARGET,
                LocalExchangeMain.DEFAULT_SENDER);
        s.setString(sid, "BeginString", "FIXT.1.1");
        s.setString(sid, "DefaultApplVerID", "FIX.5.0SP1");
        s.setString(sid, "SenderCompID", LocalExchangeMain.DEFAULT_TARGET);
        s.setString(sid, "TargetCompID", LocalExchangeMain.DEFAULT_SENDER);
        s.setString(sid, "SocketConnectHost", "127.0.0.1");
        s.setString(sid, "SocketConnectPort", String.valueOf(port));
        s.setString(sid, "HeartBtInt", "30");
        s.setString(sid, "StartTime", "00:00:00");
        s.setString(sid, "EndTime", "00:00:00");
        s.setString(sid, "UseDataDictionary", "Y");
        s.setString(sid, "TransportDataDictionary", "FIXT11.xml");
        s.setString(sid, "AppDataDictionary", "FIX50SP1.xml");
        s.setString(sid, "ResetOnLogon", "Y");
        return s;
    }

    /** Stands in for OmsFixApplication: logs on, then queues whatever the exchange sends back. */
    private static final class Oms implements Application {
        final CountDownLatch loggedOn = new CountDownLatch(1);
        final BlockingQueue<Message> inbound = new LinkedBlockingQueue<>();
        final List<Message> received = new ArrayList<>();
        volatile SessionID sessionId;

        /** Blocks until the next application message arrives, so no test needs a sleep. */
        Message next() throws InterruptedException {
            Message m = inbound.poll(20, TimeUnit.SECONDS);
            assertThat(m).as("the exchange sent nothing back within 20s").isNotNull();
            received.add(m);
            return m;
        }

        @Override
        public void onCreate(SessionID sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void onLogon(SessionID sessionId) {
            this.sessionId = sessionId;
            loggedOn.countDown();
        }

        @Override
        public void onLogout(SessionID sessionId) {
        }

        @Override
        public void toAdmin(Message message, SessionID sessionId) {
        }

        @Override
        public void fromAdmin(Message message, SessionID sessionId) {
        }

        @Override
        public void toApp(Message message, SessionID sessionId) {
        }

        @Override
        public void fromApp(Message message, SessionID sessionId) {
            inbound.add(message);
        }
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static OmsOrder order(String ref, String side, long qty, BigDecimal price) {
        OmsOrder o = new OmsOrder();
        o.setOrderRef(ref);
        o.setSide(side);
        o.setOrderType("LIMIT");
        o.setQuantity(qty);
        o.setPrice(price);
        o.setValidity("DAY");
        o.setAccountId(1L);
        return o;
    }

    private static Security security(String symbol) {
        Security s = new Security();
        s.setSymbol(symbol);
        return s;
    }
}
