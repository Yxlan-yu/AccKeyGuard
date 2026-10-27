package com.yxlanyu.acckeyguard.data;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Minimal root helper, used only when the framework hook is unavailable. */
public final class Shell {

    private static Boolean sRoot;

    private Shell() {
    }

    public static String exec(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            return "";
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static boolean root() {
        if (sRoot == null) {
            sRoot = exec("id").contains("uid=0");
        }
        return sRoot.booleanValue();
    }

    private static String quote(String v) {
        if (v == null) {
            return "''";
        }
        return "'" + v.replace("'", "'\\''") + "'";
    }

    public static String get(String key) {
        String out = exec("settings get secure " + key);
        if (out.length() == 0 || "null".equals(out)) {
            return "";
        }
        return out;
    }

    public static boolean put(String key, String value) {
        exec("settings put secure " + key + " " + quote(value));
        String back = get(key);
        return back.equals(value == null ? "" : value);
    }
}
