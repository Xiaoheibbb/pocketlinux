package com.pocketlinux.probe;

import android.content.Context;
import android.content.SharedPreferences;

/** 用户设置：桌面分辨率、性能预算、Wine 兼容选项。 */
public class Settings {
    private static final String FILE = "pl_settings";

    /** 自动按手机屏幕比例算分辨率。 */
    public boolean autoRes = true;
    /** 自定义分辨率（autoRes=false 时生效）。 */
    public int width = 680;
    public int height = 1472;
    /** 自动模式下的像素预算：越大多画面越清晰、越吃性能。 */
    public int budget = 1000000;
    /** 关闭 Wine Mono / Gecko 首次安装弹窗。 */
    public boolean disableMono = true;

    // ---- 图形渲染器：先只保存选择，等后端 Wine 启动脚本接入环境变量后生效 ----
    public static final String RENDERER_DXVK = "dxvk";
    public static final String RENDERER_DXVK_VORTEK = "dxvk_vortek";
    public static final String RENDERER_WINED3D = "wined3d";
    public String renderer = RENDERER_DXVK;

    // ---- box64 预设：占位项（UI 标「未实现」），后端尚未读取 ----
    public static final int BOX64_PERFORMANCE = 0;
    public static final int BOX64_BALANCED = 1;
    public static final int BOX64_STABILITY = 2;
    public int box64Preset = BOX64_BALANCED;

    // ---- 触控参数：驱动 JS 侧 plSetTouchMode / plSetTrackpadSens / plSetTouchThresholds ----
    public static final String TOUCH_MODE_TRACKPAD = "trackpad";
    public static final String TOUCH_MODE_DIRECT = "direct";
    public static final float TRACKPAD_SENS_MIN = 0.8f;
    public static final float TRACKPAD_SENS_MAX = 2.5f;
    public static final float TRACKPAD_SENS_DEFAULT = 1.6f;
    public static final int HOLD_DRAG_MS_MIN = 250;
    public static final int HOLD_DRAG_MS_MAX = 800;
    public static final int HOLD_DRAG_MS_DEFAULT = 400;
    public static final int LONG_RIGHT_MS_MIN = 400;
    public static final int LONG_RIGHT_MS_MAX = 1000;
    public static final int LONG_RIGHT_MS_DEFAULT = 600;

    /** 触控模式："trackpad"（默认，像笔记本触控板）或 "direct"（点哪指哪）。 */
    public String touchMode = TOUCH_MODE_TRACKPAD;
    /** 触控板灵敏度倍率：0.8–2.5，默认 1.6。 */
    public float trackpadSens = TRACKPAD_SENS_DEFAULT;
    /** 按住多久后滑动变成拖拽（毫秒）：250–800，默认 400。 */
    public int holdDragMs = HOLD_DRAG_MS_DEFAULT;
    /** 按住不动多久后抬手算右键（毫秒）：400–1000，默认 600。 */
    public int longRightMs = LONG_RIGHT_MS_DEFAULT;

    public static Settings load(Context c) {
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Settings s = new Settings();
        s.autoRes = p.getBoolean("autoRes", true);
        s.width = p.getInt("width", 680);
        s.height = p.getInt("height", 1472);
        s.budget = p.getInt("budget", 1000000);
        s.disableMono = p.getBoolean("disableMono", true);
        s.renderer = p.getString("renderer", RENDERER_DXVK);
        s.box64Preset = p.getInt("box64Preset", BOX64_BALANCED);
        s.touchMode = normalizeTouchMode(p.getString("touchMode", TOUCH_MODE_TRACKPAD));
        s.trackpadSens = clampTrackpadSens(p.getFloat("trackpadSens", TRACKPAD_SENS_DEFAULT));
        s.holdDragMs = clampHoldDragMs(p.getInt("holdDragMs", HOLD_DRAG_MS_DEFAULT));
        s.longRightMs = clampLongRightMs(p.getInt("longRightMs", LONG_RIGHT_MS_DEFAULT));
        return s;
    }

    public void save(Context c) {
        touchMode = normalizeTouchMode(touchMode);
        trackpadSens = clampTrackpadSens(trackpadSens);
        holdDragMs = clampHoldDragMs(holdDragMs);
        longRightMs = clampLongRightMs(longRightMs);
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean("autoRes", autoRes)
                .putInt("width", width)
                .putInt("height", height)
                .putInt("budget", budget)
                .putBoolean("disableMono", disableMono)
                .putString("renderer", renderer)
                .putInt("box64Preset", box64Preset)
                .putString("touchMode", touchMode)
                .putFloat("trackpadSens", trackpadSens)
                .putInt("holdDragMs", holdDragMs)
                .putInt("longRightMs", longRightMs)
                .apply();
    }

    /** 实际用的桌面分辨率：自动按比例 + 像素预算，否则用自定义值。 */
    public int[] fbSize(int screenW, int screenH) {
        if (!autoRes) {
            int w = Math.max(320, Math.min(3840, width));
            int h = Math.max(320, Math.min(3840, height));
            return new int[]{w, h};
        }
        int pw = Math.min(screenW, screenH);
        int ph = Math.max(screenW, screenH);
        double scale = Math.sqrt((double) budget / ((double) pw * ph));
        int w = Math.max(360, (int) Math.round(pw * scale / 8) * 8);
        int h = Math.max(480, (int) Math.round(ph * scale / 8) * 8);
        return new int[]{w, h};
    }

    public static String budgetName(int budget) {
        if (budget <= 600000) return "流畅";
        if (budget <= 1100000) return "均衡";
        if (budget <= 1800000) return "清晰";
        return "极清";
    }

    public static String rendererName(String r) {
        if (RENDERER_DXVK_VORTEK.equals(r)) return "DXVK (vortek)";
        if (RENDERER_WINED3D.equals(r)) return "wined3d";
        return "DXVK";
    }

    public String rendererName() {
        return rendererName(renderer);
    }

    public static String box64PresetName(int preset) {
        if (preset == BOX64_PERFORMANCE) return "性能";
        if (preset == BOX64_STABILITY) return "兼容";
        return "平衡";
    }

    // ------------------------------------------------------------------ 触控

    public static String normalizeTouchMode(String mode) {
        return TOUCH_MODE_DIRECT.equals(mode) ? TOUCH_MODE_DIRECT : TOUCH_MODE_TRACKPAD;
    }

    public static String touchModeName(String mode) {
        return TOUCH_MODE_DIRECT.equals(mode) ? "直接触摸" : "触控板";
    }

    public static float clampTrackpadSens(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return TRACKPAD_SENS_DEFAULT;
        return Math.max(TRACKPAD_SENS_MIN, Math.min(TRACKPAD_SENS_MAX, v));
    }

    public static int clampHoldDragMs(int v) {
        return Math.max(HOLD_DRAG_MS_MIN, Math.min(HOLD_DRAG_MS_MAX, v));
    }

    public static int clampLongRightMs(int v) {
        return Math.max(LONG_RIGHT_MS_MIN, Math.min(LONG_RIGHT_MS_MAX, v));
    }

    /** 注入用 getter：返回已钳制的值，直接喂给 JS。 */
    public String getTouchMode() {
        return normalizeTouchMode(touchMode);
    }

    public boolean isTrackpadMode() {
        return TOUCH_MODE_TRACKPAD.equals(getTouchMode());
    }

    public boolean isDirectTouchMode() {
        return TOUCH_MODE_DIRECT.equals(getTouchMode());
    }

    public float getTrackpadSens() {
        return clampTrackpadSens(trackpadSens);
    }

    public int getHoldDragMs() {
        return clampHoldDragMs(holdDragMs);
    }

    public int getLongRightMs() {
        return clampLongRightMs(longRightMs);
    }
}
