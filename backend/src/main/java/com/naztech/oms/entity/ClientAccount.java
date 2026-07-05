package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity @Table(name = "client_account")
@Getter @Setter @NoArgsConstructor
public class ClientAccount {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "broker_id")      private Long brokerId;
    @Column(name = "client_user_id") private Long clientUserId;
    @Column(name = "bo_id")          private String boId;
    private String name;
    @Column(name = "cash_balance") private BigDecimal cashBalance;
    @Column(name = "buying_power") private BigDecimal buyingPower;
    @Column(name = "realized_pnl") private BigDecimal realizedPnl;
    private String status;
}
