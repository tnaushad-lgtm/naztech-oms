package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Table(name = "equity_snapshot")
@Getter @Setter @NoArgsConstructor
public class EquitySnapshot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "account_id") private Long accountId;
    private LocalDateTime ts;
    @Column(name = "total_value")    private BigDecimal totalValue;
    private BigDecimal cash;
    @Column(name = "holdings_value") private BigDecimal holdingsValue;
    @Column(name = "unrealized_pnl") private BigDecimal unrealizedPnl;
    @Column(name = "realized_pnl")   private BigDecimal realizedPnl;
    @Column(name = "day_pnl")        private BigDecimal dayPnl;
}
