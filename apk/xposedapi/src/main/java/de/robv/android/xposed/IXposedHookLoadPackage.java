package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Compile-time stub, not packaged. */
public interface IXposedHookLoadPackage {

    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
