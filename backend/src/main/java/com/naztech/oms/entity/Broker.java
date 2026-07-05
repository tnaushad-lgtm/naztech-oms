package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Table(name = "broker")
@Getter @Setter @NoArgsConstructor
public class Broker {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "exchange_id") private Long exchangeId;
    @Column(name = "trec_code")   private String trecCode;
    private String name;
    private String status;
    @Column(name = "firm_limit")    private BigDecimal firmLimit;
    @Column(name = "contact_email") private String contactEmail;
    @Column(name = "onboarded_at")  private LocalDateTime onboardedAt;
}
