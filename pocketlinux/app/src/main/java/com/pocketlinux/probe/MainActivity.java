package com.pocketlinux.probe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * PocketLinux M1/M2：内置 rootfs + proot，启动 XFCE 并通过内置 noVNC 全屏显示；
 * 支持从外部传入要运行的 Windows exe（由 GameLibraryActivity 调用）。
 */
public class MainActivity extends Activity {
    private static final String TAG = "PLPROBE";

    public static final String EXTRA_EXE = "exe_path";
    public static final String EXTRA_WORKDIR = "exe_workdir";
    public static final String EXTRA_RESTART = "restart_desktop";
    /** 每次改前端就加一，强制 WebView 重新拉取，避免跑缓存的旧页面。 */
    private static final String WEB_VER = "pl9";

    private TextView log;
    private CaptureWebView web;
    private EditText kbdInput;
    private LinearLayout overlay;
    private View toggle;
    private File nativeDir;
    private File rootfsDir;
    // 桌面进程是进程级状态，不能随 Activity 实例重建而丢失（否则会被当成残留进程杀掉）
    private static Process desktopProcess;
    private static volatile boolean desktopReady;
    private static final Object DESK_LOCK = new Object();
    private int fbW = 1280, fbH = 800;
    private static int desktopFbW = -1, desktopFbH = -1;
    private Settings settings;

    // ===== 输入外设（物理键盘 / 手柄）映射 =====
    private InputMap inputMap;
    private final Map<Integer, Integer> hatState = new HashMap<>();   // hat 轴当前方向（-1/0/1）
    private float stickX = 0f, stickY = 0f;                           // 最近一次发给 JS 的摇杆值
    private long lastStickSend = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settings = Settings.load(this);
        nativeDir = new File(getFilesDir(), "native");
        rootfsDir = new File(getFilesDir(), "rootfs");
        computeFbSize();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // 全屏 WebView（桌面画面）
        web = new CaptureWebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        web.setBackgroundColor(Color.BLACK);
        web.setLongClickable(false);
        web.setHapticFeedbackEnabled(false);
        web.setOnLongClickListener(v -> true);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest req) {
                String url = req.getUrl().toString();
                String prefix = "http://appassets.local/";
                if (url.startsWith(prefix)) {
                    String path = url.substring(prefix.length());
                    int q = path.indexOf('?');
                    if (q >= 0) path = path.substring(0, q);
                    if (path.isEmpty() || path.endsWith("/")) path = path + "index.html";
                    try {
                        java.io.InputStream is = getAssets().open("novnc/" + path);
                        String mime = path.endsWith(".js") ? "application/javascript"
                                : path.endsWith(".html") ? "text/html"
                                : path.endsWith(".css") ? "text/css" : "application/octet-stream";
                        android.webkit.WebResourceResponse resp =
                                new android.webkit.WebResourceResponse(mime, "UTF-8", is);
                        java.util.Map<String, String> h = new java.util.HashMap<>();
                        h.put("Cache-Control", "no-store, no-cache, must-revalidate");
                        h.put("Pragma", "no-cache");
                        resp.setResponseHeaders(h);
                        return resp;
                    } catch (Exception e) {
                        return null;
                    }
                }
                return null;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript("window.PL_VER||'none'",
                        value -> Log.i(TAG, "jsver=" + value));
                // 把应用内触控设置注入页面
                Settings st = Settings.load(MainActivity.this);
                String js = "try{"
                        + "if(window.plSetTouchMode)plSetTouchMode('" + st.getTouchMode() + "');"
                        + "if(window.plSetTrackpadSens)plSetTrackpadSens(" + st.getTrackpadSens() + ");"
                        + "if(window.plSetTouchThresholds)plSetTouchThresholds("
                        + st.getHoldDragMs() + "," + st.getLongRightMs() + ");"
                        + "}catch(e){}";
                v.evaluateJavascript(js, null);
                Log.i(TAG, "touchMode=" + st.getTouchMode() + " sens=" + st.getTrackpadSens()
                        + " hold=" + st.getHoldDragMs() + " right=" + st.getLongRightMs());
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 准备阶段浮层（日志 + 按钮）
        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setPadding(dp(16), dp(20), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F2101015"));
        overlay.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("PocketLinux");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        overlay.addView(title);

        TextView sub = new TextView(this);
        sub.setText("首次启动会准备 Linux 环境（约 5–10 分钟），之后直接进入桌面");
        sub.setTextColor(Color.parseColor("#9AA0B4"));
        sub.setTextSize(12);
        sub.setPadding(0, dp(4), 0, dp(10));
        overlay.addView(sub);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button install = new Button(this);
        install.setText("准备环境");
        install.setAllCaps(false);
        install.setTextColor(Color.WHITE);
        install.setBackgroundColor(Color.parseColor("#2A2A38"));
        install.setOnClickListener(v -> new Thread(this::installEnv).start());
        row.addView(install, new LinearLayout.LayoutParams(0, dp(46), 1f));

        Button desktop = new Button(this);
        desktop.setText("启动桌面");
        desktop.setAllCaps(false);
        desktop.setTextColor(Color.WHITE);
        desktop.setBackgroundColor(Color.parseColor("#2A6CFF"));
        desktop.setOnClickListener(v -> new Thread(this::startDesktop).start());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        dlp.setMargins(dp(10), 0, 0, 0);
        row.addView(desktop, dlp);

        Button sets = new Button(this);
        sets.setText("设置");
        sets.setAllCaps(false);
        sets.setTextColor(Color.WHITE);
        sets.setBackgroundColor(Color.parseColor("#2A2A38"));
        sets.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        slp2.setMargins(dp(10), 0, 0, 0);
        row.addView(sets, slp2);
        overlay.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        log = new TextView(this);
        log.setTextSize(10f);
        log.setTextColor(Color.parseColor("#B8BFD0"));
        log.setTextIsSelectable(true);
        log.setText("ABI=" + Build.SUPPORTED_ABIS[0]
                + " targetSdk=" + getApplicationInfo().targetSdkVersion + "\n");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(log, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.setMargins(0, dp(12), 0, 0);
        overlay.addView(scroll, slp);

        root.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 右下角小按钮：切回日志浮层
        toggle = new TextView(this);
        ((TextView) toggle).setText("日志");
        ((TextView) toggle).setTextSize(11);
        ((TextView) toggle).setTextColor(Color.parseColor("#CCFFFFFF"));
        GradientDrawable tbg = new GradientDrawable();
        tbg.setColor(Color.parseColor("#66000000"));
        tbg.setCornerRadius(dp(18));
        toggle.setBackground(tbg);
        toggle.setPadding(dp(12), dp(6), dp(12), dp(6));
        toggle.setVisibility(View.GONE);
        toggle.setOnClickListener(v -> showOverlay(!isOverlayVisible()));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        tlp.setMargins(0, 0, dp(12), dp(12));
        root.addView(toggle, tlp);

        // 原生键盘按钮：唤起 IME，字符转成 keysym 发到远端
        TextView kbBtn = new TextView(this);
        kbBtn.setText("⌨");
        kbBtn.setTextSize(18);
        kbBtn.setTextColor(Color.parseColor("#CCFFFFFF"));
        GradientDrawable kbg = new GradientDrawable();
        kbg.setColor(Color.parseColor("#66000000"));
        kbg.setCornerRadius(dp(22));
        kbBtn.setBackground(kbg);
        kbBtn.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.BOTTOM | Gravity.START);
        klp.setMargins(dp(12), 0, 0, dp(12));
        kbBtn.setOnClickListener(v -> {
            kbdInput.requestFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(kbdInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
        root.addView(kbBtn, klp);

        // 原生 ? 帮助
        TextView helpBtn = new TextView(this);
        helpBtn.setText("?");
        helpBtn.setTextSize(16);
        helpBtn.setTextColor(Color.parseColor("#CCFFFFFF"));
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(Color.parseColor("#66000000"));
        hbg.setCornerRadius(dp(18));
        helpBtn.setBackground(hbg);
        helpBtn.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(dp(36), dp(36),
                Gravity.BOTTOM | Gravity.END);
        hlp.setMargins(0, 0, dp(96), dp(56));
        helpBtn.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("触屏操作")
                .setMessage("触控板模式：单指滑动 = 移动指针；轻点 = 左键；快速两次 = 双击\n"
                        + "按住不动再滑 = 拖拽（窗口/图标）；按住不动松手 = 右键\n"
                        + "双指上下滑 = 滚轮；双指轻点 = 右键；三指上滑 = 键盘\n\n"
                        + "外接键盘：字母/数字/F1–F12/方向键/Esc/Tab/Enter/Backspace/Ctrl/Shift/Alt\n"
                        + "外接手柄：左摇杆 = 移指针，A=左键，B=右键，X=回车，Y=Esc，L1/R1=滚轮，START=键盘\n"
                        + "映射文件：files/input-map.json（见 docs/notes/input-mapping.md）\n\n"
                        + "左下 ⌨ 打开系统键盘，可输入英文/数字")
                .setPositiveButton("知道了", null)
                .show());
        root.addView(helpBtn, hlp);

        kbdInput = new EditText(this);
        kbdInput.setBackgroundColor(Color.TRANSPARENT);
        kbdInput.setTextColor(Color.TRANSPARENT);
        kbdInput.setCursorVisible(false);
        kbdInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        kbdInput.setOnKeyListener((v, keyCode, ev) -> {
            if (ev.getAction() != android.view.KeyEvent.ACTION_DOWN) return false;
            if (keyCode == android.view.KeyEvent.KEYCODE_DEL) {
                web.evaluateJavascript("plKey(65288)", null);   // XK_BackSpace
                return true;
            }
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER) {
                web.evaluateJavascript("plKey(65293)", null);   // XK_Return
                return true;
            }
            return false;
        });
        kbdInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(android.text.Editable ed) {
                String v = ed.toString();
                if (v.isEmpty()) return;
                for (int i = 0; i < v.length(); i++) {
                    int code = v.codePointAt(i);
                    if (code == '\n' || code == '\r') continue;
                    web.evaluateJavascript("plKey(" + code + ")", null);
                }
                kbdInput.setText("");
            }
        });
        root.addView(kbdInput, new FrameLayout.LayoutParams(dp(1), dp(1)));

        reloadInputMap();

        setContentView(root);

        // 全屏沉浸：隐藏状态栏/导航栏，画面按整屏算
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);

        try {
            startService(new Intent(this, KeepAliveService.class));
        } catch (Throwable t) {
            Log.e(TAG, "keepalive start fail", t);
        }

        new Thread(this::boot).start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        final String exe = intent.getStringExtra(EXTRA_EXE);
        final String wd = intent.getStringExtra(EXTRA_WORKDIR);
        final boolean restart = intent.getBooleanExtra(EXTRA_RESTART, false);
        intent.removeExtra(EXTRA_EXE);
        intent.removeExtra(EXTRA_WORKDIR);
        intent.removeExtra(EXTRA_RESTART);
        print("### onNewIntent exe=" + exe + " restart=" + restart);
        reloadInputMap();
        new Thread(() -> {
            startDesktop(restart, null);
            if (exe != null) runExeInWine(exe, wd);
        }).start();
    }

    private void boot() {
        try { Thread.sleep(800); } catch (InterruptedException ignored) { }

        installEnv();
        prepareLibs();
        final boolean forceRestart = getIntent().getBooleanExtra(EXTRA_RESTART, false);
        if (forceRestart) getIntent().removeExtra(EXTRA_RESTART);

        // 开发调试通道：files/dev-cmd.sh 存在时先执行（在真实 App 域内跑命令）
        File dev = new File(getFilesDir(), "dev-cmd.sh");
        if (dev.exists()) {
            print("### DEV CMD start");
            runScript(dev);
            print("### DEV CMD done");
        }
        if (new File(rootfsDir, "usr/bin/startxfce4").exists()) {
            print("### 环境已就绪，启动桌面");
            startDesktop(forceRestart, null);
        } else {
            print("### 环境未就绪：开始无 root 安装 XFCE（约 5-10 分钟）");
            installXfce();
            if (new File(rootfsDir, "usr/bin/startxfce4").exists()) {
                print("### XFCE 安装完成，启动桌面");
                startDesktop(forceRestart, null);
            } else {
                print("### XFCE 安装未完成，请检查日志");
            }
        }
        String exe = getIntent().getStringExtra(EXTRA_EXE);
        if (exe != null) {
            print("### 启动 exe: " + exe);
            runExeInWine(exe, getIntent().getStringExtra(EXTRA_WORKDIR));
        }
    }

    private void showOverlay(boolean show) {
        runOnUiThread(() -> {
            if (show) {
                overlay.setVisibility(View.VISIBLE);
                overlay.animate().alpha(1f).setDuration(150).start();
            } else {
                overlay.animate().alpha(0f).setDuration(150).withEndAction(
                        () -> overlay.setVisibility(View.GONE)).start();
            }
        });
    }

    private boolean isOverlayVisible() {
        return overlay.getVisibility() == View.VISIBLE && overlay.getAlpha() > 0.5f;
    }

    private void installEnv() {
        try {
            print("== 解压 native ==");
            String[] names = getAssets().list("native");
            if (names != null) {
                for (String n : names) {
                    File dst = new File(nativeDir, n);
                    boolean good = dst.exists() && dst.length() > 0
                            && !Files.isSymbolicLink(dst.toPath());
                    if (!good) {
                        try { Files.deleteIfExists(dst.toPath()); } catch (Exception ignored) { }
                        copyAsset("native/" + n, dst);
                    }
                    dst.setExecutable(true, false);
                }
            }
            File okMark = new File(rootfsDir, ".pl-ok");
            if (!okMark.exists()) {
                if (new File(rootfsDir, "bin/sh").exists()) {
                    // 老安装：已经完整解压过，补一个完成标记，不动用户数据
                    Files.write(okMark.toPath(), "legacy".getBytes("UTF-8"));
                    print("rootfs 已存在（补完成标记）");
                } else {
                    print("== 解压 rootfs ==");
                    File tmp = new File(getFilesDir(), "rootfs.tmp");
                    deleteTree(tmp);
                    tmp.mkdirs();
                    try (InputStream is = getAssets().open("rootfs.tar")) {
                        TarExtractor.extractRaw(is, tmp.toPath(), 0, null);
                    }
                    Files.write(new File(tmp, ".pl-ok").toPath(), "ok".getBytes("UTF-8"));
                    deleteTree(rootfsDir);
                    if (!tmp.renameTo(rootfsDir)) {
                        throw new java.io.IOException("rootfs 目录改名失败");
                    }
                    print("rootfs 解压完成（有完成标记）");
                }
            } else {
                print("rootfs 已存在，跳过解压");
            }
            File resolv = new File(rootfsDir, "etc/resolv.conf");
            try { Files.deleteIfExists(resolv.toPath()); } catch (Exception ignored) { }
            Files.createDirectories(resolv.getParentFile().toPath());
            Files.write(resolv.toPath(),
                    "nameserver 223.5.5.5\nnameserver 119.29.29.29\n".getBytes("UTF-8"));
            print("环境准备完成");
        } catch (Throwable t) {
            print("安装失败: " + t);
            Log.e(TAG, "install", t);
        }
    }

    private List<String> prootCmd(String script) {
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(nativeDir, "proot").getAbsolutePath());
        cmd.add("--link2symlink");
        cmd.add("-0");
        cmd.add("-r");
        cmd.add(rootfsDir.getAbsolutePath());
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        cmd.add("-w");
        cmd.add("/root");
        cmd.add("/usr/bin/bash");
        cmd.add("-c");
        cmd.add(script);
        return cmd;
    }

    private ProcessBuilder basePb(List<String> cmd) {
        File tmp = new File(getFilesDir(), "tmp");
        tmp.mkdirs();
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(nativeDir);
        pb.redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath());
        pb.environment().put("PROOT_LOADER", new File(nativeDir, "loader").getAbsolutePath());
        pb.environment().put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        pb.environment().put("PATH", nativeDir.getAbsolutePath() + ":/system/bin");
        return pb;
    }

    private void installXfce() {
        try {
            prepareLibs();
            String nd = getApplicationInfo().nativeLibraryDir;
            String prootBin = nd + "/libproot.so";
            String loaderBin = nd + "/libproot_loader.so";
            String rf = rootfsDir.getAbsolutePath();
            File tmp = new File(getFilesDir(), "tmp");
            tmp.mkdirs();
            String script = "#!/system/bin/sh\n"
                    + "export LD_LIBRARY_PATH=" + nativeDir.getAbsolutePath() + ":" + nd + "\n"
                    + "export PROOT_LOADER=" + loaderBin + "\n"
                    + "export PROOT_TMP_DIR=" + tmp.getAbsolutePath() + "\n"
                    + "exec " + prootBin + " --link2symlink -0 -r " + rf + " -b /dev -b /proc -b /sys -w / /usr/bin/bash -c '"
                    + "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; "
                    + "export TMPDIR=/tmp; "
                    + "echo XFCE_INSTALL_START; "
                    + "dpkg --configure -a 2>&1 | tail -3; "
                    + "apt-get update -qq 2>&1 | tail -2; "
                    + "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends "
                    + "xfce4 xvfb x11-apps x11vnc websockify tigervnc-standalone-server dbus-x11 "
                    + "fonts-dejavu-core fonts-liberation xterm imagemagick 2>&1 | tail -6; "
                    + "echo XFCE_INSTALL_DONE'\n";
            File sh = new File(getFilesDir(), "install-xfce.sh");
            Files.write(sh.toPath(), script.getBytes("UTF-8"));
            sh.setExecutable(true, false);
            runScript(sh);
        } catch (Throwable t) {
            print("installXfce 失败: " + t);
        }
    }

    private void runScript(File sh) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", sh.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.environment().put("PL_ND", getApplicationInfo().nativeLibraryDir);
            pb.environment().put("PL_FILES", getFilesDir().getAbsolutePath());
            Process p = pb.start();
            InputStream in = p.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            StringBuilder last = new StringBuilder();
            while ((n = in.read(buf)) > 0) {
                last.append(new String(buf, 0, n, "UTF-8"));
                if (last.length() > 800) {
                    print(last.toString().trim());
                    last.setLength(0);
                }
            }
            p.waitFor();
            if (last.length() > 0) print(last.toString().trim());
            print("脚本结束 exit=" + p.exitValue());
        } catch (Throwable t) {
            print("脚本失败: " + t);
        }
    }

    /** 桌面启动脚本：Xvnc + XFCE + websockify，可选在桌面起来后运行 wine。 */
    private String desktopScript(String extraCmd) {
        String nd = getApplicationInfo().nativeLibraryDir;
        String prootBin = nd + "/libproot.so";
        String loaderBin = nd + "/libproot_loader.so";
        String rf = rootfsDir.getAbsolutePath();
        File tmp = new File(getFilesDir(), "tmp");
        tmp.mkdirs();
        File homeDir = prepareHome();
        String inner = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; "
                + "export TMPDIR=/tmp; export DISPLAY=:99; export HOME=/home; "
                + "export WINEPREFIX=/home/.wine; " + (settings.disableMono ? "export WINEDLLOVERRIDES=\"mscoree,mshtml=\"; " : "") 
                + "mkdir -p /tmp/.ICE-unix && chmod 1777 /tmp/.ICE-unix; "
                + "echo STARTING; "
                + "Xvnc :99 -geometry " + fbW + "x" + fbH + " -depth 24 -SecurityTypes None -ac -localhost -rfbport 5900 > /root/xvnc.log 2>&1 & "
                + "sleep 3; "
                + "dbus-launch --exit-with-session startxfce4 > /root/xfce.log 2>&1 & "
                + "sleep 6; "
                + "websockify 127.0.0.1:6080 127.0.0.1:5900 > /root/ws.log 2>&1 & "
                + "sleep 1; echo DESKTOP_READY; "
                + (extraCmd == null ? "" : extraCmd + " ")
                + "tail -f /dev/null";
        return "#!/system/bin/sh\n"
                + "export LD_LIBRARY_PATH=" + nativeDir.getAbsolutePath() + ":" + nd + "\n"
                + "export PROOT_LOADER=" + loaderBin + "\n"
                + "export PROOT_TMP_DIR=" + tmp.getAbsolutePath() + "\n"
                + "exec " + prootBin + " --link2symlink -0 -r " + rf
                + " -b /dev -b /proc -b /sys -b " + homeDir.getAbsolutePath() + ":/home"
                + " -w /home /usr/bin/bash -c '" + inner + "'\n";
    }

    private void startDesktop() {
        startDesktop(false, null);
    }

    private void startDesktop(String extraCmd) {
        startDesktop(false, extraCmd);
    }

    private void startDesktop(boolean forceRestart, String extraCmd) {
      synchronized (DESK_LOCK) {
        try {
            settings = Settings.load(this);
            computeFbSize();
            // 上次进程被杀后残留的桌面（端口被占）先清掉，避免连到旧会话
            if (desktopProcess == null && (portOpen(5900) || portOpen(6080))) {
                print("清理残留桌面进程…");
                killDesktop();
            }
            if (desktopProcess != null && desktopProcess.isAlive()
                    && !forceRestart
                    && fbW == desktopFbW && fbH == desktopFbH) {
                print("桌面已在运行（" + fbW + "×" + fbH + "）");
                loadVnc();
                return;
            }
            if (desktopProcess != null && desktopProcess.isAlive()) {
                print("重启桌面以应用新设置…");
                killDesktop();
            }
            prepareLibs();
            File sh = new File(getFilesDir(), "start-desktop.sh");
            Files.write(sh.toPath(), desktopScript(extraCmd).getBytes("UTF-8"));
            sh.setExecutable(true, false);

            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", sh.getAbsolutePath());
            pb.redirectErrorStream(true);
            pb.environment().put("PL_ND", getApplicationInfo().nativeLibraryDir);
            pb.environment().put("PL_FILES", getFilesDir().getAbsolutePath());
            desktopProcess = pb.start();
            desktopFbW = fbW;
            desktopFbH = fbH;
            final Process p = desktopProcess;
            new Thread(() -> {
                try {
                    InputStream in = p.getInputStream();
                    byte[] buf = new byte[2048];
                    int n;
                    StringBuilder acc = new StringBuilder();
                    while ((n = in.read(buf)) > 0) {
                        String s2 = new String(buf, 0, n, "UTF-8");
                        acc.append(s2);
                        if (acc.length() > 8192) acc.delete(0, acc.length() - 4096);
                        String t2 = s2.trim();
                        if (!t2.isEmpty()) print(t2);
                        if (acc.indexOf("DESKTOP_READY") >= 0) desktopReady = true;
                    }
                } catch (Throwable ignored) { }
            }).start();
            // 等桌面脚本报告就绪（最多 60 秒），再等 websockify 端口可连
            for (int i = 0; i < 600 && !desktopReady; i++) Thread.sleep(100);
            if (!desktopReady) print("等待 DESKTOP_READY 超时，仍尝试加载画面");
            for (int i = 0; i < 100 && !portOpen(6080); i++) Thread.sleep(100);
            loadVnc();
        } catch (Throwable t) {
            print("桌面启动失败: " + t);
            Log.e(TAG, "desktop", t);
        }
      }
    }

    /** exe 在宿主机上的路径 → proot 内的路径（games 目录被绑定为 /games）。 */
    private String toGuestPath(String hostPath) {
        if (hostPath == null) return null;
        String games = new File(getFilesDir(), "games").getAbsolutePath();
        if (hostPath.startsWith(games)) return "/games" + hostPath.substring(games.length());
        return hostPath;
    }

    /** 在已运行的桌面里用 box64 + wine 启动 exe。 */
    private void runExeInWine(String exePath, String workDir) {
        try {
            File games = new File(getFilesDir(), "games");
            games.mkdirs();
            boolean builtin = exePath != null && !exePath.startsWith("/");
            String exe = builtin ? exePath : toGuestPath(exePath);
            String wd = toGuestPath(workDir);
            if (wd == null || !wd.startsWith("/games")) wd = "/home";
            print("Wine 启动: " + exe + " (cwd " + wd + ")");
            String inner = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; "
                    + "export TMPDIR=/tmp; export DISPLAY=:99; export HOME=/home; export WINEPREFIX=/home/.wine; "
                    + wineRenderEnv()
                    + "export WINEDEBUG=-all; cd \"$PL_WD\"; "
                    + "echo WINE_LAUNCH; "
                    + "box64 /opt/wine-proton-11.0-3-amd64-wow64/bin/wine \"$PL_EXE\" > /root/wine-app.log 2>&1 & "
                    + "sleep 8; tail -8 /root/wine-app.log; echo WINE_LAUNCHED";
            File sh = new File(getFilesDir(), "run-wine.sh");
            Files.write(sh.toPath(), (
                    "#!/system/bin/sh\n"
                            + "export PL_EXE=" + shq(exe) + "\n"
                            + "export PL_WD=" + shq(wd) + "\n"
                            + "export LD_LIBRARY_PATH=" + nativeDir.getAbsolutePath() + ":"
                            + getApplicationInfo().nativeLibraryDir + "\n"
                            + "export PROOT_LOADER=" + getApplicationInfo().nativeLibraryDir + "/libproot_loader.so\n"
                            + "export PROOT_TMP_DIR=" + new File(getFilesDir(), "tmp").getAbsolutePath() + "\n"
                            + "exec " + getApplicationInfo().nativeLibraryDir + "/libproot.so"
                            + " --link2symlink -0 -r " + rootfsDir.getAbsolutePath()
                            + " -b /dev -b /proc -b /sys"
                            + " -b " + new File(getFilesDir(), "home").getAbsolutePath() + ":/home"
                            + " -b " + games.getAbsolutePath() + ":/games"
                            + " -w /home /usr/bin/bash -c '" + inner + "'\n").getBytes("UTF-8"));
            sh.setExecutable(true, false);
            new Thread(() -> runScript(sh)).start();
            final String label = builtin ? exePath : new File(exePath).getName();
            runOnUiThread(() -> Toast.makeText(this, "正在启动 " + label, Toast.LENGTH_SHORT).show());
        } catch (Throwable t) {
            print("Wine 启动失败: " + t);
            runOnUiThread(() -> Toast.makeText(this, "Wine 启动失败：" + t, Toast.LENGTH_LONG).show());
        }
    }

    /** 按设置拼 wine 渲染相关环境变量；容器里没有 DXVK 时自动回退 wined3d 并打日志。 */
    private String wineRenderEnv() {
        StringBuilder overrides = new StringBuilder();
        if (settings.disableMono) overrides.append("mscoree,mshtml=;");
        StringBuilder sb = new StringBuilder();
        // box64 预设
        int p = settings.box64Preset;
        if (p == Settings.BOX64_PERFORMANCE) {
            sb.append("export BOX64_DYNAREC_BIGBLOCK=3 BOX64_DYNAREC_SAFEFLAGS=0 ")
              .append("BOX64_DYNAREC_FASTNAN=1 BOX64_DYNAREC_FASTROUND=1; ");
        } else if (p == Settings.BOX64_STABILITY) {
            sb.append("export BOX64_DYNAREC_BIGBLOCK=0 BOX64_DYNAREC_SAFEFLAGS=2 ")
              .append("BOX64_DYNAREC_STRONGMEM=2; ");
        }
        // 渲染器
        String r = settings.renderer;
        boolean dxvkPresent = new File(rootfsDir, "opt/dxvk/x64/d3d11.dll").exists();
        if (Settings.RENDERER_WINED3D.equals(r)) {
            overrides.append("d3d11,d3d10core,dxgi=b;");
            print("渲染器：wined3d（内置）");
        } else if (dxvkPresent) {
            overrides.append("d3d11,d3d10core,dxgi=n;");
            sb.append("export DXVK_HUD=0; ");
            if (Settings.RENDERER_DXVK_VORTEK.equals(r)) sb.append("export GALLIUM_DRIVER=zink; ");
            print("渲染器：" + settings.rendererName());
        } else {
            print("渲染器：" + settings.rendererName() + " 未安装，回退 wined3d");
            overrides.append("d3d11,d3d10core,dxgi=b;");
        }
        if (overrides.length() > 0) {
            String ov = overrides.toString();
            while (ov.endsWith(";")) ov = ov.substring(0, ov.length() - 1);
            sb.append("export WINEDLLOVERRIDES=\"").append(ov).append("\"; ");
        }
        return sb.toString();
    }

    /** 单引号安全转义，给 shell 用。 */
    private static String shq(String v) {
        if (v == null) return "''";
        return "'" + v.replace("'", "'\\''") + "'";
    }

    /** 按手机屏幕比例算一块约 100 万像素的桌面分辨率，避免黑边与过大流量。 */
    private void computeFbSize() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int[] fb = settings.fbSize(dm.widthPixels, dm.heightPixels);
        fbW = fb[0];
        fbH = fb[1];
    }

    /** 杀掉旧的桌面进程树（Xvnc / xfce / websockify / proot），按 cmdline 扫描 /proc。 */
    private void killDesktop() {
        try {
            File sh = new File(getFilesDir(), "kill-desktop.sh");
            String script = "#!/system/bin/sh\n"
                    + "for p in /proc/[0-9]*; do\n"
                    + "  [ -r \"$p/cmdline\" ] || continue\n"
                    + "  c=$(tr '\\0' ' ' < \"$p/cmdline\" 2>/dev/null)\n"
                    + "  case \"$c\" in\n"
                    + "    *com.pocketlinux.probe/files/rootfs*) kill -9 ${p#/proc/} 2>/dev/null ;;\n"
                    + "    *Xvnc*:99*) kill -9 ${p#/proc/} 2>/dev/null ;;\n"
                    + "    *websockify*127.0.0.1:6080*) kill -9 ${p#/proc/} 2>/dev/null ;;\n"
                    + "  esac\n"
                    + "done\n"
                    + "sleep 1\n";
            Files.write(sh.toPath(), script.getBytes("UTF-8"));
            sh.setExecutable(true, false);
            Process proc = new ProcessBuilder("/system/bin/sh", sh.getAbsolutePath())
                    .redirectErrorStream(true).start();
            proc.waitFor();
            desktopProcess = null;
            desktopReady = false;
            print("旧桌面已清理");
        } catch (Throwable t) {
            print("清理桌面失败: " + t);
        }
    }

    private boolean portOpen(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void prepareLibs() {
        try {
            File nd = new File(getFilesDir(), "native");
            nd.mkdirs();
            String libDir = getApplicationInfo().nativeLibraryDir;
            linkLib(new File(libDir, "libtalloc.so"), new File(nd, "libtalloc.so.2"));
            linkLib(new File(libDir, "libandroid-shmem.so"), new File(nd, "libandroid-shmem.so"));
        } catch (Throwable t) {
            print("prepareLibs 失败: " + t);
        }
    }

    private void linkLib(File target, File link) {
        try {
            try {
                java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(link.getAbsolutePath()));
            } catch (Throwable ignored) { }
            android.system.Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
            print("链接 " + link.getName() + " -> " + target.getName());
        } catch (Throwable t) {
            print("link 失败 " + link.getName() + ": " + t);
        }
    }

    private File prepareHome() {
        File home = new File(getFilesDir(), "home");
        try {
            home.mkdirs();
            home.setReadable(true, false);
            home.setWritable(true, false);
            home.setExecutable(true, false);
        } catch (Throwable t) {
            print("prepareHome 失败: " + t);
        }
        return home;
    }

    private void deleteTree(File f) {
        File[] fs = f.listFiles();
        if (fs != null) for (File c : fs) deleteTree(c);
        if (f.exists()) f.delete();
    }

    private void loadVnc() {
        runOnUiThread(() -> {
            web.clearCache(true);
            web.loadUrl("http://appassets.local/index.html?v=" + WEB_VER);
            print("已加载 VNC 页面");
            toggle.setVisibility(View.VISIBLE);
            showOverlay(false);
        });
    }

    private void copyAsset(String path, File dst) throws Exception {
        dst.getParentFile().mkdirs();
        try (InputStream is = getAssets().open(path);
             OutputStream os = new FileOutputStream(dst)) {
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private void print(String s) {
        Log.i(TAG, s);
        runOnUiThread(() -> log.append(s + "\n"));
    }

    // ================= 输入外设：物理键盘 / 手柄 + 映射 =================
    // 数据流：
    //   物理键盘 KeyEvent  → InputMap.keyboard(KeyCode→keysym) → JS plKeyDown/plKeyUp → RFB KeyEvent
    //   手柄 KeyEvent      → InputMap.gamepadButtons(KeyCode→action) → JS plPointerButton / plWheel / plKey / plShowKeyboard
    //   手柄 MotionEvent   → gamepadAxes/hatAxes（死区+缩放）→ JS plStick(lx,ly) → rAF 相对移动指针
    // 默认映射内置；files/input-map.json 存在时按同名键覆盖（见 docs/notes/input-mapping.md）。

    private void reloadInputMap() {
        File f = new File(getFilesDir(), "input-map.json");
        inputMap = InputMap.load(f);
        print("输入映射: 键盘 " + inputMap.keyboard.size() + " / 手柄键 " + inputMap.gamepadButtons.size()
                + " / 轴 " + inputMap.gamepadAxes.size() + " / hat " + inputMap.hatAxes.size()
                + (f.isFile() ? "（已读取 input-map.json 覆盖）" : "（内置默认）"));
    }

    private void js(String code) { if (web != null) web.evaluateJavascript(code, null); }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (inputMap == null || e == null) return super.dispatchKeyEvent(e);
        final int act = e.getAction();
        if (act != KeyEvent.ACTION_DOWN && act != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(e);
        final boolean down = act == KeyEvent.ACTION_DOWN;
        final boolean firstDown = down && e.getRepeatCount() == 0;   // 长按的重复交给 X 端 auto-repeat
        final int kc = e.getKeyCode();
        // 先查手柄映射（按键名是 BUTTON_*/按键码，只有手柄会上报；不依赖 source，兼容各厂商）
        String ga = inputMap.gamepadButtons.get(kc);
        if (ga != null) {
            if (firstDown || !down) gamepadAction(ga, down);
            return true;
        }
        Integer ks = inputMap.keyboard.get(kc);
        if (ks != null) {
            if (firstDown || !down) js((down ? "plKeyDown(" : "plKeyUp(") + ks + ")");
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (inputMap == null || e == null) return super.dispatchGenericMotionEvent(e);
        if (e.getAction() != MotionEvent.ACTION_MOVE) return super.dispatchGenericMotionEvent(e);
        final int src = e.getSource();
        final boolean gamepad = (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (!gamepad) return super.dispatchGenericMotionEvent(e);
        return handleGamepadAxes(e) || super.dispatchGenericMotionEvent(e);
    }

    /** 执行一条映射动作：mouse_left/middle/right、wheel_up/down、keyboard、key:<keysym 名或 0xhex>。 */
    private void gamepadAction(String action, boolean down) {
        if (action == null) return;
        switch (action) {
            case "mouse_left":   js("plPointerButton(1," + down + ")"); return;
            case "mouse_middle": js("plPointerButton(2," + down + ")"); return;
            case "mouse_right":  js("plPointerButton(3," + down + ")"); return;
            case "wheel_up":     if (down) js("plWheel(true)"); return;
            case "wheel_down":   if (down) js("plWheel(false)"); return;
            case "keyboard":     if (down) js("plShowKeyboard()"); return;
            default:
                if (action.startsWith("key:")) {
                    int ks = InputMap.keysym(action.substring(4));
                    if (ks > 0) js((down ? "plKeyDown(" : "plKeyUp(") + ks + ")");
                }
        }
    }

    /** 读手柄轴：摇杆→鼠标相对移动速度；hat→方向键按下/抬起边沿。返回是否消费。 */
    private boolean handleGamepadAxes(MotionEvent e) {
        boolean handled = false;
        float sx = 0f, sy = 0f;
        boolean hasStick = false;
        for (Map.Entry<Integer, String> en : inputMap.gamepadAxes.entrySet()) {
            float v = inputMap.applyDeadzone(e.getAxisValue(en.getKey()));
            if ("mouse_x".equals(en.getValue())) { sx = v; hasStick = true; handled = true; }
            else if ("mouse_y".equals(en.getValue())) { sy = v; hasStick = true; handled = true; }
        }
        for (Map.Entry<Integer, String[]> en : inputMap.hatAxes.entrySet()) {
            float raw = e.getAxisValue(en.getKey());
            int dir = raw > 0.5f ? 1 : (raw < -0.5f ? -1 : 0);
            Integer prevObj = hatState.get(en.getKey());
            int prev = prevObj == null ? 0 : prevObj;
            if (dir == prev) continue;
            String[] acts = en.getValue();
            if (prev < 0) gamepadAction(acts[0], false);
            else if (prev > 0) gamepadAction(acts[1], false);
            if (dir < 0) gamepadAction(acts[0], true);
            else if (dir > 0) gamepadAction(acts[1], true);
            hatState.put(en.getKey(), dir);
            handled = true;
        }
        if (hasStick) {
            sx *= inputMap.stickSpeed;
            sy *= inputMap.stickSpeed;
            float dx = sx - stickX, dy = sy - stickY;
            boolean changed = Math.abs(dx) > 0.005f || Math.abs(dy) > 0.005f;
            boolean off = (sx == 0f && sy == 0f) && (stickX != 0f || stickY != 0f);
            long now = android.os.SystemClock.uptimeMillis();
            boolean big = Math.abs(dx) > 0.2f || Math.abs(dy) > 0.2f;
            if (changed && (big || off || now - lastStickSend >= 33)) {   // 约 30Hz 推给 JS
                stickX = sx; stickY = sy; lastStickSend = now;
                js("plStick(" + f3(sx) + "," + f3(sy) + ")");
            }
            handled = true;
        }
        return handled;
    }

    private static String f3(float v) {
        return String.format(java.util.Locale.US, "%.3f", v);
    }

    /** 内置默认映射 + files/input-map.json 覆盖后的映射表。 */
    private static class InputMap {
        final Map<Integer, Integer> keyboard = new HashMap<>();       // Android KeyCode → X keysym
        final Map<Integer, String> gamepadButtons = new HashMap<>();  // Android KeyCode → action
        final Map<Integer, String> gamepadAxes = new HashMap<>();     // axis → mouse_x / mouse_y
        final Map<Integer, String[]> hatAxes = new HashMap<>();       // axis → {负方向, 正方向} action
        float deadzone = 0.15f;
        float stickSpeed = 1.0f;

        static InputMap load(File json) {
            InputMap m = new InputMap();
            m.defaults();
            if (json != null && json.isFile()) {
                try {
                    String text = new String(Files.readAllBytes(json.toPath()), "UTF-8");
                    m.applyJson(new JSONObject(text));
                    Log.i(TAG, "input-map.json 已加载");
                } catch (Throwable t) {
                    Log.e(TAG, "input-map.json 解析失败（继续用内置默认）: " + t);
                }
            }
            return m;
        }

        /** 摇杆死区 + 归一化（死区外重新映射到 0~1，避免小位移跳变）。 */
        float applyDeadzone(float v) {
            float a = Math.abs(v);
            if (a <= deadzone) return 0f;
            float n = (a - deadzone) / (1f - deadzone);
            if (n > 1f) n = 1f;
            return v < 0 ? -n : n;
        }

        void defaults() {
            // ---- 物理键盘：字母 / 数字 / F1–F12 ----
            for (int i = 0; i < 26; i++) keyboard.put(KeyEvent.KEYCODE_A + i, 0x61 + i);
            for (int i = 0; i < 10; i++) keyboard.put(KeyEvent.KEYCODE_0 + i, 0x30 + i);
            for (int i = 0; i < 12; i++) keyboard.put(KeyEvent.KEYCODE_F1 + i, 0xFFBE + i);
            // 方向键 / 编辑键
            keyboard.put(KeyEvent.KEYCODE_DPAD_LEFT, 0xFF51);
            keyboard.put(KeyEvent.KEYCODE_DPAD_UP, 0xFF52);
            keyboard.put(KeyEvent.KEYCODE_DPAD_RIGHT, 0xFF53);
            keyboard.put(KeyEvent.KEYCODE_DPAD_DOWN, 0xFF54);
            keyboard.put(KeyEvent.KEYCODE_ESCAPE, 0xFF1B);
            keyboard.put(KeyEvent.KEYCODE_TAB, 0xFF09);
            keyboard.put(KeyEvent.KEYCODE_ENTER, 0xFF0D);
            keyboard.put(KeyEvent.KEYCODE_DEL, 0xFF08);             // Backspace
            keyboard.put(KeyEvent.KEYCODE_FORWARD_DEL, 0xFFFF);     // Delete
            keyboard.put(KeyEvent.KEYCODE_SPACE, 0x20);
            keyboard.put(KeyEvent.KEYCODE_INSERT, 0xFF63);
            keyboard.put(KeyEvent.KEYCODE_MOVE_HOME, 0xFF50);
            keyboard.put(KeyEvent.KEYCODE_MOVE_END, 0xFF57);
            keyboard.put(KeyEvent.KEYCODE_PAGE_UP, 0xFF55);
            keyboard.put(KeyEvent.KEYCODE_PAGE_DOWN, 0xFF56);
            // 修饰键（X 端按 keysym 维护按下状态，组合键成立）
            keyboard.put(KeyEvent.KEYCODE_SHIFT_LEFT, 0xFFE1);
            keyboard.put(KeyEvent.KEYCODE_SHIFT_RIGHT, 0xFFE2);
            keyboard.put(KeyEvent.KEYCODE_CTRL_LEFT, 0xFFE3);
            keyboard.put(KeyEvent.KEYCODE_CTRL_RIGHT, 0xFFE4);
            keyboard.put(KeyEvent.KEYCODE_ALT_LEFT, 0xFFE9);
            keyboard.put(KeyEvent.KEYCODE_ALT_RIGHT, 0xFFEA);
            keyboard.put(KeyEvent.KEYCODE_META_LEFT, 0xFFEB);
            keyboard.put(KeyEvent.KEYCODE_META_RIGHT, 0xFFEC);
            keyboard.put(KeyEvent.KEYCODE_CAPS_LOCK, 0xFFE5);
            // 符号键
            keyboard.put(KeyEvent.KEYCODE_MINUS, 0x2D);
            keyboard.put(KeyEvent.KEYCODE_EQUALS, 0x3D);
            keyboard.put(KeyEvent.KEYCODE_LEFT_BRACKET, 0x5B);
            keyboard.put(KeyEvent.KEYCODE_RIGHT_BRACKET, 0x5D);
            keyboard.put(KeyEvent.KEYCODE_BACKSLASH, 0x5C);
            keyboard.put(KeyEvent.KEYCODE_SEMICOLON, 0x3B);
            keyboard.put(KeyEvent.KEYCODE_APOSTROPHE, 0x27);
            keyboard.put(KeyEvent.KEYCODE_COMMA, 0x2C);
            keyboard.put(KeyEvent.KEYCODE_PERIOD, 0x2E);
            keyboard.put(KeyEvent.KEYCODE_SLASH, 0x2F);
            keyboard.put(KeyEvent.KEYCODE_GRAVE, 0x60);
            // 小键盘
            keyboard.put(KeyEvent.KEYCODE_NUM_LOCK, 0xFF7F);
            for (int i = 0; i < 10; i++) keyboard.put(KeyEvent.KEYCODE_NUMPAD_0 + i, 0xFFB0 + i);
            keyboard.put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 0xFFAF);
            keyboard.put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 0xFFAA);
            keyboard.put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 0xFFAD);
            keyboard.put(KeyEvent.KEYCODE_NUMPAD_ADD, 0xFFAB);
            keyboard.put(KeyEvent.KEYCODE_NUMPAD_DOT, 0xFFAE);
            keyboard.put(KeyEvent.KEYCODE_NUMPAD_ENTER, 0xFF8D);

            // ---- 手柄默认映射（GameSir / DroidDeck 直觉）----
            gamepadButtons.put(KeyEvent.KEYCODE_BUTTON_A, "mouse_left");
            gamepadButtons.put(KeyEvent.KEYCODE_BUTTON_B, "mouse_right");
            gamepadButtons.put(KeyEvent.KEYCODE_BUTTON_X, "key:Return");
            gamepadButtons.put(KeyEvent.KEYCODE_BUTTON_Y, "key:Escape");
            gamepadButtons.put(KeyEvent.KEYCODE_BUTTON_L1, "wheel_up");
            gamepadButtons.put(KeyEvent.KEYCODE_BUTTON_R1, "wheel_down");
            gamepadButtons.put(KeyEvent.KEYCODE_BUTTON_START, "keyboard");
            gamepadAxes.put(MotionEvent.AXIS_X, "mouse_x");
            gamepadAxes.put(MotionEvent.AXIS_Y, "mouse_y");
            hatAxes.put(MotionEvent.AXIS_HAT_X, new String[]{"key:Left", "key:Right"});
            hatAxes.put(MotionEvent.AXIS_HAT_Y, new String[]{"key:Up", "key:Down"});
        }

        /** 合并 input-map.json：同名字段覆盖内置默认，未写的保留默认。 */
        void applyJson(JSONObject root) {
            JSONObject kb = root.optJSONObject("keyboard");
            if (kb != null) {
                java.util.Iterator<String> it = kb.keys();
                while (it.hasNext()) {
                    String name = it.next();
                    int kc = keycode(name);
                    int ks = keysym(kb.optString(name, ""));
                    if (kc == 0 || ks == 0) {
                        Log.w(TAG, "键盘映射忽略: " + name + "=" + kb.optString(name));
                        continue;
                    }
                    keyboard.put(kc, ks);
                }
            }
            JSONObject gp = root.optJSONObject("gamepad");
            if (gp == null) return;
            JSONObject stick = gp.optJSONObject("stick");
            if (stick != null) {
                deadzone = (float) Math.max(0.0, Math.min(0.8, stick.optDouble("deadzone", deadzone)));
                stickSpeed = (float) Math.max(0.2, Math.min(3.0, stick.optDouble("speed", stickSpeed)));
            }
            java.util.Iterator<String> it = gp.keys();
            while (it.hasNext()) {
                String name = it.next();
                if ("stick".equals(name)) continue;
                JSONArray arr = gp.optJSONArray(name);
                if (arr != null) {                                  // hat 轴：["负方向行动作","正方向行动作"]
                    int axis = axis(name);
                    if (axis >= 0) hatAxes.put(axis, new String[]{arr.optString(0), arr.optString(1)});
                    continue;
                }
                String val = gp.optString(name, "").trim();
                if (val.isEmpty()) continue;
                if (name.startsWith("AXIS_")) {
                    int axis = axis(name);
                    if (axis >= 0 && ("mouse_x".equals(val) || "mouse_y".equals(val))) {
                        gamepadAxes.put(axis, val);
                    } else {
                        Log.w(TAG, "轴映射忽略（支持 mouse_x/mouse_y 或 [负,正] 数组）: " + name + "=" + val);
                    }
                } else {
                    int kc = keycode(name);
                    if (kc != 0) gamepadButtons.put(kc, val);
                    else Log.w(TAG, "手柄键名无法识别: " + name);
                }
            }
        }

        static int keycode(String name) {
            if (name == null || name.isEmpty()) return 0;
            String field = name.startsWith("KEYCODE_") ? name : "KEYCODE_" + name;
            try { return KeyEvent.class.getField(field).getInt(null); }
            catch (Throwable t) { return 0; }
        }

        static int axis(String name) {
            if (name == null || name.isEmpty()) return -1;
            String field = name.startsWith("AXIS_") ? name : "AXIS_" + name;
            try { return MotionEvent.class.getField(field).getInt(null); }   // 轴常量都在 MotionEvent
            catch (Throwable t) { }
            try { return InputDevice.class.getField(field).getInt(null); }
            catch (Throwable t) { return -1; }
        }

        /** 键名 / 单字符 / 0x 数值 → X keysym；0 表示无法识别。 */
        static int keysym(String s) {
            if (s == null) return 0;
            s = s.trim();
            if (s.isEmpty()) return 0;
            try {
                if (s.length() == 1) return s.charAt(0);
                if (s.startsWith("0x") || s.startsWith("0X")) return Integer.parseInt(s.substring(2), 16);
                if (s.matches("[0-9]+")) return Integer.parseInt(s);
            } catch (Throwable ignore) { }
            Integer v = KEYSYM_NAMES.get(s);
            return v == null ? 0 : v;
        }

        private static final Map<String, Integer> KEYSYM_NAMES = new HashMap<>();
        static {
            String[] names = {
                "Escape", "Tab", "Return", "Enter", "BackSpace", "Delete", "Insert",
                "Home", "End", "Page_Up", "Page_Down", "Left", "Up", "Right", "Down",
                "Shift_L", "Shift_R", "Control_L", "Control_R", "Alt_L", "Alt_R",
                "Super_L", "Super_R", "Caps_Lock", "Num_Lock", "space"
            };
            int[] values = {
                0xFF1B, 0xFF09, 0xFF0D, 0xFF0D, 0xFF08, 0xFFFF, 0xFF63,
                0xFF50, 0xFF57, 0xFF55, 0xFF56, 0xFF51, 0xFF52, 0xFF53, 0xFF54,
                0xFFE1, 0xFFE2, 0xFFE3, 0xFFE4, 0xFFE9, 0xFFEA,
                0xFFEB, 0xFFEC, 0xFFE5, 0xFF7F, 0x20
            };
            for (int i = 0; i < names.length; i++) KEYSYM_NAMES.put(names[i], values[i]);
            for (int i = 0; i < 12; i++) KEYSYM_NAMES.put("F" + (i + 1), 0xFFBE + i);
            String[] punct = {"minus", "equal", "bracketleft", "bracketright", "backslash",
                    "semicolon", "apostrophe", "comma", "period", "slash", "grave"};
            int[] pv = {0x2D, 0x3D, 0x5B, 0x5D, 0x5C, 0x3B, 0x27, 0x2C, 0x2E, 0x2F, 0x60};
            for (int i = 0; i < punct.length; i++) KEYSYM_NAMES.put(punct[i], pv[i]);
            for (int i = 0; i < 10; i++) KEYSYM_NAMES.put("KP_" + i, 0xFFB0 + i);
            KEYSYM_NAMES.put("KP_Enter", 0xFF8D);
            KEYSYM_NAMES.put("KP_Divide", 0xFFAF);
            KEYSYM_NAMES.put("KP_Multiply", 0xFFAA);
            KEYSYM_NAMES.put("KP_Subtract", 0xFFAD);
            KEYSYM_NAMES.put("KP_Add", 0xFFAB);
            KEYSYM_NAMES.put("KP_Decimal", 0xFFAE);
        }
    }

    /** 触摸全部在这里接住，转成 JS 调用；WebView 自己的手势就不会再取消触摸流。 */
    private static class CaptureWebView extends WebView {
        private long lastMove = 0;

        CaptureWebView(android.content.Context c) {
            super(c);
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent e) {
            int action = e.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_MOVE) {
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastMove < 16) return true;
                lastMove = now;
            }
            int a = action;
            if (action == android.view.MotionEvent.ACTION_POINTER_DOWN) a = 5;
            else if (action == android.view.MotionEvent.ACTION_POINTER_UP) a = 6;
            else if (action == android.view.MotionEvent.ACTION_CANCEL) a = 3;
            int n = e.getPointerCount();
            float vw = Math.max(1, getWidth());
            float vh = Math.max(1, getHeight());
            StringBuilder sb = new StringBuilder(40 + 24 * n);
            sb.append("plTouch(").append(a).append(",[");
            for (int i = 0; i < n; i++) {
                if (i > 0) sb.append(',');
                // 传 0~1 的相对坐标，避免 DPR / view 尺寸换算出偏差
                sb.append('[').append(String.format(java.util.Locale.US, "%.4f", e.getX(i) / vw))
                  .append(',').append(String.format(java.util.Locale.US, "%.4f", e.getY(i) / vh)).append(']');
            }
            sb.append("])");
            if (a != 2) Log.i("PLTOUCH", "a=" + a + " n=" + n + " x=" + (int) e.getX(0) + " y=" + (int) e.getY(0));
            evaluateJavascript(sb.toString(), null);
            return true;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
