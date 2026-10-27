package com.yxlanyu.acckeyguard;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.yxlanyu.acckeyguard.data.Engine;
import com.yxlanyu.acckeyguard.data.Model;
import com.yxlanyu.acckeyguard.data.Shell;
import com.yxlanyu.acckeyguard.ui.Bar;
import com.yxlanyu.acckeyguard.ui.Ring;
import com.yxlanyu.acckeyguard.ui.TabIcon;
import com.yxlanyu.acckeyguard.ui.Theme;
import com.yxlanyu.acckeyguard.ui.Toggle;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Single-activity dashboard. No AndroidX, no fragments: three pages + a bottom sheet. */
public class MainActivity extends Activity {

    private static final int TAB_LIST = 0;
    private static final int TAB_DIAG = 1;
    private static final int TAB_SET = 2;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private LinearLayout pages;
    private ScrollView scrollList;
    private ScrollView scrollDiag;
    private ScrollView scrollSet;
    private LinearLayout bodyList;
    private LinearLayout bodyDiag;
    private LinearLayout bodySet;
    private final TabIcon[] tabs = new TabIcon[3];

    private Engine.Snapshot snap;
    private int tab = TAB_LIST;
    private int filter = 0;      // 0 all, 1 guarded, 2 unguarded, 3 active
    private int sort = 0;        // 0 name, 1 guarded first
    private boolean busy;
    private View overlay;
    /** Raw content of the framework heartbeat file, read off the UI thread. */
    private String marker = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        w.setStatusBarColor(Theme.BG);
        w.setNavigationBarColor(Theme.BG);

        root = new FrameLayout(this);
        root.setBackgroundColor(Theme.BG);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.addView(header());
        pages = new LinearLayout(this);
        pages.setOrientation(LinearLayout.VERTICAL);
        main.addView(pages, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        scrollList = scroll();
        bodyList = (LinearLayout) scrollList.getChildAt(0);
        scrollDiag = scroll();
        bodyDiag = (LinearLayout) scrollDiag.getChildAt(0);
        scrollSet = scroll();
        bodySet = (LinearLayout) scrollSet.getChildAt(0);
        pages.addView(scrollList, fill());
        pages.addView(scrollDiag, fill());
        pages.addView(scrollSet, fill());

        root.addView(main, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        select(TAB_LIST);
        reload();
    }

    /* ================================================================ header */

    private View header() {
        Context c = this;
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int top = statusBar() + Theme.dp(c, 14);
        bar.setPadding(Theme.dp(c, 20), top, Theme.dp(c, 14), Theme.dp(c, 10));

        LinearLayout left = new LinearLayout(c);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView title = Theme.text(c, "AccGuard", 22, Theme.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView sub = Theme.text(c, "框架层保活 · 零轮询", 11, Theme.TEXT_DIM);
        sub.setPadding(0, Theme.dp(c, 3), 0, 0);
        left.addView(title);
        left.addView(sub);
        bar.addView(left, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        int[] kinds = new int[]{TabIcon.LIST, TabIcon.PULSE, TabIcon.SLIDER};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            TabIcon icon = new TabIcon(c, kinds[i]);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(Theme.dp(c, 42), Theme.dp(c, 42));
            lp.leftMargin = Theme.dp(c, 2);
            icon.setOnClickListener(v -> select(index));
            tabs[i] = icon;
            bar.addView(icon, lp);
        }
        return bar;
    }

    private int statusBar() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            return getResources().getDimensionPixelSize(id);
        }
        return Theme.dp(this, 22);
    }

    private ScrollView scroll() {
        ScrollView s = new ScrollView(this);
        s.setFillViewport(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Theme.dp(this, 16), Theme.dp(this, 6),
                Theme.dp(this, 16), Theme.dp(this, 28));
        s.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private void select(int index) {
        tab = index;
        for (int i = 0; i < 3; i++) {
            tabs[i].setSelectedState(i == index);
        }
        scrollList.setVisibility(index == TAB_LIST ? View.VISIBLE : View.GONE);
        scrollDiag.setVisibility(index == TAB_DIAG ? View.VISIBLE : View.GONE);
        scrollSet.setVisibility(index == TAB_SET ? View.VISIBLE : View.GONE);
        render();
    }

    /* ================================================================= state */

    private void reload() {
        new Thread(() -> {
            final Engine.Snapshot next;
            final String mark;
            try {
                next = Engine.load(getApplicationContext());
            } catch (Throwable t) {
                ui.post(() -> toast("读取失败：" + t));
                return;
            }
            mark = next.rootAlive
                    ? Shell.exec("cat /data/system/akg_sys.marker 2>/dev/null").trim()
                    : "";
            ui.post(() -> {
                snap = next;
                marker = mark;
                render();
            });
        }, "akg-load").start();
    }

    private void act(String label, Worker w) {
        if (busy) {
            toast("正在处理…");
            return;
        }
        busy = true;
        toast(label);
        new Thread(() -> {
            String msg;
            boolean ok;
            Engine.Snapshot next = null;
            try {
                Engine.Result r = w.run();
                ok = r.ok;
                msg = r.message;
                next = r.snapshot;
            } catch (Throwable t) {
                ok = false;
                msg = "出错：" + t;
            }
            final boolean fOk = ok;
            final String fMsg = msg;
            final Engine.Snapshot fNext = next;
            ui.post(() -> {
                busy = false;
                toast(fMsg);
                if (fNext != null) {
                    snap = fNext;
                    render();
                } else {
                    reload();
                }
                if (!fOk) {
                    // nothing else to do; the message already explains it
                }
            });
        }, "akg-act").start();
    }

    private interface Worker {
        Engine.Result run();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /* ================================================================ render */

    private void render() {
        tabs[0].setBadge(snap != null && snap.broken() > 0);
        tabs[1].setBadge(snap != null && !snap.hookAlive);
        renderList();
        renderDiag();
        renderSet();
    }

    /* ------------------------------------------------------------------ list */

    private void renderList() {
        Context c = this;
        bodyList.removeAllViews();
        if (snap == null) {
            bodyList.addView(hint("正在读取…"));
            return;
        }

        bodyList.addView(vitals(), block());
        bodyList.addView(Theme.spacer(c, 14));

        if (!snap.hookAlive) {
            bodyList.addView(warnCard(), block());
            bodyList.addView(Theme.spacer(c, 14));
        }

        bodyList.addView(filters(), block());
        bodyList.addView(Theme.spacer(c, 10));

        List<Model> items = filtered();
        if (items.isEmpty()) {
            bodyList.addView(hint(filter == 0
                    ? "没有检测到无障碍服务"
                    : "当前筛选没有匹配项"), block());
            return;
        }

        LinearLayout group = new LinearLayout(c);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackground(Theme.card(c));
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                group.addView(Theme.hline(c));
            }
            group.addView(row(items.get(i)));
        }
        bodyList.addView(group, block());
    }

    private LinearLayout.LayoutParams block() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private List<Model> filtered() {
        List<Model> out = new ArrayList<>();
        for (Model m : snap.services) {
            String comp = m.comp();
            boolean guarded = snap.isGuarded(comp);
            boolean active = snap.isActive(comp);
            if (filter == 1 && !guarded) {
                continue;
            }
            if (filter == 2 && guarded) {
                continue;
            }
            if (filter == 3 && !active) {
                continue;
            }
            out.add(m);
        }
        if (sort == 1) {
            Collections.sort(out, new Comparator<Model>() {
                @Override
                public int compare(Model a, Model b) {
                    int ga = snap.isGuarded(a.comp()) ? 0 : 1;
                    int gb = snap.isGuarded(b.comp()) ? 0 : 1;
                    if (ga != gb) {
                        return ga - gb;
                    }
                    return String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label);
                }
            });
        }
        return out;
    }

    private View vitals() {
        Context c = this;
        int guarded = snap.guarded();
        int broken = snap.broken();
        int healthy = Math.max(0, guarded - broken);
        float pct = guarded == 0 ? 0f : healthy / (float) guarded;

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Theme.card(c));
        int p = Theme.dp(c, 16);
        card.setPadding(p, p, p, p);

        Ring ring = new Ring(c);
        LinearLayout.LayoutParams rlp =
                new LinearLayout.LayoutParams(Theme.dp(c, 116), Theme.dp(c, 116));
        ring.set(guarded == 0 ? 0f : pct, broken > 0 ? Theme.WARN : Theme.OK,
                guarded == 0 ? "—" : String.valueOf(healthy),
                guarded == 0 ? "未设守护" : "在线 / " + guarded);
        card.addView(ring, rlp);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = Theme.dp(c, 16);

        TextView kicker = Theme.text(c, "保活通道", 11, Theme.TEXT_DIM);
        TextView channel = Theme.text(c, snap.note, 15, Theme.TEXT);
        channel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        channel.setPadding(0, Theme.dp(c, 3), 0, 0);

        Bar bar = new Bar(c);
        bar.set(guarded, healthy, broken);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(c, 8));
        blp.topMargin = Theme.dp(c, 12);
        blp.bottomMargin = Theme.dp(c, 10);

        LinearLayout chips = new LinearLayout(c);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.addView(pill("守护 " + guarded, Theme.ACCENT));
        if (broken > 0) {
            TextView t = pill("待恢复 " + broken, Theme.BAD);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Theme.dp(c, 6);
            chips.addView(t, lp);
        } else if (guarded > 0) {
            TextView t = pill("全部在线", Theme.OK);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = Theme.dp(c, 6);
            chips.addView(t, lp);
        }

        col.addView(kicker);
        col.addView(channel);
        col.addView(bar, blp);
        col.addView(chips);
        card.addView(col, clp);
        return card;
    }

    private TextView pill(String s, int colour) {
        TextView t = Theme.text(this, s, 11, colour);
        t.setBackground(Theme.chip(this, (colour & 0x00FFFFFF) | 0x22000000));
        int px = Theme.dp(this, 9);
        int py = Theme.dp(this, 4);
        t.setPadding(px, py, px, py);
        return t;
    }

    private View warnCard() {
        Context c = this;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(Theme.round(c, 0x1AFFB020, 18));
        int p = Theme.dp(c, 14);
        box.setPadding(p, p, p, p);

        TextView t1 = Theme.text(c, "框架未生效", 14, Theme.WARN);
        t1.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView t2 = Theme.text(c,
                "在 LSPosed 中启用本模块，作用域勾选「系统框架」与「设置存储」，然后重启手机。当前降级为 Root 直写。",
                12, Theme.TEXT_DIM);
        t2.setPadding(0, Theme.dp(c, 6), 0, 0);
        t2.setLineSpacing(Theme.dp(c, 2), 1f);
        box.addView(t1);
        box.addView(t2);
        return box;
    }

    private View filters() {
        Context c = this;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        String[] names = new String[]{"全部", "守护中", "未守护", "已生效"};
        LinearLayout seg = new LinearLayout(c);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(Theme.round(c, Theme.SURFACE, 99));
        int pad = Theme.dp(c, 3);
        seg.setPadding(pad, pad, pad, pad);
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView t = Theme.text(c, names[i], 12,
                    i == filter ? Theme.TEXT : Theme.TEXT_DIM);
            int px = Theme.dp(c, 10);
            int py = Theme.dp(c, 6);
            t.setPadding(px, py, px, py);
            if (i == filter) {
                t.setBackground(Theme.round(c, Theme.SURFACE_HI, 99));
                t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            }
            t.setOnClickListener(v -> {
                filter = idx;
                renderList();
            });
            seg.addView(t);
        }
        row.addView(seg, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView ord = Theme.text(c, sort == 0 ? "名称" : "守护优先", 12, Theme.ACCENT);
        int px = Theme.dp(c, 10);
        int py = Theme.dp(c, 6);
        ord.setPadding(px, py, px, py);
        ord.setBackground(Theme.outline(c, 0x334C8DFF));
        ord.setOnClickListener(v -> {
            sort = sort == 0 ? 1 : 0;
            renderList();
        });
        row.addView(ord);
        return row;
    }

    private View row(final Model m) {
        Context c = this;
        final String comp = m.comp();
        boolean guarded = snap.isGuarded(comp);
        boolean active = snap.isActive(comp);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int px = Theme.dp(c, 14);
        row.setPadding(px, Theme.dp(c, 11), px, Theme.dp(c, 11));

        TextView avatar = Theme.text(c, monogram(m.label), 14, Theme.ACCENT);
        avatar.setGravity(Gravity.CENTER);
        avatar.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        avatar.setBackground(Theme.round(c, 0x224C8DFF, 12));
        row.addView(avatar, new LinearLayout.LayoutParams(
                Theme.dp(c, 36), Theme.dp(c, 36)));

        LinearLayout mid = new LinearLayout(c);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mlp.leftMargin = Theme.dp(c, 12);

        TextView name = Theme.text(c, m.label, 14, Theme.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView meta = Theme.text(c, sub(m, active), 11,
                active ? Theme.OK : Theme.TEXT_MUTE);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setPadding(0, Theme.dp(c, 2), 0, 0);
        mid.addView(name);
        mid.addView(meta);
        row.addView(mid, mlp);

        final Toggle t = new Toggle(c);
        t.set(guarded, false);
        t.setListener(value -> setGuard(comp, value));
        row.addView(t, new LinearLayout.LayoutParams(
                Theme.dp(c, 46), Theme.dp(c, 27)));

        row.setOnClickListener(v -> detail(m));
        return row;
    }

    private String sub(Model m, boolean active) {
        String base = m.pkg;
        if (m.system) {
            base = base + " · 系统";
        }
        if (snap.isGuarded(m.comp())) {
            return (active ? "已生效 · " : "守护中，未生效 · ") + base;
        }
        return active ? "系统已启用 · " + base : base;
    }

    private static String monogram(String label) {
        if (label == null || label.length() == 0) {
            return "?";
        }
        String s = label.trim();
        if (s.length() == 0) {
            return "?";
        }
        String up = s.substring(0, 1).toUpperCase(Locale.ROOT);
        return up;
    }

    private void setGuard(String comp, boolean on) {
        if (snap == null) {
            return;
        }
        Set<String> next = new LinkedHashSet<>(snap.guard);
        if (on) {
            next.add(Model.norm(comp));
        } else {
            next.remove(Model.norm(comp));
        }
        final Set<String> target = next;
        final boolean enabled = snap.enabled;
        act(on ? "加入守护" : "取消守护", () -> Engine.apply(this, target, enabled));
    }

    /* ------------------------------------------------------------------ diag */

    private void renderDiag() {
        Context c = this;
        bodyDiag.removeAllViews();
        if (snap == null) {
            bodyDiag.addView(hint("正在读取…"));
            return;
        }

        LinearLayout status = new LinearLayout(c);
        status.setOrientation(LinearLayout.VERTICAL);
        status.setBackground(Theme.card(c));
        int p = Theme.dp(c, 16);
        status.setPadding(p, p, p, p);
        status.addView(kv("保活通道", snap.note,
                snap.hookAlive ? Theme.OK : (snap.rootAlive ? Theme.WARN : Theme.BAD)));
        status.addView(divider());
        status.addView(kv("框架拦截", snap.hookAlive ? "在线" : "未响应",
                snap.hookAlive ? Theme.OK : Theme.BAD));
        status.addView(divider());
        status.addView(kv("Root 权限", snap.rootAlive ? "可用" : "不可用",
                snap.rootAlive ? Theme.OK : Theme.TEXT_DIM));
        status.addView(divider());
        status.addView(kv("已安装服务", String.valueOf(snap.services.size()), Theme.TEXT));
        bodyDiag.addView(status, block());
        bodyDiag.addView(Theme.spacer(c, 14));

        LinearLayout nums = new LinearLayout(c);
        nums.setOrientation(LinearLayout.VERTICAL);
        nums.setBackground(Theme.card(c));
        nums.setPadding(p, p, p, p);
        nums.addView(kv("守护条目", String.valueOf(snap.guard.size()), Theme.TEXT));
        nums.addView(divider());
        nums.addView(kv("已生效", String.valueOf(Math.max(0, snap.guard.size() - snap.broken())),
                Theme.OK));
        nums.addView(divider());
        nums.addView(kv("待恢复", String.valueOf(snap.broken()),
                snap.broken() > 0 ? Theme.BAD : Theme.OK));
        nums.addView(divider());
        nums.addView(kv("失效条目", String.valueOf(snap.stale().size()),
                snap.stale().isEmpty() ? Theme.TEXT_DIM : Theme.WARN));
        bodyDiag.addView(nums, block());
        bodyDiag.addView(Theme.spacer(c, 14));

        bodyDiag.addView(section("系统存储值"));
        bodyDiag.addView(code(snap.stored.isEmpty() ? "(空)" : join(snap.stored)));
        bodyDiag.addView(Theme.spacer(c, 12));

        bodyDiag.addView(section("合并后应得"));
        bodyDiag.addView(code(mergePreview()));
        bodyDiag.addView(Theme.spacer(c, 14));

        bodyDiag.addView(section("心跳标记"));
        bodyDiag.addView(code(markerText()));
        bodyDiag.addView(Theme.spacer(c, 16));

        LinearLayout actions = new LinearLayout(c);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView fix = button("立即修复", Theme.ACCENT, 0xFF06101F);
        fix.setOnClickListener(v -> act("修复中", () -> Engine.repair(this)));
        TextView copy = button("复制诊断", Theme.SURFACE_HI, Theme.TEXT);
        copy.setOnClickListener(v -> copyDiag());
        actions.addView(fix, new LinearLayout.LayoutParams(0,
                Theme.dp(c, 46), 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Theme.dp(c, 46), 1f);
        lp.leftMargin = Theme.dp(c, 10);
        actions.addView(copy, lp);
        bodyDiag.addView(actions, block());
        bodyDiag.addView(Theme.spacer(c, 14));

        bodyDiag.addView(section("说明"));
        bodyDiag.addView(note("• 写入拦截：设置存储在写入前把被删掉的组件并回列表。\n"
                + "• 读取兜底：系统框架读取时若发现缺失，即时补齐。\n"
                + "• 因此不需要后台轮询，也不会和系统打架。\n"
                + "• 若「框架拦截」显示未响应，请到 LSPosed 中勾选作用域后重启。"));
    }

    private String mergePreview() {
        Set<String> out = new LinkedHashSet<>(snap.stored);
        if (snap.enabled) {
            out.addAll(snap.guard);
        }
        return out.isEmpty() ? "(空)" : join(out);
    }

    private String markerText() {
        if (!snap.rootAlive) {
            return "需要 Root 才能读取";
        }
        if (marker.length() == 0) {
            return "尚未写入（框架还没补回过任何一次读取）";
        }
        try {
            long ts = Long.parseLong(marker);
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA);
            return f.format(new Date(ts)) + "  最近一次兜底补回";
        } catch (Throwable t) {
            return marker;
        }
    }

    private void copyDiag() {
        if (snap == null) {
            return;
        }
        String text = "AccGuard 诊断\n"
                + "通道: " + snap.note + "\n"
                + "框架拦截: " + snap.hookAlive + "\n"
                + "Root: " + snap.rootAlive + "\n"
                + "服务数: " + snap.services.size() + "\n"
                + "守护: " + snap.guard.size() + " 待恢复: " + snap.broken() + "\n"
                + "存储: " + join(snap.stored) + "\n"
                + "应得: " + mergePreview() + "\n";
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("AccGuard", text));
                toast("诊断已复制");
            }
        } catch (Throwable t) {
            toast("复制失败");
        }
    }

    /* ------------------------------------------------------------------- set */

    private void renderSet() {
        Context c = this;
        bodySet.removeAllViews();
        if (snap == null) {
            bodySet.addView(hint("正在读取…"));
            return;
        }

        LinearLayout master = new LinearLayout(c);
        master.setOrientation(LinearLayout.HORIZONTAL);
        master.setGravity(Gravity.CENTER_VERTICAL);
        master.setBackground(Theme.card(c));
        int p = Theme.dp(c, 16);
        master.setPadding(p, p, p, p);

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = Theme.text(c, "保活总开关", 15, Theme.TEXT);
        t1.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView t2 = Theme.text(c, snap.enabled
                ? "正在拦截设置写入，被移除的组件会自动补回"
                : "已暂停：不再修改系统无障碍列表", 11, Theme.TEXT_DIM);
        t2.setPadding(0, Theme.dp(c, 4), 0, 0);
        t2.setLineSpacing(Theme.dp(c, 2), 1f);
        col.addView(t1);
        col.addView(t2);
        master.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Toggle tg = new Toggle(c);
        tg.set(snap.enabled, false);
        tg.setListener(v -> {
            final Set<String> g = new LinkedHashSet<>(snap.guard);
            act(v ? "恢复保活" : "暂停保活", () -> Engine.apply(this, g, v));
        });
        master.addView(tg);
        bodySet.addView(master, block());
        bodySet.addView(Theme.spacer(c, 14));

        bodySet.addView(section("操作"));
        LinearLayout ops = new LinearLayout(c);
        ops.setOrientation(LinearLayout.VERTICAL);
        ops.setBackground(Theme.card(c));
        ops.addView(action("接管当前已启用的服务", "把系统里现在生效的无障碍服务一次性加入守护",
                () -> {
                    Set<String> g = new LinkedHashSet<>(snap.active);
                    act("接管中", () -> Engine.apply(this, g, snap.enabled));
                }));
        ops.addView(Theme.hline(c));
        ops.addView(action("全部守护", "守护全部已安装的无障碍服务（" + snap.services.size() + " 项）",
                () -> {
                    Set<String> g = new LinkedHashSet<>();
                    for (Model m : snap.services) {
                        g.add(m.comp());
                    }
                    act("处理中", () -> Engine.apply(this, g, snap.enabled));
                }));
        ops.addView(Theme.hline(c));
        ops.addView(action("全部取消", "清空守护列表，停止保活",
                () -> act("处理中", () -> Engine.apply(this, new LinkedHashSet<String>(), false))));
        ops.addView(Theme.hline(c));
        ops.addView(action("立即修复", "立刻把缺失组件并回系统无障碍列表",
                () -> act("修复中", () -> Engine.repair(this))));
        ops.addView(Theme.hline(c));
        ops.addView(action("清理失效项", "删除已卸载应用留下的守护条目" + staleSuffix(),
                () -> act("清理中", () -> Engine.dropStale(this))));
        ops.addView(Theme.hline(c));
        ops.addView(action("打开系统无障碍设置", "跳转到系统的无障碍服务页面",
                () -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    } catch (Throwable t) {
                        toast("无法打开设置");
                    }
                }));
        bodySet.addView(ops, block());
        bodySet.addView(Theme.spacer(c, 14));

        bodySet.addView(section("关于"));
        LinearLayout about = new LinearLayout(c);
        about.setOrientation(LinearLayout.VERTICAL);
        about.setBackground(Theme.card(c));
        about.setPadding(p, p, p, p);
        about.addView(kv("版本", "1.0.0（100）", Theme.TEXT));
        about.addView(divider());
        about.addView(kv("包名", getPackageName(), Theme.TEXT));
        about.addView(divider());
        about.addView(kv("形态", "LSPosed 模块 + 独立 APK", Theme.TEXT));
        about.addView(Theme.spacer(c, 12));
        about.addView(note("与内核模块（zip / WebUI）版本互不依赖，可并存：本 APK 走框架层拦截，"
                + "zip 版走守护进程轮询。两者同时开启也不会重复写入，但不建议同时启用。"));
        bodySet.addView(about, block());
    }

    private String staleSuffix() {
        int n = snap.stale().size();
        return n == 0 ? "（当前没有）" : "（当前 " + n + " 项）";
    }

    private View action(String title, String subtitle, Runnable r) {
        Context c = this;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int px = Theme.dp(c, 16);
        row.setPadding(px, Theme.dp(c, 13), px, Theme.dp(c, 13));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = Theme.text(c, title, 14, Theme.TEXT);
        TextView t2 = Theme.text(c, subtitle, 11, Theme.TEXT_DIM);
        t2.setPadding(0, Theme.dp(c, 3), 0, 0);
        t2.setLineSpacing(Theme.dp(c, 1), 1f);
        col.addView(t1);
        col.addView(t2);
        row.addView(col, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = Theme.text(c, "›", 20, Theme.TEXT_MUTE);
        row.addView(arrow);
        row.setOnClickListener(v -> r.run());
        return row;
    }

    /* --------------------------------------------------------------- widgets */

    private View kv(String key, String value, int colour) {
        Context c = this;
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Theme.dp(c, 9), 0, Theme.dp(c, 9));
        TextView k = Theme.text(c, key, 12, Theme.TEXT_DIM);
        TextView v = Theme.text(c, value, 12, colour);
        v.setGravity(Gravity.END);
        row.addView(k, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(v);
        return row;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(Theme.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Theme.dp(this, 1))));
        return v;
    }

    private View section(String title) {
        TextView t = Theme.text(this, title.toUpperCase(Locale.ROOT), 11, Theme.TEXT_MUTE);
        t.setLetterSpacing(0.08f);
        t.setPadding(Theme.dp(this, 4), 0, 0, Theme.dp(this, 8));
        return t;
    }

    private View code(String text) {
        Context c = this;
        TextView t = Theme.text(c, text, 11, Theme.TEXT_DIM);
        t.setTypeface(Typeface.MONOSPACE);
        t.setBackground(Theme.round(c, Theme.SURFACE, 14));
        int p = Theme.dp(c, 12);
        t.setPadding(p, p, p, p);
        t.setLineSpacing(Theme.dp(c, 2), 1f);
        return t;
    }

    private View note(String text) {
        Context c = this;
        TextView t = Theme.text(c, text, 12, Theme.TEXT_DIM);
        t.setLineSpacing(Theme.dp(c, 3), 1f);
        return t;
    }

    private View hint(String text) {
        Context c = this;
        TextView t = Theme.text(c, text, 13, Theme.TEXT_MUTE);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, Theme.dp(c, 40), 0, Theme.dp(c, 40));
        return t;
    }

    private TextView button(String label, int bg, int fg) {
        TextView t = Theme.text(this, label, 13, fg);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setBackground(Theme.round(this, bg, 14));
        t.setClickable(true);
        return t;
    }

    private static String join(Set<String> set) {
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) {
                sb.append(" : ");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    /* ================================================================ detail */

    private void detail(final Model m) {
        Context c = this;
        final String comp = m.comp();
        boolean guarded = snap.isGuarded(comp);
        boolean active = snap.isActive(comp);

        LinearLayout sheet = new LinearLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackground(Theme.sheet(c));
        int p = Theme.dp(c, 20);
        sheet.setPadding(p, Theme.dp(c, 12), p, Theme.dp(c, 20));

        View grab = new View(c);
        grab.setBackground(Theme.round(c, Theme.TEXT_MUTE, 2));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                Theme.dp(c, 34), Theme.dp(c, 4));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        grab.setLayoutParams(glp);
        sheet.addView(grab);

        TextView title = Theme.text(c, m.label, 19, Theme.TEXT);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setPadding(0, Theme.dp(c, 16), 0, 0);
        sheet.addView(title);

        TextView pkg = Theme.text(c, comp, 11, Theme.TEXT_DIM);
        pkg.setTypeface(Typeface.MONOSPACE);
        pkg.setPadding(0, Theme.dp(c, 6), 0, 0);
        pkg.setTextIsSelectable(true);
        sheet.addView(pkg);

        LinearLayout chips = new LinearLayout(c);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, Theme.dp(c, 12), 0, 0);
        chips.addView(pill(guarded ? "守护中" : "未守护", guarded ? Theme.ACCENT : Theme.TEXT_MUTE));
        TextView sp = pill(active ? "系统已启用" : "系统未启用", active ? Theme.OK : Theme.TEXT_MUTE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = Theme.dp(c, 6);
        chips.addView(sp, lp);
        if (m.system) {
            TextView sy = pill("系统应用", Theme.TEXT_DIM);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp2.leftMargin = Theme.dp(c, 6);
            chips.addView(sy, lp2);
        }
        sheet.addView(chips);

        TextView tip = Theme.text(c, guarded
                ? "这个服务被移除时，框架会在写入前把它并回列表。"
                : "加入守护后，即使系统或第三方把它关掉，也会自动补回。", 12, Theme.TEXT_DIM);
        tip.setPadding(0, Theme.dp(c, 16), 0, 0);
        tip.setLineSpacing(Theme.dp(c, 3), 1f);
        sheet.addView(tip);

        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, Theme.dp(c, 18), 0, 0);

        TextView main = button(guarded ? "取消守护" : "加入守护",
                guarded ? Theme.SURFACE_HI : Theme.ACCENT,
                guarded ? Theme.TEXT : 0xFF06101F);
        main.setOnClickListener(v -> {
            closeSheet();
            setGuard(comp, !guarded);
        });
        bar.addView(main, new LinearLayout.LayoutParams(0, Theme.dp(c, 46), 1f));

        TextView alt = button("系统设置", Theme.SURFACE_HI, Theme.TEXT);
        alt.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            } catch (Throwable t) {
                toast("无法打开设置");
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, Theme.dp(c, 46), 1f);
        alp.leftMargin = Theme.dp(c, 10);
        bar.addView(alt, alp);
        sheet.addView(bar);

        showSheet(sheet);
    }

    /* ================================================================= sheet */

    private void showSheet(View sheet) {
        closeSheet();
        FrameLayout layer = new FrameLayout(this);
        layer.setClickable(true);

        View dim = new View(this);
        dim.setBackgroundColor(0xCC000000);
        dim.setOnClickListener(v -> closeSheet());
        layer.addView(dim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        layer.addView(sheet, lp);
        layer.setAlpha(0f);
        root.addView(layer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = layer;
        layer.animate().alpha(1f).setDuration(140L).start();
        sheet.post(() -> {
            sheet.setTranslationY(sheet.getHeight());
            sheet.animate().translationY(0f).setDuration(200L).start();
        });
    }

    private void closeSheet() {
        if (overlay == null) {
            return;
        }
        final View v = overlay;
        overlay = null;
        v.animate().alpha(0f).setDuration(120L).withEndAction(() -> {
            try {
                root.removeView(v);
            } catch (Throwable ignored) {
            }
        }).start();
    }

    @Override
    public void onBackPressed() {
        if (overlay != null) {
            closeSheet();
            return;
        }
        if (tab != TAB_LIST) {
            select(TAB_LIST);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (snap != null) {
            reload();
        }
    }
}
