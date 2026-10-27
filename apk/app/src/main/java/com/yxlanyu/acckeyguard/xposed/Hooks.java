package com.yxlanyu.acckeyguard.xposed;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** All Xposed plumbing. Nothing in here is allowed to throw into the host process. */
final class Hooks {

    private static final String SYNC_MARKER = "/data/system/akg_sys.marker";

    private static long sMarkerAt = 0L;
    private static final ThreadLocal<Boolean> BUSY = new ThreadLocal<>();

    private Hooks() {
    }

    static void log(String msg) {
        try {
            XposedBridge.log("[AccGuard] " + msg);
        } catch (Throwable ignored) {
        }
    }

    /* =========================================================== system side */

    static void installSystem(ClassLoader cl) {
        try {
            Class<?> secure = Class.forName("android.provider.Settings$Secure", false, cl);
            XposedBridge.hookAllMethods(secure, "getStringForUser", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length != 3) {
                            return;
                        }
                        if (!(param.args[1] instanceof String)) {
                            return;
                        }
                        if (!Guard.KEY_A11Y.equals(param.args[1])) {
                            return;
                        }
                        if (Boolean.TRUE.equals(BUSY.get())) {
                            return;
                        }
                        Object a0 = param.args[0];
                        if (!(a0 instanceof ContentResolver)) {
                            return;
                        }
                        BUSY.set(Boolean.TRUE);
                        try {
                            ContentResolver cr = (ContentResolver) a0;
                            Guard.refresh(cr, false);
                            if (Guard.list().length == 0 || !Guard.enabled()) {
                                return;
                            }
                            Object r = param.getResult();
                            String base = r instanceof String ? (String) r : "";
                            String merged = Guard.merge(base);
                            if (!merged.equals(base)) {
                                param.setResult(merged);
                                touchMarker();
                            }
                        } finally {
                            BUSY.remove();
                        }
                    } catch (Throwable t) {
                        log("sys hook: " + t);
                    }
                }
            });
            log("system hook installed");
        } catch (Throwable t) {
            log("system hook failed: " + t);
        }
    }

    private static void touchMarker() {
        long now = System.currentTimeMillis();
        if (now - sMarkerAt < 30000L) {
            return;
        }
        sMarkerAt = now;
        FileOutputStream out = null;
        try {
            File f = new File(SYNC_MARKER);
            out = new FileOutputStream(f, false);
            out.write(String.valueOf(now).getBytes("UTF-8"));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /* ========================================================= provider side */

    static void installProvider(ClassLoader cl) {
        try {
            Class<?> cls = Class.forName(
                    "com.android.providers.settings.SettingsProvider", false, cl);

            XposedBridge.hookAllMethods(cls, "call", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        String method = null;
                        Bundle extras = null;
                        if (param.args != null) {
                            for (Object o : param.args) {
                                if (o instanceof String && ((String) o).startsWith("akg_")) {
                                    method = (String) o;
                                } else if (o instanceof Bundle) {
                                    extras = (Bundle) o;
                                }
                            }
                        }
                        if (method == null) {
                            mergePut(param, extras);
                            return;
                        }
                        ContentResolver cr = resolver(param.thisObject);
                        if (cr == null) {
                            return;
                        }
                        String value = extras == null ? null : extras.getString(Guard.K_VALUE);
                        if (Guard.M_STATUS.equals(method)) {
                            param.setResult(reply(Guard.statusJson(cr)));
                            return;
                        }
                        if (Guard.M_CFG.equals(method)) {
                            String norm = Guard.normalize(value);
                            Guard.install(norm);
                            Settings.Secure.putString(cr, Guard.KEY_CFG, norm);
                            param.setResult(reply(Guard.statusJson(cr)));
                            log("config updated");
                            return;
                        }
                        if (Guard.M_CMD.equals(method)) {
                            String cmd = value == null ? "" : value.trim();
                            String stored = Settings.Secure.getString(cr, Guard.KEY_CFG);
                            if ("pause".equals(cmd) || "resume".equals(cmd)) {
                                Settings.Secure.putString(cr, Guard.KEY_CFG,
                                        Guard.cfgJson(stored, "resume".equals(cmd)));
                                Guard.refresh(cr, true);
                            } else if ("repair".equals(cmd) || "sync".equals(cmd)) {
                                Guard.refresh(cr, true);
                                String cur = Settings.Secure.getString(cr, Guard.KEY_A11Y);
                                String merged = Guard.merge(cur == null ? "" : cur);
                                if (!merged.equals(cur == null ? "" : cur)) {
                                    Settings.Secure.putString(cr, Guard.KEY_A11Y, merged);
                                    log("repaired accessibility list");
                                }
                            }
                            param.setResult(reply(Guard.statusJson(cr)));
                            return;
                        }
                        param.setResult(reply(Guard.statusJson(cr)));
                    } catch (Throwable t) {
                        log("call hook: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(cls, "insert", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 2) {
                            return;
                        }
                        if (!(param.args[0] instanceof Uri) || !(param.args[1] instanceof ContentValues)) {
                            return;
                        }
                        Uri uri = (Uri) param.args[0];
                        ContentValues cv = (ContentValues) param.args[1];
                        String name = stringOf(cv.get(Guard.K_NAME));
                        if (name == null) {
                            name = lastSegment(uri);
                        }
                        if (!Guard.KEY_A11Y.equals(name)) {
                            return;
                        }
                        ContentResolver cr = resolver(param.thisObject);
                        if (cr == null) {
                            return;
                        }
                        Guard.refresh(cr, false);
                        if (Guard.list().length == 0 || !Guard.enabled()) {
                            return;
                        }
                        String cur = stringOf(cv.get(Guard.K_VALUE));
                        String merged = Guard.merge(cur == null ? "" : cur);
                        if (!merged.equals(cur)) {
                            cv.put(Guard.K_VALUE, merged);
                            log("write merged (+" + Guard.missing(cur == null ? "" : cur) + ")");
                        }
                    } catch (Throwable t) {
                        log("insert hook: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(cls, "bulkInsert", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length < 2) {
                            return;
                        }
                        if (!(param.args[0] instanceof Uri) || !(param.args[1] instanceof ContentValues[])) {
                            return;
                        }
                        Uri uri = (Uri) param.args[0];
                        ContentResolver cr = resolver(param.thisObject);
                        if (cr == null) {
                            return;
                        }
                        Guard.refresh(cr, false);
                        if (Guard.list().length == 0 || !Guard.enabled()) {
                            return;
                        }
                        ContentValues[] all = (ContentValues[]) param.args[1];
                        for (ContentValues cv : all) {
                            if (cv == null) {
                                continue;
                            }
                            String name = stringOf(cv.get(Guard.K_NAME));
                            if (name == null) {
                                name = lastSegment(uri);
                            }
                            if (!Guard.KEY_A11Y.equals(name)) {
                                continue;
                            }
                            String cur = stringOf(cv.get(Guard.K_VALUE));
                            String merged = Guard.merge(cur == null ? "" : cur);
                            if (!merged.equals(cur)) {
                                cv.put(Guard.K_VALUE, merged);
                            }
                        }
                    } catch (Throwable t) {
                        log("bulkInsert hook: " + t);
                    }
                }
            });

            log("provider hook installed");
        } catch (Throwable t) {
            log("provider hook failed: " + t);
        }
    }

    /** Handles the legacy {@code PUT_secure} call path used by some system components. */
    private static void mergePut(XC_MethodHook.MethodHookParam param, Bundle extras) {
        if (extras == null) {
            return;
        }
        try {
            String name = extras.getString(Guard.K_NAME);
            if (!Guard.KEY_A11Y.equals(name)) {
                return;
            }
            ContentResolver cr = resolver(param.thisObject);
            if (cr == null) {
                return;
            }
            Guard.refresh(cr, false);
            if (Guard.list().length == 0 || !Guard.enabled()) {
                return;
            }
            String cur = extras.getString(Guard.K_VALUE);
            String merged = Guard.merge(cur == null ? "" : cur);
            if (!merged.equals(cur)) {
                extras.putString(Guard.K_VALUE, merged);
            }
        } catch (Throwable ignored) {
        }
    }

    /* ---------------------------------------------------------------- utils */

    private static Bundle reply(String json) {
        Bundle b = new Bundle();
        b.putString(Guard.K_VALUE, json);
        return b;
    }

    private static ContentResolver resolver(Object provider) {
        try {
            ContentProvider p = (ContentProvider) provider;
            return p.getContext() == null ? null : p.getContext().getContentResolver();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stringOf(Object o) {
        return o instanceof String ? (String) o : null;
    }

    private static String lastSegment(Uri uri) {
        try {
            List<String> seg = uri.getPathSegments();
            return seg.isEmpty() ? null : seg.get(seg.size() - 1);
        } catch (Throwable t) {
            return null;
        }
    }
}
