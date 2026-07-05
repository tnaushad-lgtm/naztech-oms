package com.naztech.oms.service;

import com.naztech.oms.api.Dtos.AlertRequest;
import com.naztech.oms.api.Dtos.AlertView;
import com.naztech.oms.entity.Security;
import com.naztech.oms.repo.ExchangeRepo;
import com.naztech.oms.repo.MarketDataRepo;
import com.naztech.oms.repo.SecurityRepo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Price-alert lifecycle: an ABOVE alert set just under the live price fires on the next sweep. */
@SpringBootTest
class PriceAlertServiceTest {

    @Autowired PriceAlertService svc;
    @Autowired SecurityRepo securityRepo;
    @Autowired MarketDataRepo marketRepo;
    @Autowired ExchangeRepo exchangeRepo;

    @Test
    void alert_triggers_when_price_crosses_target() {
        Long dseId = exchangeRepo.findByCode("DSE").orElseThrow().getId();
        Security gp = securityRepo.findBySymbolAndExchangeId("GP", dseId).orElseThrow();
        BigDecimal ltp = marketRepo.findById(gp.getId()).orElseThrow().getLtp();
        Assumptions.assumeTrue(ltp != null && ltp.signum() > 0, "needs a live GP price");

        AlertView a = svc.create(new AlertRequest(1L, gp.getId(), ltp.subtract(BigDecimal.ONE), "ABOVE", "unit-test"));
        assertThat(a.status()).isEqualTo("ACTIVE");

        svc.evaluate();

        List<AlertView> mine = svc.byAccount(1L);
        AlertView found = mine.stream().filter(x -> x.id().equals(a.id())).findFirst().orElseThrow();
        assertThat(found.status()).isEqualTo("TRIGGERED");
        assertThat(found.ltpAtTrigger()).isNotNull();
    }
}
