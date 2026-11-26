package com.pocketlinux.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.pocketlinux.probe.ui.DesktopStatus;
import com.pocketlinux.probe.ui.GameImporter;
import com.pocketlinux.probe.ui.SteamActivity;
import com.pocketlinux.probe.ui.SteamStore;
import com.pocketlinux.probe.ui.Ui;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页（Home）：Linux 桌面入口 + Steam 快速启动 + Windows 程序库。
 * 启动游戏仍走 MainActivity 的 EXTRA_EXE / EXTRA_WORKDIR 合同。
 */
public class GameLibraryActivity extends Activity {
    private static final String TAG = "PLPROBE";
    private static final int REQ_IMPORT_FILE = 1001;
    private static final int REQ_IMPORT_TREE = 1002;

    private static final String[] ICON_COLORS = {
            "#2A6CFF", "#7C4DFF", "#00A88F", "#E5622A", "#C9387A", "#3B8E2E"
    };

    private GameStore store;
    private SteamStore steamStore;

    private boolean importing;
    private boolean statusRunning;

    private View statusDot;
    private TextView statusPill;
    private TextView resValue;
    private TextView resCaption;
    private TextView statusSub;
    private TextView enterBtn;
    private TextView restartBtn;
    private TextView steamSub;
    private TextView countLabel;
    private TextView importBtn;
    private LinearLayout programArea;
    private LinearLayout importBanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        Ui.applyTheme(this);
        store = new GameStore(this);
        steamStore = new SteamStore(this);

        setContentView(buildRoot());
        // 首帧先显示加载态，再渲染程序库（避免数据为空时闪一下空状态）
        programArea.addView(buildLoadingCard());
        programArea.post(this::renderPrograms);
        refreshSteamCount();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从桌面/Steam/设置回来时刷新状态与数量
        refreshStatus();
        steamStore = new SteamStore(this);
        refreshSteamCount();
    }

    // ------------------------------------------------------------------ 布局

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.color(Ui.BG));
        root.addView(buildTopBar());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 28));

        LinearLayout desktopCard = buildDesktopCard();
        Ui.margins(this, desktopCard, 0, 2, 0, 10);
        content.addView(desktopCard);

        // 进入桌面卡片下方：触控设置快捷入口（直接定位到设置页的「触控」分组）
        View touchEntry = buildTouchEntry();
        Ui.margins(this, touchEntry, 0, 0, 0, 14);
        content.addView(touchEntry);

        LinearLayout steamCard = buildSteamCard();
        Ui.margins(this, steamCard, 0, 0, 0, 22);
        content.addView(steamCard);

        content.addView(buildProgramsHeader());

        importBanner = buildImportBanner();
        Ui.margins(this, importBanner, 0, 0, 0, 10);
        content.addView(importBanner);

        programArea = new LinearLayout(this);
        programArea.setOrientation(LinearLayout.VERTICAL);
        content.addView(programArea);

        TextView footer = Ui.caption(this,
                "长按程序卡片可管理 · 导入文件保存在 App 私有目录\nWine 程序运行后会出现在 XFCE 桌面里");
        footer.setGravity(Gravity.CENTER);
        footer.setLineSpacing(Ui.dp(this, 3), 1f);
        Ui.margins(this, footer, 0, 18, 0, 4);
        content.addView(footer);

        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 18), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 8));

        TextView logo = Ui.text(this, "P", 19, Ui.color("#FFFFFF"), true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.round(this, Ui.color(Ui.PRIMARY), 12));
        bar.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 38), Ui.dp(this, 38)));

        LinearLayout titles = Ui.column(this);
        Ui.margins(this, titles, 10, 0, 0, 0);
        titles.addView(Ui.text(this, "PocketLinux", 19, Ui.color(Ui.TEXT), true));
        titles.addView(Ui.text(this, "手机上的 Linux 电脑", 10.5f, Ui.color(Ui.TEXT_3)));
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView gear = Ui.iconButton(this, "⚙");
        gear.setOnClickListener(v -> openSettings());
        bar.addView(gear);
        return bar;
    }

    private LinearLayout buildDesktopCard() {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.gradient(this, Ui.color("#1D2C4C"), Ui.color("#121622"), 22));
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16));

        // 标题行：状态点 + 标题 + 状态胶囊
        LinearLayout top = Ui.row(this);
        statusDot = Ui.dot(this, Ui.color(Ui.TEXT_3), 8);
        top.addView(statusDot);
        TextView name = Ui.text(this, "Linux 桌面", 16, Ui.color(Ui.TEXT), true);
        Ui.margins(this, name, 8, 0, 0, 0);
        top.addView(name);
        top.addView(Ui.stretch(this));
        statusPill = Ui.pill(this, "检测中…", Ui.color(Ui.TEXT_2), Ui.color("#263048"));
        top.addView(statusPill);
        card.addView(top);

        // 分辨率
        LinearLayout resRow = Ui.row(this);
        Ui.margins(this, resRow, 0, 12, 0, 0);
        LinearLayout resCol = Ui.column(this);
        resValue = Ui.text(this, "…", 26, Ui.color(Ui.TEXT), true);
        resCol.addView(resValue);
        resCaption = Ui.text(this, "当前分辨率 · XFCE", 11, Ui.color("#9FB2D8"));
        Ui.margins(this, resCaption, 0, 2, 0, 0);
        resCol.addView(resCaption);
        resRow.addView(resCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tag = Ui.pill(this, "XFCE · noVNC", Ui.color("#B7C6E8"), Ui.color("#26314D"));
        resRow.addView(tag);
        card.addView(resRow);

        statusSub = Ui.text(this, "正在检测桌面状态…", 12, Ui.color("#A7B6D4"));
        statusSub.setLineSpacing(Ui.dp(this, 3), 1f);
        Ui.margins(this, statusSub, 0, 10, 0, 0);
        card.addView(statusSub);

        // 按钮
        LinearLayout btns = Ui.row(this);
        Ui.margins(this, btns, 0, 14, 0, 0);
        enterBtn = Ui.button(this, "进入桌面", true, 46);
        enterBtn.setOnClickListener(v -> startDesktop(false));
        btns.addView(enterBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f));

        restartBtn = Ui.button(this, "重启桌面", false, 46);
        restartBtn.setOnClickListener(v -> startDesktop(true));
        restartBtn.setVisibility(View.GONE);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 46));
        rlp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        btns.addView(restartBtn, rlp);
        card.addView(btns);
        return card;
    }

    /** 「进入桌面」下方的一行小入口：触控板模式 / 灵敏度 / 拖拽与右键阈值。 */
    private View buildTouchEntry() {
        LinearLayout row = Ui.row(this);
        row.setBackground(Ui.rippled(this, Ui.color(Ui.CARD), 14));
        row.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        row.setClickable(true);
        row.setFocusable(true);

        TextView icon = Ui.text(this, "👆", 15, Ui.color(Ui.TEXT), false);
        Ui.margins(this, icon, 0, 0, 10, 0);
        row.addView(icon);

        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "触控设置", 12.5f, Ui.color(Ui.TEXT), true));
        TextView cap = Ui.text(this, "触控板模式 · 灵敏度 · 拖拽/右键阈值",
                10.5f, Ui.color(Ui.TEXT_3));
        Ui.margins(this, cap, 0, 1, 0, 0);
        col.addView(cap);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = Ui.text(this, "›", 18, Ui.color(Ui.TEXT_2), false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow);

        row.setOnClickListener(v -> openTouchSettings());
        return row;
    }

    private void openTouchSettings() {
        try {
            Intent i = new Intent(this, SettingsActivity.class);
            i.putExtra(SettingsActivity.EXTRA_FOCUS, SettingsActivity.FOCUS_TOUCH);
            startActivity(i);
        } catch (Throwable t) {
            showError("打开触控设置失败", String.valueOf(t));
        }
    }

    private LinearLayout buildSteamCard() {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.gradient(this, Ui.color("#173B7A"), Ui.color("#101B35"), 22));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 15), Ui.dp(this, 16), Ui.dp(this, 15));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(v -> openSteam());

        LinearLayout row = Ui.row(this);
        TextView logo = Ui.text(this, "S", 18, Ui.color("#FFFFFF"), true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.round(this, Ui.color(Ui.PRIMARY), 13));
        row.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        LinearLayout col = Ui.column(this);
        Ui.margins(this, col, 12, 0, 0, 0);
        col.addView(Ui.text(this, "Steam 快速启动", 15, Ui.color("#FFFFFF"), true));
        steamSub = Ui.text(this, "一键启动已添加的 Windows 游戏", 11, Ui.color("#A9BFE8"));
        Ui.margins(this, steamSub, 0, 3, 0, 0);
        col.addView(steamSub);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = Ui.text(this, "›", 24, Ui.color("#A9BFE8"), false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow);
        card.addView(row);
        return card;
    }

    private View buildProgramsHeader() {
        LinearLayout head = Ui.row(this);
        Ui.margins(this, head, 0, 0, 0, 10);
        head.addView(Ui.heading(this, "Windows 程序"));
        countLabel = Ui.text(this, "", 11, Ui.color(Ui.TEXT_3));
        Ui.margins(this, countLabel, 8, 0, 0, 0);
        head.addView(countLabel);
        head.addView(Ui.stretch(this));
        importBtn = Ui.smallButton(this, "+ 导入", true);
        importBtn.setOnClickListener(v -> showImportMenu());
        head.addView(importBtn);
        return head;
    }

    private LinearLayout buildImportBanner() {
        LinearLayout banner = Ui.row(this);
        banner.setBackground(Ui.round(this, Ui.color(Ui.PRIMARY_SOFT), 16));
        banner.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        banner.setVisibility(View.GONE);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        bar.setIndeterminateTintList(ColorStateList.valueOf(Ui.color(Ui.PRIMARY_DIM)));
        banner.addView(bar, new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22)));

        TextView t = Ui.text(this, "正在导入，复制大文件可能需要几分钟…", 12, Ui.color("#C7D5F5"));
        Ui.margins(this, t, 10, 0, 0, 0);
        banner.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return banner;
    }

    // ------------------------------------------------------------- 程序库渲染

    private void renderPrograms() {
        programArea.removeAllViews();
        if (store == null) return;

        List<Game> games = new ArrayList<>(store.all());
        games.sort((a, b) -> Long.compare(b.lastPlayed, a.lastPlayed));
        countLabel.setText(games.isEmpty() ? "" : games.size() + " 个");

        if (games.isEmpty()) {
            programArea.addView(buildEmptyState());
            return;
        }

        int i = 0;
        while (i < games.size()) {
            LinearLayout row = Ui.row(this);
            row.setGravity(Gravity.TOP);
            for (int col = 0; col < 3; col++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), Ui.dp(this, 10));
                if (i < games.size()) {
                    row.addView(buildGameCard(games.get(i)), lp);
                    i++;
                } else {
                    row.addView(new View(this), lp);
                }
            }
            programArea.addView(row);
        }
    }

    private View buildEmptyState() {
        LinearLayout card = Ui.card(this, 20, Ui.color(Ui.CARD), 18);
        card.setGravity(Gravity.CENTER);
        Ui.margins(this, card, 0, 2, 0, 0);

        TextView icon = Ui.text(this, "📥", 30, Ui.color("#FFFFFF"), false);
        icon.setGravity(Gravity.CENTER);
        card.addView(icon);

        TextView title = Ui.text(this, "还没有 Windows 程序", 14, Ui.color(Ui.TEXT), true);
        title.setGravity(Gravity.CENTER);
        Ui.margins(this, title, 0, 8, 0, 0);
        card.addView(title);

        TextView desc = Ui.text(this,
                "导入 .exe 或整个游戏文件夹，就能在这里一键启动，不用每次钻进桌面翻文件。",
                12, Ui.color(Ui.TEXT_2));
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(Ui.dp(this, 3), 1f);
        Ui.margins(this, desc, 8, 6, 8, 14);
        card.addView(desc);

        LinearLayout btns = Ui.row(this);
        btns.setGravity(Gravity.CENTER);
        TextView importExe = Ui.smallButton(this, "导入 exe", true);
        importExe.setOnClickListener(v -> {
            startActivityForResult(pickFileIntent(), REQ_IMPORT_FILE);
        });
        btns.addView(importExe);
        TextView importDir = Ui.smallButton(this, "导入文件夹", false);
        Ui.margins(this, importDir, 10, 0, 0, 0);
        importDir.setOnClickListener(v -> {
            startActivityForResult(pickFolderIntent(), REQ_IMPORT_TREE);
        });
        btns.addView(importDir);
        card.addView(btns);
        return card;
    }

    private View buildGameCard(Game g) {
        LinearLayout card = Ui.column(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(Ui.rippled(this, Ui.color(Ui.CARD), 16));
        card.setPadding(Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 9));
        card.setClickable(true);
        card.setFocusable(true);

        int accent = Ui.color(ICON_COLORS[Math.abs(g.name == null ? 0 : g.name.hashCode()) % ICON_COLORS.length]);
        TextView icon = Ui.text(this, firstChar(g.name), 19, accent, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(Ui.round(this, withAlpha(accent, 0x30), 13));
        card.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));

        TextView name = Ui.text(this, g.name == null ? "未命名" : g.name, 12, Ui.color(Ui.TEXT), true);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setMinLines(2);
        name.setLineSpacing(Ui.dp(this, 1), 1f);
        Ui.margins(this, name, 0, 7, 0, 0);
        card.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean missing = g.exePath != null && g.exePath.startsWith("/") && !new File(g.exePath).exists();
        String meta;
        if (missing) meta = "文件缺失";
        else if (g.lastPlayed > 0) meta = "最近启动";
        else meta = GameImporter.formatSize(g.size);
        TextView metaView = Ui.text(this, meta, 10,
                missing ? Ui.color(Ui.AMBER) : Ui.color(Ui.TEXT_3));
        metaView.setGravity(Gravity.CENTER);
        metaView.setSingleLine(true);
        Ui.margins(this, metaView, 0, 3, 0, 0);
        card.addView(metaView);

        card.setOnClickListener(v -> launch(g));
        card.setOnLongClickListener(v -> {
            showGameMenu(g);
            return true;
        });
        return card;
    }

    private View buildLoadingCard() {
        LinearLayout card = Ui.card(this, 18, Ui.color(Ui.CARD), 18);
        card.setGravity(Gravity.CENTER);
        ProgressBar bar = new ProgressBar(this);
        bar.setIndeterminateTintList(ColorStateList.valueOf(Ui.color(Ui.PRIMARY)));
        card.addView(bar, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
        TextView t = Ui.text(this, "正在读取程序库…", 12, Ui.color(Ui.TEXT_2));
        Ui.margins(this, t, 0, 8, 0, 0);
        card.addView(t);
        return card;
    }

    // ----------------------------------------------------------------- 状态

    private void refreshStatus() {
        if (statusRunning) return;
        statusRunning = true;
        if (statusPill != null) {
            statusPill.setText("检测中…");
            statusPill.setTextColor(Ui.color(Ui.TEXT_2));
        }
        if (statusDot != null) {
            statusDot.setBackground(Ui.circle(this, Ui.color(Ui.TEXT_3)));
        }
        new Thread(() -> {
            final DesktopStatus.Info info = DesktopStatus.detect(this);
            runOnUiThread(() -> {
                statusRunning = false;
                if (isFinishing() || isDestroyed()) return;
                applyStatus(info);
            });
        }).start();
    }

    private void applyStatus(DesktopStatus.Info info) {
        resValue.setText(info.width + " × " + info.height);
        String mode = info.autoRes ? "自动" : "自定义";
        resCaption.setText(mode + "分辨率 · " + info.budgetName + " · " + info.rendererName);

        switch (info.state) {
            case RUNNING:
                statusDot.setBackground(Ui.circle(this, Ui.color(Ui.GREEN)));
                statusPill.setText("运行中");
                statusPill.setTextColor(Ui.color(Ui.GREEN));
                statusPill.setBackground(Ui.round(this, withAlpha(Ui.color(Ui.GREEN), 0x28), 20));
                statusSub.setText("桌面正在运行，可以从最近任务切回；点下方按钮重新连接画面。");
                enterBtn.setText("继续桌面");
                restartBtn.setVisibility(View.VISIBLE);
                break;
            case READY:
                statusDot.setBackground(Ui.circle(this, Ui.color(Ui.PRIMARY_DIM)));
                statusPill.setText("已就绪");
                statusPill.setTextColor(Ui.color("#9CC0FF"));
                statusPill.setBackground(Ui.round(this, withAlpha(Ui.color(Ui.PRIMARY), 0x33), 20));
                statusSub.setText("环境已就绪，点击进入 XFCE 桌面（首次连接约 10 秒）。");
                enterBtn.setText("进入桌面");
                restartBtn.setVisibility(View.GONE);
                break;
            default:
                statusDot.setBackground(Ui.circle(this, Ui.color(Ui.AMBER)));
                statusPill.setText("首次准备");
                statusPill.setTextColor(Ui.color(Ui.AMBER));
                statusPill.setBackground(Ui.round(this, withAlpha(Ui.color(Ui.AMBER), 0x28), 20));
                statusSub.setText("还没安装桌面环境：首次进入会自动下载安装（约 5–10 分钟），请保持前台并接上电源。");
                enterBtn.setText("开始准备");
                restartBtn.setVisibility(View.GONE);
                break;
        }
    }

    private void refreshSteamCount() {
        if (steamSub == null) return;
        int n = steamStore == null ? 0 : steamStore.size();
        steamSub.setText(n == 0
                ? "添加 Windows 游戏，一键从桌面启动"
                : "已添加 " + n + " 个游戏 · 点击进入大屏库");
    }

    // ----------------------------------------------------------------- 动作

    private void startDesktop(boolean restart) {
        try {
            Intent i = new Intent(this, MainActivity.class);
            if (restart) i.putExtra(MainActivity.EXTRA_RESTART, true);
            startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "start desktop fail", t);
            showError("启动桌面失败", t.getMessage() == null ? String.valueOf(t) : t.getMessage());
        }
    }

    private void openSettings() {
        try {
            startActivity(new Intent(this, SettingsActivity.class));
        } catch (Throwable t) {
            showError("打开设置失败", String.valueOf(t));
        }
    }

    private void openSteam() {
        try {
            startActivity(new Intent(this, SteamActivity.class));
        } catch (Throwable t) {
            Log.e(TAG, "open steam fail", t);
            showError("Steam 页暂不可用",
                    "请确认 AndroidManifest.xml 已注册 .ui.SteamActivity\n" + t.getMessage());
        }
    }

    private void launch(Game g) {
        if (g.exePath == null || g.exePath.isEmpty()) {
            showError("无法启动", "这条记录没有可执行文件路径");
            return;
        }
        boolean builtin = !g.exePath.startsWith("/");
        if (!builtin && !new File(g.exePath).exists()) {
            new AlertDialog.Builder(this)
                    .setTitle("找不到程序文件")
                    .setMessage("文件可能已被删除或移动：\n" + g.exePath)
                    .setPositiveButton("删除这条记录", (d, w) -> removeGame(g, false))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        g.lastPlayed = System.currentTimeMillis();
        store.update(g);
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra(MainActivity.EXTRA_EXE, g.exePath);
            i.putExtra(MainActivity.EXTRA_WORKDIR, g.workDir);
            startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "launch fail", t);
            showError("启动失败", t.getMessage() == null ? String.valueOf(t) : t.getMessage());
        }
    }

    private void showGameMenu(Game g) {
        String[] items = {"启动", "详细信息", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(g.name == null ? "程序" : g.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) launch(g);
                    else if (which == 1) showGameInfo(g);
                    else confirmDelete(g);
                })
                .show();
    }

    private void showGameInfo(Game g) {
        String msg = "名称：" + g.name
                + "\n程序：" + g.exePath
                + "\n目录：" + g.workDir
                + "\n大小：" + (g.size > 0 ? GameImporter.formatSize(g.size) : "未知")
                + "\n最近启动：" + (g.lastPlayed > 0 ? formatTime(g.lastPlayed) : "从未");
        new AlertDialog.Builder(this)
                .setTitle("详细信息")
                .setMessage(msg)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void confirmDelete(Game g) {
        new AlertDialog.Builder(this)
                .setTitle("删除程序")
                .setMessage("将删除「" + g.name + "」的记录和导入的文件（仅限 App 私有目录）。")
                .setPositiveButton("删除", (d, w) -> removeGame(g, true))
                .setNegativeButton("取消", null)
                .show();
    }

    private void removeGame(Game g, boolean deleteFiles) {
        store.remove(g.id);
        if (deleteFiles && g.exePath != null && g.exePath.startsWith("/")) {
            File gamesRoot = new File(getFilesDir(), "games");
            File exe = new File(g.exePath);
            File dir = GameImporter.topImportDir(gamesRoot, exe);
            if (dir != null) {
                GameImporter.deleteTree(dir);
            } else if (GameImporter.isInside(gamesRoot, exe)) {
                //noinspection ResultOfMethodCallIgnored
                exe.delete();
            }
        }
        renderPrograms();
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
    }

    // ----------------------------------------------------------------- 导入

    private Intent pickFileIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        return i;
    }

    private Intent pickFolderIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    }

    private void showImportMenu() {
        String[] items = {"导入 exe 文件", "导入游戏文件夹（自动查找 exe）"};
        new AlertDialog.Builder(this)
                .setTitle("导入 Windows 程序")
                .setItems(items, (d, which) -> {
                    try {
                        startActivityForResult(which == 0 ? pickFileIntent() : pickFolderIntent(),
                                which == 0 ? REQ_IMPORT_FILE : REQ_IMPORT_TREE);
                    } catch (Throwable t) {
                        showError("无法打开文件选择器", String.valueOf(t));
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        if (requestCode != REQ_IMPORT_FILE && requestCode != REQ_IMPORT_TREE) return;
        if (importing) {
            Toast.makeText(this, "正在导入，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean tree = requestCode == REQ_IMPORT_TREE;
        setImporting(true);
        new Thread(() -> {
            try {
                File gamesRoot = new File(getFilesDir(), "games");
                if (!gamesRoot.exists() && !gamesRoot.mkdirs()) {
                    throw new java.io.IOException("无法创建游戏目录");
                }
                GameImporter.Result r = tree
                        ? GameImporter.importTree(this, uri, gamesRoot)
                        : GameImporter.importFile(this, uri, gamesRoot);
                Game g = new Game(String.valueOf(System.currentTimeMillis()), r.name, r.exePath, r.workDir);
                g.size = r.size;
                runOnUiThread(() -> {
                    setImporting(false);
                    store.add(g);
                    renderPrograms();
                    String tip = "已导入：" + g.name
                            + (r.failedFiles > 0 ? "（" + r.failedFiles + " 个文件复制失败）" : "");
                    Toast.makeText(this, tip, Toast.LENGTH_LONG).show();
                });
            } catch (Throwable t) {
                Log.e(TAG, "import fail", t);
                final String msg = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                runOnUiThread(() -> {
                    setImporting(false);
                    showError("导入失败", msg);
                });
            }
        }).start();
    }

    private void setImporting(boolean on) {
        importing = on;
        if (importBanner != null) importBanner.setVisibility(on ? View.VISIBLE : View.GONE);
        if (importBtn != null) {
            importBtn.setEnabled(!on);
            importBtn.setAlpha(on ? 0.5f : 1f);
        }
    }

    // ----------------------------------------------------------------- 工具

    private void showError(String title, String message) {
        try {
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("知道了", null)
                    .show();
        } catch (Throwable t) {
            Toast.makeText(this, title + "：" + message, Toast.LENGTH_LONG).show();
        }
    }

    private String formatTime(long ts) {
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60_000L) return "刚刚";
        if (diff < 3600_000L) return (diff / 60_000L) + " 分钟前";
        if (diff < 86400_000L) return (diff / 3600_000L) + " 小时前";
        return (diff / 86400_000L) + " 天前";
    }

    private String firstChar(String name) {
        if (name == null || name.isEmpty()) return "?";
        return name.substring(0, 1).toUpperCase();
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
