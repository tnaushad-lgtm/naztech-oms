package com.naztech.oms.controller;

import com.naztech.oms.entity.Exchange;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.ExchangeRepo;
import com.naztech.oms.repo.SecurityRepo;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SecurityController {

    private final SecurityRepo securityRepo;
    private final ExchangeRepo exchangeRepo;

    public SecurityController(SecurityRepo securityRepo, ExchangeRepo exchangeRepo) {
        this.securityRepo = securityRepo;
        this.exchangeRepo = exchangeRepo;
    }

    @GetMapping("/exchanges")
    public List<Exchange> exchanges() { return exchangeRepo.findAll(); }

    @GetMapping("/securities")
    public List<Security> securities(@RequestParam(required = false) String exchange,
                                     @RequestParam(required = false) String assetClass) {
        if (exchange != null) {
            Long exId = exchangeRepo.findByCode(exchange).map(Exchange::getId).orElse(-1L);
            return securityRepo.findByExchangeId(exId);
        }
        if (assetClass != null) return securityRepo.findByAssetClass(assetClass);
        return securityRepo.findAll();
    }

    @GetMapping("/securities/{id}")
    public Object security(@PathVariable Long id) {
        return securityRepo.findById(id).map(s -> (Object) s)
                .orElse(Map.of("error", "not found"));
    }
}
