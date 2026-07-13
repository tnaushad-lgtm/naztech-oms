package com.naztech.oms.exchange.itch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A live ITCH feed over MoldUDP64 multicast — how a real exchange ships market data to everybody at
 * once, and how DSE ships its X-Stream feed.
 *
 * <p>Multicast is fast because it is not reliable. Nothing acknowledges, nothing retries, and a switch
 * under load drops packets without telling anyone. So this class does the two things a multicast
 * consumer must do, and which a TCP consumer never has to think about:
 *
 * <ol>
 *   <li><b>Notice.</b> Every packet is numbered. {@link ItchSequencer} holds anything that arrives
 *       early rather than applying it out of order, because an <em>Add</em> applied before the
 *       <em>Delete</em> that should have preceded it leaves liquidity on the book that does not exist.</li>
 *   <li><b>Ask again.</b> When a gap opens, send a retransmission request — unicast, to the rewind
 *       server — naming the sequence and count we are missing. The replayed messages arrive on the same
 *       socket and the sequencer slots them in.</li>
 * </ol>
 *
 * <p>Heartbeats matter here in a way they do not on TCP: during a lull there is no next message to
 * reveal that one went missing, so the feed sends an empty packet carrying the sequence you <em>should</em>
 * be at. That is often how a gap is discovered at all.
 *
 * <p>With no rewind server configured, a gap is detected, logged loudly, and eventually given up on —
 * which is honest. The alternative, applying what arrives and hoping, produces a book that looks fine
 * and is wrong, and there is no worse outcome than that.
 */
public final class MoldUdp64Source implements ItchSource {

    private static final Logger log = LoggerFactory.getLogger(MoldUdp64Source.class);
    private static final int MAX_DATAGRAM = 65_535;
    private static final int MAX_QUEUED = 200_000;
    /** Do not spam the rewind server: one request per gap, then wait for it to be answered. */
    private static final long REQUEST_COOLDOWN_MS = 250;

    private final String group;
    private final int port;
    private final String sessionName;
    private final InetSocketAddress rewind;      // null when the feed offers no retransmission

    private final Queue<Itch.Msg> inbound = new ConcurrentLinkedQueue<>();
    private final ItchSequencer sequencer = new ItchSequencer(0, 100_000);
    private final AtomicBoolean running = new AtomicBoolean(true);

    private final MulticastSocket socket;
    private final DatagramSocket requestSocket;
    private final Thread reader;

    private volatile boolean endOfSession;
    private volatile long lastRequestAt;

    public MoldUdp64Source(String group, int port, String sessionName,
                           String rewindHost, int rewindPort, String nic) throws IOException {
        this.group = group;
        this.port = port;
        this.sessionName = sessionName == null ? "" : sessionName;
        this.rewind = rewindHost == null || rewindHost.isBlank()
                ? null : new InetSocketAddress(rewindHost, rewindPort);

        socket = new MulticastSocket(port);
        socket.setReuseAddress(true);
        socket.setSoTimeout(1_000);          // so the reader thread can notice it has been asked to stop

        InetAddress addr = InetAddress.getByName(group);
        NetworkInterface iface = nic == null || nic.isBlank() ? null : NetworkInterface.getByName(nic);
        socket.joinGroup(new InetSocketAddress(addr, port), iface);

        requestSocket = rewind == null ? null : new DatagramSocket();

        log.info("ITCH MoldUDP64: joined {}:{} (session '{}', rewind {})",
                group, port, this.sessionName, rewind == null ? "not configured" : rewind);

        reader = new Thread(this::readLoop, "itch-moldudp64");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop() {
        byte[] buf = new byte[MAX_DATAGRAM];
        while (running.get() && !endOfSession) {
            DatagramPacket dg = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(dg);
            } catch (java.net.SocketTimeoutException e) {
                continue;                     // a quiet market is not a broken one
            } catch (IOException e) {
                if (running.get()) {
                    log.warn("ITCH MoldUDP64: receive failed: {}", e.toString());
                }
                continue;
            }

            try {
                // dg.getLength(), not buf.length: the buffer is far bigger than the packet, and decoding
                // the slack produces messages that are garbage but look almost plausible.
                MoldUdp64.Packet p = MoldUdp64.decode(dg.getData(), dg.getLength());
                handle(p);
            } catch (Exception e) {
                log.warn("ITCH MoldUDP64: undecodable datagram ({} bytes) dropped: {}",
                        dg.getLength(), e.toString());
            }
        }
    }

    private void handle(MoldUdp64.Packet p) {
        if (!sessionName.isEmpty() && !sessionName.equals(p.session())) {
            return;      // a packet from another session is not a gap in ours; applying it would be worse
        }
        if (p.isEndOfSession()) {
            log.info("ITCH MoldUDP64: end of session at sequence {} — {}",
                    p.sequence(), sequencer.inSequence() ? "the book is whole" : "WITH MESSAGES STILL MISSING");
            endOfSession = true;
            return;
        }
        if (p.isHeartbeat()) {
            // Nothing to apply — but it tells us where the stream is, which during a lull is the only
            // way we would ever learn that something went missing.
            if (p.sequence() > sequencer.expected() && sequencer.expected() > 0) {
                requestRetransmit(new ItchSequencer.Gap(
                        sequencer.expected(), p.sequence() - sequencer.expected()));
            }
            return;
        }

        ItchSequencer.Delivery d = sequencer.accept(p.sequence(), p.messages());
        for (byte[] frame : d.ready()) {
            if (inbound.size() >= MAX_QUEUED) {
                log.error("ITCH MoldUDP64: {} messages queued and nothing is draining them — dropping", MAX_QUEUED);
                break;
            }
            try {
                inbound.add(ItchCodec.decode(frame));
            } catch (Exception e) {
                log.warn("ITCH MoldUDP64: undecodable message dropped: {}", e.toString());
            }
        }
        if (d.hasGap()) {
            requestRetransmit(d.missing());
        }
    }

    /** "Send me {@code count} messages from {@code from}." Unicast, to the rewind server. */
    private void requestRetransmit(ItchSequencer.Gap gap) {
        if (requestSocket == null) {
            return;                     // no rewind server: the sequencer will log the loss and resync
        }
        long now = System.currentTimeMillis();
        if (now - lastRequestAt < REQUEST_COOLDOWN_MS) {
            return;                     // one request per gap; the replay is in flight
        }
        lastRequestAt = now;
        try {
            byte[] req = MoldUdp64.request(sessionName, gap.from(), (int) Math.min(gap.count(), 0xFFFE));
            requestSocket.send(new DatagramPacket(req, req.length, rewind));
            log.info("ITCH MoldUDP64: requested retransmission of {} message(s) from {}",
                    gap.count(), gap.from());
        } catch (IOException e) {
            log.warn("ITCH MoldUDP64: could not reach the rewind server at {}: {}", rewind, e.toString());
        }
    }

    @Override
    public List<Itch.Msg> open() {
        return List.of();      // the feed sends its own day-start sequence
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
        return "moldudp64:" + group + ":" + port;
    }

    /** Feed health — gaps seen, gaps recovered, messages given up on. */
    public ItchSequencer.Health health() {
        return sequencer.health();
    }

    @Override
    public void close() {
        running.set(false);
        reader.interrupt();
        socket.close();
        if (requestSocket != null) {
            requestSocket.close();
        }
    }
}
