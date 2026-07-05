package com.naztech.oms.repo;

import com.naztech.oms.entity.Broker;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BrokerRepo extends JpaRepository<Broker, Long> {
    List<Broker> findByExchangeId(Long exchangeId);
}
