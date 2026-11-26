package com.pocketlinux.probe.ui;

import android.content.Context;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.pocketlinux.probe.Settings;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;

/**
 * 首页「Linux 桌面」卡片的状态探测：
 * 运行中 / 已就绪未启动 / 首次准备中。全部只在后台线程调用。
 */
public final class DesktopStatus {

    public enum State {
        /** 正在探测。 */
        CHECKING,
        /** rootfs 里还没有桌面环境，首次进入需要安装（约 5–10 分钟）。 */
        PREPARING,
        /** 环境已就绪，但桌面没在跑。 */
        READY,
        /** websockify / Xvnc 端口已开，桌面正在运行。 */
        RUNNING
    }

    public static final class Info {
        public State state = State.CHECKING;
        public int width;
        public int height;
        public boolean autoRes;
        public String budgetName = "";
        public String rendererName = "";
    }

    private DesktopStatus() {}

    /** 阻塞探测，最长约 1 秒（两次本地端口 connect 超时）。 */
    public static Info detect(Context ctx) {
        Info info = new Info();
        try {
            Settings s = Settings.load(ctx);
            info.autoRes = s.autoRes;
            info.budgetName = Settings.budgetName(s.budget);
            info.rendererName = Settings.rendererName(s.renderer);

            int sw = 0, sh = 0;
            try {
                DisplayMetrics dm = new DisplayMetrics();
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                if (wm != null) {
                    wm.getDefaultDisplay().getRealMetrics(dm);
                    sw = dm.widthPixels;
                    sh = dm.heightPixels;
                }
            } catch (Throwable ignored) {
            }
            if (sw <= 0 || sh <= 0) {
                DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
                sw = dm.widthPixels;
                sh = dm.heightPixels;
            }
            int[] fb = s.fbSize(sw, sh);
            info.width = fb[0];
            info.height = fb[1];

            boolean running = portOpen(6080) || portOpen(5900);
            if (running) {
                int[] res = readXvncResolution();
                if (res != null) {
                    info.width = res[0];
                    info.height = res[1];
                }
                info.state = State.RUNNING;
            } else if (new File(ctx.getFilesDir(), "rootfs/usr/bin/startxfce4").exists()) {
                info.state = State.READY;
            } else {
                info.state = State.PREPARING;
            }
        } catch (Throwable t) {
            info.state = State.READY;
        }
        return info;
    }

    private static boolean portOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 从 /proc 里找 Xvnc 命令行，读真实的 -geometry WxH（读不到就返回 null）。 */
    private static int[] readXvncResolution() {
        try {
            String[] pids = new File("/proc").list();
            if (pids == null) return null;
            for (String pid : pids) {
                if (!isDigits(pid)) continue;
                File cmd = new File("/proc/" + pid + "/cmdline");
                try {
                    byte[] buf = Files.readAllBytes(cmd.toPath());
                    if (buf.length == 0) continue;
                    String line = new String(buf, "UTF-8");
                    if (!line.contains("Xvnc")) continue;
                    String[] args = line.split("\u0000");
                    for (int i = 0; i + 1 < args.length; i++) {
                        if (!"-geometry".equals(args[i])) continue;
                        String g = args[i + 1];
                        int x = g.indexOf('x');
                        if (x <= 0) continue;
                        int w = Integer.parseInt(g.substring(0, x).trim());
                        int h = Integer.parseInt(g.substring(x + 1).trim());
                        if (w >= 320 && h >= 320) return new int[]{w, h};
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isDigits(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
