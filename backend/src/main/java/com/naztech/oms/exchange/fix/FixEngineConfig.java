package com.naztech.oms.exchange.fix;

import com.naztech.oms.exchange.config.FixProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import quickfix.DefaultMessageFactory;
import quickfix.FileLogFactory;
import quickfix.Initiator;
import quickfix.LogFactory;
import quickfix.MemoryStoreFactory;
import quickfix.MessageFactory;
import quickfix.MessageStoreFactory;
import quickfix.SessionID;
import quickfix.SessionSettings;
import quickfix.SocketInitiator;

/**
 * Boots the QuickFIX/J initiator whenever {@code fix.enabled=true} and host/port are configured —
 * independent of {@code exchange.mode}, so the FIX session can log on to FIXSIM for connectivity
 * testing while the demo keeps running on the simulator. Session settings are built entirely from
 * {@link FixProperties} (no DSE value is hard-coded).
 *
 * <p>Order routing over this session is handled by {@code FixTradingGateway} (only when
 * {@code exchange.mode} is a real DSE venue); this class owns the session lifecycle only.
 */
@Configuration
@ConditionalOnProperty(prefix = "fix", name = "enabled", havingValue = "true")
public class FixEngineConfig {

    private static final Logger log = LoggerFactory.getLogger(FixEngineConfig.class);

    private final FixProperties props;
    private final OmsFixApplication application;
    private final FixSessionState state;
    private Initiator initiator;

    public FixEngineConfig(FixProperties props, OmsFixApplication application, FixSessionState state) {
        this.props = props;
        this.application = application;
        this.state = state;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.isEnabled()) {
            log.info("FIX disabled (fix.enabled=false) — not connecting. {}", props);
            state.event("DISABLED");
            return;
        }
        if (!props.isConfigured()) {
            log.warn("FIX enabled but host/port not set — refusing to connect to a blank endpoint. {}", props);
            state.event("UNCONFIGURED");
            return;
        }
        try {
            SessionSettings settings = buildSettings();
            MessageStoreFactory store = new MemoryStoreFactory();          // Phase 2 → durable JdbcStore
            LogFactory logFactory = new FileLogFactory(settings);          // writes ./fixlog/*.event/.messages logs
            MessageFactory messageFactory = new DefaultMessageFactory();
            initiator = new SocketInitiator(application, store, settings, logFactory, messageFactory);
            initiator.start();
            state.event("STARTING");
            log.info("FIX initiator started -> {}:{} (sender={}, target={}, ssl={})",
                    props.getHost(), props.getPort(), props.getSenderCompId(), props.getTargetCompId(), props.isSsl());
        } catch (Exception e) {
            log.error("FIX initiator failed to start: {}", e.toString(), e);
            state.event("START_FAILED");
        }
    }

    private SessionSettings buildSettings() {
        SessionSettings s = new SessionSettings();
        s.setString("ConnectionType", "initiator");
        s.setString("ReconnectInterval", String.valueOf(props.getReconnectSeconds()));
        s.setString("FileStorePath", "./fixstore");
        s.setString("FileLogPath", "./fixlog");

        SessionID sid = new SessionID(props.getBeginString(), props.getSenderCompId(), props.getTargetCompId());
        s.setString(sid, "BeginString", props.getBeginString());
        s.setString(sid, "DefaultApplVerID", props.getDefaultApplVerId());
        s.setString(sid, "SenderCompID", props.getSenderCompId());
        s.setString(sid, "TargetCompID", props.getTargetCompId());
        s.setString(sid, "SocketConnectHost", props.getHost());
        s.setString(sid, "SocketConnectPort", String.valueOf(props.getPort()));
        s.setString(sid, "HeartBtInt", String.valueOf(props.getHeartbeatInt()));
        s.setString(sid, "StartTime", "00:00:00");
        s.setString(sid, "EndTime", "00:00:00");
        s.setString(sid, "UseDataDictionary", "Y");
        // FIX 5.0 SP1 rides on FIXT.1.1: transport dictionary is FIXT11, application dictionary FIX50SP1.
        // Both ship inside the quickfixj-messages jars and resolve from the classpath by name.
        s.setString(sid, "TransportDataDictionary", "FIXT11.xml");
        s.setString(sid, "AppDataDictionary", "FIX50SP1.xml");
        s.setString(sid, "ResetOnLogon", props.isResetSeqNumFlag() ? "Y" : "N");
        if (props.isSsl()) {
            s.setString(sid, "SocketUseSSL", "Y");
        }
        return s;
    }

    @PreDestroy
    public void stop() {
        if (initiator != null) {
            initiator.stop();
            log.info("FIX initiator stopped");
        }
    }
}
