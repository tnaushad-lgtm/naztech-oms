package com.naztech.oms.exchange.itch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

/**
 * Decorator that tees every message flowing out of an inner {@link ItchSource} into an
 * {@link ItchSessionWriter}, then passes it through unchanged. Wrapping the live simulator (or, later,
 * a real transport source) with this captures the exact session to a {@code .itch} file for replay —
 * without the gateway knowing recording is happening. Recording failures are logged, never fatal to the feed.
 */
public final class RecordingSource implements ItchSource {

    private static final Logger log = LoggerFactory.getLogger(RecordingSource.class);

    private final ItchSource inner;
    private final ItchSessionWriter writer;

    public RecordingSource(ItchSource inner, ItchSessionWriter writer) {
        this.inner = inner;
        this.writer = writer;
    }

    @Override public List<Itch.Msg> open() { List<Itch.Msg> ms = inner.open(); tee(ms); return ms; }

    @Override public List<Itch.Msg> poll() { List<Itch.Msg> ms = inner.poll(); tee(ms); return ms; }

    private void tee(List<Itch.Msg> ms) {
        for (Itch.Msg m : ms) {
            try { writer.record(m); }
            catch (IOException e) { log.warn("ITCH record failed ({}), continuing feed", e.toString()); }
        }
    }

    @Override public boolean isActive() { return inner.isActive(); }

    @Override public String name() { return inner.name() + "+record"; }

    @Override public void close() throws IOException {
        try { inner.close(); } finally { writer.close(); }
    }
}
