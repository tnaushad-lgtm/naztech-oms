package com.naztech.oms.exchange.fixsim;

import com.naztech.oms.exchange.fix.AsyncLogFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Acceptor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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
    public static final int DEFAULT_CONTROL_PORT = 15001;   // the OMS pushes the trading phase here
    public static final String DEFAULT_SENDER = "FIXSIMDEMO";   // the exchange's identity
    public static final String DEFAULT_TARGET = "TareqN";       // the OMS's identity

    private LocalExchangeMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.getInteger("exchange.port", DEFAULT_PORT);
        int controlPort = Integer.getInteger("exchange.control-port", DEFAULT_CONTROL_PORT);
        String sender = System.getProperty("exchange.sender", DEFAULT_SENDER);
        String target = System.getProperty("exchange.target", DEFAULT_TARGET);

        LocalExchange exchange = new LocalExchange(
                Long.getLong("exchange.ack-ms", 250L),
                Long.getLong("exchange.partial-fill-ms", 1200L),
                Long.getLong("exchange.final-fill-ms", 2800L));

        Acceptor acceptor = acceptor(exchange, settings(port, sender, target, "./exchangelog"));
        acceptor.start();
        startControlEndpoint(exchange, controlPort);

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

    /**
     * A tiny control endpoint so the OMS can tell the venue what the trading phase is:
     * {@code POST /session?phase=PRE_OPEN|OPEN|HALTED|CLOSED}, and {@code GET /status}.
     *
     * <p>A real exchange runs its own calendar and the OMS simply obeys. Here the OMS admin drives
     * the session, so the venue has to be told — and it must be <em>told</em>, not trusted to infer:
     * before this existed, the exchange filled orders during pre-open because it had no idea the
     * market had not opened yet.
     *
     * <p>The JDK's own HTTP server, so the exchange keeps its zero dependencies.
     */
    private static void startControlEndpoint(LocalExchange exchange, int port) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

        http.createContext("/session", ex -> {
            String query = ex.getRequestURI().getQuery();
            String phase = query == null ? "" : query.replaceFirst("^.*phase=([A-Za-z_]+).*$", "$1");
            String body;
            int code;
            try {
                LocalExchange.Phase next = LocalExchange.Phase.valueOf(phase.toUpperCase());
                exchange.setPhase(next, exchange.activeSession());
                body = "{\"ok\":true,\"phase\":\"" + next + "\"}";
                code = 200;
            } catch (Exception e) {
                body = "{\"ok\":false,\"error\":\"phase must be CLOSED, PRE_OPEN, OPEN or HALTED\"}";
                code = 400;
            }
            respond(ex, code, body);
        });

        // The OMS pushes its live market: one "SYMBOL=PRICE" per line. The venue prices orders off
        // this, so what the trader sees and what the exchange trades are the same market.
        http.createContext("/prices", ex -> {
            Map<String, BigDecimal> prices = new HashMap<>();
            try (var in = ex.getRequestBody()) {
                for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                    int eq = line.indexOf('=');
                    if (eq <= 0) {
                        continue;
                    }
                    try {
                        prices.put(line.substring(0, eq).trim().toUpperCase(),
                                new BigDecimal(line.substring(eq + 1).trim()));
                    } catch (NumberFormatException ignored) {
                        // one bad line must not throw away the whole market
                    }
                }
            }
            exchange.setPrices(prices);
            respond(ex, 200, "{\"ok\":true,\"priced\":" + exchange.pricedInstruments() + "}");
        });

        // The outcome mix for a performance run: what share of orders this venue rejects, and what
        // share it leaves half-filled. POST /config?rejectPct=10&partialPct=20&partialFillPct=50
        http.createContext("/config", ex -> {
            String q = ex.getRequestURI().getQuery();
            exchange.setOutcomeMix(new LocalExchange.OutcomeMix(
                    intParam(q, "rejectPct", 0),
                    intParam(q, "partialPct", 0),
                    intParam(q, "partialFillPct", 50)));
            respond(ex, 200, "{\"ok\":true," + mixJson(exchange) + "}");
        });

        http.createContext("/status", ex ->
                respond(ex, 200, "{\"phase\":\"" + exchange.phase()
                        + "\",\"priced\":" + exchange.pricedInstruments()
                        + "," + mixJson(exchange) + "}"));

        http.setExecutor(null);
        http.start();
        log.info("Exchange control endpoint on http://127.0.0.1:{}/session?phase=OPEN", port);
    }

    private static String mixJson(LocalExchange exchange) {
        LocalExchange.OutcomeMix m = exchange.outcomeMix();
        return "\"mix\":{\"rejectPct\":" + m.rejectPct()
                + ",\"partialPct\":" + m.partialPct()
                + ",\"partialFillPct\":" + m.partialFillPct() + "}";
    }

    /** One query parameter, or the default if it is absent or not a number. */
    static int intParam(String query, String name, int fallback) {
        if (query == null) {
            return fallback;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return Integer.parseInt(pair.substring(eq + 1).trim());
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, out.length);
        try (var os = ex.getResponseBody()) {
            os.write(out);
        }
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
