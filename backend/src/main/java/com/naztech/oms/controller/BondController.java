package com.naztech.oms.controller;

import com.naztech.oms.api.Dtos.BondQuote;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.SecurityRepo;
import com.naztech.oms.service.BondService;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/** Live bond price↔yield quoting for the order ticket. */
@RestController
@RequestMapping("/api/bonds")
public class BondController {

    private final SecurityRepo securityRepo;
    private final BondService bonds;

    public BondController(SecurityRepo securityRepo, BondService bonds) {
        this.securityRepo = securityRepo;
        this.bonds = bonds;
    }

    /** GET /api/bonds/{securityId}/quote?basis=YIELD&value=8.5  (or basis=PRICE&value=98.4) */
    @GetMapping("/{securityId}/quote")
    public BondQuote quote(@PathVariable Long securityId,
                           @RequestParam(defaultValue = "YIELD") String basis,
                           @RequestParam BigDecimal value) {
        Security s = securityRepo.findById(securityId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown security"));
        if (!bonds.isBond(s)) throw new IllegalArgumentException(s.getSymbol() + " is not a bond");
        return bonds.quote(s, basis, value);
    }
}
