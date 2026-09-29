package com.bengal.power;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ServiceManager;
import android.system.Os;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import vendor.xyauto.accpower.IAccPowerChannel;

/** Actual AndroidEndpoint with synthetic FD/Binder boundaries; not an Android syscall test. */
public final class AndroidFdLifetimeTest {
    private interface Action { void run() throws Exception; }
    private static int count;
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private static void await(CountDownLatch latch) throws InterruptedException {
        check(latch.await(5, TimeUnit.SECONDS), "latch watchdog");
    }
    private static void pause(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try { await(release); } catch (InterruptedException error) { throw new AssertionError(error); }
    }
    private static Thread thread(Action action, AtomicReference<Throwable> error) {
        Thread thread = new Thread(() -> { try { action.run(); } catch (Throwable failure) { error.set(failure); } });
        thread.start(); return thread;
    }
    private static void join(Thread thread, AtomicReference<Throwable> error) throws InterruptedException {
        thread.join(5000); check(!thread.isAlive(), "worker leak");
        if (error.get() != null) throw new AssertionError(error.get());
    }
    private static BacpChannel.Endpoint endpoint() throws Exception {
        Class<?> type = Class.forName("com.bengal.power.AndroidAccPowerChannel$AndroidEndpoint");
        Constructor<?> constructor = type.getDeclaredConstructor(); constructor.setAccessible(true);
        return (BacpChannel.Endpoint) constructor.newInstance();
    }
    private static Object lock(BacpChannel.Endpoint endpoint) throws Exception {
        try {
            Field field = endpoint.getClass().getDeclaredField("fdLock"); field.setAccessible(true); return field.get(endpoint);
        } catch (NoSuchFieldException baseline) { return new Object(); } // Execute old code's race, not a field-presence check.
    }
    private static void test(String name, Action body) throws Exception {
        body.run(); count++; System.out.println("PASS FD lifetime " + name);
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 1) { borrower(args[0].equals("shutdown")); return; }
        test("serialization owner survives timeout/disposal and closes after return", () -> offer(false));
        test("serialization exception closes exclusive PFD exactly once", () -> offer(true));
        test("timeout before bootstrap claim closes the pending PFD", () -> {
            int before = Os.closes.get(); BacpChannel.Endpoint endpoint = endpoint(); endpoint.close(); endpoint.dispose();
            try { endpoint.offer(() -> {}); throw new AssertionError("offer after timeout"); }
            catch (java.io.IOException expected) { /* No pending PFD. */ }
            check(Os.closes.get() - before == 5, "pending resource leak");
        });
        test("signal borrower excludes disposal through native write", () -> borrower(false));
        test("shutdown borrower excludes disposal through native shutdown", () -> borrower(true));
        System.out.println("PASS " + count + " FD lifetime tests");
    }
    private static void offer(boolean fail) throws Exception {
        int before = Os.closes.get(); BacpChannel.Endpoint endpoint = endpoint(); Object fdLock = lock(endpoint);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<ParcelFileDescriptor> borrowed = new AtomicReference<>();
        final class Service implements IBinder, IAccPowerChannel {
            @Override public void linkToDeath(DeathRecipient death, int flags) { check(!Thread.holdsLock(fdLock), "Binder under FD lock"); }
            @Override public boolean unlinkToDeath(DeathRecipient death, int flags) { check(!Thread.holdsLock(fdLock), "unlink under FD lock"); return true; }
            @Override public int getInterfaceVersion() { return VERSION; }
            @Override public String getInterfaceHash() { return HASH; }
            @Override public void offerChannel(ParcelFileDescriptor descriptor) throws Exception {
                check(!Thread.holdsLock(fdLock), "submission under FD lock");
                borrowed.set(descriptor); pause(entered, release);
                check(!descriptor.isClosed(), "PFD closed during serialization borrow");
                if (fail) throw new java.io.IOException("submission failed");
            }
        }
        ServiceManager.service = new Service(); AtomicReference<Throwable> error = new AtomicReference<>();
        Thread submit = thread(() -> {
            try { endpoint.offer(() -> {}); check(!fail, "missing submission failure"); }
            catch (java.io.IOException expected) { check(fail, "unexpected submission failure"); }
        }, error);
        try {
            await(entered); endpoint.close(); endpoint.dispose();
            check(!borrowed.get().isClosed(), "timeout/dispose closed bootstrap-exclusive PFD");
            check(Os.closes.get() - before == 4, "only one bounded serialization owner may remain");
        } finally { release.countDown(); join(submit, error); }
        check(borrowed.get().closes.get() == 1 && Os.closes.get() - before == 5, "exclusive PFD leaked/double closed");
    }
    private static void borrower(boolean shutdown) throws Exception {
        BacpChannel.Endpoint endpoint = endpoint(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        if (shutdown) Os.shuttingDown = fd -> pause(entered, release);
        else Os.writing = fd -> pause(entered, release);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread use = thread(shutdown ? endpoint::close : endpoint::signal, error); await(entered);
        Thread dispose = thread(endpoint::dispose, error);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (dispose.isAlive() && dispose.getState() != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield();
            check(dispose.getState() == Thread.State.BLOCKED, "disposal passed an active descriptor borrower");
        } finally {
            release.countDown(); join(use, error); join(dispose, error);
            Os.writing = fd -> {}; Os.shuttingDown = fd -> {};
            endpoint.close(); endpoint.dispose();
        }
    }
}
