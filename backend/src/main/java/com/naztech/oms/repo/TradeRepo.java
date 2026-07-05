package com.naztech.oms.repo;

import com.naztech.oms.entity.Trade;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TradeRepo extends JpaRepository<Trade, Long> {
    List<Trade> findTop100BySecurityIdOrderByExecutedAtDesc(Long securityId);
    List<Trade> findTop1000BySecurityIdOrderByExecutedAtDesc(Long securityId);
    List<Trade> findTop200ByOrderByExecutedAtDesc();
    List<Trade> findTop200ByBuyOrderIdInOrSellOrderIdInOrderByExecutedAtDesc(
            java.util.Collection<Long> buyIds, java.util.Collection<Long> sellIds);
}
