package com.naztech.oms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * NAZTECH OMS — Exchange-Hosted Order Management System for DSE / CSE.
 *
 * <p>A web-based, centrally hosted OMS for TREC-holders (brokers): submit, validate,
 * route, modify and track client orders, with pre-trade risk controls, a pluggable
 * matching-engine gateway, real BD market data, and on-prem AI (semantic search +
 * order risk scoring). Built for the DSE "License &amp; AMC" RFP.
 */
@SpringBootApplication
@EnableScheduling
public class NaztechOmsApplication {
    public static void main(String[] args) {
        SpringApplication.run(NaztechOmsApplication.class, args);
    }
}
