package com.naztech.oms.repo;

import com.naztech.oms.entity.Security;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SecurityRepo extends JpaRepository<Security, Long> {
    Optional<Security> findBySymbolAndExchangeId(String symbol, Long exchangeId);
    List<Security> findByExchangeId(Long exchangeId);
    List<Security> findByAssetClass(String assetClass);
}
