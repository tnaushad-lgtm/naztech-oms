package com.naztech.oms.exchange.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ITCH market-data settings (DSE X-stream ITCH v2.2 binary feed).
 * Phase 3 ships a local {@code simulator}; later {@code soupbintcp}/{@code moldudp64} transports
 * connect to the real feed, plus {@code replay} from recorded binary files.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "itch")
public class ItchProperties {

    private boolean enabled = false;

    /** simulator | soupbintcp | moldudp64 */
    private String transport = "simulator";

    private String host;
    private int port = 0;

    /** Multicast group (moldudp64). */
    private String group;

    /**
     * The trading session's name, as the exchange stamps it on every packet. A packet from another
     * session is not a gap in ours — dropping it is correct, and applying it is not.
     * Blank accepts whatever session the feed is publishing.
     */
    private String session = "";

    /** SoupBinTCP credentials. Put the password in {@code secrets.properties}, never here. */
    private String username = "";
    private String password = "";

    /**
     * MoldUDP64 rewind (retransmission) server. Without it a gap is detected, logged and eventually
     * given up on — which is honest, but it is not recovery.
     */
    private String rewindHost;
    private int rewindPort = 0;

    /** Which NIC to join the multicast group on. Blank lets the OS choose — fine until it isn't. */
    private String networkInterface;

    private boolean replay = false;
    private String replayFile;

    /** Deterministic simulator seed — same seed ⇒ identical message sequence (reproducible capture/replay). */
    private long seed = 20260703L;

    /** Messages emitted per scheduled tick by the simulator / replay source. */
    private int burst = 30;

    /** Tee the live feed to a length-framed {@code .itch} capture at {@link #recordFile}. */
    private boolean record = false;
    private String recordFile;

    /** Opt-in: run order-book invariant checks each tick and log any violations. */
    private boolean validate = false;
}
