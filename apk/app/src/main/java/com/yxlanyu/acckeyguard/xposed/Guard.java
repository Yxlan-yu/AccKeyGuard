package com.yxlanyu.acckeyguard.xposed;

import android.content.ContentResolver;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Shared state for both hooked processes.
 *
 * The guard list lives in secure settings under {@link #KEY_CFG} because that is the only
 * store both the settings provider (system uid) and system_server can read without extra
 * permissions. Writes to it are tunnelled through the provider hook (see Hooks).
 */
final class Guard {

    static final String KEY_CFG = "akg_guard";
    static final String KEY_A11Y = "enabled_accessibility_services";

    static final String M_CFG = "akg_cfg";
    static final String M_STATUS = "akg_status";
    static final String M_CMD = "akg_cmd";

    static final String K_VALUE = "value";
    static final String K_NAME = "name";

    private static final Object LOCK = new Object();
    /** Guards {@link #refresh} against re-entering itself through the settings provider hook. */
    private static final ThreadLocal<Boolean> READING = new ThreadLocal<>();

    private static String[] sList = new String[0];
    private static boolean sEnabled = true;
    private static long sStamp = 0L;
    private static long sRead = 0L;

    private Guard() {
    }

    /* ---------------------------------------------------------------- config */

    static void refresh(ContentResolver cr, boolean force) {
        if (cr == null) {
            return;
        }
        // Second safety net: our own Settings.Secure read can land back in the provider hook,
        // which used to call statusJson() -> refresh() -> getString() -> hook -> ... forever.
        if (Boolean.TRUE.equals(READING.get())) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (!force && sStamp != 0L && now - sRead < 2000L) {
                return;
            }
        }
        String raw;
        READING.set(Boolean.TRUE);
        try {
            raw = Settings.Secure.getString(cr, KEY_CFG);
        } catch (Throwable ignored) {
            // provider not ready yet or blocked; keep the previous value, throttle retries
            synchronized (LOCK) {
                sRead = System.currentTimeMillis();
                if (sStamp == 0L) {
                    sStamp = sRead;
                }
            }
            return;
        } finally {
            READING.remove();
        }
        apply(raw);
    }

    private static void apply(String raw) {
        String[] list = new String[0];
        boolean enabled = true;
        if (raw != null && raw.length() > 0) {
            try {
                JSONObject o = new JSONObject(raw);
                enabled = o.optBoolean("enabled", true);
                JSONArray arr = o.optJSONArray("services");
                if (arr != null) {
                    List<String> out = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        String s = arr.optString(i, "").trim();
                        if (s.length() > 0 && s.indexOf('/') > 0 && !out.contains(s)) {
                            out.add(s);
                        }
                    }
                    list = out.toArray(new String[0]);
                }
            } catch (Throwable ignored) {
                list = new String[0];
            }
        }
        synchronized (LOCK) {
            sList = list;
            sEnabled = enabled;
            sStamp = System.currentTimeMillis();
            sRead = sStamp;
        }
    }

    /** Force the in-memory copy after the caller has just written a new config. */
    static void install(String raw) {
        apply(raw);
    }

    static String[] list() {
        synchronized (LOCK) {
            return sList;
        }
    }

    static boolean enabled() {
        synchronized (LOCK) {
            return sEnabled;
        }
    }

    /* ----------------------------------------------------------------- merge */

    static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String s : raw.split(":")) {
            String t = s.trim();
            if (t.length() > 0 && !out.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /** Appends the guarded components to {@code base}, keeping the original order. */
    static String merge(String base) {
        String[] g = list();
        if (g.length == 0 || !enabled()) {
            return base;
        }
        LinkedHashSet<String> set = new LinkedHashSet<>(split(base));
        for (String s : g) {
            set.add(s);
        }
        return join(new ArrayList<>(set));
    }

    static int missing(String base) {
        String[] g = list();
        if (g.length == 0) {
            return 0;
        }
        List<String> have = split(base);
        int n = 0;
        for (String s : g) {
            if (!have.contains(s)) {
                n++;
            }
        }
        return n;
    }

    /* ---------------------------------------------------------------- status */

    /** Canonical form of a config payload, tolerating junk from external writers. */
    static String normalize(String raw) {
        try {
            JSONObject o = new JSONObject(raw == null ? "{}" : raw);
            return cfgJson(raw, o.optBoolean("enabled", true));
        } catch (Throwable t) {
            return "{\"v\":1,\"enabled\":true,\"services\":[]}";
        }
    }

    static String cfgJson(String raw, boolean enabled) {
        try {
            JSONObject o = raw != null && raw.length() > 0 ? new JSONObject(raw) : new JSONObject();
            JSONArray arr = o.optJSONArray("services");
            JSONObject out = new JSONObject();
            out.put("v", 1);
            out.put("enabled", enabled);
            out.put("services", arr == null ? new JSONArray() : arr);
            return out.toString();
        } catch (Throwable t) {
            return "{\"v\":1,\"enabled\":" + enabled + ",\"services\":[]}";
        }
    }

    static String statusJson(ContentResolver cr) {
        refresh(cr, true);
        String stored = null;
        try {
            stored = Settings.Secure.getString(cr, KEY_A11Y);
        } catch (Throwable ignored) {
        }
        String base = stored == null ? "" : stored;
        JSONObject o = new JSONObject();
        try {
            JSONArray g = new JSONArray();
            for (String s : list()) {
                g.put(s);
            }
            o.put("ok", true);
            o.put("v", 1);
            o.put("enabled", enabled());
            o.put("guard", g);
            o.put("stored", base);
            o.put("merged", merge(base));
            o.put("missing", missing(base));
            o.put("count", list().length);
            o.put("ts", System.currentTimeMillis());
        } catch (Throwable ignored) {
        }
        return o.toString();
    }
}
