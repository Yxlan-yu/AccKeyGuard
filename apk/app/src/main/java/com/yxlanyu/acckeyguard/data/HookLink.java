package com.yxlanyu.acckeyguard.data;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

/** Transport to the framework hook: plain ContentResolver.call() on the settings provider. */
public final class HookLink {

    private static final Uri URI = Uri.parse("content://settings/secure");

    public static final String M_STATUS = "akg_status";
    public static final String M_CFG = "akg_cfg";
    public static final String M_CMD = "akg_cmd";

    private HookLink() {
    }

    private static Bundle call(Context ctx, String method, String value) {
        Bundle in = new Bundle();
        if (value != null) {
            in.putString("value", value);
        }
        try {
            return ctx.getContentResolver().call(URI, method, null, in);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Status status(Context c) {
        Bundle b = call(c, M_STATUS, null);
        String v = Status.value(b);
        if (v == null) {
            return Status.dead();
        }
        return Status.parse(v);
    }

    public static boolean push(Context c, String json) {
        return call(c, M_CFG, json) != null;
    }

    public static boolean cmd(Context c, String cmd) {
        return call(c, M_CMD, cmd) != null;
    }
}
