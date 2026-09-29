package com.bengal.power;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class InstalledDeepCapabilityTest {
    private static void check(boolean okay, String why) { if (!okay) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        Map<String, byte[]> facts = new LinkedHashMap<>();
        facts.put("/proc/sys/kernel/osrelease", ascii("6.18.32-4k-gd84cfa4479d9\n"));
        String[] names = {"guard_registered", "guard_coverage", "guard_failed", "ops_registered",
                "require_hooks", "force_register", "production_idle_guard", "state_name"};
        String[] values = {"Y", "255", "0", "Y", "Y", "N", "Y", "system-pc"};
        for (int i = 0; i < names.length; i++) facts.put(InstalledDeepCapability.MODULE + names[i], ascii(values[i] + "\n"));
        facts.put("/sys/devices/system/cpu/online", ascii("0-7\n"));
        facts.put("/sys/power/mem_sleep", ascii("[s2idle] deep\n"));
        int[] reads = {0};
        InstalledDeepCapability capability = new InstalledDeepCapability(path -> { reads[0]++; return facts.get(path); });
        check(capability.ready(null) && reads[0] == 11, "before READY eligibility");
        facts.put("/sys/power/mem_sleep", ascii("s2idle [deep]\n"));
        check(capability.ready(null) && reads[0] == 22, "post AUTHORIZE eligibility/fresh reads");
        for (String path : facts.keySet()) {
            byte[] good = facts.get(path);
            for (byte[] bad : new byte[][] {null, new byte[0], new byte[257], ascii("unknown\n"),
                    ascii(new String(good, StandardCharsets.US_ASCII) + "\n"), new byte[] {(byte) 0xff}}) {
                facts.put(path, bad);
                check(!capability.ready(null), "accepted bad status " + path);
            }
            facts.put(path, good);
            check(capability.ready(null), "failed to recover fresh status " + path);
        }
        for (String wrong : new String[] {"0255", "+255", "256", "18446744073709551616"}) {
            facts.put(InstalledDeepCapability.MODULE + "guard_coverage", ascii(wrong));
            check(!capability.ready(null), "noncanonical/overflow coverage");
        }
        facts.put(InstalledDeepCapability.MODULE + "guard_coverage", ascii("255"));
        check(capability.ready(null), "canonical status without newline");
        InstalledDeepCapability denied = new InstalledDeepCapability(path -> { throw new SecurityException("denied"); });
        check(!denied.ready(null), "read error became ready");
        Path file = Files.createTempFile(Path.of(args[0]), "status-bound-", ".txt");
        try {
            Files.write(file, new byte[256]);
            check(InstalledDeepCapability.readBounded(file.toString()).length == 256, "256-byte bound");
            Files.write(file, new byte[257]);
            try { InstalledDeepCapability.readBounded(file.toString()); throw new AssertionError("oversize file accepted"); }
            catch (IllegalArgumentException expected) { }
        } finally { Files.delete(file); }
        System.out.println("PASS installed deep capability: both selections, all11 exact fields, no cache, malformed/error/size controls");
    }
    private static byte[] ascii(String text) { return text.getBytes(StandardCharsets.US_ASCII); }
}
