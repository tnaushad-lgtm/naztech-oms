package com.naztech.oms.repo;

import com.naztech.oms.entity.OmsOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OmsOrderRepo extends JpaRepository<OmsOrder, Long> {
    Optional<OmsOrder> findByOrderRef(String orderRef);   // ClOrdID lookup for FIX execution reports
    List<OmsOrder> findByBrokerIdOrderByCreatedAtDesc(Long brokerId);
    List<OmsOrder> findByAccountIdOrderByCreatedAtDesc(Long accountId);
    List<OmsOrder> findTop200ByOrderByCreatedAtDesc();
    List<OmsOrder> findBySecurityIdAndSideAndStatusIn(Long securityId, String side, List<String> statuses);
    List<OmsOrder> findByStatusIn(List<String> statuses);

    @Query("select o from OmsOrder o where o.accountId = :accountId and o.securityId = :securityId " +
           "and o.side = :side and o.status in :statuses")
    List<OmsOrder> findWashCandidates(@Param("accountId") Long accountId,
                                      @Param("securityId") Long securityId,
                                      @Param("side") String side,
                                      @Param("statuses") List<String> statuses);
}
