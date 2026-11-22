package com.revtools.revmarkup;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small line icons drawn in code (24-unit grid): no image files, no licences needed. */
final class Icon extends View {
    static final int SELECT = 0, PEN = 1, HIGHLIGHT = 2, TEXT = 3, BOX = 4, ELLIPSE = 5, CLOUD = 6, ARROW = 7,
            LINE = 8, ERASER = 9, UNDO = 20, REDO = 21, OPEN = 22, SAVE = 23, SHARE = 24, DELETE = 25, MORE = 26,
            PREV = 27, NEXT = 28,
            FINE = 30, PENCIL = 31, NOTE = 32, SHAPES = 33, LASSO = 34, BACK = 35, LIST = 36, SHEETS = 37,
            RECENT = 38, TABLET = 39, DRIVE = 40, PLUS = 41, SETTINGS = 42, SEARCH = 43, FILE = 44, CHEVRON = 45,
            ROUNDBOX = 46, CIRCLE = 47, POLYLINE = 48, POLYARROW = 49, FOLDER = 50;

    int id;
    boolean active;
    int color = U.INK2, activeBg = U.ACTIVE, activeColor = U.INK;
    int bar;            // colour bar under the glyph when active (0 = none)
    float glyph = 0.46f; // glyph size relative to the view
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    Icon(Context c, int id) {
        super(c);
        this.id = id;
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        setClickable(true);
        setFocusable(true);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h);
        if (active && activeBg != 0) {
            bg.setColor(activeBg);
            r.set(w / 2 - s / 2, h / 2 - s / 2, w / 2 + s / 2, h / 2 + s / 2);
            c.drawRoundRect(r, s * 0.25f, s * 0.25f, bg);
        }
        boolean withBar = active && bar != 0;
        float u = s * glyph / 24f;
        float cy = withBar ? h / 2 - s * 0.07f : h / 2;
        c.save();
        c.translate(w / 2 - 12 * u, cy - 12 * u);
        c.scale(u, u);
        int col = active ? activeColor : color;
        if (!isEnabled()) col = (col & 0x00FFFFFF) | 0x55000000;
        p.setColor(col);
        p.setStrokeWidth(1.8f);
        p.setStyle(Paint.Style.STROKE);
        p.setPathEffect(null);
        glyphs(c, id);
        c.restore();
        if (withBar) {
            bg.setColor(bar);
            float bw = s * 0.19f, bh = s * 0.065f, y = cy + 12 * u + s * 0.06f;
            r.set(w / 2 - bw, y, w / 2 + bw, y + bh);
            c.drawRoundRect(r, bh / 2, bh / 2, bg);
        }
    }

    private void line(Canvas c, float... xy) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        c.drawPath(path, p);
    }

    private void dot(Canvas c, float x, float y, float rad) {
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(x, y, rad, p);
        p.setStyle(Paint.Style.STROKE);
    }

    private void cloud(Canvas c) {
        path.reset();
        path.moveTo(6.5f, 19);
        path.cubicTo(1.5f, 19, 1.5f, 10.5f, 6.1f, 10);
        path.cubicTo(7, 3.5f, 16.5f, 3, 17.6f, 8.6f);
        path.cubicTo(23.5f, 9, 23.5f, 19, 18, 19);
        path.close();
        c.drawPath(path, p);
    }

    private void glyphs(Canvas c, int g) {
        switch (g) {
            case SELECT: line(c, 6, 3, 6, 19, 10, 15, 13, 21, 15.5f, 20, 12.5f, 14, 18, 14, 6, 3); break;
            case PEN: line(c, 4, 20, 5, 16, 16, 5, 19, 8, 8, 19, 4, 20); line(c, 14, 7, 17, 10); break;
            case FINE: line(c, 15, 4, 20, 9, 10, 19, 5, 19, 5, 14, 15, 4); line(c, 5, 19, 7.5f, 16.5f); break;
            case PENCIL: line(c, 4, 20, 5.5f, 15, 16, 4.5f, 19.5f, 8, 9, 18.5f, 4, 20); line(c, 13.5f, 7, 17, 10.5f);
                line(c, 5.5f, 15, 9, 18.5f); break;
            case HIGHLIGHT: line(c, 9, 14, 15, 4, 19, 6.5f, 13, 16.5f, 9, 14); line(c, 9, 14, 8, 18, 11, 19.5f, 13, 16.5f);
                line(c, 4, 21, 13, 21); break;
            case TEXT: line(c, 5, 7, 5, 5, 19, 5, 19, 7); line(c, 12, 5, 12, 19); line(c, 9, 19, 15, 19); break;
            case NOTE: line(c, 4, 5, 20, 5, 20, 16, 10, 16, 6, 20, 6, 16, 4, 16, 4, 5); line(c, 8, 9, 16, 9); line(c, 8, 12, 13, 12); break;
            case SHAPES: r.set(3, 11, 12, 20); c.drawRoundRect(r, 1, 1, p); c.drawCircle(16, 8, 5, p); break;
            case BOX: r.set(4, 6, 20, 18); c.drawRect(r, p); break;
            case ROUNDBOX: r.set(4, 6, 20, 18); c.drawRoundRect(r, 3.5f, 3.5f, p); break;
            case ELLIPSE: r.set(3, 6, 21, 18); c.drawOval(r, p); break;
            case CIRCLE: c.drawCircle(12, 12, 8, p); break;
            case CLOUD: cloud(c); break;
            case ARROW: line(c, 5, 19, 19, 5); line(c, 11, 5, 19, 5, 19, 13); break;
            case LINE: line(c, 5, 19, 19, 5); break;
            case POLYLINE: line(c, 3, 19, 9, 8, 15, 15, 21, 5); break;
            case POLYARROW: line(c, 3, 19, 9, 8, 15, 15, 21, 5); line(c, 15.5f, 5.5f, 21, 5, 21, 10.5f); break;
            case LASSO: p.setPathEffect(new DashPathEffect(new float[]{3, 2.5f}, 0)); r.set(4, 5, 20, 19);
                c.drawRoundRect(r, 1.5f, 1.5f, p); p.setPathEffect(null); break;
            case ERASER: line(c, 8, 20, 20, 20); line(c, 4.5f, 15.5f, 13.5f, 6.5f, 16.3f, 6.5f, 18.5f, 8.7f, 18.5f, 11.5f, 11, 19, 8, 19, 4.5f, 15.5f);
                line(c, 9, 11, 14, 16); break;
            case UNDO: line(c, 9, 7, 4, 12, 9, 17); path.reset(); path.moveTo(4, 12); path.lineTo(14, 12);
                path.cubicTo(21, 12, 21, 20, 14, 20); c.drawPath(path, p); break;
            case REDO: line(c, 15, 7, 20, 12, 15, 17); path.reset(); path.moveTo(20, 12); path.lineTo(10, 12);
                path.cubicTo(3, 12, 3, 20, 10, 20); c.drawPath(path, p); break;
            case OPEN: case FOLDER: line(c, 3, 19, 3, 5, 9, 5, 11, 7, 21, 7, 21, 19, 3, 19); break;
            case SAVE: line(c, 5, 4, 16, 4, 19, 7, 19, 20, 5, 20, 5, 4); line(c, 8, 4, 8, 9, 15, 9, 15, 4); line(c, 8, 20, 8, 14, 16, 14, 16, 20); break;
            case SHARE: c.drawCircle(18, 5, 2.5f, p); c.drawCircle(6, 12, 2.5f, p); c.drawCircle(18, 19, 2.5f, p);
                line(c, 8.2f, 10.8f, 15.8f, 6.4f); line(c, 8.2f, 13.2f, 15.8f, 17.6f); break;
            case DELETE: line(c, 4, 6, 20, 6); line(c, 9, 6, 9, 3, 15, 3, 15, 6); line(c, 6, 6, 7, 21, 17, 21, 18, 6);
                line(c, 10, 10, 10, 17); line(c, 14, 10, 14, 17); break;
            case MORE: dot(c, 12, 5, 1.8f); dot(c, 12, 12, 1.8f); dot(c, 12, 19, 1.8f); break;
            case PREV: line(c, 15, 5, 8, 12, 15, 19); break;
            case NEXT: line(c, 9, 5, 16, 12, 9, 19); break;
            case BACK: line(c, 19, 12, 5, 12); line(c, 11, 6, 5, 12, 11, 18); break;
            case LIST: line(c, 8, 6, 20, 6); line(c, 8, 12, 20, 12); line(c, 8, 18, 20, 18);
                dot(c, 4, 6, 1.1f); dot(c, 4, 12, 1.1f); dot(c, 4, 18, 1.1f); break;
            case SHEETS: r.set(7, 3, 20, 18); c.drawRoundRect(r, 1.5f, 1.5f, p); line(c, 4, 7, 4, 20, 16, 20); break;
            case RECENT: c.drawCircle(12, 12, 9, p); line(c, 12, 7, 12, 12, 15, 14); break;
            case TABLET: r.set(5, 2.5f, 19, 21.5f); c.drawRoundRect(r, 2, 2, p); line(c, 10, 18.5f, 14, 18.5f); break;
            case DRIVE: line(c, 8.5f, 3, 15.5f, 3, 21.5f, 13.5f, 18, 19.5f, 6, 19.5f, 2.5f, 13.5f, 8.5f, 3);
                line(c, 8.5f, 3, 14.5f, 13.5f); line(c, 15.5f, 3, 9.5f, 13.5f); line(c, 2.5f, 13.5f, 21.5f, 13.5f); break;
            case PLUS: line(c, 12, 5, 12, 19); line(c, 5, 12, 19, 12); break;
            case SETTINGS: c.drawCircle(12, 12, 3, p); line(c, 12, 2.5f, 12, 5.5f); line(c, 12, 18.5f, 12, 21.5f);
                line(c, 2.5f, 12, 5.5f, 12); line(c, 18.5f, 12, 21.5f, 12); line(c, 5.3f, 5.3f, 7.4f, 7.4f);
                line(c, 16.6f, 16.6f, 18.7f, 18.7f); line(c, 5.3f, 18.7f, 7.4f, 16.6f); line(c, 16.6f, 7.4f, 18.7f, 5.3f); break;
            case SEARCH: c.drawCircle(11, 11, 6.5f, p); line(c, 16, 16, 20.5f, 20.5f); break;
            case FILE: line(c, 6, 2.5f, 14, 2.5f, 19, 7.5f, 19, 21.5f, 6, 21.5f, 6, 2.5f); line(c, 14, 2.5f, 14, 7.5f, 19, 7.5f); break;
            case CHEVRON: line(c, 6, 9, 12, 15, 18, 9); break;
            default: break;
        }
    }
}
