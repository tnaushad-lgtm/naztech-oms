package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity @Table(name = "price_history")
@Getter @Setter @NoArgsConstructor
public class PriceHistory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "security_id") private Long securityId;
    @Column(name = "trade_date")  private LocalDate tradeDate;
    @Column(name = "open_price")  private BigDecimal openPrice;
    @Column(name = "high_price")  private BigDecimal highPrice;
    @Column(name = "low_price")   private BigDecimal lowPrice;
    @Column(name = "close_price") private BigDecimal closePrice;
    private Long volume;
}
