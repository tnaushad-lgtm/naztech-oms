package com.naztech.oms.repo;

import com.naztech.oms.entity.PriceHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceHistoryRepo extends JpaRepository<PriceHistory, Long> {
    List<PriceHistory> findBySecurityIdOrderByTradeDateAsc(Long securityId);
}
