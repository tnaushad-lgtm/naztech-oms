package com.naztech.oms.exchange.fixsim;

import com.naztech.oms.exchange.fix.AsyncLogFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Acceptor;
import quickfix.DefaultMessageFactory;
import quickfix.FileLogFactory;
import quickfix.LogFactory;
import quickfix.MemoryStoreFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketAcceptor;

/**
 * Runs {@link LocalExchange} as a standalone FIX acceptor — the "exchange" the OMS dials.
 *
 * <p>It is deliberately NOT a Spring bean: it must run as its own process (started by
 * {@code start-local-exchange.bat}) so it survives backend restarts and can be watched
 * independently, exactly as a real venue would be.
 *
 * <p>Defaults mirror what the OMS expects from {@code application.yml}, with the CompIDs reversed
 * as they must be for the far side of the session. Override with system properties:
 * {@code -Dexchange.port=15000 -Dexchange.sender=FIXSIMDEMO -Dexchange.target=TareqN}.
 */
public final class LocalExchangeMain {

    private static final Logger log = LoggerFactory.getLogger(LocalExchangeMain.class);

    public static final int DEFAULT_PORT = 15000;
    public static final String DEFAULT_SENDER = "FIXSIMDEMO";   // the exchange's identity
    public static final String DEFAULT_TARGET = "TareqN";       // the OMS's identity

    private LocalExchangeMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("exchange.port", DEFAULT_PORT);
        String sender = System.getProperty("exchange.sender", DEFAULT_SENDER);
        String target = System.getProperty("exchange.target", DEFAULT_TARGET);

        LocalExchange exchange = new LocalExchange(
                Long.getLong("exchange.ack-ms", 250L),
                Long.getLong("exchange.partial-fill-ms", 1200L),
                Long.getLong("exchange.final-fill-ms", 2800L));

        Acceptor acceptor = acceptor(exchange, settings(port, sender, target, "./exchangelog"));
        acceptor.start();

        log.info("");
        log.info("=========================================================");
        log.info(" LOCAL EXCHANGE is listening on port {}", port);
        log.info("   session : FIXT.1.1 / FIX.5.0SP1   {} <- {}", sender, target);
        log.info("   waiting for the OMS to log on. Orders will be acked,");
        log.info("   partially filled, then completed.");
        log.info("   Ctrl+C to stop.");
        log.info("=========================================================");
        log.info("");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting the local exchange down...");
            acceptor.stop();
            exchange.shutdown();
        }));

        Thread.currentThread().join();
    }

    /** Builds the acceptor. Shared with the tests so they exercise the very same wiring. */
    public static Acceptor acceptor(LocalExchange exchange, SessionSettings settings) throws Exception {
        MessageStoreFactory store = new MemoryStoreFactory();
        // Same exchangelog/ files, written off the session thread: a synchronous disk write per
        // message would throttle the venue and, with it, every order the OMS is trying to send.
        LogFactory logs = new AsyncLogFactory(new FileLogFactory(settings));
        MessageFactory messages = new DefaultMessageFactory();
        return new SocketAcceptor(exchange, store, settings, logs, messages);
    }

    /**
     * Session settings for the exchange side. Mirrors the OMS initiator, with 49/56 swapped.
     *
     * <p>{@code ResetOnLogon=Y} matters: the OMS logs on with ResetSeqNumFlag(141)=Y and an
     * in-memory store, so it always starts at sequence 1. Without this, the exchange would expect
     * a higher sequence after a restart and drop the logon — which is precisely how the hosted
     * FIXSIM session failed.
     */
    public static SessionSettings settings(int port, String sender, String target, String logPath) {
        SessionSettings s = new SessionSettings();
        s.setString("ConnectionType", "acceptor");
        s.setString("SocketAcceptPort", String.valueOf(port));
        s.setString("FileLogPath", logPath);

        SessionID sid = new SessionID("FIXT.1.1", sender, target);
        s.setString(sid, "BeginString", "FIXT.1.1");
        s.setString(sid, "DefaultApplVerID", "FIX.5.0SP1");
        s.setString(sid, "SenderCompID", sender);
        s.setString(sid, "TargetCompID", target);
        s.setString(sid, "SocketAcceptPort", String.valueOf(port));
        s.setString(sid, "StartTime", "00:00:00");
        s.setString(sid, "EndTime", "00:00:00");          // equal times = a 24-hour session
        s.setString(sid, "UseDataDictionary", "Y");
        s.setString(sid, "TransportDataDictionary", "FIXT11.xml");
        s.setString(sid, "AppDataDictionary", "FIX50SP1.xml");
        s.setString(sid, "ResetOnLogon", "Y");
        return s;
    }
}
