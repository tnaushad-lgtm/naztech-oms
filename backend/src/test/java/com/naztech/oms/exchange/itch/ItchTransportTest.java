package com.naztech.oms.exchange.itch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The two wire transports a real DSE ITCH feed arrives on. The framing is the whole game here: a
 * length that is off by one shifts every packet after it, and the feed does not fail — it dissolves
 * into messages that decode to nonsense. So these tests check the bytes, and then drive a real
 * SoupBinTCP session over a loopback socket to prove the source reads what a server actually sends.
 */
class ItchTransportTest {

    private ServerSocket server;
    private SoupBinTcpSource source;

    @AfterEach
    void tearDown() throws IOException {
        if (source != null) {
            source.close();
        }
        if (server != null) {
            server.close();
        }
    }

    // ---------------------------------------------------------------- SoupBinTCP

    @Test
    @DisplayName("SoupBinTCP: a packet is [2-byte length][type][payload], and the length counts the type")
    void soupBinFramingRoundTrips() throws Exception {
        byte[] wire = SoupBinTcp.encode(
                new SoupBinTcp.Packet(SoupBinTcp.SEQUENCED_DATA, "hello".getBytes(StandardCharsets.US_ASCII)));

        // 5 payload bytes + 1 type byte = a length of 6. Not 5, and not 7.
        assertThat(wire[0]).isZero();
        assertThat(wire[1]).isEqualTo((byte) 6);
        assertThat(wire).hasSize(8);

        SoupBinTcp.Packet back = SoupBinTcp.decode(new DataInputStream(new java.io.ByteArrayInputStream(wire)));
        assertThat(back.type()).isEqualTo(SoupBinTcp.SEQUENCED_DATA);
        assertThat(new String(back.payload(), StandardCharsets.US_ASCII)).isEqualTo("hello");
    }

    @Test
    @DisplayName("SoupBinTCP: the login request is fixed-width ASCII — a server drops it silently if it is not")
    void soupBinLoginRequestIsFixedWidth() {
        byte[] wire = SoupBinTcp.loginRequest("dragon", "s3cret", "SESSION1", 4_001);

        assertThat(wire).hasSize(2 + 1 + 46);              // length + type + 6 + 10 + 10 + 20
        assertThat((char) wire[2]).isEqualTo(SoupBinTcp.LOGIN_REQUEST);

        String body = new String(wire, 3, 46, StandardCharsets.US_ASCII);
        assertThat(body).startsWith("dragon");
        assertThat(body.substring(6, 16)).isEqualTo("s3cret    ");     // space-padded to 10
        assertThat(body.substring(16, 26)).isEqualTo("SESSION1  ");
        assertThat(body.substring(26).trim()).isEqualTo("4001");       // resume here after a gap

        // Sequence 0 means "start me at the next message you send" — blank, not the digit zero.
        String fresh = new String(SoupBinTcp.loginRequest("d", "p", "S", 0), 3, 46, StandardCharsets.US_ASCII);
        assertThat(fresh.substring(26).trim()).isEmpty();
    }

    @Test
    @DisplayName("SoupBinTCP: reads a real session off a socket — login, sequenced ITCH, end of session")
    void soupBinSourceReadsALiveSession() throws Exception {
        server = new ServerSocket(0);
        CountDownLatch loggedIn = new CountDownLatch(1);
        AtomicReference<Long> resumeAt = new AtomicReference<>();

        Thread venue = new Thread(() -> {
            try (Socket s = server.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                SoupBinTcp.Packet login = SoupBinTcp.decode(in);
                assertThat(login.type()).isEqualTo(SoupBinTcp.LOGIN_REQUEST);
                String seq = new String(login.payload(), 26, 20, StandardCharsets.US_ASCII).trim();
                resumeAt.set(seq.isEmpty() ? 0L : Long.parseLong(seq));
                loggedIn.countDown();

                OutputStream out = s.getOutputStream();
                out.write(SoupBinTcp.loginAccepted("DSE20260714", 1));

                // Three real ITCH messages, encoded by the real codec — the exact bytes DSE would send.
                for (Itch.Msg m : List.of(
                        add(1, 101, 'B', 10_050, 500),
                        add(2, 101, 'S', 10_100, 300),
                        add(3, 101, 'B', 10_040, 700))) {
                    out.write(SoupBinTcp.encode(
                            new SoupBinTcp.Packet(SoupBinTcp.SEQUENCED_DATA, ItchCodec.encode(m))));
                }
                out.write(SoupBinTcp.encode(SoupBinTcp.Packet.of(SoupBinTcp.END_OF_SESSION)));
                out.flush();
                Thread.sleep(300);        // let the client drain before the socket goes away
            } catch (Exception ignored) {
                // the assertions below are what fail the test; a dead venue thread shows up there
            }
        }, "fake-itch-venue");
        venue.setDaemon(true);
        venue.start();

        source = new SoupBinTcpSource("127.0.0.1", server.getLocalPort(), "dragon", "pw", "DSE20260714");

        assertThat(loggedIn.await(5, TimeUnit.SECONDS)).isTrue();
        // A fresh connect asks for sequence 1 — a FULL replay — not 0 ("only what happens from now").
        // SoupBinTCP has no snapshot service, so the day's directory messages and the resting book are
        // only obtainable by replaying from the start; requesting 0 is the "ITCH connects but no data"
        // symptom. This assertion used to expect 0 and had been failing since connect() was corrected.
        assertThat(resumeAt.get()).as("a fresh connect asks for a full replay from sequence 1").isEqualTo(1L);

        await().atMost(5, TimeUnit.SECONDS).until(() -> source.health().delivered() >= 3);

        List<Itch.Msg> got = source.poll();
        assertThat(got).hasSize(3);
        assertThat(got).allMatch(m -> m.type() == 'A');
        assertThat(((Itch.AddOrder) got.get(0)).price()).isEqualTo(10_050);
        assertThat(((Itch.AddOrder) got.get(1)).verb()).isEqualTo('S');

        ItchSequencer.Health h = source.health();
        assertThat(h.delivered()).isEqualTo(3);
        assertThat(h.lost()).isZero();
        assertThat(h.healthy()).isTrue();

        await().atMost(5, TimeUnit.SECONDS).until(() -> !source.isActive());   // end of session
    }

    @Test
    @DisplayName("SoupBinTCP: an outage longer than the old retry budget still recovers — it never gives up")
    void soupBinReconnectsAfterAnOutageThatOutlastsTheRetryBudget() throws Exception {
        // The regression: reconnect() used to stop after 10 attempts and set running = false, killing
        // the reader thread for the life of the JVM. On 2026-07-21 a VPN drop exhausted those ten
        // attempts 96 seconds before the tunnel came back, and market data stayed dead for the rest of
        // the session while FIX — which QuickFIX/J retries forever — reconnected on its own.
        server = new ServerSocket(0);
        int port = server.getLocalPort();
        CountDownLatch firstLogin = new CountDownLatch(1);

        Thread venue = new Thread(() -> {
            try {
                Socket s = server.accept();
                DataInputStream in = new DataInputStream(s.getInputStream());
                SoupBinTcp.decode(in);                       // login request
                s.getOutputStream().write(SoupBinTcp.loginAccepted("DSE20260721", 1));
                s.getOutputStream().flush();
                firstLogin.countDown();
                s.close();                                   // drop it, like a tunnel going away
            } catch (Exception ignored) {
                // assertions below are what fail the test
            }
        }, "fake-itch-venue-drop");
        venue.setDaemon(true);
        venue.start();

        // 5 ms backoff step, so many attempts happen fast; production still uses 1 s.
        source = new SoupBinTcpSource("127.0.0.1", port, "dragon", "pw", "DSE20260721", 5);
        assertThat(firstLogin.await(5, TimeUnit.SECONDS)).isTrue();

        // Take the venue away entirely and let the source fail well past the old 10-attempt ceiling.
        server.close();
        await().atMost(10, TimeUnit.SECONDS).until(() -> source.reconnectAttempts() > 15);

        // The point of the fix: still trying, not dead.
        assertThat(source.isActive())
                .as("the source must keep retrying after an outage longer than the old budget")
                .isTrue();

        // Bring the venue back on the same port — it must find its way home unaided.
        ServerSocket revived = new ServerSocket(port);
        CountDownLatch secondLogin = new CountDownLatch(1);
        Thread again = new Thread(() -> {
            try (Socket s = revived.accept()) {
                DataInputStream in = new DataInputStream(s.getInputStream());
                SoupBinTcp.decode(in);
                s.getOutputStream().write(SoupBinTcp.loginAccepted("DSE20260721", 1));
                s.getOutputStream().flush();
                secondLogin.countDown();
                Thread.sleep(300);
            } catch (Exception ignored) {
                // as above
            }
        }, "fake-itch-venue-revived");
        again.setDaemon(true);
        again.start();

        assertThat(secondLogin.await(20, TimeUnit.SECONDS))
                .as("the source reconnects by itself once the venue is reachable again")
                .isTrue();

        // Shut the source down while it is CONNECTED, not mid-reconnect. Now that it retries forever,
        // a source left spinning here will happily dial the port the next test's ServerSocket(0) gets
        // handed and answer its login — which is exactly how this leaked into a sibling test once.
        source.close();
        source = null;
        revived.close();
        server = null;                                        // tearDown must not double-close
    }

    @Test
    @DisplayName("SoupBinTCP: an endpoint that is not there fails loudly, so the gateway can fall back")
    void soupBinRefusesToPretendItIsConnected() {
        // A feed that silently produces nothing is indistinguishable from a market with no trades in
        // it. Failing here is what lets ItchGateway say "falling back to the simulator" out loud.
        assertThatThrownBy(() -> new SoupBinTcpSource("127.0.0.1", 1, "u", "p", "S"))
                .isInstanceOf(IOException.class);
    }

    // ---------------------------------------------------------------- MoldUDP64

    @Test
    @DisplayName("MoldUDP64: a packet is a 20-byte header and a run of length-prefixed messages")
    void moldFramingRoundTrips() {
        byte[] one = ItchCodec.encode(add(7, 101, 'B', 9_900, 400));
        byte[] two = ItchCodec.encode(add(8, 101, 'S', 10_100, 250));

        byte[] wire = MoldUdp64.encode("DSE20260714", 4_001, List.of(one, two));
        assertThat(wire).hasSize(MoldUdp64.HEADER_BYTES + 2 + one.length + 2 + two.length);

        MoldUdp64.Packet p = MoldUdp64.decode(wire);
        assertThat(p.session()).isEqualTo("DSE20260714".substring(0, 10));   // the field is 10 bytes wide
        assertThat(p.sequence()).isEqualTo(4_001);
        assertThat(p.count()).isEqualTo(2);
        assertThat(p.messages()).hasSize(2);

        // …and the messages inside decode back to the real ITCH they went in as.
        Itch.AddOrder back = (Itch.AddOrder) ItchCodec.decode(p.messages().get(0));
        assertThat(back.orderNumber()).isEqualTo(7);
        assertThat(back.price()).isEqualTo(9_900);
    }

    @Test
    @DisplayName("MoldUDP64: a heartbeat carries no messages but still says where the stream is")
    void moldHeartbeatCarriesTheSequence() {
        MoldUdp64.Packet hb = MoldUdp64.decode(MoldUdp64.heartbeat("DSE", 5_000));

        assertThat(hb.isHeartbeat()).isTrue();
        assertThat(hb.messages()).isEmpty();
        // This is the point of it: during a lull there is no next message to reveal a gap, so the
        // heartbeat's sequence is the only way a consumer learns it has fallen behind.
        assertThat(hb.sequence()).isEqualTo(5_000);
    }

    @Test
    @DisplayName("MoldUDP64: end of session is a count of 0xFFFF, not a message count")
    void moldEndOfSession() {
        MoldUdp64.Packet eos = MoldUdp64.decode(MoldUdp64.endOfSession("DSE", 9_999));
        assertThat(eos.isEndOfSession()).isTrue();
        assertThat(eos.isHeartbeat()).isFalse();
        assertThat(eos.sequence()).isEqualTo(9_999);
    }

    @Test
    @DisplayName("MoldUDP64: a retransmission request is a bare header — the count is a demand, not a payload")
    void moldRequestNamesTheGap() {
        byte[] wire = MoldUdp64.request("DSE", 4_002, 3);
        assertThat(wire).hasSize(MoldUdp64.HEADER_BYTES);      // no message bodies: we are asking, not sending

        MoldUdp64.Packet req = MoldUdp64.decodeRequest(wire);
        assertThat(req.sequence()).isEqualTo(4_002);
        assertThat(req.count()).isEqualTo(3);
        assertThat(req.messages()).isEmpty();

        // …and it is not a data packet: reading it as one goes looking for three messages that a
        // request, by definition, does not carry.
        assertThatThrownBy(() -> MoldUdp64.decode(wire)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("MoldUDP64: only the bytes the datagram actually carried are decoded")
    void moldIgnoresTheSlackInTheBuffer() {
        byte[] msg = ItchCodec.encode(add(1, 101, 'B', 10_000, 100));
        byte[] wire = MoldUdp64.encode("DSE", 1, List.of(msg));

        // A DatagramPacket hands back a 64KB buffer with a small packet at the front of it. Decoding
        // the slack produces messages that are garbage but look almost plausible — the worst kind.
        byte[] buffer = new byte[65_535];
        System.arraycopy(wire, 0, buffer, 0, wire.length);

        MoldUdp64.Packet p = MoldUdp64.decode(buffer, wire.length);
        assertThat(p.messages()).hasSize(1);
        assertThat(p.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("MoldUDP64: a truncated packet is rejected rather than half-decoded")
    void moldRejectsATruncatedPacket() {
        byte[] wire = MoldUdp64.encode("DSE", 1, List.of(ItchCodec.encode(add(1, 101, 'B', 10_000, 100))));
        byte[] cut = java.util.Arrays.copyOf(wire, wire.length - 4);

        assertThatThrownBy(() -> MoldUdp64.decode(cut))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MoldUdp64.decode(new byte[8]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("header alone is 20");
    }

    private static Itch.AddOrder add(long orderNumber, long orderbook, char side, long price, long qty) {
        return new Itch.AddOrder(0, orderNumber, side, qty, orderbook, price, 0);
    }
}
