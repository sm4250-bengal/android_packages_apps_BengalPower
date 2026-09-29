package com.bengal.power;

import android.os.PowerManager;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Executes the actual Android adapters against minimal Android doubles and a manual scheduler. */
public final class AndroidAdaptersTest {
    private static int tests;
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void test(String name, Runnable body) {
        body.run(); tests++; System.out.println("PASS adapter " + name);
    }
    private static void expect(Class<? extends RuntimeException> type, Runnable action) {
        try { action.run(); }
        catch (RuntimeException failure) {
            check(type.isInstance(failure), "wrong failure: " + failure); return;
        }
        throw new AssertionError("missing " + type.getSimpleName());
    }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "latch watchdog expired"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static Thread start(Runnable task, AtomicReference<Throwable> failure) {
        Thread thread = new Thread(() -> {
            try { task.run(); } catch (Throwable caught) { failure.set(caught); }
        });
        thread.start(); return thread;
    }
    private static void join(Thread thread, AtomicReference<Throwable> failure) {
        try { thread.join(5000); }
        catch (InterruptedException caught) { Thread.currentThread().interrupt(); throw new AssertionError(caught); }
        check(!thread.isAlive(), "thread watchdog expired");
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
    private static final class Task extends FutureTask<Void> implements RunnableScheduledFuture<Void> {
        boolean interruptRequested;
        Task(Runnable task) { super(task, null); }
        @Override public boolean isPeriodic() { return false; }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
        @Override public boolean cancel(boolean interrupt) {
            interruptRequested |= interrupt;
            return super.cancel(interrupt);
        }
    }
    private static final class ManualScheduler extends ScheduledThreadPoolExecutor {
        final List<Task> tasks = new ArrayList<>();
        boolean reject, runInline;
        long delay;
        ManualScheduler() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable command, long duration, TimeUnit unit) {
            if (reject) throw new RejectedExecutionException("scheduler unavailable");
            check(unit == TimeUnit.MILLISECONDS, "timer delay units");
            delay = duration;
            Task task = new Task(command); tasks.add(task);
            if (runInline) task.run();
            return task;
        }
    }

    public static void main(String[] args) {
        test("actual call resamples uptime and uses typed reasons/no-doze", () -> {
            PowerManager power = new PowerManager(); AndroidPowerHost host = new AndroidPowerHost(power);
            SystemClock.uptime = 21; SystemClock.elapsed = 50_000;
            long earlier = host.uptimeMillis(); check(earlier == 21, "host uptime");
            SystemClock.uptime = 35; host.sleep(earlier);
            check(power.sleepTime == 35 && power.sleepReason == PowerManager.GO_TO_SLEEP_REASON_APPLICATION
                    && power.sleepFlags == PowerManager.GO_TO_SLEEP_FLAG_NO_DOZE, "sleep adapter");
            SystemClock.uptime = 49; host.wake(Long.MAX_VALUE);
            check(power.wakeTime == 49 && power.wakeReason == PowerManager.WAKE_REASON_APPLICATION
                    && "BengalPower:acc".equals(power.wakeDetails), "wake adapter");
        });
        test("partial bounded per-cycle lease and idempotent release", () -> {
            PowerManager power = new PowerManager(); AndroidPowerHost host = new AndroidPowerHost(power);
            AccPowerCoordinator.Lease first = host.acquirePreparationLease(1);
            AccPowerCoordinator.Lease next = host.acquirePreparationLease(30_000);
            PowerManager.WakeLock a = power.locks.get(0), b = power.locks.get(1);
            check(a != b && a.flags == PowerManager.PARTIAL_WAKE_LOCK && !a.referenceCounted
                    && a.timeout == 1 && b.timeout == 30_000 && "BengalPower:prepare".equals(a.tag), "lease configuration");
            first.release(); first.release();
            check(a.releases == 1 && !a.held && b.held, "old lease affected newer cycle");
            next.release(); check(!b.held && b.releases == 1, "new lease release");
        });
        test("expiry before release and concurrent release", () -> {
            PowerManager power = new PowerManager(); AndroidPowerHost host = new AndroidPowerHost(power);
            AccPowerCoordinator.Lease lease = host.acquirePreparationLease(1000);
            PowerManager.WakeLock lock = power.locks.get(0); lock.expire();
            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread one = start(() -> { await(start); lease.release(); }, failure);
            Thread two = start(() -> { await(start); lock.expire(); lease.release(); }, failure);
            start.countDown(); join(one, failure); join(two, failure);
            check(!lock.held && lock.releases == 1, "release after timeout was not idempotent");
        });
        test("invalid lease bounds never acquire", () -> {
            PowerManager power = new PowerManager(); AndroidPowerHost host = new AndroidPowerHost(power);
            for (long timeout : new long[] {-1, 0, 30_001, Long.MAX_VALUE}) {
                expect(IllegalArgumentException.class, () -> host.acquirePreparationLease(timeout));
            }
            check(power.locks.isEmpty(), "invalid acquisition");
        });
        test("platform failures propagate and failed acquire cleans up", () -> {
            PowerManager power = new PowerManager(); AndroidPowerHost host = new AndroidPowerHost(power);
            power.failAcquire = true;
            expect(SecurityException.class, () -> host.acquirePreparationLease(100));
            check(!power.locks.get(0).held && power.locks.get(0).releases == 1, "failed acquisition leaked");
            power.failSleep = true; expect(SecurityException.class, () -> host.sleep(1));
            power.failWake = true; expect(SecurityException.class, () -> host.wake(1));
        });
        test("elapsed clock and cancellation remove work without interruption", () -> {
            ManualScheduler scheduler = new ManualScheduler(); AndroidPreparationTimer timer = new AndroidPreparationTimer(scheduler);
            SystemClock.uptime = 5; SystemClock.elapsed = 70_000;
            check(timer.monotonicMillis() == 70_000, "timer used uptime instead of elapsedRealtime");
            AtomicInteger calls = new AtomicInteger();
            AccPowerCoordinator.Lease lease = timer.schedule(123, calls::incrementAndGet);
            check(scheduler.delay == 123, "wrong delay");
            expect(IllegalStateException.class, timer::close);
            lease.release(); lease.release(); scheduler.tasks.get(0).run();
            check(calls.get() == 0 && scheduler.tasks.get(0).isCancelled()
                    && !scheduler.tasks.get(0).interruptRequested, "cancelled task ran or was interrupted");
            timer.close(); timer.close(); check(scheduler.isShutdown(), "owned scheduler did not close");
            expect(RejectedExecutionException.class, () -> timer.schedule(1, () -> {}));
        });
        test("timer capacity is bounded and cancellation frees slots", () -> {
            ManualScheduler scheduler = new ManualScheduler(); AndroidPreparationTimer timer = new AndroidPreparationTimer(scheduler);
            List<AccPowerCoordinator.Lease> leases = new ArrayList<>();
            for (int i = 0; i < AndroidPreparationTimer.MAX_PENDING; i++) leases.add(timer.schedule(1000, () -> {}));
            expect(RejectedExecutionException.class, () -> timer.schedule(1000, () -> {}));
            leases.get(0).release(); leases.add(timer.schedule(1000, () -> {}));
            for (AccPowerCoordinator.Lease lease : leases) lease.release();
            timer.close();
        });
        test("timer bound failures and rejected scheduler do not leak capacity", () -> {
            ManualScheduler scheduler = new ManualScheduler(); AndroidPreparationTimer timer = new AndroidPreparationTimer(scheduler);
            for (long timeout : new long[] {-1, 0, 30_001}) {
                expect(IllegalArgumentException.class, () -> timer.schedule(timeout, () -> {}));
            }
            scheduler.reject = true;
            for (int i = 0; i < 20; i++) expect(RejectedExecutionException.class, () -> timer.schedule(1, () -> {}));
            scheduler.reject = false;
            timer.schedule(1, () -> {}).release(); timer.close();
        });
        test("completion before future attachment and throwing callback clean up", () -> {
            ManualScheduler scheduler = new ManualScheduler(); scheduler.runInline = true;
            AndroidPreparationTimer timer = new AndroidPreparationTimer(scheduler);
            AtomicInteger calls = new AtomicInteger();
            timer.schedule(1, calls::incrementAndGet).release();
            timer.schedule(1, () -> { throw new IllegalStateException("callback failed"); }).release();
            check(calls.get() == 1, "inline callback count"); timer.close();
        });
        test("running callback cannot be interrupted or closed underneath", () -> {
            ManualScheduler scheduler = new ManualScheduler(); AndroidPreparationTimer timer = new AndroidPreparationTimer(scheduler);
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AccPowerCoordinator.Lease lease = timer.schedule(1, () -> { entered.countDown(); await(release); });
            Thread worker = start(scheduler.tasks.get(0), failure);
            try {
                await(entered); lease.release(); expect(IllegalStateException.class, timer::close);
                check(!scheduler.tasks.get(0).interruptRequested, "running callback interrupted");
            } finally { release.countDown(); join(worker, failure); }
            timer.close();
        });
        test("production scheduler cancellation removes its queued task", () -> {
            AndroidPreparationTimer timer = new AndroidPreparationTimer();
            ScheduledThreadPoolExecutor scheduler;
            try {
                Field field = AndroidPreparationTimer.class.getDeclaredField("scheduler");
                field.setAccessible(true); scheduler = (ScheduledThreadPoolExecutor) field.get(timer);
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            // No task is allowed to run; the long deadline is immediately cancelled.
            AccPowerCoordinator.Lease lease = timer.schedule(30_000, () -> { throw new AssertionError("cancelled timer ran"); });
            check(scheduler.getCorePoolSize() == 1 && scheduler.getQueue().size() == 1, "unbounded worker count or missing timer");
            lease.release(); check(scheduler.getQueue().isEmpty(), "cancelled task retained in queue");
            timer.close();
            try { check(scheduler.awaitTermination(5, TimeUnit.SECONDS), "owned worker did not terminate"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
        });
        System.out.println("PASS " + tests + " adapter tests");
    }
}
