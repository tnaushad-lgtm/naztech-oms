package com.naztech.oms.exchange.itch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase-B proof: a session recorded through {@link ItchSessionWriter} replays byte-for-byte through
 * {@link FileReplaySource}, and {@link RecordingSource} tees the live feed transparently. This is the
 * capture-and-replay path we will reuse to regression-test against real DSE SoupBinTCP/MoldUDP64 sessions.
 */
class ItchRecordReplayTest {

    private static List<ItchSimulator.Instrument> gp() {
        return List.of(new ItchSimulator.Instrument(1, "GP", "Grameenphone", 25_000, 2, 10));
    }

    @Test
    void record_then_replay_reproduces_the_stream_byte_for_byte(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("session.itch");

        List<Itch.Msg> emitted = new ArrayList<>();
        SimulatorSource src = new SimulatorSource(gp(), 7L, 25);
        try (ItchSessionWriter w = new ItchSessionWriter(file)) {
            for (Itch.Msg m : src.open()) { emitted.add(m); w.record(m); }
            for (int i = 0; i < 5; i++) for (Itch.Msg m : src.poll()) { emitted.add(m); w.record(m); }
        }
        assertThat(emitted).isNotEmpty();

        List<Itch.Msg> replayed = new ArrayList<>();
        try (FileReplaySource rep = new FileReplaySource(file, 25)) {
            replayed.addAll(rep.open());
            for (List<Itch.Msg> batch = rep.poll(); !batch.isEmpty(); batch = rep.poll()) replayed.addAll(batch);
            assertThat(rep.isActive()).isFalse();
        }

        assertThat(replayed).hasSameSizeAs(emitted);
        for (int i = 0; i < emitted.size(); i++) {
            assertThat(ItchCodec.encode(replayed.get(i)))
                    .as("frame %d", i)
                    .isEqualTo(ItchCodec.encode(emitted.get(i)));
        }
    }

    @Test
    void recording_source_tees_and_passes_through(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("rec.itch");

        int emitted;
        try (RecordingSource rs = new RecordingSource(new SimulatorSource(gp(), 3L, 10),
                                                      new ItchSessionWriter(file))) {
            int n = rs.open().size();
            n += rs.poll().size();
            emitted = n;
            assertThat(emitted).isGreaterThan(0);
        }

        int replayed = 0;
        try (FileReplaySource rep = new FileReplaySource(file, 1000)) {
            for (List<Itch.Msg> batch = rep.poll(); !batch.isEmpty(); batch = rep.poll()) replayed += batch.size();
        }
        assertThat(replayed).isEqualTo(emitted);
    }

    @Test
    void rejects_a_non_capture_file(@TempDir Path dir) throws Exception {
        Path bogus = dir.resolve("bogus.itch");
        java.nio.file.Files.write(bogus, "not an itch capture".getBytes());
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> new FileReplaySource(bogus, 10))).isNotNull();
    }
}
