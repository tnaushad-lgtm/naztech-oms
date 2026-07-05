package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Table(name = "price_alert")
@Getter @Setter @NoArgsConstructor
public class PriceAlert {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "account_id")  private Long accountId;
    @Column(name = "security_id") private Long securityId;
    @Column(name = "target_price") private BigDecimal targetPrice;
    private String direction;            // ABOVE | BELOW
    private String status;               // ACTIVE | TRIGGERED | CANCELLED
    private String note;
    @Column(name = "ltp_at_trigger") private BigDecimal ltpAtTrigger;
    @Column(name = "created_at")    private LocalDateTime createdAt;
    @Column(name = "triggered_at")  private LocalDateTime triggeredAt;
}
