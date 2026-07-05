package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.LoginResponse;
import com.naztech.oms.entity.AppUser;
import com.naztech.oms.entity.Broker;
import com.naztech.oms.entity.ClientAccount;
import com.naztech.oms.repo.AppUserRepo;
import com.naztech.oms.repo.BrokerRepo;
import com.naztech.oms.repo.ClientAccountRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Username/password auth with single active session per user (RFP §2.6). */
@Service
public class AuthService {

    private final AppUserRepo userRepo;
    private final BrokerRepo brokerRepo;
    private final ClientAccountRepo accountRepo;
    private final AuditService audit;

    public AuthService(AppUserRepo userRepo, BrokerRepo brokerRepo,
                       ClientAccountRepo accountRepo, AuditService audit) {
        this.userRepo = userRepo;
        this.brokerRepo = brokerRepo;
        this.accountRepo = accountRepo;
        this.audit = audit;
    }

    @Transactional
    public LoginResponse login(String username, String password) {
        AppUser u = userRepo.findByUsername(username == null ? "" : username.trim())
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));
        if (!"ACTIVE".equals(u.getStatus()))
            throw new IllegalStateException("User is " + u.getStatus());
        if (!sha256(password).equalsIgnoreCase(u.getPasswordHash()))
            throw new IllegalArgumentException("Invalid username or password");

        String token = UUID.randomUUID().toString().replace("-", "");
        u.setSessionToken(token);            // overwrite => previous session invalidated
        u.setLastLogin(LocalDateTime.now());
        userRepo.save(u);

        String brokerName = null;
        Long defaultAccount = null;
        if (u.getBrokerId() != null) {
            Broker b = brokerRepo.findById(u.getBrokerId()).orElse(null);
            brokerName = b == null ? null : b.getName();
            // an investor (CLIENT) is scoped to their own account; others default to the first
            ClientAccount own = "CLIENT".equals(u.getRole())
                    ? accountRepo.findFirstByClientUserId(u.getId()).orElse(null) : null;
            if (own != null) defaultAccount = own.getId();
            else {
                List<ClientAccount> accts = accountRepo.findByBrokerId(u.getBrokerId());
                if (!accts.isEmpty()) defaultAccount = accts.get(0).getId();
            }
        }
        audit.audit(username, "LOGIN", "USER", String.valueOf(u.getId()), "role=" + u.getRole());
        return new LoginResponse(token, u.getUsername(), u.getDisplayName(), u.getRole(),
                u.getBrokerId(), brokerName, defaultAccount);
    }

    public AppUser fromToken(String token) {
        if (token == null) return null;
        return userRepo.findBySessionToken(token).orElse(null);
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((s == null ? "" : s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
