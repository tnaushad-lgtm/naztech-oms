package com.naztech.oms.exchange.fix;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Live FIX session health, updated by {@link OmsFixApplication}. Always present (even in simulator
 * mode) so the Exchange-Connectivity admin page (Phase 6) can show current status. Thread-safe via
 * volatile fields; read-only from the OMS side.
 */
@Getter
@Setter
@Component
public class FixSessionState {

    private volatile boolean loggedOn = false;
    private volatile String sessionId = "-";
    private volatile String lastEvent = "NOT_STARTED";
    private volatile Instant lastEventAt;
    private volatile Instant lastHeartbeatAt;
    private volatile String lastInMsgType;
    private volatile String lastOutMsgType;
    private volatile int nextSenderMsgSeqNum;
    private volatile int nextTargetMsgSeqNum;

    /** Record a lifecycle event with its timestamp. */
    public void event(String e) {
        this.lastEvent = e;
        this.lastEventAt = Instant.now();
    }
}
