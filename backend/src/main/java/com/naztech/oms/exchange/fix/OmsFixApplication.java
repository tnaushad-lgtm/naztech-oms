package com.naztech.oms.exchange.fix;

import com.naztech.oms.exchange.config.FixProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import quickfix.Application;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;

import java.time.Instant;

/**
 * QuickFIX/J {@link Application} callbacks for the DSE FIX session. QuickFIX/J owns the
 * session protocol (logon/logout/heartbeat/test-request/resend/gap-fill/sequencing); this class
 * only observes it, injects the Logon credentials, keeps {@link FixSessionState} current, and
 * journals every message to the FIX-IN / FIX-OUT loggers.
 *
 * <p>Phase 1 = session lifecycle + logging only. Inbound application messages
 * (ExecutionReport/OrderCancelReject) are routed to the OMS lifecycle in Phase 5.
 */
@Component
@ConditionalOnProperty(prefix = "fix", name = "enabled", havingValue = "true")
public class OmsFixApplication implements Application {

    private static final Logger log = LoggerFactory.getLogger(OmsFixApplication.class);
    private static final Logger FIX_IN = LoggerFactory.getLogger("FIX-IN");
    private static final Logger FIX_OUT = LoggerFactory.getLogger("FIX-OUT");

    private static final int TAG_MSG_TYPE = 35;
    private static final int TAG_USERNAME = 553;
    private static final int TAG_PASSWORD = 554;
    private static final int TAG_RESET_SEQ = 141;
    private static final String MSGTYPE_LOGON = "A";
    private static final String MSGTYPE_HEARTBEAT = "0";

    // Regex to mask the password (tag 554) in logged messages. FIX fields are SOH-delimited (0x01).
    private static final String PASSWORD_MASK = "554=[^\\x01]*";

    private final FixProperties props;
    private final FixSessionState state;
    private final ExecutionService execution;

    public OmsFixApplication(FixProperties props, FixSessionState state, ExecutionService execution) {
        this.props = props;
        this.state = state;
        this.execution = execution;
    }

    @Override
    public void onCreate(SessionID sessionId) {
        state.setSessionId(sessionId.toString());
        state.event("CREATED");
        log.info("FIX session created: {}", sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        state.setLoggedOn(true);
        state.event("LOGON");
        refreshSeq(sessionId);
        log.info("FIX LOGON ok: {}", sessionId);
    }

    @Override
    public void onLogout(SessionID sessionId) {
        state.setLoggedOn(false);
        state.event("LOGOUT");
        log.info("FIX LOGOUT: {}", sessionId);
    }

    /** Outbound session (admin) messages — attach Username/Password on the Logon. */
    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        try {
            String msgType = message.getHeader().getString(TAG_MSG_TYPE);
            if (MSGTYPE_LOGON.equals(msgType)) {
                if (props.getUsername() != null && !props.getUsername().isBlank()) {
                    message.setString(TAG_USERNAME, props.getUsername());
                }
                if (props.getPassword() != null && !props.getPassword().isBlank()) {
                    message.setString(TAG_PASSWORD, props.getPassword());
                }
                if (props.isResetSeqNumFlag()) {
                    message.setBoolean(TAG_RESET_SEQ, true);
                }
            }
            state.setLastOutMsgType(msgType);
            FIX_OUT.info("ADMIN {} :: {}", sessionId, scrub(message));
        } catch (Exception e) {
            log.warn("toAdmin handling error: {}", e.toString());
        }
    }

    /** Inbound session (admin) messages — heartbeat/test-request/resend/logout etc. */
    @Override
    public void fromAdmin(Message message, SessionID sessionId) {
        try {
            String msgType = message.getHeader().getString(TAG_MSG_TYPE);
            state.setLastInMsgType(msgType);
            if (MSGTYPE_HEARTBEAT.equals(msgType)) {
                state.setLastHeartbeatAt(Instant.now());
            }
            refreshSeq(sessionId);
            FIX_IN.info("ADMIN {} :: {}", sessionId, message);
        } catch (Exception e) {
            log.warn("fromAdmin handling error: {}", e.toString());
        }
    }

    /**
     * Outbound application messages (NewOrderSingle/Cancel/Replace).
     *
     * <p>Logged at DEBUG, deliberately. This runs on the thread that is sending the order, and at
     * INFO it wrote a line to the console for every message — console I/O on Windows is slow and
     * serialised, and under load it became the single largest cost of placing an order, queueing
     * every thread behind it. The message itself is still journalled to {@code fixlog/} by the FIX
     * engine (asynchronously), so nothing is lost from the audit trail: that file, not this line, is
     * the record of what we sent the exchange.
     */
    @Override
    public void toApp(Message message, SessionID sessionId) {
        try {
            state.setLastOutMsgType(message.getHeader().getString(TAG_MSG_TYPE));
            if (FIX_OUT.isDebugEnabled()) {
                FIX_OUT.debug("APP  {} :: {}", sessionId, message);
            }
        } catch (Exception e) {
            log.warn("toApp handling error: {}", e.toString());
        }
    }

    /** Inbound application messages (ExecutionReport/OrderCancelReject). DEBUG — see {@link #toApp}. */
    @Override
    public void fromApp(Message message, SessionID sessionId) {
        try {
            String msgType = message.getHeader().getString(TAG_MSG_TYPE);
            state.setLastInMsgType(msgType);
            if (FIX_IN.isDebugEnabled()) {
                FIX_IN.debug("APP  {} :: {}", sessionId, message);
            }
            switch (msgType) {
                case "8" -> execution.onExecutionReport(message);   // ExecutionReport → OMS lifecycle
                case "9" -> execution.onCancelReject(message);      // OrderCancelReject
                default -> { /* other app messages ignored for now */ }
            }
        } catch (Exception e) {
            log.warn("fromApp handling error: {}", e.toString());
        }
    }

    private void refreshSeq(SessionID sessionId) {
        try {
            Session s = Session.lookupSession(sessionId);
            if (s != null) {
                state.setNextSenderMsgSeqNum(s.getExpectedSenderNum());
                state.setNextTargetMsgSeqNum(s.getExpectedTargetNum());
            }
        } catch (Exception ignore) {
            // best-effort status only
        }
    }

    /** Never log the FIX password (tag 554). */
    private String scrub(Message message) {
        return message.toString().replaceAll(PASSWORD_MASK, "554=***");
    }
}
