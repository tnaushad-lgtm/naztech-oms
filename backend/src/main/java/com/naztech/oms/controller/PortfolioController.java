package com.naztech.oms.controller;

import com.naztech.oms.api.Dtos.PortfolioView;
import com.naztech.oms.entity.ClientAccount;
import com.naztech.oms.repo.ClientAccountRepo;
import com.naztech.oms.service.EquityService;
import com.naztech.oms.service.PortfolioService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PortfolioController {

    private final PortfolioService portfolio;
    private final ClientAccountRepo accountRepo;
    private final EquityService equity;

    public PortfolioController(PortfolioService portfolio, ClientAccountRepo accountRepo, EquityService equity) {
        this.portfolio = portfolio;
        this.accountRepo = accountRepo;
        this.equity = equity;
    }

    @GetMapping("/accounts")
    public List<ClientAccount> accounts(@RequestParam(required = false) Long brokerId) {
        return brokerId == null ? accountRepo.findAll() : accountRepo.findByBrokerId(brokerId);
    }

    @GetMapping("/portfolio/{accountId}")
    public Object portfolio(@PathVariable Long accountId) {
        PortfolioView v = portfolio.portfolio(accountId);
        return v == null ? Map.of("error", "account not found") : v;
    }

    @GetMapping("/portfolio/{accountId}/fills")
    public Object fills(@PathVariable Long accountId) {
        return portfolio.fills(accountId);
    }

    @GetMapping("/portfolio/{accountId}/equity")
    public Object equity(@PathVariable Long accountId, @RequestParam(defaultValue = "120") int limit) {
        return equity.series(accountId, limit);
    }

    /** Broker-wide trade book for the back-office report. */
    @GetMapping("/reports/tradebook")
    public Object tradebook(@RequestParam Long brokerId) {
        return portfolio.brokerFills(brokerId);
    }
}
