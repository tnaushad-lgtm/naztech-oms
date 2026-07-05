package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity @Table(name = "holding")
@Getter @Setter @NoArgsConstructor
public class Holding {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "account_id")  private Long accountId;
    @Column(name = "security_id") private Long securityId;
    private Long quantity;
    @Column(name = "avg_cost") private BigDecimal avgCost;
}
