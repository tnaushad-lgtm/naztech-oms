package com.naztech.oms.exchange.itch;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Transport-level (raw-frame) recorder for an ITCH session. Writes a length-framed binary capture:
 * an 8-byte magic header followed by records of {@code [4-byte big-endian length][frame bytes]}.
 *
 * <p>It operates on <b>frames</b>, not on decoded objects, so it is reusable across transports:
 * the simulator path records {@link #record(Itch.Msg)} (the frame = {@link ItchCodec#encode}, i.e. the
 * exact wire bytes a real feed would carry), while a future live SoupBinTCP / MoldUDP64 reader records
 * the raw socket payloads verbatim via {@link #recordFrame(byte[])}. Either way the resulting {@code .itch}
 * file replays byte-for-byte through {@link FileReplaySource} — enabling regression tests against captured
 * real sessions without touching the architecture above the {@link ItchSource} seam.
 */
public final class ItchSessionWriter implements Closeable {

    static final byte[] MAGIC = "DSEITCH1".getBytes(StandardCharsets.US_ASCII);

    private final DataOutputStream out;
    private int frames = 0;

    public ItchSessionWriter(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)));
        out.write(MAGIC);
    }

    /** Record a message by encoding it to its exact ITCH wire frame. */
    public synchronized void record(Itch.Msg m) throws IOException { recordFrame(ItchCodec.encode(m)); }

    /** Record a raw wire frame verbatim (used when capturing a live transport's socket payloads). */
    public synchronized void recordFrame(byte[] frame) throws IOException {
        out.writeInt(frame.length);   // big-endian
        out.write(frame);
        out.flush();                  // keep the capture complete even without a clean shutdown
        frames++;
    }

    public int frameCount() { return frames; }

    @Override
    public synchronized void close() throws IOException { out.close(); }
}
