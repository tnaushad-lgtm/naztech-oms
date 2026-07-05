package com.naztech.oms.repo;

import com.naztech.oms.entity.ClientAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClientAccountRepo extends JpaRepository<ClientAccount, Long> {
    List<ClientAccount> findByBrokerId(Long brokerId);
    Optional<ClientAccount> findByBoId(String boId);
    Optional<ClientAccount> findFirstByClientUserId(Long clientUserId);
}
