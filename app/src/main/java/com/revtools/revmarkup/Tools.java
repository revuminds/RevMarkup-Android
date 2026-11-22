package com.revtools.revmarkup;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** The v2 tools and their own remembered settings (colour presets, size, line style, type ...). */
final class Tools {
    static final int PEN = 0, FINE = 1, PENCIL = 2, HL = 3, ERASER = 4, TEXT = 5, NOTE = 6, SHAPES = 7, CLOUD = 8, LASSO = 9, N = 10;
    static final String[] NAMES = {"Pen", "Fineliner", "Pencil", "Highlighter", "Eraser", "Text", "Comment note",
            "Shapes", "Revision cloud", "Lasso select"};
    static final int[] ICONS = {Icon.PEN, Icon.FINE, Icon.PENCIL, Icon.HIGHLIGHT, Icon.ERASER, Icon.TEXT, Icon.NOTE,
            Icon.SHAPES, Icon.CLOUD, Icon.LASSO};

    static final String[] SHAPE_NAMES = {"Rectangle", "Rounded rectangle", "Ellipse", "Circle", "Line", "Arrow",
            "Multi-point line", "Multi-point arrow"};
    static final int[] SHAPE_ICONS = {Icon.BOX, Icon.ROUNDBOX, Icon.ELLIPSE, Icon.CIRCLE, Icon.LINE, Icon.ARROW, Icon.POLYLINE, Icon.POLYARROW};
    static final String[] CLOUD_NAMES = {"Rectangle", "Ellipse", "Freehand"};
    static final String[] LINE_NAMES = {"Variable", "Fixed", "Dashed", "Dotted"};
    static final int LINE_VARIABLE = 0, LINE_FIXED = 1;
    static final float[] ERASER_DP = {5, 10, 20};

    static final String[] PALETTE = {"#D3262F", "#E8711A", "#F5D400", "#1E8A4C", "#4CD964", "#00A3A3",
            "#1F5FBF", "#7A3EC8", "#FF5FA2", "#8B5A2B", "#4A4F59", "#1A1B1F"};

    static final class S {
        String[] colors = new String[3];
        int colorSel;          // 0..2 preset, 3 = custom
        String custom = "";
        int size = 1;          // 0..2
        int line = LINE_FIXED; // line style (drawn from the next update; kept already)
        float stab = 0.3f;     // stabilization 0..1 (next update)
        boolean snap = true;   // hold to snap shape (next update)
        int type;              // shape type / cloud type / highlighter: 1 = straight
        boolean border;        // text box border
        int erase;             // 0 whole stroke, 1 partial (later)

        String color() {
            if (colorSel == 3 && !custom.isEmpty()) return custom;
            return colors[Math.max(0, Math.min(2, colorSel))];
        }

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("colors", new JSONArray(java.util.Arrays.asList(colors)));
            o.put("colorSel", colorSel).put("custom", custom).put("size", size).put("line", line).put("stab", (double) stab)
                    .put("snap", snap).put("type", type).put("border", border).put("erase", erase);
            return o;
        }

        void read(JSONObject o) {
            JSONArray a = o.optJSONArray("colors");
            if (a != null) for (int i = 0; i < 3 && i < a.length(); i++) { String c = a.optString(i, ""); if (valid(c)) colors[i] = c; }
            colorSel = clamp(o.optInt("colorSel", colorSel), 0, 3);
            String cu = o.optString("custom", "");
            custom = valid(cu) ? cu : "";
            if (colorSel == 3 && custom.isEmpty()) colorSel = 0;
            size = clamp(o.optInt("size", size), 0, 2);
            line = clamp(o.optInt("line", line), 0, 3);
            stab = (float) Math.max(0, Math.min(1, o.optDouble("stab", stab)));
            snap = o.optBoolean("snap", snap);
            type = clamp(o.optInt("type", type), 0, 7);
            border = o.optBoolean("border", border);
            erase = clamp(o.optInt("erase", erase), 0, 1);
        }
    }

    static boolean valid(String c) { return c != null && c.matches("#[0-9A-Fa-f]{6}"); }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    static S defaults(int t) {
        S s = new S();
        String[] c;
        switch (t) {
            case PENCIL: c = new String[]{"#4A4F59", "#D3262F", "#1F5FBF"}; s.size = 0; break;
            case FINE: c = new String[]{"#1A1B1F", "#D3262F", "#1F5FBF"}; s.size = 0; break;
            case HL: c = new String[]{"#F5D400", "#4CD964", "#FF5FA2"}; break;
            case TEXT: c = new String[]{"#D3262F", "#1F5FBF", "#1A1B1F"}; break;
            case NOTE: c = new String[]{"#D3262F", "#1F5FBF", "#1A1B1F"}; s.border = true; break;
            case CLOUD: case SHAPES: c = new String[]{"#D3262F", "#1F5FBF", "#1E8A4C"}; break;
            default: c = new String[]{"#D3262F", "#1F5FBF", "#1A1B1F"};
        }
        s.colors = c;
        if (t == SHAPES) s.type = 5;   // arrow: the most used shape when marking up drawings
        return s;
    }

    final S[] s = new S[N];
    int current;
    private final SharedPreferences prefs;

    Tools(SharedPreferences prefs) {
        this.prefs = prefs;
        for (int t = 0; t < N; t++) {
            s[t] = defaults(t);
            String j = prefs.getString("ts" + t, null);
            if (j != null) try { s[t].read(new JSONObject(j)); } catch (Exception ignored) { /* keep defaults */ }
        }
        current = clamp(prefs.getInt("tool2", PEN), 0, N - 1);
    }

    S cur() { return s[current]; }

    void save(int t) {
        try { prefs.edit().putString("ts" + t, s[t].json().toString()).apply(); } catch (Exception ignored) { }
    }

    void select(int t) {
        current = t;
        prefs.edit().putInt("tool2", t).apply();
    }

    static boolean penLike(int t) { return t == PEN || t == FINE || t == PENCIL; }

    /** Hand the current tool's settings to the sheet view. */
    void apply(PageView v) {
        v.finishPoly();
        S st = cur();
        v.color = st.color();
        v.sizeIdx = st.size;
        v.textNoBox = false;
        switch (current) {
            case PEN: case FINE: case PENCIL: v.tool = PageView.PEN; v.variant[PageView.PEN] = 0; break;
            case HL: v.tool = PageView.HIGHLIGHT; v.variant[PageView.HIGHLIGHT] = st.type == 1 ? 1 : 0; break;
            case ERASER: v.tool = PageView.ERASER; v.eraserDp = ERASER_DP[st.size]; break;
            case TEXT: case NOTE: v.tool = PageView.TEXT; v.textNoBox = !st.border; break;
            case SHAPES: {
                int ty = st.type;
                v.tool = ty <= 1 ? PageView.BOX : ty <= 3 ? PageView.ELLIPSE : (ty == 4 || ty == 6) ? PageView.LINE : PageView.ARROW;
                v.variant[v.tool] = ty <= 3 ? ty % 2 : ty >= 6 ? 1 : 0;
                break;
            }
            case CLOUD: v.tool = PageView.CLOUD; v.variant[PageView.CLOUD] = Math.min(2, st.type); break;
            default: v.tool = PageView.SELECT;
        }
    }

    /** Exact size label for the size presets, on the current sheet. */
    static String sizeLabel(int t, int size, double unit) {
        if (t == TEXT || t == NOTE) return String.format(Locale.US, "%.0f pt", Mk.FONT[size] * unit);
        if (t == ERASER) return "";
        double w = (t == HL ? Mk.HL_W : Mk.PEN_W)[size] * unit * 25.4 / 72;
        return String.format(Locale.US, w < 1 ? "%.2f mm" : "%.1f mm", w);
    }

    /** Dot diameter (dp) drawn in a size preset. */
    static float sizeDot(int t, int size) {
        if (t == ERASER) return new float[]{6, 11, 18}[size];
        if (t == HL) return new float[]{8, 12, 17}[size];
        return new float[]{5, 10, 16}[size];
    }
}
