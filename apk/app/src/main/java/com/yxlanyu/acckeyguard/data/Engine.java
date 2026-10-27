package com.yxlanyu.acckeyguard.data;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Orchestrates one full state read and one full write, picking the best available transport.
 *
 * Transport order:
 * 1. framework hook (settings provider) -- zero polling, intercepts at the write
 * 2. root shell -- writes {@code enabled_accessibility_services} directly
 * If neither works the UI says so and nothing is written.
 */
public final class Engine {

    public static final String CH_HOOK = "hook";
    public static final String CH_ROOT = "root";
    public static final String CH_NONE = "none";

    private Engine() {
    }

    /* -------------------------------------------------------------- snapshot */

    public static final class Snapshot {
        public List<Model> services = new ArrayList<>();
        public Set<String> guard = new LinkedHashSet<>();
        public Set<String> active = new LinkedHashSet<>();
        public Set<String> stored = new LinkedHashSet<>();
        public boolean enabled = true;
        public boolean hookAlive;
        public boolean rootAlive;
        public String channel = CH_NONE;
        public String note = "";
        public int missing;

        public int guarded() {
            return guard.size();
        }

        public int broken() {
            int n = 0;
            for (String s : guard) {
                if (!active.contains(s)) {
                    n++;
                }
            }
            return n;
        }

        public int missingActive() {
            return Math.max(broken(), missing);
        }

        public boolean isGuarded(String comp) {
            return guard.contains(Model.norm(comp));
        }

        public boolean isActive(String comp) {
            return active.contains(Model.norm(comp));
        }

        public String labelOf(String comp) {
            String c = Model.norm(comp);
            for (Model m : services) {
                if (m.comp().equals(c)) {
                    return m.label;
                }
            }
            int i = c.indexOf('/');
            return i > 0 ? c.substring(0, i) : c;
        }

        /** Guard entries that no longer resolve to an installed service. */
        public List<String> stale() {
            List<String> out = new ArrayList<>();
            for (String s : guard) {
                boolean hit = false;
                for (Model m : services) {
                    if (m.comp().equals(s)) {
                        hit = true;
                        break;
                    }
                }
                if (!hit) {
                    out.add(s);
                }
            }
            return out;
        }
    }

    public static Snapshot load(Context c) {
        Snapshot s = new Snapshot();
        s.services = Services.list(c);
        s.active = Services.active(c);
        s.guard = Store.guard(c);

        Status st = HookLink.status(c);
        s.hookAlive = st.alive;
        if (st.alive) {
            s.enabled = st.enabled;
            if (!st.guard.isEmpty()) {
                s.guard = st.guardSet();
            }
            s.stored = st.storedSet();
            s.missing = st.missing;
        }
        s.rootAlive = Shell.root();
        if (!s.hookAlive) {
            String cur = Shell.get("enabled_accessibility_services");
            for (String p : cur.split(":")) {
                String v = Model.norm(p);
                if (v.length() > 0 && v.indexOf('/') > 0) {
                    s.stored.add(v);
                }
            }
            s.missing = missingIn(s.stored, s.guard);
        }

        if (s.hookAlive) {
            s.channel = CH_HOOK;
            s.note = "框架拦截";
        } else if (s.rootAlive) {
            s.channel = CH_ROOT;
            s.note = "Root 直写";
        } else {
            s.channel = CH_NONE;
            s.note = "缺少 LSPosed 与 Root，无法保活";
        }
        return s;
    }

    private static int missingIn(Set<String> have, Set<String> guard) {
        int n = 0;
        for (String s : guard) {
            if (!have.contains(s)) {
                n++;
            }
        }
        return n;
    }

    /* ----------------------------------------------------------------- apply */

    public static final class Result {
        public boolean ok;
        public String channel = CH_NONE;
        public String message = "";
        public Snapshot snapshot;

        public static Result fail(String msg) {
            Result r = new Result();
            r.ok = false;
            r.message = msg;
            return r;
        }
    }

    /**
     * Persists the guard list + enabled flag and immediately forces the accessibility
     * list to match (merge on write, no waiting for the next poll).
     */
    public static Result apply(Context c, Set<String> guard, boolean enabled) {
        LinkedHashSet<String> clean = new LinkedHashSet<>();
        for (String s : guard) {
            String v = Model.norm(s);
            if (v.length() > 0 && v.indexOf('/') > 0) {
                clean.add(v);
            }
        }
        String json = cfgJson(clean, enabled);
        Store.setGuard(c, clean);
        Store.setEnabled(c, enabled);

        Status st = HookLink.status(c);
        if (st.alive) {
            if (HookLink.push(c, json)) {
                HookLink.cmd(c, enabled ? "repair" : "sync");
                Store.setChannel(c, CH_HOOK);
                Result r = new Result();
                r.ok = true;
                r.channel = CH_HOOK;
                r.message = enabled ? "已通过框架生效" : "已保存（保活已暂停）";
                r.snapshot = load(c);
                return r;
            }
        }
        if (Shell.root()) {
            if (HookLink.push(c, json)) {
                // hook transport appeared in the meantime
                HookLink.cmd(c, enabled ? "repair" : "sync");
                Store.setChannel(c, CH_HOOK);
                Result r = new Result();
                r.ok = true;
                r.channel = CH_HOOK;
                r.message = enabled ? "已通过框架生效" : "已保存（保活已暂停）";
                r.snapshot = load(c);
                return r;
            }
            String cur = Shell.get("enabled_accessibility_services");
            String next = enabled ? merge(cur, clean) : cur;
            if (enabled && !Shell.put("enabled_accessibility_services", next)) {
                return Result.fail("Root 写入失败，请检查授权");
            }
            boolean stored = Shell.put("akg_guard", json);
            Store.setChannel(c, CH_ROOT);
            Result r = new Result();
            r.ok = true;
            r.channel = CH_ROOT;
            r.message = stored ? "已通过 Root 写入" : "已写入无障碍列表（配置未落盘）";
            r.snapshot = load(c);
            return r;
        }
        Store.setChannel(c, CH_NONE);
        return Result.fail("LSPosed 未生效且没有 Root，改动无法生效");
    }

    /** Re-merges the stored accessibility list without touching the guard list. */
    public static Result repair(Context c) {
        Snapshot s = load(c);
        if (s.hookAlive) {
            HookLink.cmd(c, "repair");
            Result r = new Result();
            r.ok = true;
            r.channel = CH_HOOK;
            r.message = "已请求框架补回";
            r.snapshot = load(c);
            return r;
        }
        if (Shell.root()) {
            String cur = Shell.get("enabled_accessibility_services");
            String next = merge(cur, s.guard);
            if (!next.equals(cur == null ? "" : cur)) {
                if (!Shell.put("enabled_accessibility_services", next)) {
                    return Result.fail("Root 写入失败");
                }
            }
            Result r = new Result();
            r.ok = true;
            r.channel = CH_ROOT;
            r.message = next.equals(cur == null ? "" : cur) ? "列表已完整" : "已补回缺失组件";
            r.snapshot = load(c);
            return r;
        }
        return Result.fail("没有可用的写入通道");
    }

    /** Drops guard entries whose service is no longer installed. */
    public static Result dropStale(Context c) {
        Snapshot s = load(c);
        List<String> stale = s.stale();
        if (stale.isEmpty()) {
            Result r = new Result();
            r.ok = true;
            r.channel = s.channel;
            r.message = "没有失效项";
            r.snapshot = s;
            return r;
        }
        LinkedHashSet<String> next = new LinkedHashSet<>(s.guard);
        next.removeAll(stale);
        Result r = apply(c, next, s.enabled);
        if (r.ok) {
            r.message = "已清理 " + stale.size() + " 项失效";
        }
        return r;
    }

    /* ----------------------------------------------------------------- utils */

    public static String merge(String base, Set<String> guard) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (base != null) {
            for (String p : base.split(":")) {
                String v = Model.norm(p);
                if (v.length() > 0) {
                    out.add(v);
                }
            }
        }
        if (guard != null) {
            out.addAll(guard);
        }
        return join(out);
    }

    public static String join(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) {
                sb.append(':');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    public static String cfgJson(Set<String> guard, boolean enabled) {
        JSONObject o = new JSONObject();
        try {
            o.put("v", 1);
            o.put("enabled", enabled);
            JSONArray arr = new JSONArray();
            for (String s : guard) {
                arr.put(s);
            }
            o.put("services", arr);
        } catch (Throwable ignored) {
        }
        return o.toString();
    }
}
