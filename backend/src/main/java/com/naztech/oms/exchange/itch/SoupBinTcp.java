package com.naztech.oms.exchange.itch;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * SoupBinTCP 3.00 framing — the TCP envelope an ITCH feed travels in.
 *
 * <p>ITCH says what a message means; SoupBinTCP says how it gets here. Every packet is
 * {@code [2-byte big-endian length][1-byte type][payload]}, where the length counts the type byte —
 * a detail worth stating because getting it off by one shifts every packet after it, and the feed
 * dissolves into noise rather than failing cleanly.
 *
 * <p>The sequence number is <b>implicit</b>: it appears once, in the Login Accepted packet, and every
 * Sequenced Data packet after it is the next one. That is why recovery over SoupBinTCP is a reconnect
 * — you log in again asking for the sequence you still want, and the server replays from there. There
 * is no "resend me packet 4,001" in this protocol; the session <em>is</em> the recovery mechanism.
 */
public final class SoupBinTcp {

    // Server → client
    public static final char LOGIN_ACCEPTED = 'A';
    public static final char LOGIN_REJECTED = 'J';
    public static final char SEQUENCED_DATA = 'S';
    public static final char SERVER_HEARTBEAT = 'H';
    public static final char END_OF_SESSION = 'Z';
    public static final char DEBUG = '+';

    // Client → server
    public static final char LOGIN_REQUEST = 'L';
    public static final char UNSEQUENCED_DATA = 'U';
    public static final char CLIENT_HEARTBEAT = 'R';
    public static final char LOGOUT_REQUEST = 'O';

    /** A SoupBinTCP packet. For {@link #SEQUENCED_DATA} the payload is one whole ITCH message. */
    public record Packet(char type, byte[] payload) {
        public static Packet of(char type) {
            return new Packet(type, new byte[0]);
        }
    }

    /** What the server said when we logged in: which session, and the sequence it will start from. */
    public record LoginAccepted(String session, long nextSequence) {
    }

    private SoupBinTcp() {
    }

    public static byte[] encode(Packet p) {
        byte[] payload = p.payload() == null ? new byte[0] : p.payload();
        ByteBuffer b = ByteBuffer.allocate(2 + 1 + payload.length);
        b.putShort((short) (payload.length + 1));      // the length includes the type byte
        b.put((byte) p.type());
        b.put(payload);
        return b.array();
    }

    /** Reads exactly one packet, blocking until it is whole. Throws {@link EOFException} at end of stream. */
    public static Packet decode(DataInputStream in) throws IOException {
        int len = in.readUnsignedShort();
        if (len < 1) {
            throw new IOException("SoupBinTCP packet length " + len + " — the stream is out of frame");
        }
        char type = (char) (in.readByte() & 0xFF);
        byte[] payload = new byte[len - 1];
        in.readFully(payload);
        return new Packet(type, payload);
    }

    /**
     * Log in and ask to start at a given sequence.
     *
     * <p>{@code sequence = 0} means "start me at the next message you send" — the right choice on a
     * fresh connect. A reconnect after a gap passes the sequence it still wants, and the server
     * replays the day from there; that is the whole of SoupBinTCP's recovery story.
     *
     * <p>The fields are fixed-width ASCII, space-padded — 6, 10, 10, 20 — and a server will simply drop
     * a login whose widths are wrong, silently, which is a debugging afternoon nobody enjoys.
     */
    public static byte[] loginRequest(String username, String password, String session, long sequence) {
        ByteBuffer b = ByteBuffer.allocate(46);
        b.put(fixed(username, 6));
        b.put(fixed(password, 10));
        b.put(fixed(session, 10));
        // The requested sequence is RIGHT-justified, unlike the alpha fields — the DSE/nFIX ITCH spec
        // is explicit about it. 0 stays blank ("only new messages"); a real number is padded on the
        // left so "1" arrives as 19 spaces then "1", which is what the server parses.
        b.put(rjustNum(sequence <= 0 ? "" : String.valueOf(sequence), 20));
        return encode(new Packet(LOGIN_REQUEST, b.array()));
    }

    public static LoginAccepted parseLoginAccepted(byte[] payload) {
        if (payload.length < 30) {
            throw new IllegalArgumentException("Login Accepted is 30 bytes, got " + payload.length);
        }
        String session = new String(payload, 0, 10, StandardCharsets.US_ASCII).trim();
        String seq = new String(payload, 10, 20, StandardCharsets.US_ASCII).trim();
        return new LoginAccepted(session, seq.isEmpty() ? 1 : Long.parseLong(seq));
    }

    public static byte[] loginAccepted(String session, long nextSequence) {
        ByteBuffer b = ByteBuffer.allocate(30);
        b.put(fixed(session, 10));
        b.put(fixed(String.valueOf(nextSequence), 20));
        return encode(new Packet(LOGIN_ACCEPTED, b.array()));
    }

    /** Left-aligned, space-padded, truncated if too long — SoupBin's fixed-width ASCII convention. */
    private static byte[] fixed(String s, int width) {
        byte[] out = new byte[width];
        java.util.Arrays.fill(out, (byte) ' ');
        byte[] src = (s == null ? "" : s).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(src, 0, out, 0, Math.min(src.length, width));
        return out;
    }

    /** Right-aligned, space-padded — for the numeric Sequence field in the Login Request. */
    private static byte[] rjustNum(String s, int width) {
        byte[] out = new byte[width];
        java.util.Arrays.fill(out, (byte) ' ');
        byte[] src = (s == null ? "" : s).getBytes(StandardCharsets.US_ASCII);
        int n = Math.min(src.length, width);
        System.arraycopy(src, 0, out, width - n, n);
        return out;
    }
}
