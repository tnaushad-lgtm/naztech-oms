package com.naztech.oms.repo;

import com.naztech.oms.entity.Holding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HoldingRepo extends JpaRepository<Holding, Long> {
    List<Holding> findByAccountId(Long accountId);
    Optional<Holding> findByAccountIdAndSecurityId(Long accountId, Long securityId);
}
