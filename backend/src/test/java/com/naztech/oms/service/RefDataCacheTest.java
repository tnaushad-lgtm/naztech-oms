package com.naztech.oms.service;

import com.naztech.oms.entity.RiskLimit;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.RiskLimitRepo;
import com.naztech.oms.repo.SecurityRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cache exists for speed, but the only thing that can go wrong with it is correctness — so these
 * tests are about staleness, not hit rates. The sharpest case is the broker's kill-switch, which is
 * why the broker is not cached here at all: {@link #doesNotCacheTheBroker()}.
 */
class RefDataCacheTest {

    private SecurityRepo securityRepo;
    private RiskLimitRepo limitRepo;

    @BeforeEach
    void setUp() {
        securityRepo = mock(SecurityRepo.class);
        limitRepo = mock(RiskLimitRepo.class);

        Security sec = new Security();
        sec.setId(7L);
        sec.setSymbol("BRACBANK");
        when(securityRepo.findById(anyLong())).thenReturn(Optional.of(sec));

        RiskLimit lim = new RiskLimit();
        lim.setScope("CLIENT");
        when(limitRepo.findByScopeAndEntityId(anyString(), anyLong())).thenReturn(Optional.of(lim));
    }

    @Test
    @DisplayName("reads reference data once and serves the rest from memory")
    void cachesWithinTheTtl() {
        RefDataCache cache = new RefDataCache(securityRepo, limitRepo, 60_000);

        for (int i = 0; i < 50; i++) {
            assertThat(cache.security(7L)).isPresent();
            assertThat(cache.limit("CLIENT", 1L)).isPresent();
        }

        verify(securityRepo, times(1)).findById(7L);                       // 50 orders, 1 query
        verify(limitRepo, times(1)).findByScopeAndEntityId("CLIENT", 1L);
    }

    @Test
    @DisplayName("an edited risk limit applies to the next order, not a TTL later")
    void invalidateAppliesAnEditedLimitImmediately() {
        RefDataCache cache = new RefDataCache(securityRepo, limitRepo, 60_000);

        RiskLimit before = new RiskLimit();
        before.setScope("CLIENT");
        before.setMaxOrderQty(200_000L);
        when(limitRepo.findByScopeAndEntityId("CLIENT", 1L)).thenReturn(Optional.of(before));
        assertThat(cache.limit("CLIENT", 1L).orElseThrow().getMaxOrderQty()).isEqualTo(200_000L);

        // RMS edits the limit and invalidates — as RmsController does.
        RiskLimit after = new RiskLimit();
        after.setScope("CLIENT");
        after.setMaxOrderQty(500L);
        when(limitRepo.findByScopeAndEntityId("CLIENT", 1L)).thenReturn(Optional.of(after));
        cache.invalidate();

        assertThat(cache.limit("CLIENT", 1L).orElseThrow().getMaxOrderQty()).isEqualTo(500L);
    }

    @Test
    @DisplayName("without an explicit invalidation, staleness is bounded by the TTL")
    void expiresAfterTheTtl() throws Exception {
        RefDataCache cache = new RefDataCache(securityRepo, limitRepo, 50);

        assertThat(cache.security(7L)).isPresent();
        Thread.sleep(80);
        assertThat(cache.security(7L)).isPresent();

        verify(securityRepo, times(2)).findById(7L);   // re-read once the TTL lapsed
    }

    @Test
    @DisplayName("the broker is NOT cacheable here — the kill-switch must never read a stale status")
    void doesNotCacheTheBroker() {
        // A halt can be applied by any code path (RmsController, a repo save in a test, another node
        // entirely), and none of them can reliably invalidate this cache. So RiskService reads the
        // broker straight from the repository, and RefDataCache offers no way to cache it at all.
        assertThat(RefDataCache.class.getDeclaredMethods())
                .as("RefDataCache must not expose a broker cache")
                .noneMatch(m -> m.getName().toLowerCase().contains("broker"));
    }

    @Test
    @DisplayName("a missing row is cached too, so a bad id cannot hammer the database")
    void cachesAbsence() {
        when(securityRepo.findById(99L)).thenReturn(Optional.empty());
        RefDataCache cache = new RefDataCache(securityRepo, limitRepo, 60_000);

        for (int i = 0; i < 20; i++) {
            assertThat(cache.security(99L)).isEmpty();
        }
        verify(securityRepo, times(1)).findById(99L);
    }
}
