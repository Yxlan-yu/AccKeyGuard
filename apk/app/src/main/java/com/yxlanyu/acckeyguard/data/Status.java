package com.yxlanyu.acckeyguard.data;

import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Parsed reply from the settings-provider hook. */
public final class Status {

    public boolean alive;
    public boolean enabled = true;
    public String raw = "";
    public String stored = "";
    public String merged = "";
    public List<String> guard = new ArrayList<>();
    public int missing;
    public String error = "";

    public static Status dead() {
        Status s = new Status();
        s.alive = false;
        s.error = "框架未响应";
        return s;
    }

    public static String value(Bundle b) {
        if (b == null) {
            return null;
        }
        try {
            return b.getString("value");
        } catch (Throwable t) {
            return null;
        }
    }

    public static Status parse(String json) {
        Status s = new Status();
        if (json == null || json.length() == 0) {
            return dead();
        }
        try {
            JSONObject o = new JSONObject(json);
            s.alive = o.optBoolean("ok", false);
            s.enabled = o.optBoolean("enabled", true);
            s.stored = o.optString("stored", "");
            s.merged = o.optString("merged", "");
            s.missing = o.optInt("missing", 0);
            JSONArray arr = o.optJSONArray("guard");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String v = arr.optString(i, "").trim();
                    if (v.length() > 0) {
                        s.guard.add(Model.norm(v));
                    }
                }
            }
            if (!s.alive) {
                s.error = "框架返回异常";
            }
        } catch (Throwable t) {
            return dead();
        }
        return s;
    }

    public Set<String> guardSet() {
        return new LinkedHashSet<>(guard);
    }

    public Set<String> storedSet() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : stored.split(":")) {
            String v = Model.norm(s);
            if (v.length() > 0) {
                out.add(v);
            }
        }
        return out;
    }

    public String describe() {
        if (!alive) {
            return error.length() == 0 ? "未响应" : error;
        }
        return "在线 · 守护 " + guard.size() + " · 补回 " + missing;
    }
}
