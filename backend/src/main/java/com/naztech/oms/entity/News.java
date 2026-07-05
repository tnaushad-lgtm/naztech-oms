package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity @Table(name = "news")
@Getter @Setter @NoArgsConstructor
public class News {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "exchange_id") private Long exchangeId;
    @Column(name = "broker_id")   private Long brokerId;
    private String category;
    private String symbol;
    private String title;
    @Column(columnDefinition = "TEXT") private String body;
    @Column(name = "attachment_url") private String attachmentUrl;
    private String sentiment;
    @Column(name = "published_at") private LocalDateTime publishedAt;
}
