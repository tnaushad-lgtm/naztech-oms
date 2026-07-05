package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity @Table(name = "audit_log")
@Getter @Setter @NoArgsConstructor
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String actor;
    private String action;
    @Column(name = "entity_type") private String entityType;
    @Column(name = "entity_id")   private String entityId;
    @Column(columnDefinition = "TEXT") private String detail;
    @Column(name = "ip_address") private String ipAddress;
    @Column(name = "created_at") private LocalDateTime createdAt;
}
