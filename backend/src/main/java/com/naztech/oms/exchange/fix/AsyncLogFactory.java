package com.naztech.oms.exchange.fix;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Log;
import quickfix.LogFactory;
import quickfix.SessionID;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes the FIX message log on a background thread instead of on the thread that is sending the
 * order.
 *
 * <p>QuickFIX/J's {@code FileLog} writes every inbound and outbound message to disk synchronously,
 * inside the session's send path. Under load that put a disk write — under a lock — in the middle of
 * every order: the FIX send was measured at 11.4ms of the ~40ms an order cost, the largest single
 * slice, and it serialised every order behind every other one.
 *
 * <p>The log itself is not optional: it is the audit trail of what the OMS actually sent the
 * exchange, and the FIX Monitor and "Download Logs" screens read those very files. So this decorates
 * the real log rather than replacing it — same files, same format, same location — and only moves
 * the write off the hot path.
 *
 * <p><b>The trade-off, stated plainly:</b> messages are queued in memory and written a moment later,
 * so a hard crash (kill -9, power loss) can lose the last few log lines. It cannot lose orders or
 * fills — those are in the database and in the FIX session store, neither of which this touches. If
 * a regulator ever requires the message log to be synchronously durable, set {@code fix.async-log=false}
 * and take the latency.
 */
public class AsyncLogFactory implements LogFactory {

    private static final Logger log = LoggerFactory.getLogger(AsyncLogFactory.class);

    /** Bounded: if the writer somehow cannot keep up, we drop log lines rather than orders. */
    private static final int QUEUE_CAPACITY = 100_000;

    private final LogFactory delegate;
    private final BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong dropped = new AtomicLong();
    private final Thread writer;

    public AsyncLogFactory(LogFactory delegate) {
        this.delegate = delegate;
        this.writer = new Thread(this::drain, "fix-log-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    @Override
    public Log create(SessionID sessionID) {
        return new AsyncLog(delegate.create(sessionID));
    }

    private void drain() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                queue.take().run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("FIX log write failed: {}", e.toString());
            }
        }
    }

    private void submit(Runnable write) {
        if (!queue.offer(write) && dropped.incrementAndGet() % 1000 == 1) {
            log.warn("FIX log queue is full — {} message(s) not written to the log so far. "
                    + "Orders and fills are unaffected.", dropped.get());
        }
    }

    /** Hands every write to the background thread; the caller returns immediately. */
    private final class AsyncLog implements Log {

        private final Log delegate;

        private AsyncLog(Log delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onIncoming(String message) {
            submit(() -> delegate.onIncoming(message));
        }

        @Override
        public void onOutgoing(String message) {
            submit(() -> delegate.onOutgoing(message));
        }

        @Override
        public void onEvent(String text) {
            submit(() -> delegate.onEvent(text));
        }

        @Override
        public void onErrorEvent(String text) {
            submit(() -> delegate.onErrorEvent(text));
        }

        @Override
        public void clear() {
            delegate.clear();
        }
    }
}
