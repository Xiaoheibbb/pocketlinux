package com.pocketlinux.probe;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.pocketlinux.probe.ui.Ui;

/**
 * 设置页：显示 / 触控 / 性能 / 图形渲染器 / Wine / 高级。
 * 分组参照 Winlator 的 Container Settings 与 DroidDeck 的 Setup 分类，每组带一句人话说明与当前值。
 * 首页「触控设置」入口可通过 EXTRA_FOCUS=FOCUS_TOUCH 直接定位到触控分组。
 */
public class SettingsActivity extends Activity {
    private Settings s;

    private Switch autoSwitch;
    private Switch monoSwitch;
    private EditText wEdit, hEdit;
    private TextView[] presetBtns;
    private TextView[] perfBtns;
    private TextView[] rendererBtns;
    private TextView[] box64Btns;
    private TextView resHint, perfHint, rendererDesc, box64Desc, summaryValue, summarySub;

    // 触控分组控件
    private ScrollView scrollView;
    private LinearLayout touchCard;
    private TextView[] touchModeBtns;
    private TextView touchDescTrackpad, touchDescDirect, sensValue, holdValue, rightValue;
    private SeekBar sensBar, holdBar, rightBar;

    private static final int[][] PRESETS = {{540, 1170}, {680, 1472}, {900, 1950}};
    private static final int[] BUDGETS = {550000, 1000000, 1600000, 2400000};
    private static final String[] BUDGET_NAMES = {"流畅", "均衡", "清晰", "极清"};

    private static final String[] RENDERER_VALUES = {
            Settings.RENDERER_DXVK, Settings.RENDERER_DXVK_VORTEK, Settings.RENDERER_WINED3D};
    private static final String[] RENDERER_LABELS = {"DXVK", "DXVK (vortek)", "wined3d"};
    private static final String[] RENDERER_DESCS = {
            "把 DirectX 9–11 翻译成 Vulkan：性能和兼容性最好，绝大多数 3D 游戏选这个。",
            "Winlator 的 Vulkan 包装层（vortek）：适合部分 Mali / 驱动不兼容场景，稳定性仍在验证。",
            "用 OpenGL 模拟 DirectX：兼容性最广、性能较低，黑屏或老游戏排查时用。"};

    private static final int[] BOX64_VALUES = {
            Settings.BOX64_PERFORMANCE, Settings.BOX64_BALANCED, Settings.BOX64_STABILITY};
    private static final String[] BOX64_NAMES = {"性能", "平衡", "兼容"};
    private static final String[] BOX64_DESCS = {
            "优先帧率，适合配置高、愿意折腾的机器（对应 Winlator Performance 预设）。",
            "默认档，兼顾帧率与稳定性。",
            "优先稳定性，Unity / 老游戏崩溃时可选（对应 Winlator Stability 预设）。"};

    // ---- 触控分组 ----
    public static final String EXTRA_FOCUS = "settings_focus";
    public static final String FOCUS_TOUCH = "touch";
    private static final String[] TOUCH_MODE_VALUES = {
            Settings.TOUCH_MODE_TRACKPAD, Settings.TOUCH_MODE_DIRECT};
    private static final String[] TOUCH_MODE_LABELS = {"触控板", "直接触摸"};
    private static final String TOUCH_DESC_TRACKPAD =
            "触控板：单指滑动移指针，轻点=左键，按住后滑=拖拽，按住不动抬手=右键，双指上下滑=滚轮。";
    private static final String TOUCH_DESC_DIRECT =
            "直接触摸：点哪指哪，像用平板；适合点按钮/菜单，拖拽时按住目标再移动。";
    private static final int SENS_STEPS = 17;    // 0.8–2.5，步进 0.1
    private static final int HOLD_STEPS = 55;    // 250–800ms，步进 10ms
    private static final int RIGHT_STEPS = 60;   // 400–1000ms，步进 10ms

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        Ui.applyTheme(this);
        s = Settings.load(this);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 28));
        content.setBackgroundColor(Ui.color(Ui.BG));

        content.addView(buildHeader());
        content.addView(buildSummaryCard());
        content.addView(buildDisplayCard());
        content.addView(buildTouchCard());
        content.addView(buildPerfCard());
        content.addView(buildRendererCard());
        content.addView(buildWineCard());
        content.addView(buildAdvancedCard());
        content.addView(buildAboutCard());
        content.addView(buildActions());

        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setBackgroundColor(Ui.color(Ui.BG));
        scrollView.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);

        refreshEnabled();
        applyFocus();
    }

    // ------------------------------------------------------------------ 头部

    private View buildHeader() {
        LinearLayout head = Ui.row(this);
        TextView back = Ui.text(this, "‹", 30, Ui.color(Ui.TEXT), false);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, Ui.dp(this, 12), 0);
        back.setOnClickListener(v -> finish());
        head.addView(back);

        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "设置", 23, Ui.color(Ui.TEXT), true));
        col.addView(Ui.text(this, "修改后点底部「保存」生效", 11, Ui.color(Ui.TEXT_3)));
        head.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView reset = Ui.smallButton(this, "恢复默认", false);
        reset.setOnClickListener(v -> {
            s.width = 680;
            s.height = 1472;
            s.budget = 1000000;
            s.disableMono = true;
            s.renderer = Settings.RENDERER_DXVK;
            s.box64Preset = Settings.BOX64_BALANCED;
            s.touchMode = Settings.TOUCH_MODE_TRACKPAD;
            s.trackpadSens = Settings.TRACKPAD_SENS_DEFAULT;
            s.holdDragMs = Settings.HOLD_DRAG_MS_DEFAULT;
            s.longRightMs = Settings.LONG_RIGHT_MS_DEFAULT;
            wEdit.setText(String.valueOf(s.width));
            hEdit.setText(String.valueOf(s.height));
            // setText 的监听会把 autoRes 置 false，所以放到最后再打开自动
            s.autoRes = true;
            refreshEnabled();
            Toast.makeText(this, "已恢复默认（记得保存）", Toast.LENGTH_SHORT).show();
        });
        head.addView(reset);
        Ui.margins(this, head, 0, 0, 0, 14);
        return head;
    }

    private View buildSummaryCard() {
        LinearLayout card = Ui.card(this, 16, Ui.color(Ui.PRIMARY_SOFT), 18);
        card.setBackground(Ui.round(this, Ui.color(Ui.PRIMARY_SOFT), 18,
                Ui.color("#2C4A86"), 1));
        TextView label = Ui.text(this, "当前配置", 11, Ui.color("#8FB3FF"), true);
        card.addView(label);
        summaryValue = Ui.text(this, "", 14, Ui.color(Ui.TEXT), true);
        Ui.margins(this, summaryValue, 0, 5, 0, 0);
        card.addView(summaryValue);
        summarySub = Ui.text(this, "", 11, Ui.color("#9FB2D8"));
        Ui.margins(this, summarySub, 0, 4, 0, 0);
        card.addView(summarySub);
        Ui.margins(this, card, 0, 0, 0, 14);
        return card;
    }

    // ------------------------------------------------------------------ 显示

    private LinearLayout buildDisplayCard() {
        LinearLayout card = card("显示");
        card.addView(desc("桌面分辨率：越高越清晰，也越吃性能与网络。"));

        autoSwitch = switchRow(card, "自动分辨率（按手机屏幕比例）", s.autoRes);
        autoSwitch.setOnCheckedChangeListener((b, checked) -> {
            s.autoRes = checked;
            refreshEnabled();
        });

        card.addView(label("常用分辨率"));
        LinearLayout presetRow = Ui.row(this);
        Ui.margins(this, presetRow, 0, 6, 0, 0);
        presetBtns = new TextView[PRESETS.length];
        for (int i = 0; i < PRESETS.length; i++) {
            final int idx = i;
            TextView b = segment(PRESETS[i][0] + "×" + PRESETS[i][1]);
            b.setOnClickListener(v -> {
                s.autoRes = false;
                s.width = PRESETS[idx][0];
                s.height = PRESETS[idx][1];
                wEdit.setText(String.valueOf(s.width));
                hEdit.setText(String.valueOf(s.height));
                refreshEnabled();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1f);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            presetRow.addView(b, lp);
            presetBtns[i] = b;
        }
        card.addView(presetRow);

        LinearLayout customRow = Ui.row(this);
        Ui.margins(this, customRow, 0, 12, 0, 0);
        TextView cw = Ui.text(this, "宽", 12, Ui.color(Ui.TEXT_2));
        customRow.addView(cw);
        wEdit = numEdit(s.width, false);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(Ui.dp(this, 80), Ui.dp(this, 42));
        wlp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        customRow.addView(wEdit, wlp);

        TextView ch = Ui.text(this, "高", 12, Ui.color(Ui.TEXT_2));
        Ui.margins(this, ch, 14, 0, 0, 0);
        customRow.addView(ch);
        hEdit = numEdit(s.height, true);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(Ui.dp(this, 80), Ui.dp(this, 42));
        hlp.setMargins(Ui.dp(this, 8), 0, 0, 0);
        customRow.addView(hEdit, hlp);
        TextView px = Ui.text(this, "px", 11, Ui.color(Ui.TEXT_3));
        Ui.margins(this, px, 8, 0, 0, 0);
        customRow.addView(px);
        card.addView(customRow);

        resHint = Ui.text(this, "", 11, Ui.color(Ui.TEXT_3));
        Ui.margins(this, resHint, 0, 10, 0, 0);
        card.addView(resHint);
        return card;
    }

    // ------------------------------------------------------------------ 性能

    private LinearLayout buildPerfCard() {
        LinearLayout card = card("性能");
        card.addView(desc("按像素预算自动缩放画面：档位越高越清晰，也越吃 CPU/内存。"));

        perfBtns = new TextView[BUDGETS.length];
        LinearLayout perfRow = Ui.row(this);
        Ui.margins(this, perfRow, 0, 8, 0, 0);
        for (int i = 0; i < BUDGETS.length; i++) {
            final int idx = i;
            TextView b = segment(BUDGET_NAMES[i]);
            b.setOnClickListener(v -> {
                s.budget = BUDGETS[idx];
                refreshEnabled();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1f);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            perfRow.addView(b, lp);
            perfBtns[i] = b;
        }
        card.addView(perfRow);

        perfHint = Ui.text(this, "", 11, Ui.color(Ui.TEXT_3));
        Ui.margins(this, perfHint, 0, 10, 0, 0);
        card.addView(perfHint);
        return card;
    }

    // ------------------------------------------------------------------ 渲染器

    private LinearLayout buildRendererCard() {
        LinearLayout card = card("图形渲染器");
        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "DirectX 转译方式", 12, Ui.color(Ui.TEXT_2)));
        head.addView(Ui.stretch(this));
        head.addView(Ui.pill(this, "待接入", Ui.color(Ui.AMBER), alpha(Ui.color(Ui.AMBER), 0x28)));
        card.addView(head);

        rendererBtns = new TextView[RENDERER_VALUES.length];
        LinearLayout row = Ui.row(this);
        Ui.margins(this, row, 0, 8, 0, 0);
        for (int i = 0; i < RENDERER_VALUES.length; i++) {
            final int idx = i;
            TextView b = segment(RENDERER_LABELS[i]);
            b.setTextSize(11.5f);
            b.setOnClickListener(v -> {
                s.renderer = RENDERER_VALUES[idx];
                refreshEnabled();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1f);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            row.addView(b, lp);
            rendererBtns[i] = b;
        }
        card.addView(row);

        rendererDesc = Ui.text(this, "", 11.5f, Ui.color(Ui.TEXT_2));
        rendererDesc.setLineSpacing(Ui.dp(this, 3), 1f);
        Ui.margins(this, rendererDesc, 0, 10, 0, 0);
        card.addView(rendererDesc);

        TextView note = Ui.text(this,
                "说明：当前版本只保存选择，后端 Wine 启动脚本还没读取；接入后会通过环境变量切换 DXVK / wined3d。",
                10.5f, Ui.color(Ui.TEXT_3));
        note.setLineSpacing(Ui.dp(this, 2), 1f);
        Ui.margins(this, note, 0, 8, 0, 0);
        card.addView(note);
        return card;
    }

    // ------------------------------------------------------------------ Wine

    private LinearLayout buildWineCard() {
        LinearLayout card = card("Wine");
        monoSwitch = switchRow(card, "关闭 Mono / Gecko 首次安装弹窗", s.disableMono);
        monoSwitch.setOnCheckedChangeListener((b, checked) -> {
            s.disableMono = checked;
            refreshEnabled();
        });
        TextView hint = Ui.text(this,
                "首次运行 Windows 程序会自动初始化 Wine 环境（约 1–2 分钟），之后会快很多。"
                        + "需要 .NET 的软件请关闭此开关，或在桌面里安装 Wine Mono。",
                11, Ui.color(Ui.TEXT_2));
        hint.setLineSpacing(Ui.dp(this, 3), 1f);
        Ui.margins(this, hint, 0, 10, 0, 0);
        card.addView(hint);
        return card;
    }

    // ------------------------------------------------------------------ 高级

    private LinearLayout buildAdvancedCard() {
        LinearLayout card = card("高级");
        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "box64 预设", 12, Ui.color(Ui.TEXT_2)));
        head.addView(Ui.stretch(this));
        head.addView(Ui.pill(this, "未实现", Ui.color(Ui.TEXT_2), Ui.color(Ui.CARD_3)));
        card.addView(head);

        box64Btns = new TextView[BOX64_VALUES.length];
        LinearLayout row = Ui.row(this);
        Ui.margins(this, row, 0, 8, 0, 0);
        for (int i = 0; i < BOX64_VALUES.length; i++) {
            final int idx = i;
            TextView b = segment(BOX64_NAMES[i]);
            b.setOnClickListener(v -> {
                s.box64Preset = BOX64_VALUES[idx];
                refreshEnabled();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1f);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            row.addView(b, lp);
            box64Btns[i] = b;
        }
        card.addView(row);

        box64Desc = Ui.text(this, "", 11.5f, Ui.color(Ui.TEXT_2));
        box64Desc.setLineSpacing(Ui.dp(this, 3), 1f);
        Ui.margins(this, box64Desc, 0, 10, 0, 0);
        card.addView(box64Desc);

        TextView warn = Ui.text(this,
                "占位项：后端启动脚本尚未读取该值，切换不会改变实际运行效果。",
                10.5f, Ui.color(Ui.AMBER));
        Ui.margins(this, warn, 0, 8, 0, 12);
        card.addView(warn);

        View divider = new View(this);
        divider.setBackgroundColor(Ui.color(Ui.STROKE));
        card.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));

        LinearLayout testRow = Ui.row(this);
        Ui.margins(this, testRow, 0, 12, 0, 0);
        LinearLayout testCol = Ui.column(this);
        testCol.addView(Ui.text(this, "运行 notepad 测试", 12.5f, Ui.color(Ui.TEXT), true));
        testCol.addView(Ui.text(this, "打开 Wine 自带记事本，确认 Wine 链路是否正常。", 10.5f, Ui.color(Ui.TEXT_3)));
        testRow.addView(testCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView testBtn = Ui.smallButton(this, "运行", true);
        testBtn.setOnClickListener(v -> {
            try {
                Intent i = new Intent(this, MainActivity.class);
                i.putExtra(MainActivity.EXTRA_EXE, "notepad");
                startActivity(i);
            } catch (Throwable t) {
                Toast.makeText(this, "启动失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        testRow.addView(testBtn);
        card.addView(testRow);
        return card;
    }

    // ------------------------------------------------------------------ 关于

    private LinearLayout buildAboutCard() {
        LinearLayout card = card("关于");
        String ver = "0.1";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        TextView t = Ui.text(this, "PocketLinux " + ver, 13, Ui.color(Ui.TEXT), true);
        card.addView(t);
        TextView info = Ui.text(this,
                "内置 Ubuntu 24.04 · Wine 11 · box64 0.4.4\n当前 Android " + Build.VERSION.RELEASE
                        + " · targetSdk " + getApplicationInfo().targetSdkVersion
                        + "\n界面设计参考 Winlator、DroidDeck、盖世游戏。",
                10.5f, Ui.color(Ui.TEXT_3));
        info.setLineSpacing(Ui.dp(this, 2), 1f);
        Ui.margins(this, info, 0, 6, 0, 0);
        card.addView(info);
        return card;
    }

    // ------------------------------------------------------------------ 底部

    private View buildActions() {
        LinearLayout btnRow = Ui.row(this);
        Ui.margins(this, btnRow, 0, 6, 0, 0);

        TextView save = Ui.button(this, "保存", false, 50);
        save.setOnClickListener(v -> doSave(false));
        btnRow.addView(save, new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f));

        TextView apply = Ui.button(this, "保存并重启桌面", true, 50);
        apply.setOnClickListener(v -> doSave(true));
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1.6f);
        alp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        btnRow.addView(apply, alp);
        return btnRow;
    }

    // ------------------------------------------------------------------ 控件

    private LinearLayout card(String title) {
        LinearLayout card = Ui.card(this, 16, Ui.color(Ui.CARD), 20);
        TextView t = Ui.text(this, title, 13, Ui.color(Ui.TEXT), true);
        card.addView(t);
        Ui.margins(this, card, 0, 0, 0, 12);
        return card;
    }

    private TextView desc(String text) {
        TextView t = Ui.text(this, text, 11, Ui.color(Ui.TEXT_2));
        t.setLineSpacing(Ui.dp(this, 2), 1f);
        Ui.margins(this, t, 0, 4, 0, 4);
        return t;
    }

    private TextView label(String text) {
        TextView t = Ui.text(this, text, 11, Ui.color(Ui.TEXT_3));
        Ui.margins(this, t, 0, 12, 0, 0);
        return t;
    }

    private TextView segment(String label) {
        TextView t = Ui.text(this, label, 12, Ui.color(Ui.TEXT), true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    private EditText numEdit(int value, final boolean heightField) {
        EditText e = new EditText(this);
        e.setText(String.valueOf(value));
        e.setTextColor(Ui.color(Ui.TEXT));
        e.setTextSize(14);
        e.setGravity(Gravity.CENTER);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setSingleLine(true);
        e.setBackground(Ui.round(this, Ui.color(Ui.CARD_2), 10));
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence cs, int a, int b, int c) {}

            public void onTextChanged(CharSequence cs, int a, int b, int c) {}

            public void afterTextChanged(Editable ed) {
                try {
                    int v = Integer.parseInt(ed.toString().trim());
                    if (heightField) s.height = v;
                    else s.width = v;
                } catch (Exception ignored) {
                }
                s.autoRes = false;
                refreshEnabled();
            }
        });
        return e;
    }

    private Switch switchRow(LinearLayout parent, String label, boolean checked) {
        LinearLayout row = Ui.row(this);
        Ui.margins(this, row, 0, 8, 0, 0);
        TextView t = Ui.text(this, label, 12.5f, Ui.color(Ui.TEXT));
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setPadding(0, 0, 0, 0);
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{-android.R.attr.state_checked}};
        sw.setThumbTintList(new android.content.res.ColorStateList(states,
                new int[]{Ui.color(Ui.PRIMARY), Ui.color("#8A90A6")}));
        sw.setTrackTintList(new android.content.res.ColorStateList(states,
                new int[]{alpha(Ui.color(Ui.PRIMARY), 0x66), alpha(Ui.color(Ui.CARD_3), 0xFF)}));
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(row);
        return sw;
    }

    // ------------------------------------------------------------------ 刷新

    private void refreshEnabled() {
        if (wEdit == null || autoSwitch == null) return;

        if (autoSwitch.isChecked() != s.autoRes) autoSwitch.setChecked(s.autoRes);

        boolean manual = !s.autoRes;
        wEdit.setEnabled(manual);
        hEdit.setEnabled(manual);
        wEdit.setAlpha(manual ? 1f : 0.4f);
        hEdit.setAlpha(manual ? 1f : 0.4f);

        for (int i = 0; i < presetBtns.length; i++) {
            boolean sel = manual && s.width == PRESETS[i][0] && s.height == PRESETS[i][1];
            paintSegment(presetBtns[i], sel);
        }
        for (int i = 0; i < perfBtns.length; i++) {
            paintSegment(perfBtns[i], s.budget == BUDGETS[i]);
        }
        int rendererIdx = 0;
        for (int i = 0; i < RENDERER_VALUES.length; i++) {
            boolean sel = RENDERER_VALUES[i].equals(s.renderer);
            paintSegment(rendererBtns[i], sel);
            if (sel) rendererIdx = i;
        }
        rendererDesc.setText(RENDERER_DESCS[rendererIdx]);

        int box64Idx = 1;
        for (int i = 0; i < BOX64_VALUES.length; i++) {
            boolean sel = s.box64Preset == BOX64_VALUES[i];
            paintSegment(box64Btns[i], sel);
            if (sel) box64Idx = i;
        }
        box64Desc.setText(BOX64_DESCS[box64Idx]);

        // ---- 触控回显 ----
        boolean trackpad = s.isTrackpadMode();
        paintSegment(touchModeBtns[0], trackpad);
        paintSegment(touchModeBtns[1], !trackpad);
        touchDescTrackpad.setTextColor(trackpad ? Ui.color(Ui.TEXT) : Ui.color(Ui.TEXT_3));
        touchDescDirect.setTextColor(trackpad ? Ui.color(Ui.TEXT_3) : Ui.color(Ui.TEXT));
        setProgressIfDiff(sensBar, sensToProgress(s.trackpadSens));
        setProgressIfDiff(holdBar, holdToProgress(s.holdDragMs));
        setProgressIfDiff(rightBar, rightToProgress(s.longRightMs));
        sensValue.setText(String.format(java.util.Locale.US, "%.1f×", s.getTrackpadSens()));
        holdValue.setText(s.getHoldDragMs() + " ms");
        rightValue.setText(s.getLongRightMs() + " ms");

        int[] fb = s.fbSize(screenW(), screenH());
        String rendererName = Settings.rendererName(s.renderer);
        resHint.setText("生效分辨率 " + fb[0] + "×" + fb[1]
                + "（" + (s.autoRes ? "自动" : "自定义") + "）");
        perfHint.setText("当前档位：" + Settings.budgetName(s.budget)
                + " · 约 " + String.format("%.1f", fb[0] * fb[1] / 1000000.0) + " 百万像素");
        summaryValue.setText(fb[0] + " × " + fb[1]
                + " · " + Settings.budgetName(s.budget)
                + " · " + rendererName
                + " · Mono " + (s.disableMono ? "关闭" : "开启"));
        summarySub.setText("触控 " + Settings.touchModeName(s.getTouchMode())
                + " · 灵敏度 " + String.format(java.util.Locale.US, "%.1f×", s.getTrackpadSens())
                + " · 拖拽 " + s.getHoldDragMs() + "ms · 右键 " + s.getLongRightMs() + "ms");
    }

    private void paintSegment(TextView v, boolean selected) {
        v.setBackground(Ui.round(this, selected ? Ui.color(Ui.PRIMARY) : Ui.color(Ui.CARD_2), 10));
        v.setTextColor(selected ? Color.WHITE : Ui.color(Ui.TEXT));
    }

    // ------------------------------------------------------------------ 触控分组

    private LinearLayout buildTouchCard() {
        LinearLayout card = card("触控");
        touchCard = card;

        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "触控方式与阈值", 12, Ui.color(Ui.TEXT_2)));
        head.addView(Ui.stretch(this));
        head.addView(Ui.pill(this, "实时数值", Ui.color(Ui.GREEN),
                alpha(Ui.color(Ui.GREEN), 0x28)));
        card.addView(head);

        card.addView(desc("滑动手感可以按习惯调；拖动滑杆时数值实时变化，保存后进入桌面生效。"));

        // 模式
        touchModeBtns = new TextView[TOUCH_MODE_VALUES.length];
        LinearLayout modeRow = Ui.row(this);
        Ui.margins(this, modeRow, 0, 8, 0, 0);
        for (int i = 0; i < TOUCH_MODE_VALUES.length; i++) {
            final int idx = i;
            TextView b = segment(TOUCH_MODE_LABELS[i]);
            b.setOnClickListener(v -> {
                s.touchMode = TOUCH_MODE_VALUES[idx];
                refreshEnabled();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1f);
            lp.setMargins(Ui.dp(this, 3), 0, Ui.dp(this, 3), 0);
            modeRow.addView(b, lp);
            touchModeBtns[i] = b;
        }
        card.addView(modeRow);

        touchDescTrackpad = Ui.text(this, TOUCH_DESC_TRACKPAD, 11, Ui.color(Ui.TEXT_3));
        touchDescTrackpad.setLineSpacing(Ui.dp(this, 2), 1f);
        Ui.margins(this, touchDescTrackpad, 0, 8, 0, 0);
        card.addView(touchDescTrackpad);

        touchDescDirect = Ui.text(this, TOUCH_DESC_DIRECT, 11, Ui.color(Ui.TEXT_3));
        touchDescDirect.setLineSpacing(Ui.dp(this, 2), 1f);
        Ui.margins(this, touchDescDirect, 0, 4, 0, 0);
        card.addView(touchDescDirect);

        // 灵敏度
        sensValue = sliderTitle(card, "触控板灵敏度");
        sliderDesc(card, "数值越大，同样滑动手指标移动越快：0.8× 更稳，2.5× 更灵敏。");
        sensBar = seekBar(SENS_STEPS);
        card.addView(sensBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sensBar.setOnSeekBarChangeListener(new SeekAdapter() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                s.trackpadSens = progressToSens(progress);
                refreshEnabled();
            }
        });

        // 按住拖拽触发
        holdValue = sliderTitle(card, "按住拖拽触发");
        sliderDesc(card, "按住多久后继续滑动会变成「按住左键拖拽」，用来拖窗口/图标。");
        holdBar = seekBar(HOLD_STEPS);
        card.addView(holdBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        holdBar.setOnSeekBarChangeListener(new SeekAdapter() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                s.holdDragMs = progressToHold(progress);
                refreshEnabled();
            }
        });

        // 长按右键
        rightValue = sliderTitle(card, "长按右键");
        sliderDesc(card, "按住不动多久后抬手算右键（优先级低于双指右键）。");
        rightBar = seekBar(RIGHT_STEPS);
        card.addView(rightBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        rightBar.setOnSeekBarChangeListener(new SeekAdapter() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser) return;
                s.longRightMs = progressToRight(progress);
                refreshEnabled();
            }
        });

        TextView note = Ui.text(this,
                "点底部「保存并重启桌面」可立即应用到正在运行的桌面；只点「保存」则下次进入桌面生效。",
                10.5f, Ui.color(Ui.TEXT_3));
        note.setLineSpacing(Ui.dp(this, 2), 1f);
        Ui.margins(this, note, 0, 12, 0, 0);
        card.addView(note);
        return card;
    }

    private TextView sliderTitle(LinearLayout card, String title) {
        LinearLayout row = Ui.row(this);
        Ui.margins(this, row, 0, 14, 0, 0);
        row.addView(Ui.text(this, title, 12.5f, Ui.color(Ui.TEXT), true));
        row.addView(Ui.stretch(this));
        TextView value = Ui.text(this, "", 12.5f, Ui.color(Ui.PRIMARY_DIM), true);
        row.addView(value);
        card.addView(row);
        return value;
    }

    private void sliderDesc(LinearLayout card, String text) {
        TextView t = Ui.text(this, text, 10.5f, Ui.color(Ui.TEXT_3));
        t.setLineSpacing(Ui.dp(this, 2), 1f);
        Ui.margins(this, t, 0, 3, 0, 2);
        card.addView(t);
    }

    private SeekBar seekBar(int max) {
        SeekBar bar = new SeekBar(this);
        bar.setMax(max);
        bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(Ui.color(Ui.PRIMARY)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Ui.color(Ui.CARD_3)));
        bar.setThumbTintList(ColorStateList.valueOf(Ui.color(Ui.PRIMARY_DIM)));
        bar.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6));
        return bar;
    }

    private void setProgressIfDiff(SeekBar bar, int progress) {
        if (bar == null) return;
        if (progress < 0) progress = 0;
        if (progress > bar.getMax()) progress = bar.getMax();
        if (bar.getProgress() != progress) bar.setProgress(progress);
    }

    private static int sensToProgress(float sens) {
        return Math.max(0, Math.min(SENS_STEPS,
                Math.round((Settings.clampTrackpadSens(sens) - Settings.TRACKPAD_SENS_MIN) * 10f)));
    }

    private static float progressToSens(int progress) {
        return Math.round((Settings.TRACKPAD_SENS_MIN + progress * 0.1f) * 10f) / 10f;
    }

    private static int holdToProgress(int ms) {
        return Math.max(0, Math.min(HOLD_STEPS,
                Math.round((Settings.clampHoldDragMs(ms) - Settings.HOLD_DRAG_MS_MIN) / 10f)));
    }

    private static int progressToHold(int progress) {
        return Settings.HOLD_DRAG_MS_MIN + progress * 10;
    }

    private static int rightToProgress(int ms) {
        return Math.max(0, Math.min(RIGHT_STEPS,
                Math.round((Settings.clampLongRightMs(ms) - Settings.LONG_RIGHT_MS_MIN) / 10f)));
    }

    private static int progressToRight(int progress) {
        return Settings.LONG_RIGHT_MS_MIN + progress * 10;
    }

    /** 从首页「触控设置」进来时滚动到触控分组并高亮一下。 */
    private void applyFocus() {
        Intent intent = getIntent();
        if (intent == null || !FOCUS_TOUCH.equals(intent.getStringExtra(EXTRA_FOCUS))) return;
        if (scrollView == null || touchCard == null) return;
        scrollView.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            scrollView.smoothScrollTo(0, Math.max(0,
                    touchCard.getTop() - Ui.dp(this, 8)));
            touchCard.setBackground(Ui.round(this, Ui.color(Ui.CARD), 20,
                    Ui.color(Ui.PRIMARY), 2));
            touchCard.postDelayed(() -> {
                if (isFinishing() || isDestroyed()) return;
                touchCard.setBackground(Ui.round(this, Ui.color(Ui.CARD), 20));
            }, 1800);
        });
    }

    private abstract static class SeekAdapter implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {}
    }

    private int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    private int screenW() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        return dm.widthPixels;
    }

    private int screenH() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        return dm.heightPixels;
    }

    private void doSave(boolean restart) {
        try {
            s.width = Integer.parseInt(wEdit.getText().toString().trim());
        } catch (Exception ignored) {
        }
        try {
            s.height = Integer.parseInt(hEdit.getText().toString().trim());
        } catch (Exception ignored) {
        }
        s.width = Math.max(320, Math.min(3840, s.width));
        s.height = Math.max(320, Math.min(3840, s.height));
        s.save(this);
        Toast.makeText(this, restart ? "已保存，正在重启桌面…" : "已保存，重启桌面后生效",
                Toast.LENGTH_SHORT).show();
        if (restart) {
            try {
                Intent i = new Intent(this, MainActivity.class);
                i.putExtra(MainActivity.EXTRA_RESTART, true);
                startActivity(i);
            } catch (Throwable t) {
                Toast.makeText(this, "重启桌面失败：" + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }
}
