package com.naztech.oms.repo;

import com.naztech.oms.entity.Exchange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExchangeRepo extends JpaRepository<Exchange, Long> {
    Optional<Exchange> findByCode(String code);
}
