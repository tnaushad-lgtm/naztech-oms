package com.naztech.oms.exchange.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Valkey (or Redis, or Memurai on Windows): the live market picture — quotes, depth, tick fan-out.
 * Off by default; the OMS falls back to MySQL and the in-JVM book.
 *
 * <p>A password, if the deployment uses one, belongs in the gitignored {@code secrets.properties}
 * ({@code hotstore.password}) — never here.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "hotstore")
public class HotStoreProperties {

    private boolean enabled = false;
    private String host = "127.0.0.1";
    private int port = 6379;
    private String password;
    private int timeoutMs = 500;              // a market-data cache must never hold up an order
    private int maxConnections = 32;

    /** Depth is worthless when stale: a snapshot expires rather than being served as if current. */
    private int depthTtlSeconds = 30;
}
