package com.naztech.oms.exchange;

import com.naztech.oms.api.Dtos.Depth;
import com.naztech.oms.service.MarketDataGateway;
import com.naztech.oms.service.MatchingGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default market-data source (when {@code itch.enabled=false}): serves depth from the in-process
 * matching engine's book — i.e. exactly today's behaviour, unchanged. When the ITCH feed is turned
 * on, {@code ItchGateway} takes over instead.
 */
@Component
@ConditionalOnProperty(prefix = "itch", name = "enabled", havingValue = "false", matchIfMissing = true)
public class SimulatedMarketDataGateway implements MarketDataGateway {

    private final MatchingGateway matching;

    public SimulatedMarketDataGateway(MatchingGateway matching) {
        this.matching = matching;
    }

    @Override
    public Depth depth(Long securityId, int levels) {
        return matching.depth(securityId, levels);
    }

    @Override
    public String source() { return "simulator-book"; }
}
