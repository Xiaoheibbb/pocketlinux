package com.pocketlinux.probe.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * PocketLinux 统一视觉层：深色底、圆角卡片、主色 #2A6CFF。
 * 纯原生 View 实现，不依赖 androidx。
 */
public final class Ui {
    // ---- 调色板 ----
    public static final String BG = "#0B0C11";
    public static final String CARD = "#171A23";
    public static final String CARD_2 = "#1E2230";
    public static final String CARD_3 = "#252B3B";
    public static final String STROKE = "#2A3042";
    public static final String TEXT = "#F2F4FA";
    public static final String TEXT_2 = "#9AA3BA";
    public static final String TEXT_3 = "#6B7386";
    public static final String PRIMARY = "#2A6CFF";
    public static final String PRIMARY_DIM = "#3D7BFF";
    public static final String PRIMARY_SOFT = "#1B2A4D";
    public static final String GREEN = "#35D07F";
    public static final String AMBER = "#FFB020";
    public static final String RED = "#FF5D5D";
    public static final String DARK_BLUE = "#0F1B33";
    public static final String DARK_BLUE_2 = "#12306B";

    private static final int MP = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WC = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Ui() {}

    // ---- 基础工具 ----

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    public static int color(String hex) {
        return Color.parseColor(hex);
    }

    /** 深色状态栏/导航栏，窗口底色统一。 */
    public static void applyTheme(Activity a) {
        try {
            Window w = a.getWindow();
            w.setStatusBarColor(color(BG));
            w.setNavigationBarColor(color(BG));
            w.setBackgroundDrawable(new ColorDrawable(color(BG)));
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            View decor = w.getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                    & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        } catch (Throwable ignored) {
        }
    }

    public static Typeface medium() {
        try {
            return Typeface.create("sans-serif-medium", Typeface.NORMAL);
        } catch (Throwable t) {
            return Typeface.DEFAULT_BOLD;
        }
    }

    // ---- 背景 ----

    public static GradientDrawable round(Context c, int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable round(Context c, int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable d = round(c, fill, radiusDp);
        d.setStroke(dp(c, strokeDp), stroke);
        return d;
    }

    public static GradientDrawable gradient(Context c, int from, int to, float radiusDp) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{from, to});
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable circle(Context c, int fill) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    /** 带按压水波纹的背景（圆角矩形）。 */
    public static Drawable rippled(Context c, int fill, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
                round(c, fill, radiusDp), null);
    }

    /** 主色按钮背景：轻微渐变 + 水波纹。 */
    public static Drawable primaryBg(Context c) {
        GradientDrawable d = gradient(c, color(PRIMARY_DIM), color(PRIMARY), 14);
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), d, null);
    }

    // ---- 文本 ----

    public static TextView text(Context c, CharSequence s, float sp, int color) {
        return text(c, s, sp, color, false);
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (medium) t.setTypeface(medium());
        return t;
    }

    public static TextView title(Context c, CharSequence s) {
        return text(c, s, 22, color(TEXT), true);
    }

    public static TextView heading(Context c, CharSequence s) {
        return text(c, s, 15, color(TEXT), true);
    }

    public static TextView subheading(Context c, CharSequence s) {
        return text(c, s, 13, color(TEXT_2), true);
    }

    public static TextView body(Context c, CharSequence s) {
        TextView t = text(c, s, 12.5f, color(TEXT_2));
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    public static TextView caption(Context c, CharSequence s) {
        return text(c, s, 11, color(TEXT_3));
    }

    /** 大号圆角按钮。primary=true 为主色，否则为深色描边按钮。 */
    public static TextView button(Context c, CharSequence label, boolean primary, float heightDp) {
        TextView t = text(c, label, 14, primary ? Color.WHITE : color(TEXT), true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setBackground(primary ? primaryBg(c) : rippled(c, color(CARD_2), 14));
        t.setClickable(true);
        t.setFocusable(true);
        t.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        t.setMinHeight(0);
        t.setMinimumHeight(0);
        t.setLayoutParams(new LinearLayout.LayoutParams(WC, dp(c, heightDp), 0f));
        return t;
    }

    /** 小号胶囊按钮（列表头 / 卡片内动作）。 */
    public static TextView smallButton(Context c, CharSequence label, boolean primary) {
        TextView t = text(c, label, 12, primary ? Color.WHITE : color(TEXT), true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setBackground(primary ? primaryBg(c) : rippled(c, color(CARD_3), 20));
        t.setClickable(true);
        t.setFocusable(true);
        t.setPadding(dp(c, 14), dp(c, 7), dp(c, 14), dp(c, 7));
        t.setLayoutParams(new LinearLayout.LayoutParams(WC, WC, 0f));
        return t;
    }

    /** 圆形图标按钮（顶栏用）。 */
    public static TextView iconButton(Context c, CharSequence glyph) {
        TextView t = text(c, glyph, 17, color(TEXT), false);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rippled(c, color(CARD_2), 20));
        t.setClickable(true);
        t.setFocusable(true);
        int s = dp(c, 38);
        t.setLayoutParams(new LinearLayout.LayoutParams(s, s, 0f));
        return t;
    }

    /** 状态胶囊：如「运行中」「待接入」。 */
    public static TextView pill(Context c, CharSequence label, int fg, int bg) {
        TextView t = text(c, label, 10.5f, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setBackground(round(c, bg, 20));
        t.setPadding(dp(c, 9), dp(c, 3), dp(c, 9), dp(c, 3));
        t.setLayoutParams(new LinearLayout.LayoutParams(WC, WC, 0f));
        return t;
    }

    /** 小圆点（状态指示）。 */
    public static View dot(Context c, int fill, float sizeDp) {
        View v = new View(c);
        v.setBackground(circle(c, fill));
        int s = dp(c, sizeDp);
        v.setLayoutParams(new LinearLayout.LayoutParams(s, s, 0f));
        return v;
    }

    // ---- 布局 ----

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(MP, WC));
        return l;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(MP, WC));
        return l;
    }

    public static LinearLayout card(Context c) {
        return card(c, 16f, color(CARD), 20f);
    }

    public static LinearLayout card(Context c, float padDp, int fill, float radiusDp) {
        LinearLayout l = column(c);
        l.setBackground(round(c, fill, radiusDp));
        int p = dp(c, padDp);
        l.setPadding(p, p, p, p);
        return l;
    }

    /** 横向留白用。 */
    public static View spacer(Context c, float hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(MP, dp(c, hDp), 0f));
        return v;
    }

    public static View stretch(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return v;
    }

    /** 区块头：标题 + 可选右侧说明。 */
    public static LinearLayout sectionHeader(Context c, CharSequence title, CharSequence trailing) {
        LinearLayout r = row(c);
        TextView t = heading(c, title);
        r.addView(t, new LinearLayout.LayoutParams(WC, WC, 0f));
        r.addView(stretch(c));
        if (trailing != null) {
            TextView tr = caption(c, trailing);
            r.addView(tr, new LinearLayout.LayoutParams(WC, WC, 0f));
        }
        return r;
    }

    /** 给 View 设置 dp 外边距（保留原有宽高/权重）。 */
    public static <T extends View> T margins(Context c, T v, float l, float t, float r, float b) {
        ViewGroup.LayoutParams p = v.getLayoutParams();
        LinearLayout.LayoutParams lp;
        if (p instanceof LinearLayout.LayoutParams) {
            lp = (LinearLayout.LayoutParams) p;
        } else {
            lp = new LinearLayout.LayoutParams(p == null ? WC : p.width,
                    p == null ? WC : p.height);
        }
        lp.setMargins(dp(c, l), dp(c, t), dp(c, r), dp(c, b));
        v.setLayoutParams(lp);
        return v;
    }

    /** 列表项的通用横向布局参数。 */
    public static LinearLayout.LayoutParams lp(Context c, int width, int height, float weight) {
        return new LinearLayout.LayoutParams(width, height, weight);
    }

    public static int match() {
        return MP;
    }

    public static int wrap() {
        return WC;
    }
}
