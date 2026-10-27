package com.yxlanyu.acckeyguard.xposed;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Entry point declared in assets/xposed_init. */
public final class HookEntry implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            if (lpparam == null || lpparam.packageName == null) {
                return;
            }
            if ("android".equals(lpparam.packageName)) {
                Hooks.installSystem(lpparam.classLoader);
            } else if ("com.android.providers.settings".equals(lpparam.packageName)) {
                Hooks.installProvider(lpparam.classLoader);
            }
        } catch (Throwable t) {
            try {
                XposedBridge.log("[AccGuard] entry: " + t);
            } catch (Throwable ignored) {
            }
        }
    }
}
