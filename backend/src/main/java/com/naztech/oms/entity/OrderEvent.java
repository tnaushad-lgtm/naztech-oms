package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity @Table(name = "order_event")
@Getter @Setter @NoArgsConstructor
public class OrderEvent {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_id")   private Long orderId;
    @Column(name = "event_type") private String eventType;
    private String detail;
    @Column(name = "created_at") private LocalDateTime createdAt;
}
