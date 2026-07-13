package com.naztech.oms.exchange.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * FIX order-entry session settings (DSE X-stream = FIX 5.0 SP1 over FIXT.1.1).
 *
 * <p>To point at FIXSIM / DSE-cert / DSE-prod the operator only fills
 * {@code host, port, senderCompId, targetCompId, username} here and
 * {@code fix.password} in the gitignored {@code secrets.properties} — nothing else.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "fix")
public class FixProperties {

    /** Actually open the FIX session? Keep false in simulator mode. */
    private boolean enabled = false;

    /** FIXT.1.1 transport for FIX 5.0 SP1. */
    private String beginString = "FIXT.1.1";

    /** Application version negotiated at logon (tag 1137). DSE = FIX.5.0SP1; FIXSIM pins FIX.5.0SP2. */
    private String defaultApplVerId = "FIX.5.0SP1";

    /** QuickFIX/J application dictionary. Must match {@link #defaultApplVerId} or the acceptor drops the logon. */
    private String appDataDictionary = "FIX50SP1.xml";

    /** QuickFIX/J transport dictionary — FIXT.1.1 for every FIX 5.x session. */
    private String transportDataDictionary = "FIXT11.xml";

    private String host;
    private int port = 0;
    private String senderCompId;
    private String targetCompId;
    private String username;

    /**
     * FIX Logon password (tag 554). Supplied OUT-OF-BAND by IBCS-Primax/DSE and must live
     * ONLY in the gitignored {@code secrets.properties} (key {@code fix.password}).
     * Never commit, never log — see {@link #toString()}.
     */
    private String password;

    private int heartbeatInt = 30;
    private boolean resetSeqNumFlag = false;
    private boolean ssl = false;
    private int reconnectSeconds = 5;

    /** True once host+port are actually set, so we can fail fast instead of dialing a blank endpoint. */
    public boolean isConfigured() {
        return host != null && !host.isBlank() && port > 0;
    }

    @Override
    public String toString() { // never leak the password in logs
        return "FixProperties{enabled=" + enabled + ", host=" + host + ", port=" + port
                + ", senderCompId=" + senderCompId + ", targetCompId=" + targetCompId
                + ", username=" + username + ", heartbeatInt=" + heartbeatInt
                + ", ssl=" + ssl + ", password=" + (password == null || password.isBlank() ? "<unset>" : "***") + "}";
    }
}
