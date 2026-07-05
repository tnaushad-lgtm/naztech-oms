package com.naztech.oms.repo;

import com.naztech.oms.entity.OrderEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderEventRepo extends JpaRepository<OrderEvent, Long> {
    List<OrderEvent> findByOrderIdOrderByCreatedAtAsc(Long orderId);
}
