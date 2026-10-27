package com.yxlanyu.acckeyguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.yxlanyu.acckeyguard.data.Engine;
import com.yxlanyu.acckeyguard.data.Shell;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Boot-time safety net. The framework hook already protects reads and writes, so this only
 * matters when the hook is not active but root is (it re-merges the list once after boot).
 */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (intent == null || intent.getAction() == null) {
                return;
            }
            String a = intent.getAction();
            if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                    && !"android.intent.action.QUICKBOOT_POWERON".equals(a)) {
                return;
            }
            final Context app = context.getApplicationContext();
            new Thread(() -> {
                try {
                    Set<String> guard = new LinkedHashSet<>(
                            Engine.load(app).guard);
                    if (guard.isEmpty()) {
                        return;
                    }
                    if (!Shell.root()) {
                        return;
                    }
                    String cur = Shell.get("enabled_accessibility_services");
                    String next = Engine.merge(cur, guard);
                    if (!next.equals(cur == null ? "" : cur)) {
                        Shell.put("enabled_accessibility_services", next);
                    }
                } catch (Throwable ignored) {
                }
            }, "akg-boot").start();
        } catch (Throwable ignored) {
        }
    }
}
