package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity @Table(name = "oms_order")
@Getter @Setter @NoArgsConstructor
public class OmsOrder {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "order_ref")   private String orderRef;
    @Column(name = "exchange_id") private Long exchangeId;
    @Column(name = "broker_id")   private Long brokerId;
    @Column(name = "dealer_id")   private Long dealerId;
    @Column(name = "account_id")  private Long accountId;
    @Column(name = "security_id") private Long securityId;
    private String side;                  // BUY / SELL
    @Column(name = "order_type")   private String orderType;   // LIMIT/MARKET/STOP/STOP_LIMIT
    @Column(name = "trade_window") private String tradeWindow;  // NORMAL/SPOT/BLOCK/...
    private String validity;              // DAY/GTD/GTC/GTS
    @Column(name = "expire_date") private LocalDate expireDate;
    private BigDecimal price;
    @Column(name = "stop_price") private BigDecimal stopPrice;
    @Column(name = "order_yield") private BigDecimal orderYield;   // yield the order was placed at (bonds)
    @Column(name = "price_basis") private String priceBasis;       // PRICE | YIELD
    private Long quantity;
    @Column(name = "filled_qty")      private Long filledQty;
    @Column(name = "avg_fill_price")  private BigDecimal avgFillPrice;
    private String status;                // NEW/PENDING_RISK/REJECTED/OPEN/PARTIAL/FILLED/CANCELLED/EXPIRED
    @Column(name = "reject_reason") private String rejectReason;
    @Column(name = "risk_score")    private BigDecimal riskScore;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;

    public long remainingQty() {
        long f = filledQty == null ? 0 : filledQty;
        return (quantity == null ? 0 : quantity) - f;
    }
}
