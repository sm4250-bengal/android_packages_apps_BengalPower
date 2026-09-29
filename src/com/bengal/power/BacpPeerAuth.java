package com.bengal.power;

import java.util.Arrays;

/** Exact user0 levelFrom=all identity policy; no PID lookup or context prefix matching. */
public final class BacpPeerAuth {
    private BacpPeerAuth() {}
    public static void authenticateOwnIdentity(int ownUid, String ownContext) {
        if (ownUid < 10_000 || ownUid > 19_999) deny();
        int appId = ownUid - 10_000;
        int[] expected = {appId & 255, 256 + ((appId >> 8) & 255), 512, 768};
        if (!Arrays.equals(categories(ownContext, "bengal_power_app"), expected)) deny();
    }
    private static int[] categories(String context, String domain) {
        if (context == null || context.length() > 256) return deny();
        String[] fields = context.split(":", -1);
        if (fields.length != 5 || !fields[0].equals("u") || !fields[1].equals("r")
                || !fields[2].equals(domain) || !fields[3].equals("s0")) return deny();
        int[] result = new int[4]; int count = 0, previous = -1;
        for (String range : fields[4].split(",", -1)) {
            String[] limits = range.split("\\.", -1);
            if (limits.length < 1 || limits.length > 2) return deny();
            int first = category(limits[0]), last = limits.length == 1 ? first : category(limits[1]);
            if (first <= previous || last < first || last - first >= 4) return deny();
            for (int value = first; value <= last; value++) {
                if (count == 4) return deny();
                result[count++] = value;
            }
            previous = last;
        }
        if (count != 4) return deny();
        return result;
    }
    private static int category(String value) {
        if (!value.matches("c(0|[1-9][0-9]{0,3})")) { deny(); }
        int category = Integer.parseInt(value.substring(1));
        if (category > 1023) deny();
        return category;
    }
    private static int[] deny() { throw new SecurityException("BACP peer identity denied"); }
}
