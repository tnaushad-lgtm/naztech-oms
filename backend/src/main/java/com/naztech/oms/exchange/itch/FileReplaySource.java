package com.naztech.oms.exchange.itch;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link ItchSource} that replays a length-framed {@code .itch} capture written by {@link ItchSessionWriter}.
 * It reads raw frames and decodes each with {@link ItchCodec#decode} — exactly how a live transport reader
 * turns socket bytes into messages — so replay exercises the real decode path and reproduces a recorded
 * session deterministically (byte-for-byte). This is the regression-test and capture-replay on-ramp for the
 * future live DSE feed: capture once, replay into the gateway or a test forever.
 */
public final class FileReplaySource implements ItchSource {

    private final DataInputStream in;
    private final int batch;
    private final String label;
    private boolean eof = false;

    public FileReplaySource(Path file, int batch) throws IOException {
        this.batch = Math.max(1, batch);
        this.label = "replay:" + file.getFileName();
        in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)));
        byte[] magic = new byte[ItchSessionWriter.MAGIC.length];
        in.readFully(magic);
        if (!Arrays.equals(magic, ItchSessionWriter.MAGIC))
            throw new IOException("not a DSE ITCH capture (bad magic header): " + file);
    }

    /** A replay carries its own opening messages inside the file, so there is nothing to seed here. */
    @Override public List<Itch.Msg> open() { return List.of(); }

    @Override public List<Itch.Msg> poll() {
        if (eof) return List.of();
        List<Itch.Msg> out = new ArrayList<>();
        for (int i = 0; i < batch; i++) {
            byte[] frame = nextFrame();
            if (frame == null) { eof = true; break; }
            out.add(ItchCodec.decode(frame));
        }
        return out;
    }

    private byte[] nextFrame() {
        try {
            int len = in.readInt();
            if (len < 0 || len > (1 << 20)) throw new IOException("implausible frame length " + len);
            byte[] frame = new byte[len];
            in.readFully(frame);
            return frame;
        } catch (EOFException e) {
            return null;                 // clean end of capture
        } catch (IOException e) {
            eof = true;
            return null;
        }
    }

    @Override public boolean isActive() { return !eof; }

    @Override public String name() { return label; }

    @Override public void close() throws IOException { in.close(); }
}
