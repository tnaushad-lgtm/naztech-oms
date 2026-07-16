package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Table(name = "trade")
@Getter @Setter @NoArgsConstructor
public class Trade {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "trade_ref")   private String tradeRef;
    @Column(name = "security_id") private Long securityId;
    @Column(name = "buy_order_id")  private Long buyOrderId;
    @Column(name = "sell_order_id") private Long sellOrderId;
    private BigDecimal price;
    private Long quantity;
    @Column(name = "aggressor_side") private String aggressorSide;
    @Column(name = "executed_at")    private LocalDateTime executedAt;

    // ---- fixed income (DSE Bond BRS §1.1.2 / §1.1.8): null for equities ----
    /** Accrued interest per unit at settlement. Settlement price = price (clean) + accrued. */
    @Column(name = "accrued_interest") private BigDecimal accruedInterest;
    /** Yield implied by the traded clean price — shown on trades per the BRS ("displayed on ... Trades Tables"). */
    @Column(name = "trade_yield")      private BigDecimal tradeYield;
}
