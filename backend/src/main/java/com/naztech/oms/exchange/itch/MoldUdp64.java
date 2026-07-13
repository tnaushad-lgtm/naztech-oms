package com.naztech.oms.exchange.itch;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * MoldUDP64 framing — how ITCH is actually shipped: one UDP multicast datagram carrying a run of
 * messages, numbered.
 *
 * <p>The header is 20 bytes: a 10-byte session name, an 8-byte big-endian sequence number (the number
 * of the <em>first</em> message in the packet), and a 2-byte count. Then each message is
 * {@code [2-byte length][payload]}.
 *
 * <p>Two of the counts are special, and both matter:
 * <ul>
 *   <li><b>0</b> — a heartbeat. It carries no messages and exists to tell you the sequence you should
 *       be at, so a quiet instrument does not look like a dead feed. It is also how you learn you have
 *       missed something during a lull, when there is no next message to reveal the gap.</li>
 *   <li><b>0xFFFF</b> — end of session. The day is over; anything still missing is never coming.</li>
 * </ul>
 *
 * <p>Because it is multicast, there is no connection to lose and no back-pressure to apply: packets
 * are dropped by any switch under load and nobody tells you. Recovery is a <b>request</b> — the same
 *20-byte header, sent unicast to a rewind server, naming the sequence and the count you want back.
 * {@link ItchSequencer} decides when to send one.
 */
public final class MoldUdp64 {

    public static final int HEADER_BYTES = 20;
    public static final int HEARTBEAT = 0;
    public static final int END_OF_SESSION = 0xFFFF;

    /**
     * @param session  the trading session's name — a packet from yesterday's session is not a gap in
     *                 today's, and applying it would be worse than dropping it
     * @param sequence the sequence number of the first message in {@code messages}
     * @param messages the ITCH messages, whole; empty for a heartbeat or an end-of-session marker
     */
    public record Packet(String session, long sequence, int count, List<byte[]> messages) {

        public boolean isHeartbeat() {
            return count == HEARTBEAT;
        }

        public boolean isEndOfSession() {
            return count == END_OF_SESSION;
        }
    }

    private MoldUdp64() {
    }

    public static byte[] encode(String session, long sequence, List<byte[]> messages) {
        int size = HEADER_BYTES;
        for (byte[] m : messages) {
            size += 2 + m.length;
        }
        ByteBuffer b = ByteBuffer.allocate(size);
        b.put(session(session));
        b.putLong(sequence);
        b.putShort((short) messages.size());
        for (byte[] m : messages) {
            b.putShort((short) m.length);
            b.put(m);
        }
        return b.array();
    }

    /** A heartbeat: no messages, but it still tells you where the stream has got to. */
    public static byte[] heartbeat(String session, long nextSequence) {
        return encode(session, nextSequence, List.of());
    }

    public static byte[] endOfSession(String session, long nextSequence) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES);
        b.put(session(session));
        b.putLong(nextSequence);
        b.putShort((short) END_OF_SESSION);
        return b.array();
    }

    /**
     * A retransmission request: "send me {@code count} messages from {@code from}." Unicast, to the
     * rewind server.
     *
     * <p>It is the same 20-byte header and <b>nothing else</b> — the count is what we are <em>asking
     * for</em>, not what is enclosed. That asymmetry is why a request cannot be read back with
     * {@link #decode}, which would go looking for message bodies that were never there; use
     * {@link #decodeRequest}.
     */
    public static byte[] request(String session, long from, int count) {
        ByteBuffer b = ByteBuffer.allocate(HEADER_BYTES);
        b.put(session(session));
        b.putLong(from);
        b.putShort((short) Math.min(count, 0xFFFE));   // 0xFFFF means end-of-session, so it cannot mean a count
        return b.array();
    }

    /** Read a retransmission request back: the header only, and the count is a demand, not a payload. */
    public static Packet decodeRequest(byte[] data) {
        if (data.length < HEADER_BYTES) {
            throw new IllegalArgumentException("MoldUDP64 request is " + data.length + " bytes; the header is 20");
        }
        ByteBuffer b = ByteBuffer.wrap(data, 0, HEADER_BYTES);
        byte[] sess = new byte[10];
        b.get(sess);
        return new Packet(new String(sess, StandardCharsets.US_ASCII).trim(),
                b.getLong(), b.getShort() & 0xFFFF, List.of());
    }

    /**
     * Decode one datagram.
     *
     * @param data the datagram
     * @param len  how many bytes of it are real — {@code DatagramPacket} hands back a buffer that is
     *             usually bigger than the packet, and decoding the slack as messages produces garbage
     *             that looks almost plausible, which is the worst kind
     */
    public static Packet decode(byte[] data, int len) {
        if (len < HEADER_BYTES) {
            throw new IllegalArgumentException("MoldUDP64 datagram is " + len + " bytes; the header alone is 20");
        }
        ByteBuffer b = ByteBuffer.wrap(data, 0, len);
        byte[] sess = new byte[10];
        b.get(sess);
        String session = new String(sess, StandardCharsets.US_ASCII).trim();
        long sequence = b.getLong();
        int count = b.getShort() & 0xFFFF;

        if (count == END_OF_SESSION || count == HEARTBEAT) {
            return new Packet(session, sequence, count, List.of());
        }

        List<byte[]> messages = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (b.remaining() < 2) {
                throw new IllegalArgumentException("MoldUDP64 packet claims " + count
                        + " messages but ran out after " + i);
            }
            int msgLen = b.getShort() & 0xFFFF;
            if (b.remaining() < msgLen) {
                throw new IllegalArgumentException("MoldUDP64 message " + i + " claims " + msgLen
                        + " bytes but only " + b.remaining() + " remain");
            }
            byte[] msg = new byte[msgLen];
            b.get(msg);
            messages.add(msg);
        }
        return new Packet(session, sequence, count, messages);
    }

    public static Packet decode(byte[] data) {
        return decode(data, data.length);
    }

    private static byte[] session(String s) {
        byte[] out = new byte[10];
        Arrays.fill(out, (byte) ' ');
        byte[] src = (s == null ? "" : s).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, out, 0, Math.min(src.length, 10));
        return out;
    }
}
