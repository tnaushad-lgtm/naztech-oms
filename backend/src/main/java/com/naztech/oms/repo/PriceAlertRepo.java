package com.naztech.oms.repo;

import com.naztech.oms.entity.PriceAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PriceAlertRepo extends JpaRepository<PriceAlert, Long> {
    List<PriceAlert> findByAccountIdOrderByCreatedAtDesc(Long accountId);
    List<PriceAlert> findByStatus(String status);
}
