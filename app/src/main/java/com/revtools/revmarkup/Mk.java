package com.revtools.revmarkup;

import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * One markup. Field meaning and the "am1|..." text format are identical to the desktop
 * RevMarkup (Model.cs), so markups stay editable when the PDF goes back to the PC.
 * Coordinates: display points of the sheet, origin top-left, y down.
 */
final class Mk {
    // Kind numbers = desktop enum order. Do not change.
    static final int PEN = 0, HIGHLIGHT = 1, TEXT = 2, ARROW = 3, BOX = 4, CLOUD = 5, PIN = 6, ELLIPSE = 7;
    static final int NOBOX = 2;

    int k;
    int page;
    String color = "#E53935";
    int size = 1;
    double w = 2, font = 12, bump = 14;
    int seq;
    float[] p = new float[0];       // x0,y0,x1,y1,...
    String text = "";
    int heads = 1;
    String fill = "";
    int fillA = 30;
    double textW;
    int flags;

    static final double[] PEN_W = {1.2, 2.2, 3.6};
    static final double[] HL_W = {8, 14, 22};
    static final double[] FONT = {9, 12, 16};
    static final double[] BUMP = {10, 14, 19};

    static double unit(float pageW, float pageH) { return Math.max(pageW, pageH) / 1190.55; }

    void applySize(double unit) {
        w = (k == HIGHLIGHT ? HL_W[size] : PEN_W[size]) * unit;
        font = FONT[size] * unit;
        bump = BUMP[size] * unit;
    }

    int n() { return p.length / 2; }
    float x(int i) { return p[2 * i]; }
    float y(int i) { return p[2 * i + 1]; }

    Mk copy() {
        Mk m = new Mk();
        m.k = k; m.page = page; m.color = color; m.size = size; m.w = w; m.font = font; m.bump = bump;
        m.seq = seq; m.p = p.clone(); m.text = text; m.heads = heads; m.fill = fill; m.fillA = fillA;
        m.textW = textW; m.flags = flags;
        return m;
    }

    Mk moved(float dx, float dy) {
        Mk m = copy();
        for (int i = 0; i < m.p.length; i += 2) { m.p[i] += dx; m.p[i + 1] += dy; }
        return m;
    }

    int argb() { return 0xFF000000 | Integer.parseInt(color.substring(1), 16); }

    /** Markups this app can draw and edit itself. Others stay inside the PDF untouched. */
    boolean editableHere() {
        switch (k) {
            case PEN: case HIGHLIGHT: case CLOUD: return n() >= 1;
            case BOX: case ELLIPSE: return n() >= 2 && fill.isEmpty();
            case ARROW: return n() == 2;
            case TEXT: return n() == 1 && textW == 0;
            default: return false;
        }
    }

    // ------------------------------------------------------------ text format
    String serialize() {
        StringBuilder sb = new StringBuilder("am1|");
        sb.append(k).append('|').append(page).append('|').append(color).append('|').append(size).append('|')
          .append(num(w)).append('|').append(num(font)).append('|').append(num(bump)).append('|').append(seq).append('|');
        for (int i = 0; i < n(); i++) {
            if (i > 0) sb.append(';');
            sb.append(num3(x(i))).append(',').append(num3(y(i)));
        }
        sb.append('|').append(Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)));
        sb.append('|').append(heads).append('|').append(fill).append('|').append(fillA)
          .append('|').append(num3(textW)).append('|').append(flags);
        return sb.toString();
    }

    static Mk parse(String s) {
        try {
            String[] f = s.split("\\|", -1);
            if (f.length < 11 || !f[0].equals("am1")) return null;
            Mk m = new Mk();
            m.k = Integer.parseInt(f[1]);
            if (m.k < 0 || m.k > 9) return null;
            m.page = Integer.parseInt(f[2]);
            m.color = isHex(f[3]) ? f[3].toUpperCase(Locale.ROOT) : "#E53935";
            m.size = Math.max(0, Math.min(2, Integer.parseInt(f[4])));
            m.w = clamp(f[5], 2, 0.05, 1000);
            m.font = clamp(f[6], 12, 0.5, 1000);
            m.bump = clamp(f[7], 14, 1, 1000);
            m.seq = Integer.parseInt(f[8]);
            if (!f[9].isEmpty()) {
                String[] pts = f[9].split(";");
                if (pts.length > 200000) return null;
                m.p = new float[pts.length * 2];
                for (int i = 0; i < pts.length; i++) {
                    String[] xy = pts[i].split(",");
                    double x = Double.parseDouble(xy[0]), y = Double.parseDouble(xy[1]);
                    if (!fin(x) || !fin(y) || Math.abs(x) > 1e6 || Math.abs(y) > 1e6) return null;
                    m.p[2 * i] = (float) x; m.p[2 * i + 1] = (float) y;
                }
            }
            if (f[10].length() > 40 * 1024 * 1024) return null;
            m.text = new String(Base64.getDecoder().decode(f[10]), StandardCharsets.UTF_8);
            if (f.length >= 16) {
                m.heads = Math.max(0, Math.min(2, Integer.parseInt(f[11])));
                m.fill = isHex(f[12]) ? f[12] : "";
                m.fillA = Math.max(0, Math.min(100, Integer.parseInt(f[13])));
                m.textW = clamp(f[14], 0, 0, 1e5);
                m.flags = Integer.parseInt(f[15]);
            }
            return m;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String num(double v) {
        return new BigDecimal(Double.toString(v)).stripTrailingZeros().toPlainString();
    }

    private static String num3(double v) {
        String s = new BigDecimal(v).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return s.equals("-0") ? "0" : s;
    }

    private static boolean fin(double v) { return !Double.isNaN(v) && !Double.isInfinite(v); }

    private static double clamp(String s, double def, double min, double max) {
        double v = Double.parseDouble(s);
        if (!fin(v)) return def;
        return Math.max(min, Math.min(max, v));
    }

    static boolean isHex(String c) {
        if (c == null || c.length() != 7 || c.charAt(0) != '#') return false;
        for (int i = 1; i < 7; i++) if (Character.digit(c.charAt(i), 16) < 0) return false;
        return true;
    }

    // ------------------------------------------------------------ geometry
    /** One polyline of a shape (display points). */
    static final class Sub {
        final float[] pts; final boolean closed, fill;
        Sub(float[] pts, boolean closed, boolean fill) { this.pts = pts; this.closed = closed; this.fill = fill; }
    }

    List<Sub> build() {
        List<Sub> out = new ArrayList<>();
        switch (k) {
            case PEN: case HIGHLIGHT:
                out.add(new Sub(p.length == 2 ? new float[]{p[0], p[1], p[0] + 0.01f, p[1] + 0.01f} : p, false, false));
                break;
            case BOX: {
                float x0 = Math.min(x(0), x(1)), x1 = Math.max(x(0), x(1)), y0 = Math.min(y(0), y(1)), y1 = Math.max(y(0), y(1));
                out.add(new Sub(new float[]{x0, y0, x1, y0, x1, y1, x0, y1}, true, false));
                break;
            }
            case ELLIPSE: {
                float cx = (x(0) + x(1)) / 2, cy = (y(0) + y(1)) / 2, rx = Math.abs(x(1) - x(0)) / 2, ry = Math.abs(y(1) - y(0)) / 2;
                int n = 72;
                float[] e = new float[2 * n];
                for (int i = 0; i < n; i++) {
                    double a = 2 * Math.PI * i / n;
                    e[2 * i] = (float) (cx + rx * Math.cos(a)); e[2 * i + 1] = (float) (cy + ry * Math.sin(a));
                }
                out.add(new Sub(e, true, false));
                break;
            }
            case CLOUD: out.add(cloud()); break;
            case ARROW: arrow(out); break;
            default: break;
        }
        return out;
    }

    private Sub cloud() {
        float x0 = Math.min(x(0), x(1)), x1 = Math.max(x(0), x(1)), y0 = Math.min(y(0), y(1)), y1 = Math.max(y(0), y(1));
        float[][] c = {{x0, y0}, {x1, y0}, {x1, y1}, {x0, y1}, {x0, y0}};
        FloatList out = new FloatList();
        out.add(x0, y0);
        for (int e = 0; e < 4; e++) {
            float ax = c[e][0], ay = c[e][1], bx = c[e + 1][0], by = c[e + 1][1];
            double L = Math.hypot(bx - ax, by - ay);
            if (L < 1e-6) continue;
            int n = (int) Math.max(1, Math.min(2000, Math.round(L / Math.max(bump, 1e-6))));
            double px = ax, py = ay;
            for (int i = 1; i <= n; i++) {
                double nx = ax + (bx - ax) * i / (double) n, ny = ay + (by - ay) * i / (double) n;
                arcOut(out, px, py, nx, ny);
                px = nx; py = ny;
            }
        }
        return new Sub(out.toArray(), true, false);
    }

    /** Arc from a to b, radius = chord*0.55, bulging outwards (left of travel on screen), like the desktop. */
    private static void arcOut(FloatList out, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay, c = Math.hypot(dx, dy);
        if (c < 1e-9) return;
        double r = c * 0.55, h = c / 2, ux = dx / c, uy = dy / c;
        double ox = uy, oy = -ux;                       // outward normal
        double dist = Math.sqrt(Math.max(r * r - h * h, 0));
        double cx = ax + dx / 2 - ox * dist, cy = ay + dy / 2 - oy * dist;
        double a0 = Math.atan2(ay - cy, ax - cx), a1 = Math.atan2(by - cy, bx - cx);
        double top = Math.atan2(oy, ox);
        double sweep = norm(a1 - a0);
        // go the way that passes the outward point
        double toTop = norm(top - a0);
        if (sweep > 0 ? !(toTop > 0 && toTop < sweep) : !(toTop < 0 && toTop > sweep))
            sweep = sweep > 0 ? sweep - 2 * Math.PI : sweep + 2 * Math.PI;
        int steps = 10;
        for (int i = 1; i <= steps; i++) {
            double a = a0 + sweep * i / steps;
            out.add((float) (cx + r * Math.cos(a)), (float) (cy + r * Math.sin(a)));
        }
    }

    private static double norm(double x) {
        while (x <= -Math.PI) x += 2 * Math.PI;
        while (x > Math.PI) x -= 2 * Math.PI;
        return x;
    }

    private void arrow(List<Sub> out) {
        float ax = x(0), ay = y(0), bx = x(1), by = y(1);
        double L = Math.max(Math.hypot(bx - ax, by - ay), 1e-6), ux = (bx - ax) / L, uy = (by - ay) / L;
        double hl = Math.min(w * 5.0, L * (heads == 2 ? 0.4 : 0.6)), hw = hl * 0.55;
        double ex = heads >= 1 ? bx - ux * hl : bx, ey = heads >= 1 ? by - uy * hl : by;
        double sx = heads == 2 ? ax + ux * hl : ax, sy = heads == 2 ? ay + uy * hl : ay;
        out.add(new Sub(new float[]{(float) sx, (float) sy, (float) ex, (float) ey}, false, false));
        if (heads >= 1) out.add(head(bx, by, ux, uy, hl, hw));
        if (heads == 2) out.add(head(ax, ay, -ux, -uy, hl, hw));
    }

    private static Sub head(double tx, double ty, double ux, double uy, double hl, double hw) {
        double bx = tx - ux * hl, by = ty - uy * hl, px = -uy * hw / 2, py = ux * hw / 2;
        return new Sub(new float[]{(float) tx, (float) ty, (float) (bx + px), (float) (by + py), (float) (bx - px), (float) (by - py)}, true, true);
    }

    // ------------------------------------------------------------ text layout
    private static final Paint TP = new Paint(Paint.ANTI_ALIAS_FLAG);
    static { TP.setTypeface(Typeface.SANS_SERIF); }

    double padX() { return font * 0.5; }
    double padY() { return font * 0.3; }
    boolean noBox() { return k == TEXT && (flags & NOBOX) != 0; }
    double lineH() { return font * 1.2; }
    double frameW() { return Math.max(0.5, font * 0.08); }
    String[] lines() { return text.replace("\r", "").split("\n", -1); }

    /** Text box in display points: [x0,y0,x1,y1]. */
    RectF textBox() {
        synchronized (TP) {
            TP.setTextSize((float) font);
            float wmax = 0;
            for (String l : lines()) wmax = Math.max(wmax, TP.measureText(l));
            // Helvetica in the PDF is a little wider than the screen font: keep some room
            float wBox = (float) (wmax * 1.06 + 2 * padX()), hBox = (float) (lines().length * lineH() + 2 * padY());
            return new RectF(x(0), y(0), x(0) + wBox, y(0) + hBox);
        }
    }

    /** Bounds in display points incl. line width. */
    RectF bounds() {
        if (k == TEXT) return textBox();
        RectF r = new RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        for (Sub s : build()) for (int i = 0; i < s.pts.length; i += 2) r.union(s.pts[i], s.pts[i + 1]);
        float pad = (float) (w / 2 + (k == CLOUD ? bump * 0.3 : 0));
        r.inset(-pad, -pad);
        return r;
    }

    /** Hit test, tol in display points. */
    boolean hit(float qx, float qy, float tol) {
        if (k == TEXT) { RectF b = textBox(); b.inset(-tol, -tol); return b.contains(qx, qy); }
        float t = (float) (tol + w / 2 + (k == CLOUD ? bump * 0.35 : 0));
        for (Sub s : build()) {
            float[] a = s.pts;
            int n = a.length / 2;
            for (int i = 0; i + 1 < n; i++) if (segDist(qx, qy, a[2 * i], a[2 * i + 1], a[2 * i + 2], a[2 * i + 3]) <= t) return true;
            if (s.closed && n > 2 && segDist(qx, qy, a[2 * n - 2], a[2 * n - 1], a[0], a[1]) <= t) return true;
            if (n == 1 && Math.hypot(qx - a[0], qy - a[1]) <= t) return true;
        }
        return false;
    }

    static float segDist(float px, float py, float ax, float ay, float bx, float by) {
        float dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        float t = l2 < 1e-9f ? 0 : Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2));
        return (float) Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
    }

    static final class FloatList {
        float[] a = new float[64]; int n;
        void add(float x, float y) {
            if (n + 2 > a.length) a = java.util.Arrays.copyOf(a, a.length * 2);
            a[n++] = x; a[n++] = y;
        }
        float[] toArray() { return java.util.Arrays.copyOf(a, n); }
    }
}
