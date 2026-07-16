package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Entity @Table(name = "security")
@Getter @Setter @NoArgsConstructor
public class Security {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "exchange_id") private Long exchangeId;
    private String symbol;
    private String name;
    @Column(name = "asset_class") private String assetClass;
    private String board;
    private String sector;
    @Column(name = "face_value") private BigDecimal faceValue;
    @Column(name = "lot_size")   private Integer lotSize;
    @Column(name = "tick_size")  private BigDecimal tickSize;
    @Column(name = "market_lot") private Integer marketLot;
    private String status;
    private String category;                 // A / B / N / Z / G (DSE share category)
    @Column(name = "is_shariah") private Boolean shariah;

    // ---- fixed-income (bonds / sukuk); null for equities ----
    @Column(name = "coupon_rate")   private BigDecimal couponRate;   // annual coupon %
    @Column(name = "coupon_freq")   private Integer couponFreq;      // coupon payments per year
    @Column(name = "maturity_date") private LocalDate maturityDate;  // null = perpetual
    @Column(name = "issue_date")    private LocalDate issueDate;     // DSE BRS §1.2 config attribute
    @Column(name = "total_issued")  private Long totalIssued;        // total issued quantity
    @Column(name = "day_count")     private String dayCount;         // ACT/ACT (the BRS-required convention)

    /** Text used to build the semantic search embedding. */
    public String toSearchText() {
        return (symbol == null ? "" : symbol) + " " +
               (name == null ? "" : name) + " " +
               (sector == null ? "" : sector) + " " +
               (assetClass == null ? "" : assetClass);
    }
}
