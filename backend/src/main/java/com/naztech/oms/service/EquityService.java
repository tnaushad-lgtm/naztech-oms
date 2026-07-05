package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.EquityPoint;
import com.naztech.oms.api.Dtos.PortfolioView;
import com.naztech.oms.entity.ClientAccount;
import com.naztech.oms.entity.EquitySnapshot;
import com.naztech.oms.repo.ClientAccountRepo;
import com.naztech.oms.repo.EquitySnapshotRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Records point-in-time account equity for the P&amp;L-over-time / equity-curve charts.
 * A background job appends a live snapshot periodically; the first time a curve is
 * requested for a thin account it is back-filled with a deterministic ~30-day history
 * anchored to the current value, so the chart is immediately meaningful in a demo.
 */
@Service
public class EquityService {

    private static final Logger log = LoggerFactory.getLogger(EquityService.class);

    private final PortfolioService portfolio;
    private final ClientAccountRepo accountRepo;
    private final EquitySnapshotRepo repo;

    public EquityService(PortfolioService portfolio, ClientAccountRepo accountRepo, EquitySnapshotRepo repo) {
        this.portfolio = portfolio;
        this.accountRepo = accountRepo;
        this.repo = repo;
    }

    /** Append a live equity snapshot for every account every 2 minutes. */
    @Scheduled(fixedDelay = 120_000, initialDelay = 60_000)
    public void snapshotAll() {
        for (ClientAccount a : accountRepo.findAll()) {
            try { snapshot(a.getId()); } catch (Exception e) { log.debug("snapshot failed {}", e.getMessage()); }
        }
    }

    @Transactional
    public void snapshot(Long accountId) {
        PortfolioView p = portfolio.portfolio(accountId);
        if (p == null) return;
        save(accountId, LocalDateTime.now(), p.totalValue(), p.cash(), p.holdingsValue(),
                p.unrealizedPnl(), p.realizedPnl(), p.dayPnl());
    }

    public List<EquityPoint> series(Long accountId, int limit) {
        if (repo.countByAccountId(accountId) < 5) backfill(accountId);
        List<EquitySnapshot> rows = repo.findByAccountIdOrderByTsAsc(accountId);
        int from = Math.max(0, rows.size() - limit);
        List<EquityPoint> out = new ArrayList<>();
        for (EquitySnapshot s : rows.subList(from, rows.size())) {
            long t = s.getTs().atZone(ZoneId.systemDefault()).toEpochSecond();
            double unreal = d(s.getUnrealizedPnl()), real = d(s.getRealizedPnl());
            out.add(new EquityPoint(t, d(s.getTotalValue()), unreal, real, unreal + real, d(s.getDayPnl())));
        }
        return out;
    }

    /** Deterministic 30-day daily history ending at the current equity. */
    @Transactional
    public void backfill(Long accountId) {
        PortfolioView p = portfolio.portfolio(accountId);
        if (p == null) return;
        double curTotal = d(p.totalValue());
        double costBase = curTotal - d(p.unrealizedPnl());   // holdings cost + cash (kept constant)
        double realized = d(p.realizedPnl());
        int days = 30;
        Random rnd = new Random(accountId * 7919L);
        double[] totals = new double[days];
        totals[days - 1] = curTotal;
        for (int i = days - 2; i >= 0; i--)
            totals[i] = Math.max(costBase * 0.4, totals[i + 1] / (1 + (rnd.nextDouble() - 0.5) * 0.02));
        LocalDateTime now = LocalDateTime.now();
        double prev = totals[0];
        for (int i = 0; i < days; i++) {
            double total = Math.round(totals[i] * 100) / 100.0;
            double unreal = total - costBase;
            double dayPnl = total - prev; prev = total;
            LocalDateTime ts = now.minusDays(days - 1 - i).withHour(15).withMinute(0).withSecond(0).withNano(0);
            save(accountId, ts, bd(total), p.cash(), bd(total - d(p.cash())), bd(unreal), bd(realized), bd(dayPnl));
        }
        log.info("Back-filled {} equity points for account {}", days, accountId);
    }

    private void save(Long accountId, LocalDateTime ts, BigDecimal total, BigDecimal cash, BigDecimal holdings,
                      BigDecimal unreal, BigDecimal realized, BigDecimal dayPnl) {
        EquitySnapshot s = new EquitySnapshot();
        s.setAccountId(accountId); s.setTs(ts);
        s.setTotalValue(total); s.setCash(cash); s.setHoldingsValue(holdings);
        s.setUnrealizedPnl(unreal); s.setRealizedPnl(realized); s.setDayPnl(dayPnl);
        repo.save(s);
    }

    private static double d(BigDecimal b) { return b == null ? 0 : b.doubleValue(); }
    private static BigDecimal bd(double v) { return BigDecimal.valueOf(Math.round(v * 100) / 100.0); }
}
