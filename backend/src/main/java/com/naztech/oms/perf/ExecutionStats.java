package com.naztech.oms.perf;

import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * What actually came back from the exchange, and what this machine was doing while it did.
 *
 * <p>A throughput number on its own proves very little: orders sent fast and never filled is not a
 * working OMS, it is a fast way to lose them. The performance report has to show the round trip —
 * executions received, partial fills, full fills — alongside the cost of producing it (CPU, memory,
 * threads, and how far behind schedule the generator fell).
 */
@Component
public class ExecutionStats {

    private final LongAdder executions = new LongAdder();     // ExecutionReports processed
    private final LongAdder partialFills = new LongAdder();
    private final LongAdder fullFills = new LongAdder();
    private final LongAdder exchangeRejects = new LongAdder();
    private final LongAdder cancels = new LongAdder();

    /** Called for every ExecutionReport the OMS applies, whichever venue it came from. */
    public void execution(String ordStatus) {
        executions.increment();
        if (ordStatus == null) {
            return;
        }
        switch (ordStatus) {
            case "PARTIAL" -> partialFills.increment();
            case "FILLED" -> fullFills.increment();
            case "REJECTED" -> exchangeRejects.increment();
            case "CANCELLED" -> cancels.increment();
            default -> { /* OPEN / EXPIRED — counted in executions only */ }
        }
    }

    public void reset() {
        executions.reset();
        partialFills.reset();
        fullFills.reset();
        exchangeRejects.reset();
        cancels.reset();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("executions", executions.sum());
        m.put("partialFills", partialFills.sum());
        m.put("fullFills", fullFills.sum());
        m.put("exchangeRejects", exchangeRejects.sum());
        m.put("cancels", cancels.sum());
        return m;
    }

    /** What the JVM and the box were doing. The demo has to show the cost, not just the rate. */
    public static Map<String, Object> resources() {
        Runtime rt = Runtime.getRuntime();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);

        double cpu = -1;
        try {
            // com.sun's extension carries process CPU load; not on every JVM, so it is optional.
            var os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                cpu = sun.getProcessCpuLoad() * 100.0;
            }
        } catch (Throwable ignored) {
            // a missing CPU reading must not fail a performance run
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpuPct", cpu < 0 ? null : Math.round(cpu * 10) / 10.0);
        m.put("heapUsedMb", usedMb);
        m.put("heapMaxMb", maxMb);
        m.put("threads", threads.getThreadCount());
        m.put("cores", rt.availableProcessors());
        return m;
    }
}
