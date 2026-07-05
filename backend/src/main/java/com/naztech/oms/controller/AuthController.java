package com.naztech.oms.controller;

import com.naztech.oms.api.Dtos.LoginRequest;
import com.naztech.oms.api.Dtos.LoginResponse;
import com.naztech.oms.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) { this.auth = auth; }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest req) {
        try {
            LoginResponse res = auth.login(req.username(), req.password());
            return ResponseEntity.ok(res);
        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
    }
}
