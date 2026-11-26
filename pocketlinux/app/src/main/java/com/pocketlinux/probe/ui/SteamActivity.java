package com.pocketlinux.probe.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.pocketlinux.probe.MainActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Steam 快速启动页：Steam 本体状态 + 已添加游戏的快捷启动。
 * 启动游戏仍走 MainActivity 的 EXTRA_EXE / EXTRA_WORKDIR 合同。
 */
public class SteamActivity extends Activity {
    private static final String TAG = "PLPROBE";
    private static final int REQ_FILE = 2001;
    private static final int REQ_TREE = 2002;

    private static final String[] ICON_COLORS = {
            "#4A90FF", "#8C5CFF", "#00B39A", "#F07A3C", "#E0559A", "#4CAF50"
    };

    private SteamStore store;
    private File detectedSteamExe;

    private TextView steamPill;
    private TextView steamDesc;
    private TextView steamAction;
    private TextView steamSubAction;
    private TextView topSub;
    private TextView countLabel;
    private LinearLayout listArea;
    private boolean importing;

    private AlertDialog gameDialog;
    private EditText nameEdit, pathEdit, appIdEdit;
    private SteamGame editing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        Ui.applyTheme(this);
        store = new SteamStore(this);
        setContentView(buildRoot());
        refreshSteamDetect();
        renderList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSteamDetect();
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

        content.addView(buildSteamCard());
        content.addView(buildListHeader());

        listArea = new LinearLayout(this);
        listArea.setOrientation(LinearLayout.VERTICAL);
        content.addView(listArea);

        TextView hint1 = Ui.caption(this,
                "快捷方式模式：添加游戏 exe 后，可从手机一键启动，不必先进入桌面。");
        hint1.setLineSpacing(Ui.dp(this, 3), 1f);
        Ui.margins(this, hint1, 0, 16, 0, 0);
        content.addView(hint1);

        TextView hint2 = Ui.caption(this,
                "路径可点「从文件选择」导入，或填写容器内路径（如 /home/…）；"
                        + "C:\\ 路径会自动映射到 Wine 默认前缀。\n"
                        + "Steam 账号库同步 / appid 启动需要后端支持 steam -applaunch，当前未接入。");
        hint2.setLineSpacing(Ui.dp(this, 3), 1f);
        Ui.margins(this, hint2, 0, 6, 0, 0);
        content.addView(hint2);

        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 8));

        TextView back = Ui.text(this, "‹", 30, Ui.color(Ui.TEXT), false);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, 0, Ui.dp(this, 10), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, "Steam", 20, Ui.color(Ui.TEXT), true));
        topSub = Ui.text(this, "大屏游戏库 · 一键启动", 10.5f, Ui.color(Ui.TEXT_3));
        col.addView(topSub);
        bar.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView add = Ui.iconButton(this, "＋");
        add.setTextColor(Color.WHITE);
        add.setBackground(Ui.primaryBg(this));
        add.setOnClickListener(v -> showGameDialog(null));
        bar.addView(add);
        return bar;
    }

    private View buildSteamCard() {
        LinearLayout card = Ui.column(this);
        card.setBackground(Ui.gradient(this, Ui.color("#0E2A4D"), Ui.color("#0F172A"), 22));
        card.setPadding(Ui.dp(this, 16), Ui.dp(this, 15), Ui.dp(this, 16), Ui.dp(this, 15));
        Ui.margins(this, card, 0, 2, 0, 18);

        LinearLayout row = Ui.row(this);
        TextView logo = Ui.text(this, "S", 18, Color.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.round(this, Ui.color("#1B4B8F"), 13));
        row.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        LinearLayout col = Ui.column(this);
        Ui.margins(this, col, 12, 0, 0, 0);
        col.addView(Ui.text(this, "Steam 本体", 15, Color.WHITE, true));
        steamDesc = Ui.text(this, "正在检测…", 11, Ui.color("#A9BFE8"));
        Ui.margins(this, steamDesc, 0, 3, 0, 0);
        col.addView(steamDesc);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        steamPill = Ui.pill(this, "检测中", Ui.color(Ui.TEXT_2), Ui.color("#26314D"));
        row.addView(steamPill);
        card.addView(row);

        LinearLayout btns = Ui.row(this);
        Ui.margins(this, btns, 0, 12, 0, 0);
        steamAction = Ui.button(this, "去桌面安装 Steam", true, 44);
        steamAction.setOnClickListener(v -> {
            if (detectedSteamExe != null) launchSteam();
            else startDesktop();
        });
        btns.addView(steamAction, new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));

        steamSubAction = Ui.button(this, "重新检测", false, 44);
        steamSubAction.setOnClickListener(v -> {
            refreshSteamDetect();
            Toast.makeText(this, detectedSteamExe != null ? "检测到 Steam 本体" : "没找到 Steam 本体",
                    Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 44));
        slp.setMargins(Ui.dp(this, 10), 0, 0, 0);
        btns.addView(steamSubAction, slp);
        card.addView(btns);

        TextView note = Ui.text(this,
                "Steam 需要先在 Linux 桌面里登录一次；登录后可从本页快速启动 Steam 或直接启动已添加的游戏。",
                10.5f, Ui.color("#8FA6CC"));
        note.setLineSpacing(Ui.dp(this, 2), 1f);
        Ui.margins(this, note, 0, 10, 0, 0);
        card.addView(note);
        return card;
    }

    private View buildListHeader() {
        LinearLayout head = Ui.row(this);
        Ui.margins(this, head, 0, 0, 0, 10);
        head.addView(Ui.heading(this, "我的 Steam 游戏"));
        countLabel = Ui.text(this, "", 11, Ui.color(Ui.TEXT_3));
        Ui.margins(this, countLabel, 8, 0, 0, 0);
        head.addView(countLabel);
        head.addView(Ui.stretch(this));
        TextView add = Ui.smallButton(this, "+ 添加", true);
        add.setOnClickListener(v -> showGameDialog(null));
        head.addView(add);
        return head;
    }

    // ------------------------------------------------------------------ 列表

    private void renderList() {
        listArea.removeAllViews();
        List<SteamGame> games = new ArrayList<>(store.all());
        games.sort((a, b) -> Long.compare(b.lastPlayed, a.lastPlayed));
        countLabel.setText(games.isEmpty() ? "" : games.size() + " 个");
        if (topSub != null) {
            topSub.setText(games.isEmpty() ? "大屏游戏库 · 一键启动" : "大屏游戏库 · " + games.size() + " 个快捷方式");
        }
        if (games.isEmpty()) {
            listArea.addView(buildEmptyState());
            return;
        }
        for (SteamGame g : games) {
            View row = buildGameRow(g);
            Ui.margins(this, row, 0, 0, 0, 10);
            listArea.addView(row);
        }
    }

    private View buildEmptyState() {
        LinearLayout card = Ui.card(this, 20, Ui.color(Ui.CARD), 18);
        card.setGravity(Gravity.CENTER);
        card.addView(Ui.text(this, "🎮", 30, Color.WHITE, false));

        TextView title = Ui.text(this, "还没有 Steam 游戏快捷方式", 14, Ui.color(Ui.TEXT), true);
        title.setGravity(Gravity.CENTER);
        Ui.margins(this, title, 0, 8, 0, 0);
        card.addView(title);

        TextView desc = Ui.text(this,
                "把游戏的可执行文件加进来，就能从手机桌面一键启动；"
                        + "也可以先点上方「去桌面安装 Steam」，在桌面里装好游戏再来添加。",
                12, Ui.color(Ui.TEXT_2));
        desc.setGravity(Gravity.CENTER);
        desc.setLineSpacing(Ui.dp(this, 3), 1f);
        Ui.margins(this, desc, 6, 6, 6, 14);
        card.addView(desc);

        LinearLayout btns = Ui.row(this);
        btns.setGravity(Gravity.CENTER);
        TextView add = Ui.smallButton(this, "添加游戏", true);
        add.setOnClickListener(v -> showGameDialog(null));
        btns.addView(add);
        TextView desktop = Ui.smallButton(this, "打开 Linux 桌面", false);
        Ui.margins(this, desktop, 10, 0, 0, 0);
        desktop.setOnClickListener(v -> startDesktop());
        btns.addView(desktop);
        card.addView(btns);
        return card;
    }

    private View buildGameRow(SteamGame g) {
        LinearLayout card = Ui.row(this);
        card.setBackground(Ui.rippled(this, Ui.color(Ui.CARD), 16));
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        card.setClickable(true);
        card.setFocusable(true);

        int accent = Ui.color(ICON_COLORS[Math.abs(g.name == null ? 0 : g.name.hashCode()) % ICON_COLORS.length]);
        TextView icon = Ui.text(this, firstChar(g.name), 17, accent, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(Ui.round(this, withAlpha(accent, 0x30), 12));
        card.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));

        LinearLayout col = Ui.column(this);
        Ui.margins(this, col, 12, 0, 10, 0);
        TextView name = Ui.text(this, g.name == null ? "未命名" : g.name, 14, Ui.color(Ui.TEXT), true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);

        String exe = normalizeExePath(g.exePath);
        File host = exe == null ? null : resolveHostFile(exe);
        boolean missing = host != null && !host.exists();
        String meta;
        if (missing) {
            meta = "文件缺失 · 请长按编辑路径";
        } else if (g.appId != null && !g.appId.isEmpty()) {
            meta = "AppID " + g.appId + " · " + fileName(exe);
        } else {
            meta = fileName(exe);
        }
        TextView metaView = Ui.text(this, meta, 10.5f,
                missing ? Ui.color(Ui.AMBER) : Ui.color(Ui.TEXT_3));
        metaView.setSingleLine(true);
        metaView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        Ui.margins(this, metaView, 0, 3, 0, 0);
        col.addView(metaView);
        card.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView start = Ui.smallButton(this, "启动", true);
        start.setOnClickListener(v -> launchGame(g));
        card.addView(start);

        card.setOnClickListener(v -> launchGame(g));
        card.setOnLongClickListener(v -> {
            showGameMenu(g);
            return true;
        });
        return card;
    }

    // ------------------------------------------------------------------ Steam 本体

    private void refreshSteamDetect() {
        detectedSteamExe = findSteamExe();
        if (steamPill == null) return;
        if (detectedSteamExe != null) {
            steamPill.setText("已安装");
            steamPill.setTextColor(Ui.color(Ui.GREEN));
            steamPill.setBackground(Ui.round(this, withAlpha(Ui.color(Ui.GREEN), 0x28), 20));
            steamDesc.setText("检测到本体：" + detectedSteamExe.getName() + "（" + parentName(detectedSteamExe) + "）");
            steamAction.setText("启动 Steam");
        } else {
            steamPill.setText("未安装");
            steamPill.setTextColor(Ui.color(Ui.AMBER));
            steamPill.setBackground(Ui.round(this, withAlpha(Ui.color(Ui.AMBER), 0x28), 20));
            steamDesc.setText("没在 Wine 默认前缀里找到 steam.exe");
            steamAction.setText("去桌面安装 Steam");
        }
    }

    private File findSteamExe() {
        File home = new File(getFilesDir(), "home");
        File wine = new File(home, ".wine/drive_c");
        File[] direct = {
                new File(wine, "Program Files (x86)/Steam/steam.exe"),
                new File(wine, "Program Files/Steam/steam.exe"),
                new File(wine, "Steam/steam.exe")
        };
        for (File f : direct) {
            if (f.isFile()) return f;
        }
        // 再浅扫一层 Program Files（有些安装器会放进子目录）
        File[] roots = {new File(wine, "Program Files (x86)"), new File(wine, "Program Files")};
        for (File root : roots) {
            File hit = findNamed(root, "steam.exe", 3);
            if (hit != null) return hit;
        }
        return null;
    }

    private File findNamed(File dir, String lowerName, int depth) {
        if (depth < 0 || dir == null || !dir.isDirectory()) return null;
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isFile() && f.getName().toLowerCase().equals(lowerName)) return f;
        }
        for (File f : fs) {
            if (f.isDirectory()) {
                File hit = findNamed(f, lowerName, depth - 1);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private void launchSteam() {
        if (detectedSteamExe == null) {
            startDesktop();
            return;
        }
        String guest = hostToGuest(detectedSteamExe);
        startWine(guest, null);
    }

    // ------------------------------------------------------------------ 启动

    private void launchGame(SteamGame g) {
        String exe = normalizeExePath(g.exePath);
        if (exe == null || exe.isEmpty()) {
            showError("无法启动", "这条快捷方式没有程序路径，请长按编辑。");
            return;
        }
        File host = resolveHostFile(exe);
        if (host != null && !host.exists()) {
            new AlertDialog.Builder(this)
                    .setTitle("找不到游戏文件")
                    .setMessage("路径可能已失效：\n" + exe
                            + "\n\n如果游戏还在，请长按这条快捷方式编辑路径；从文件导入的副本可能已被清理。")
                    .setPositiveButton("编辑路径", (d, w) -> showGameDialog(g))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        String workDir = null;
        File gamesRoot = new File(getFilesDir(), "games");
        File exeFile = new File(exe);
        if (GameImporter.isInside(gamesRoot, exeFile)) {
            File parent = exeFile.getParentFile();
            if (parent != null) workDir = parent.getAbsolutePath();
        }
        g.lastPlayed = System.currentTimeMillis();
        g.exePath = exe;
        store.update(g);
        renderList();
        startWine(exe, workDir);
    }

    private void startWine(String exe, String workDir) {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.putExtra(MainActivity.EXTRA_EXE, exe);
            if (workDir != null) i.putExtra(MainActivity.EXTRA_WORKDIR, workDir);
            startActivity(i);
        } catch (Throwable t) {
            Log.e(TAG, "start wine fail", t);
            showError("启动失败", t.getMessage() == null ? String.valueOf(t) : t.getMessage());
        }
    }

    private void startDesktop() {
        try {
            startActivity(new Intent(this, MainActivity.class));
        } catch (Throwable t) {
            showError("打开桌面失败", String.valueOf(t));
        }
    }

    // ------------------------------------------------------------------ 添加 / 编辑

    private void showGameMenu(SteamGame g) {
        String[] items = {"启动", "编辑", "删除"};
        new AlertDialog.Builder(this)
                .setTitle(g.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) launchGame(g);
                    else if (which == 1) showGameDialog(g);
                    else confirmDelete(g);
                })
                .show();
    }

    private void confirmDelete(SteamGame g) {
        boolean imported = isImportedCopy(g);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("删除快捷方式")
                .setMessage("「" + g.name + "」将从列表里移除。"
                        + (imported ? "\n\n这个条目是通过文件导入的，也可以同时删除 App 里的副本。" : ""))
                .setNegativeButton("取消", null);
        if (imported) {
            b.setPositiveButton("连文件一起删", (d, w) -> deleteGame(g, true));
            b.setNeutralButton("只删记录", (d, w) -> deleteGame(g, false));
        } else {
            b.setPositiveButton("删除", (d, w) -> deleteGame(g, false));
        }
        b.show();
    }

    private boolean isImportedCopy(SteamGame g) {
        String exe = normalizeExePath(g.exePath);
        if (exe == null) return false;
        return GameImporter.isInside(new File(getFilesDir(), "games"), new File(exe));
    }

    private void deleteGame(SteamGame g, boolean deleteFiles) {
        store.remove(g.id);
        if (deleteFiles) {
            File gamesRoot = new File(getFilesDir(), "games");
            String exe = normalizeExePath(g.exePath);
            if (exe != null) {
                File exeFile = new File(exe);
                File dir = GameImporter.topImportDir(gamesRoot, exeFile);
                if (dir != null) {
                    GameImporter.deleteTree(dir);
                } else if (GameImporter.isInside(gamesRoot, exeFile)) {
                    //noinspection ResultOfMethodCallIgnored
                    exeFile.delete();
                }
            }
        }
        renderList();
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
    }

    private void showGameDialog(SteamGame game) {
        editing = game;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(Ui.dp(this, 20), Ui.dp(this, 8), Ui.dp(this, 20), Ui.dp(this, 4));

        form.addView(fieldLabel("游戏名称"));
        nameEdit = new EditText(this);
        nameEdit.setHint("例如：只狼 / 星露谷物语");
        nameEdit.setText(game == null ? "" : (game.name == null ? "" : game.name));
        styleEdit(nameEdit, InputType.TYPE_CLASS_TEXT);
        form.addView(nameEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));

        form.addView(fieldLabel("程序路径（exe）"));
        pathEdit = new EditText(this);
        pathEdit.setHint("C:\\Games\\game.exe 或 /home/.wine/drive_c/…");
        pathEdit.setText(game == null ? "" : (game.exePath == null ? "" : game.exePath));
        styleEdit(pathEdit, InputType.TYPE_CLASS_TEXT);
        form.addView(pathEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));

        if (game == null) {
            TextView tip = Ui.text(this,
                    "不知道路径？先点下面「从文件选择」把 exe 复制进来，路径会自动填好。",
                    10.5f, Ui.color(Ui.TEXT_3));
            tip.setLineSpacing(Ui.dp(this, 2), 1f);
            Ui.margins(this, tip, 0, 6, 0, 8);
            form.addView(tip);
        }

        LinearLayout importRow = Ui.row(this);
        TextView pickFile = Ui.smallButton(this, "从文件选择", false);
        pickFile.setOnClickListener(v -> pickImport(false));
        importRow.addView(pickFile);
        TextView pickTree = Ui.smallButton(this, "从文件夹导入", false);
        Ui.margins(this, pickTree, 8, 0, 0, 0);
        pickTree.setOnClickListener(v -> pickImport(true));
        importRow.addView(pickTree);
        Ui.margins(this, importRow, 0, 4, 0, 8);
        form.addView(importRow);

        form.addView(fieldLabel("Steam AppID（可选）"));
        appIdEdit = new EditText(this);
        appIdEdit.setHint("例如 730，留给以后 appid 启动用");
        appIdEdit.setText(game == null || game.appId == null ? "" : game.appId);
        styleEdit(appIdEdit, InputType.TYPE_CLASS_NUMBER);
        form.addView(appIdEdit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)));

        gameDialog = new AlertDialog.Builder(this)
                .setTitle(game == null ? "添加 Steam 游戏" : "编辑 Steam 游戏")
                .setView(form)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", (d, w) -> editing = null)
                .create();
        gameDialog.setOnShowListener(d -> gameDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> commitDialog()));
        gameDialog.show();
    }

    private TextView fieldLabel(String text) {
        TextView t = Ui.text(this, text, 11, Ui.color(Ui.TEXT_3));
        Ui.margins(this, t, 0, 10, 0, 4);
        return t;
    }

    private void styleEdit(EditText e, int inputType) {
        e.setTextColor(Ui.color(Ui.TEXT));
        e.setHintTextColor(Ui.color(Ui.TEXT_3));
        e.setTextSize(13);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setBackground(Ui.round(this, Ui.color(Ui.CARD_2), 10));
        e.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
    }

    private void commitDialog() {
        String name = nameEdit.getText().toString().trim();
        String raw = pathEdit.getText().toString().trim();
        if (name.isEmpty()) {
            nameEdit.setError("请填写游戏名称");
            return;
        }
        String exe = normalizeExePath(raw);
        if (exe == null || exe.isEmpty()) {
            pathEdit.setError("请填写程序路径，或点「从文件选择」导入");
            return;
        }
        File host = resolveHostFile(exe);
        if (host != null && !host.exists()) {
            pathEdit.setError("找不到这个文件：" + exe);
            return;
        }
        String appId = appIdEdit.getText().toString().trim();
        File gamesRoot = new File(getFilesDir(), "games");
        File exeFile = new File(exe);
        String workDir = null;
        if (GameImporter.isInside(gamesRoot, exeFile)) {
            File parent = exeFile.getParentFile();
            if (parent != null) workDir = parent.getAbsolutePath();
        }
        if (editing != null) {
            editing.name = name;
            editing.exePath = exe;
            editing.appId = appId;
            if (workDir != null) editing.workDir = workDir;
            store.update(editing);
        } else {
            SteamGame g = new SteamGame(String.valueOf(System.currentTimeMillis()), name, exe);
            g.appId = appId;
            g.workDir = workDir;
            store.add(g);
        }
        editing = null;
        gameDialog.dismiss();
        renderList();
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    private void pickImport(boolean folder) {
        try {
            Intent i;
            if (folder) {
                i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            } else {
                i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
            }
            startActivityForResult(i, folder ? REQ_TREE : REQ_FILE);
        } catch (Throwable t) {
            Toast.makeText(this, "无法打开文件选择器：" + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        final Uri uri = data.getData();
        if (uri == null) return;
        if (requestCode != REQ_FILE && requestCode != REQ_TREE) return;
        if (importing) {
            Toast.makeText(this, "正在导入，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean tree = requestCode == REQ_TREE;
        importing = true;
        Toast.makeText(this, "正在导入到 App 私有目录…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                File gamesRoot = new File(getFilesDir(), "games");
                if (!gamesRoot.exists() && !gamesRoot.mkdirs()) {
                    throw new java.io.IOException("无法创建游戏目录");
                }
                GameImporter.Result r = tree
                        ? GameImporter.importTree(this, uri, gamesRoot)
                        : GameImporter.importFile(this, uri, gamesRoot);
                runOnUiThread(() -> {
                    importing = false;
                    if (gameDialog != null && gameDialog.isShowing() && nameEdit != null && pathEdit != null) {
                        if (nameEdit.getText().toString().trim().isEmpty()) nameEdit.setText(r.name);
                        pathEdit.setText(r.exePath);
                        String tip = "已导入，点「保存」加入列表"
                                + (r.failedFiles > 0 ? "（" + r.failedFiles + " 个文件复制失败）" : "");
                        Toast.makeText(this, tip, Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "已导入：" + r.name, Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Throwable t) {
                Log.e(TAG, "steam import fail", t);
                final String msg = t.getMessage() == null ? String.valueOf(t) : t.getMessage();
                runOnUiThread(() -> {
                    importing = false;
                    showError("导入失败", msg);
                });
            }
        }).start();
    }

    // ------------------------------------------------------------------ 工具

    /** C:\\xxx → /home/.wine/drive_c/xxx；反斜杠统一成 /；其它原样。 */
    public static String normalizeExePath(String raw) {
        if (raw == null) return null;
        String p = raw.trim();
        if (p.isEmpty()) return null;
        if (p.length() >= 2 && p.charAt(1) == ':' && Character.isLetter(p.charAt(0))) {
            String rest = p.substring(2).replace('\\', '/');
            while (rest.startsWith("/")) rest = rest.substring(1);
            return "/home/.wine/drive_c/" + rest;
        }
        return p.replace('\\', '/');
    }

    /** 把 MainActivity 合同里的路径解析成 App 进程可见的 File；解析不了返回 null。 */
    private File resolveHostFile(String exe) {
        try {
            if (exe.startsWith("/home/") || exe.equals("/home")) {
                File home = new File(getFilesDir(), "home");
                String rel = exe.length() > 6 ? exe.substring(6) : "";
                return new File(home, rel);
            }
            String filesDir = getFilesDir().getAbsolutePath();
            if (exe.startsWith(filesDir + "/")) return new File(exe);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** host 上的文件 → 容器内路径（/home 绑定）。 */
    private String hostToGuest(File host) {
        try {
            File home = new File(getFilesDir(), "home");
            String hp = host.getCanonicalPath();
            String hd = home.getCanonicalPath();
            if (hp.startsWith(hd + File.separator)) {
                return "/home/" + hp.substring(hd.length() + 1).replace(File.separatorChar, '/');
            }
        } catch (Throwable ignored) {
        }
        return host.getAbsolutePath();
    }

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

    private String fileName(String path) {
        if (path == null) return "";
        int i = path.lastIndexOf('/');
        return i >= 0 ? path.substring(i + 1) : path;
    }

    private String parentName(File f) {
        File p = f.getParentFile();
        return p == null ? "" : p.getName();
    }

    private String firstChar(String name) {
        if (name == null || name.isEmpty()) return "?";
        return name.substring(0, 1).toUpperCase();
    }

    private int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }
}
