package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Latest snapshot per security (PK == security_id). Upserted by the feed/ME. */
@Entity @Table(name = "market_data")
@Getter @Setter @NoArgsConstructor
public class MarketData {
    @Id @Column(name = "security_id") private Long securityId;
    private BigDecimal ltp;
    @Column(name = "open_price")  private BigDecimal openPrice;
    @Column(name = "high_price")  private BigDecimal highPrice;
    @Column(name = "low_price")   private BigDecimal lowPrice;
    @Column(name = "close_price") private BigDecimal closePrice;
    private BigDecimal ycp;
    private BigDecimal bid;
    private BigDecimal ask;
    private Long volume;
    private Integer trades;
    @Column(name = "value_mn")   private BigDecimal valueMn;
    @Column(name = "change_pct") private BigDecimal changePct;
    private String source;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
}
