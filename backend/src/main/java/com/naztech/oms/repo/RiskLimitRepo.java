package com.naztech.oms.repo;

import com.naztech.oms.entity.RiskLimit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RiskLimitRepo extends JpaRepository<RiskLimit, Long> {
    Optional<RiskLimit> findByScopeAndEntityId(String scope, Long entityId);
}
