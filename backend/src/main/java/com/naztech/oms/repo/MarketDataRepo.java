package com.naztech.oms.repo;

import com.naztech.oms.entity.MarketData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface MarketDataRepo extends JpaRepository<MarketData, Long> {
    @Query("select m from MarketData m order by m.valueMn desc")
    List<MarketData> findAllByTurnover();

    /**
     * Zero the day's cumulative counters for the given securities, in one statement. Called when the
     * ITCH feed is about to rebuild the whole day from a full replay (market open, or a venue restart) —
     * the replay re-adds every trade, so the running totals must start from nothing or each restart
     * stacks another day on top. Last price, YCP and bid/ask are left alone: the replay overwrites LTP,
     * YCP is yesterday's close, and open/high/low rebuild from the first replayed trade.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update MarketData m set m.volume = 0, m.trades = 0, m.valueMn = 0, "
            + "m.openPrice = null, m.highPrice = null, m.lowPrice = null where m.securityId in :ids")
    int resetDayStats(@Param("ids") Collection<Long> ids);
}
