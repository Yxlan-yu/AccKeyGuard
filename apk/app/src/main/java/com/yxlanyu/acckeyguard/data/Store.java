package com.yxlanyu.acckeyguard.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashSet;
import java.util.Set;

/** Local mirror of the guard list so the UI works even before the hook answers. */
public final class Store {

    private static final String NAME = "akg";

    private Store() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static Set<String> guard(Context c) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            for (String s : sp(c).getStringSet("guard", new LinkedHashSet<String>())) {
                String v = Model.norm(s);
                if (v.length() > 0) {
                    out.add(v);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static void setGuard(Context c, Set<String> set) {
        SharedPreferences.Editor e = sp(c).edit();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : set) {
            String v = Model.norm(s);
            if (v.length() > 0) {
                out.add(v);
            }
        }
        e.putStringSet("guard", out);
        e.apply();
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", true);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("enabled", v).apply();
    }

    public static String lastChannel(Context c) {
        return sp(c).getString("channel", "");
    }

    public static void setChannel(Context c, String v) {
        sp(c).edit().putString("channel", v).apply();
    }
}
