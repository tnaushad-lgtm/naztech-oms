package com.naztech.oms.repo;

import com.naztech.oms.entity.EquitySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EquitySnapshotRepo extends JpaRepository<EquitySnapshot, Long> {
    List<EquitySnapshot> findByAccountIdOrderByTsAsc(Long accountId);
    long countByAccountId(Long accountId);
}
