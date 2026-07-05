package com.naztech.oms.repo;

import com.naztech.oms.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepo extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findTop100ByOrderByCreatedAtDesc();
}
