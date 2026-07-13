package com.naztech.oms.market;

import com.naztech.oms.entity.MarketData;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.MarketDataRepo;
import com.naztech.oms.repo.SecurityRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Keeps the local exchange's prices in step with the OMS's live market.
 *
 * <p>The venue decides what is marketable — whether a bid trades or rests. It was deciding that
 * against a hardcoded table of seeded prices, while the feed moved the real ones underneath it, so
 * the two drifted apart: a dealer would bid above the price on their screen and watch the order sit
 * there untouched, because the exchange still thought the stock was worth what it was worth on the
 * day the table was written. The exchange must trade against the market that exists.
 *
 * <p>Every push is also a crossing opportunity at the venue: a resting bid fills the moment the
 * market falls to it, which is what a book does and what makes the demo behave like one.
 *
 * <p>Only for the local exchange. Against a real venue this does nothing — DSE has its own prices,
 * and would not thank us for ours. Set {@code market.venue-control-url} blank to disable.
 */
@Component
public class VenuePriceSync {

    private static final Logger log = LoggerFactory.getLogger(VenuePriceSync.class);

    private final SecurityRepo securityRepo;
    private final MarketDataRepo marketRepo;
    private final MarketSessionService session;

    @Value("${market.venue-control-url:http://127.0.0.1:15001}")
    private String venueControlUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    private volatile boolean reachable = true;

    public VenuePriceSync(SecurityRepo securityRepo, MarketDataRepo marketRepo, MarketSessionService session) {
        this.securityRepo = securityRepo;
        this.marketRepo = marketRepo;
        this.session = session;
    }

    @Scheduled(fixedDelayString = "${market.venue-price-sync-ms:3000}")
    public void push() {
        if (venueControlUrl == null || venueControlUrl.isBlank() || !session.anyOpen()) {
            return;
        }
        Map<Long, BigDecimal> ltp = new HashMap<>();
        for (MarketData m : marketRepo.findAll()) {
            if (m.getLtp() != null && m.getLtp().signum() > 0) {
                ltp.put(m.getSecurityId(), m.getLtp());
            }
        }

        StringBuilder body = new StringBuilder();
        int n = 0;
        for (Security s : securityRepo.findAll()) {
            BigDecimal px = ltp.get(s.getId());
            if (px == null || "INDEX".equals(s.getAssetClass()) || !"ACTIVE".equals(s.getStatus())) {
                continue;
            }
            body.append(s.getSymbol()).append('=').append(px.toPlainString()).append('\n');
            n++;
        }
        if (n == 0) {
            return;
        }

        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(venueControlUrl + "/prices"))
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
            if (!reachable) {
                reachable = true;
                log.info("Venue price sync restored — {} instruments", n);
            }
        } catch (Exception e) {
            if (reachable) {
                reachable = false;
                log.warn("Venue price sync failed ({}) — the exchange will price off its seeded table "
                        + "until it comes back. Trading is unaffected.", e.toString());
            }
        }
    }
}
