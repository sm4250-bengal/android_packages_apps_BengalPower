package com.bengal.power;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Fresh read-only installed-backend eligibility; selected-deep arm proof remains native AUTHORIZE. */
final class InstalledDeepCapability implements AccPowerCoordinator.DeepCapability {
    static final int MAX_BYTES = 256;
    static final String MODULE = "/sys/module/bengal_deep_suspend/parameters/";
    interface Reader { byte[] read(String path) throws Exception; }
    private final Reader reader;

    InstalledDeepCapability() { this(InstalledDeepCapability::readBounded); }
    InstalledDeepCapability(Reader reader) { this.reader = Objects.requireNonNull(reader); }

    @Override public boolean ready(AccPowerCoordinator.Cycle cycle) {
        try {
            return value("/proc/sys/kernel/osrelease").equals("6.18.32-4k-gd84cfa4479d9")
                    && value(MODULE + "guard_registered").equals("Y")
                    && value(MODULE + "guard_coverage").equals("255")
                    && value(MODULE + "guard_failed").equals("0")
                    && value(MODULE + "ops_registered").equals("Y")
                    && value(MODULE + "require_hooks").equals("Y")
                    && value(MODULE + "force_register").equals("N")
                    && value(MODULE + "production_idle_guard").equals("Y")
                    && value(MODULE + "state_name").equals("system-pc")
                    && value("/sys/devices/system/cpu/online").equals("0-7")
                    && eligibleSelection(value("/sys/power/mem_sleep"));
        } catch (Exception missingOrMalformed) { return false; }
    }

    private static boolean eligibleSelection(String value) {
        return value.equals("[s2idle] deep") || value.equals("s2idle [deep]");
    }

    private String value(String path) throws Exception {
        byte[] bytes = Objects.requireNonNull(reader.read(path));
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("status size");
        for (byte b : bytes) if (b < 0 || b == 0) throw new IllegalArgumentException("non-ASCII status");
        int length = bytes.length;
        if (bytes[length - 1] == '\n') length--; // Exactly one conventional sysfs/proc newline.
        return new String(bytes, 0, length, StandardCharsets.US_ASCII);
    }

    static byte[] readBounded(String path) throws Exception {
        // Bounded bytes/descriptors, not a claim that a stuck kernel read is interruptible.
        try (FileInputStream input = new FileInputStream(path)) {
            ByteArrayOutputStream result = new ByteArrayOutputStream(MAX_BYTES + 1);
            byte[] bytes = new byte[MAX_BYTES + 1];
            int count;
            while (result.size() <= MAX_BYTES
                    && (count = input.read(bytes, 0, MAX_BYTES + 1 - result.size())) != -1) {
                if (count == 0) throw new IllegalStateException("zero-length status read");
                result.write(bytes, 0, count);
            }
            if (result.size() > MAX_BYTES) throw new IllegalArgumentException("oversize status");
            return result.toByteArray();
        }
    }
}
