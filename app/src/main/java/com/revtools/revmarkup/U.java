package com.revtools.revmarkup;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** Shared look (v2 light theme) and small view helpers. */
final class U {
    static final int INK = 0xFF1A1B1F, INK2 = 0xFF3B3F47, MUTED = 0xFF5B616E, FAINT = 0xFF9AA0AA,
            LINE = 0xFFE3E5E9, LINE2 = 0xFFEEF0F2, BG = 0xFFF3F4F6, CANVAS = 0xFFE6E7EA, ACTIVE = 0xFFEEF1F6,
            ACCENT = 0xFFC8262E, BLUE = 0xFF1F5FBF, BLUE_BG = 0xFFE8EFFB, GREEN = 0xFF1E8A4C,
            DARK = 0xFF1C1D21, DARK_SEL = 0xFF2E3036, DARK_TEXT = 0xFFE8E9EC, DARK_MUTED = 0xFFA3A7B0, DARK_LINE = 0xFF4A4D55;

    static float dp = 1;

    static int px(float v) { return Math.round(v * dp); }

    static GradientDrawable round(int color, float rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(px(rDp));
        return g;
    }

    static GradientDrawable round(int color, float rDp, int stroke, float strokeDp) {
        GradientDrawable g = round(color, rDp);
        g.setStroke(Math.max(1, px(strokeDp)), stroke);
        return g;
    }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(bold ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.SANS_SERIF);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        return t;
    }

    /** Floating white card with a soft shadow. */
    static void card(View v, float rDp, float elevDp) {
        v.setBackground(round(Color.WHITE, rDp));
        v.setElevation(px(elevDp));
    }

    static LinearLayout.LayoutParams lp(int w, int h) { return new LinearLayout.LayoutParams(w, h); }

    static LinearLayout.LayoutParams lp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    static View divider(Context c, boolean vertical, int color) {
        View d = new View(c);
        d.setBackgroundColor(color);
        d.setLayoutParams(vertical ? lp(px(1), px(30), px(4), 0, px(4), 0) : lp(LinearLayout.LayoutParams.MATCH_PARENT, px(1)));
        return d;
    }

    /** Pill-shaped text button. */
    static TextView pill(Context c, String s, int fg, int bg, View.OnClickListener l) {
        TextView t = text(c, s, 14, fg, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(14), 0, px(14), 0);
        t.setBackground(round(bg, 12));
        t.setOnClickListener(l);
        t.setMinHeight(px(40));
        return t;
    }

    /** "Today 10:42", "Yesterday", "18 Nov", "18 Nov 2025". */
    static String when(long ms) {
        if (ms <= 0) return "";
        Calendar now = Calendar.getInstance(), c = Calendar.getInstance();
        c.setTimeInMillis(ms);
        int days = now.get(Calendar.YEAR) == c.get(Calendar.YEAR) ? now.get(Calendar.DAY_OF_YEAR) - c.get(Calendar.DAY_OF_YEAR) : 999;
        if (days == 0) return "Today " + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(ms));
        if (days == 1) return "Yesterday";
        return new SimpleDateFormat(days < 999 && now.get(Calendar.YEAR) == c.get(Calendar.YEAR) ? "d MMM" : "d MMM yyyy",
                Locale.getDefault()).format(new Date(ms));
    }

    static String size(long b) {
        if (b < 0) return "—";
        if (b < 1024 * 1024) return Math.max(1, b / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
    }

    static int parse(String hex, int fallback) {
        try { return Color.parseColor(hex); } catch (Exception e) { return fallback; }
    }

    /** Round colour / size / "+" button used in the settings row. */
    static final class Swatch extends View {
        static final int COLOR = 0, PLUS = 1, SIZE = 2;
        final int kind;
        int color; float dot; boolean on;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Swatch(Context c, int kind) {
            super(c);
            this.kind = kind;
            setClickable(true);
            setFocusable(true);
        }

        @Override protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            p.setStyle(Paint.Style.FILL);
            if (kind == COLOR) {
                if (on) {
                    p.setColor(BLUE); c.drawCircle(cx, cy, px(17), p);
                    p.setColor(Color.WHITE); c.drawCircle(cx, cy, px(15), p);
                    p.setColor(color); c.drawCircle(cx, cy, px(12), p);
                } else {
                    p.setColor(color); c.drawCircle(cx, cy, px(15), p);
                    if ((color & 0xFFFFFF) > 0xF0F0F0) { p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(px(1)); p.setColor(LINE); c.drawCircle(cx, cy, px(15), p); }
                }
            } else if (kind == PLUS) {
                p.setColor(ACTIVE); c.drawCircle(cx, cy, px(15), p);
                p.setColor(INK2); p.setStrokeWidth(px(1.8f)); p.setStyle(Paint.Style.STROKE);
                c.drawLine(cx - px(6), cy, cx + px(6), cy, p); c.drawLine(cx, cy - px(6), cx, cy + px(6), p);
            } else {
                if (on) {
                    p.setColor(Color.WHITE); c.drawCircle(cx, cy, px(17), p);
                    p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(px(2)); p.setColor(BLUE); c.drawCircle(cx, cy, px(16), p);
                    p.setStyle(Paint.Style.FILL);
                } else { p.setColor(ACTIVE); c.drawCircle(cx, cy, px(17), p); }
                p.setColor(color != 0 ? color : INK2);
                c.drawCircle(cx, cy, Math.max(px(1.5f), px(dot) / 2f), p);
            }
        }
    }
}
