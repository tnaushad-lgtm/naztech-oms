package com.naztech.oms.service;

import com.naztech.oms.entity.RiskLimit;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.RiskLimitRepo;
import com.naztech.oms.repo.SecurityRepo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches the reference data every order re-reads: the security and the three risk-limit rows. These
 * change a few times a day, yet each order was paying a database round-trip for each of them —
 * measured at ~9.7ms of the ~40ms an order costs, most of it spent re-fetching rows that had not
 * changed.
 *
 * <h2>What is deliberately NOT cached, and why</h2>
 * <ul>
 *   <li><b>The broker</b> — it carries the RMS kill-switch. Caching it means a halt can be applied
 *       or lifted without this cache knowing: any code path that saves a Broker outside
 *       {@code RmsController} leaves us stale, and in a multi-node deployment one node's
 *       {@link #invalidate()} cannot reach another node's memory. A kill-switch that keeps trading
 *       after a halt is the one outcome an RMS may never produce, so the broker is read fresh on
 *       every order. It costs one indexed primary-key read.</li>
 *   <li><b>The client account</b> (buying power moves on every fill), <b>holdings</b>, and
 *       <b>market data</b> (the LTP changes constantly) — risk decisions on stale values of these
 *       are wrong decisions.</li>
 * </ul>
 *
 * <p>What remains cached is bounded by a short TTL ({@code app.refdata.cache-ms}, default 1000ms)
 * and invalidated outright by {@code RmsController} when a limit is edited, so an RMS change applies
 * to the very next order rather than a second later.
 */
@Component
public class RefDataCache {

    private record Entry<T>(T value, long expiresAtNanos) {
        boolean fresh() {
            return System.nanoTime() < expiresAtNanos;
        }
    }

    private final SecurityRepo securityRepo;
    private final RiskLimitRepo limitRepo;

    private final Map<Long, Entry<Optional<Security>>> securities = new ConcurrentHashMap<>();
    private final Map<String, Entry<Optional<RiskLimit>>> limits = new ConcurrentHashMap<>();

    private final long ttlNanos;

    public RefDataCache(SecurityRepo securityRepo, RiskLimitRepo limitRepo,
                        @Value("${app.refdata.cache-ms:1000}") long ttlMs) {
        this.securityRepo = securityRepo;
        this.limitRepo = limitRepo;
        this.ttlNanos = ttlMs * 1_000_000L;
    }

    public Optional<Security> security(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        Entry<Optional<Security>> e = securities.get(id);
        if (e != null && e.fresh()) {
            return e.value();
        }
        Optional<Security> loaded = securityRepo.findById(id);
        securities.put(id, new Entry<>(loaded, System.nanoTime() + ttlNanos));
        return loaded;
    }

    public Optional<RiskLimit> limit(String scope, Long entityId) {
        if (scope == null || entityId == null) {
            return Optional.empty();
        }
        String key = scope + ":" + entityId;
        Entry<Optional<RiskLimit>> e = limits.get(key);
        if (e != null && e.fresh()) {
            return e.value();
        }
        Optional<RiskLimit> loaded = limitRepo.findByScopeAndEntityId(scope, entityId);
        limits.put(key, new Entry<>(loaded, System.nanoTime() + ttlNanos));
        return loaded;
    }

    /** Called by RMS whenever a limit is edited, so the change applies to the next order. */
    public void invalidate() {
        securities.clear();
        limits.clear();
    }
}
