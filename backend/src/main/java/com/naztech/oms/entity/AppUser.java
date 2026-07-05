package com.naztech.oms.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity @Table(name = "app_user")
@Getter @Setter @NoArgsConstructor
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    @Column(name = "display_name")  private String displayName;
    @Column(name = "password_hash") private String passwordHash;
    private String role;
    @Column(name = "broker_id") private Long brokerId;
    @Column(name = "parent_id") private Long parentId;
    private String status;
    @Column(name = "session_token") private String sessionToken;
    @Column(name = "last_login")    private LocalDateTime lastLogin;
    @Column(name = "created_at")    private LocalDateTime createdAt;
}
