package com.bengal.power;

import android.os.SystemClock;

import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns only the preparation deadline worker; never the coordinator's power/preparation workers. */
public final class AndroidPreparationTimer implements AccPowerCoordinator.Timer, AutoCloseable {
    static final int MAX_PENDING = 8;
    private final Object lock = new Object();
    private final ScheduledExecutorService scheduler;
    private int pending;
    private boolean closed;

    public AndroidPreparationTimer() { this(newScheduler()); }

    /** Test seam; ownership of this dedicated scheduler transfers to the timer. */
    AndroidPreparationTimer(ScheduledExecutorService scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    private static ScheduledExecutorService newScheduler() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "BengalPower-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    @Override public long monotonicMillis() { return SystemClock.elapsedRealtime(); }

    @Override public AccPowerCoordinator.Lease schedule(long timeoutMillis, Runnable timeout) {
        AndroidPowerHost.checkTimeout(timeoutMillis);
        Objects.requireNonNull(timeout);
        synchronized (lock) {
            if (closed || pending == MAX_PENDING) {
                throw new RejectedExecutionException("preparation timer closed or at capacity");
            }
            pending++;
        }
        Ticket ticket = new Ticket(timeout);
        try {
            ticket.attach(Objects.requireNonNull(
                    scheduler.schedule(ticket, timeoutMillis, TimeUnit.MILLISECONDS)));
        } catch (RuntimeException failure) {
            ticket.release();
            throw failure;
        }
        return ticket;
    }

    private void finished() { synchronized (lock) { pending--; } }

    private final class Ticket implements Runnable, AccPowerCoordinator.Lease {
        private final Runnable timeout;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private volatile ScheduledFuture<?> future;
        Ticket(Runnable timeout) { this.timeout = timeout; }
        void attach(ScheduledFuture<?> scheduled) {
            future = scheduled;
            if (claimed.get()) scheduled.cancel(false);
        }
        @Override public void run() {
            if (!claimed.compareAndSet(false, true)) return;
            try { timeout.run(); }
            finally { finished(); }
        }
        @Override public void release() {
            if (!claimed.compareAndSet(false, true)) return;
            try {
                ScheduledFuture<?> scheduled = future;
                if (scheduled != null) scheduled.cancel(false);
            } finally { finished(); }
        }
    }

    /**
     * Owner must first close the coordinator and drain its pending power compensation.
     * This guard rejects active timer work; it is not proof the separate power lane drained.
     * No shutdownNow or interruption of a running callback is used.
     */
    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            if (pending != 0) throw new IllegalStateException("preparation deadlines still active");
            closed = true;
        }
        scheduler.shutdown();
    }
}
