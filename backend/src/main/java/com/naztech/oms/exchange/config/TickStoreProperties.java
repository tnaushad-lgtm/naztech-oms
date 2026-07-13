package com.naztech.oms.exchange.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * QuestDB: the tick time-series. Off by default — the OMS runs without it exactly as it always has.
 *
 * <p>Ingest is the InfluxDB line protocol on {@code ilp-port}; queries are SQL over HTTP on
 * {@code http-port}. No driver, no client library.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "tickstore")
public class TickStoreProperties {

    private boolean enabled = false;
    private String host = "127.0.0.1";
    private int ilpPort = 9009;      // line protocol (writes)
    private int httpPort = 9000;     // SQL over HTTP (reads, DDL)
}
