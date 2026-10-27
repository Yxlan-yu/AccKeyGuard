package com.yxlanyu.acckeyguard.data;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Discovers accessibility services installed on the device. */
public final class Services {

    private Services() {
    }

    public static List<Model> list(Context c) {
        List<Model> out = new ArrayList<>();
        try {
            PackageManager pm = c.getPackageManager();
            Intent intent = new Intent(AccessibilityService.SERVICE_INTERFACE);
            List<ResolveInfo> ris = pm.queryIntentServices(intent, 0);
            if (ris == null) {
                return out;
            }
            Set<String> seen = new LinkedHashSet<>();
            for (ResolveInfo ri : ris) {
                ServiceInfo si = ri.serviceInfo;
                if (si == null || si.packageName == null || si.name == null) {
                    continue;
                }
                if (!"android.permission.BIND_ACCESSIBILITY_SERVICE".equals(si.permission)) {
                    continue;
                }
                String key = si.packageName + "/" + si.name;
                if (!seen.add(key)) {
                    continue;
                }
                CharSequence lab = null;
                try {
                    lab = si.loadLabel(pm);
                } catch (Throwable ignored) {
                }
                String label = lab == null ? si.name : lab.toString().trim();
                if (label.length() == 0) {
                    label = si.name;
                }
                boolean system = si.applicationInfo != null
                        && (si.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                out.add(new Model(si.packageName, si.name, label, system));
            }
            Collections.sort(out, new Comparator<Model>() {
                @Override
                public int compare(Model a, Model b) {
                    int r = String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
                    return r != 0 ? r : String.CASE_INSENSITIVE_ORDER.compare(a.pkg, b.pkg);
                }
            });
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** Best-effort view of what the framework currently believes is enabled. */
    public static Set<String> active(Context c) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        try {
            AccessibilityManager am =
                    (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am != null) {
                List<AccessibilityServiceInfo> l =
                        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
                if (l != null) {
                    for (AccessibilityServiceInfo info : l) {
                        if (info == null || info.getResolveInfo() == null
                                || info.getResolveInfo().serviceInfo == null) {
                            continue;
                        }
                        ServiceInfo si = info.getResolveInfo().serviceInfo;
                        out.add(Model.norm(si.packageName + "/" + si.name));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
