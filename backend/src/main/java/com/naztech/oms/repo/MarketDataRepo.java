package com.naztech.oms.repo;

import com.naztech.oms.entity.MarketData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MarketDataRepo extends JpaRepository<MarketData, Long> {
    @Query("select m from MarketData m order by m.valueMn desc")
    List<MarketData> findAllByTurnover();
}
