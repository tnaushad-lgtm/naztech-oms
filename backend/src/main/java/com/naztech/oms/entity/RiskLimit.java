package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity @Table(name = "risk_limit")
@Getter @Setter @NoArgsConstructor
public class RiskLimit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String scope;                 // BROKER / TRADER / CLIENT
    @Column(name = "entity_id") private Long entityId;
    @Column(name = "max_order_value")    private BigDecimal maxOrderValue;
    @Column(name = "max_order_qty")      private Long maxOrderQty;
    @Column(name = "max_gross_exposure") private BigDecimal maxGrossExposure;
    @Column(name = "mtm_loss_limit")     private BigDecimal mtmLossLimit;
    @Column(name = "wash_sale_block")    private Boolean washSaleBlock;
    private Boolean enabled;
}
