package com.naztech.oms.exchange.itch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A live ITCH feed over SoupBinTCP — the reliable, session-based transport DSE offers alongside
 * multicast, and the one to use from a co-located OMS that cannot join a multicast group.
 *
 * <p>The socket is read on its own thread and decoded messages are queued; {@link #poll()} drains
 * the queue, so the {@link ItchGateway}'s existing pull model is unchanged. That inversion is the
 * whole reason the {@link ItchSource} seam exists: the gateway, the market-data service, the books and
 * the UI never learn where the bytes came from.
 *
 * <h2>Recovery</h2>
 * SoupBinTCP has no "resend packet N" — the sequence is implicit in the stream. Recovery is therefore
 * a reconnect: we log back in asking for the sequence we still want ({@link ItchSequencer#expected()})
 * and the server replays from there. So a dropped connection is not a disaster and a missed message is
 * not permanent; both are the same event, handled the same way, and the book is whole afterwards.
 *
 * <p>If the endpoint is not there — no DSE feed on this laptop, which is the usual case — the
 * constructor throws and {@code ItchGateway} falls back to the simulator. That is deliberate: a
 * market-data feed that silently produces nothing is indistinguishable from a market with no trades in
 * it, and the difference matters enormously.
 */
public final class SoupBinTcpSource implements ItchSource {

    private static final Logger log = LoggerFactory.getLogger(SoupBinTcpSource.class);
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int MAX_QUEUED = 100_000;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String session;

    private final Queue<Itch.Msg> inbound = new ConcurrentLinkedQueue<>();
    private final ItchSequencer sequencer;
    private final AtomicBoolean running = new AtomicBoolean(true);

    private volatile Socket socket;
    private volatile Thread reader;
    private volatile boolean endOfSession;
    /** Set when the venue's sequence rolled back (restart); the gateway clears its books and unsets it. */
    private volatile boolean sessionReset;
    /** When the last packet of any kind arrived — the true liveness signal, since heartbeats count. */
    private volatile long lastPacketAt;

    public SoupBinTcpSource(String host, int port, String username, String password, String session)
            throws IOException {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.session = session;
        this.sequencer = new ItchSequencer(0, 50_000);

        connect();
        Thread t = new Thread(this::readLoop, "itch-soupbin");
        t.setDaemon(true);
        t.start();
        this.reader = t;
    }

    /**
     * Log in.
     *
     * <p>On the <b>first</b> connect we request sequence <b>1</b> — a full replay of the day. That is
     * not optional: SoupBinTCP has no separate snapshot service, so the day's opening directory ([R]
     * messages) and the current resting book are only obtainable by replaying from the start. Requesting
     * 0 ("only messages from now") leaves us with an empty book in a quiet market — which is exactly the
     * "ITCH connects but no data" symptom in the integration guide's troubleshooting table.
     *
     * <p>On a <b>reconnect</b> we ask for {@code sequencer.expected()} — the exact next message we are
     * missing — so recovery replays only the gap, in order.
     */
    private void connect() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        s.setTcpNoDelay(true);           // market data is latency, not throughput
        socket = s;

        long resumeAt = Math.max(1, sequencer.expected());   // first connect: 1 = full replay
        OutputStream out = s.getOutputStream();
        out.write(SoupBinTcp.loginRequest(username, password, session, resumeAt));
        out.flush();
        log.info("ITCH SoupBinTCP: connected to {}:{} as '{}', requesting from sequence {}",
                host, port, username, resumeAt);
    }

    private void readLoop() {
        while (running.get() && !endOfSession) {
            try (DataInputStream in = new DataInputStream(socket.getInputStream())) {
                long seq = 0;                                    // set by Login Accepted
                while (running.get()) {
                    SoupBinTcp.Packet p = SoupBinTcp.decode(in);
                    lastPacketAt = System.currentTimeMillis();   // ANY packet — data OR heartbeat — is life
                    switch (p.type()) {
                        case SoupBinTcp.LOGIN_ACCEPTED -> {
                            SoupBinTcp.LoginAccepted la = SoupBinTcp.parseLoginAccepted(p.payload());
                            seq = la.nextSequence();
                            // The venue restarted: same feed, but its sequence has rolled back below
                            // where we were. Resuming at our old (higher) number would ask for messages
                            // that no longer exist and the feed would sit silent forever — which is
                            // exactly the stall we hit when nFIX was bounced. Adopt the server's new
                            // sequence and rebuild the book from the replay it is about to send.
                            if (seq < sequencer.expected()) {
                                log.warn("ITCH SoupBinTCP: session '{}' rolled back to {} (we were at {}) "
                                                + "— venue restarted; re-syncing the book from scratch",
                                        la.session(), seq, sequencer.expected());
                                sequencer.resyncTo(seq);
                                sessionReset = true;               // tell the gateway to clear stale books
                            }
                            log.info("ITCH SoupBinTCP: logged in to session '{}', stream starts at {}",
                                    la.session(), seq);
                        }
                        case SoupBinTcp.LOGIN_REJECTED -> {
                            log.error("ITCH SoupBinTCP: login rejected — the feed will not start");
                            running.set(false);
                        }
                        case SoupBinTcp.SEQUENCED_DATA -> {
                            // The sequence is implicit: this packet is the next one, always.
                            deliver(seq++, p.payload());
                        }
                        case SoupBinTcp.END_OF_SESSION -> {
                            log.info("ITCH SoupBinTCP: end of session — the trading day is over");
                            endOfSession = true;
                            running.set(false);
                        }
                        case SoupBinTcp.SERVER_HEARTBEAT, SoupBinTcp.DEBUG -> { /* nothing to do */ }
                        default -> log.debug("ITCH SoupBinTCP: ignoring packet type '{}'", p.type());
                    }
                }
            } catch (EOFException e) {
                if (running.get()) {
                    reconnect("the server closed the connection");
                }
            } catch (IOException e) {
                if (running.get()) {
                    reconnect(e.toString());
                }
            }
        }
    }

    /**
     * The connection dropped. Reconnecting is not a workaround here — it <em>is</em> the protocol's
     * recovery: we log back in at the sequence we still want and the server replays the gap.
     */
    private void reconnect(String why) {
        log.warn("ITCH SoupBinTCP: {} — reconnecting to replay from {}", why, sequencer.expected());
        closeSocket();
        for (int attempt = 1; running.get() && attempt <= 10; attempt++) {
            try {
                Thread.sleep(Math.min(1000L * attempt, 5_000L));
                connect();
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running.set(false);
            } catch (IOException e) {
                log.warn("ITCH SoupBinTCP: reconnect attempt {} failed ({})", attempt, e.toString());
            }
        }
        log.error("ITCH SoupBinTCP: could not reconnect — the feed is down");
        running.set(false);
    }

    /** One ITCH message, in sequence. The sequencer is what decides whether it may be applied yet. */
    private void deliver(long seq, byte[] payload) {
        ItchSequencer.Delivery d = sequencer.accept(seq, List.of(payload));
        for (byte[] frame : d.ready()) {
            if (inbound.size() >= MAX_QUEUED) {
                log.error("ITCH SoupBinTCP: {} messages queued and the gateway is not draining them — dropping",
                        MAX_QUEUED);
                return;
            }
            try {
                inbound.add(ItchCodec.decode(frame));
            } catch (Exception e) {
                log.warn("ITCH SoupBinTCP: undecodable message dropped: {}", e.toString());
            }
        }
    }

    @Override
    public List<Itch.Msg> open() {
        return List.of();     // the feed itself sends the day-start sequence; we do not invent one
    }

    @Override
    public List<Itch.Msg> poll() {
        List<Itch.Msg> batch = new ArrayList<>();
        Itch.Msg m;
        while ((m = inbound.poll()) != null) {
            batch.add(m);
        }
        return batch;
    }

    @Override
    public boolean isActive() {
        return running.get() && !endOfSession;
    }

    @Override
    public String name() {
        return "soupbintcp:" + host + ":" + port;
    }

    /** Feed health — gaps seen, gaps recovered, messages given up on. Surfaced on the connectivity page. */
    public ItchSequencer.Health health() {
        return sequencer.health();
    }

    /**
     * Milliseconds since the last packet arrived — the honest liveness signal.
     *
     * <p>A quiet market sends no order flow but still sends heartbeats (~1/s), so "is the message count
     * rising?" mistakes a calm market for a dead feed. "Did anything at all arrive recently?" does not.
     * Returns a large number if we have never received a packet.
     */
    public long msSinceLastPacket() {
        return lastPacketAt == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastPacketAt;
    }

    /** True once (per restart) after the venue's sequence rolled back — the gateway clears its books and consumes it. */
    public boolean consumeSessionReset() {
        if (sessionReset) {
            sessionReset = false;
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        running.set(false);
        Thread t = reader;
        if (t != null) {
            t.interrupt();
        }
        closeSocket();
    }

    private void closeSocket() {
        Socket s = socket;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing a socket that is already gone is not an error worth reporting
            }
        }
    }
}
