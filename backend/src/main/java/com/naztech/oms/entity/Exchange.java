package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;

@Entity @Table(name = "exchange")
@Getter @Setter @NoArgsConstructor
public class Exchange {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String code;
    private String name;
    private String timezone;
    private String currency;
    @Column(name = "open_time")  private LocalTime openTime;
    @Column(name = "close_time") private LocalTime closeTime;
    private String status;
}
