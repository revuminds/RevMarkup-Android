package com.revtools.revmarkup;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Small line icons drawn in code (24-unit grid): no image files, no licences needed. */
final class Icon extends View {
    static final int SELECT = 0, PEN = 1, HIGHLIGHT = 2, TEXT = 3, BOX = 4, ELLIPSE = 5, CLOUD = 6, ARROW = 7,
            LINE = 8, ERASER = 9, UNDO = 20, REDO = 21, OPEN = 22, SAVE = 23, SHARE = 24, DELETE = 25, MORE = 26,
            PREV = 27, NEXT = 28;

    final int id;
    boolean active, more;   // more: has variants (small corner mark)
    int color = 0xFFE8EAED, activeBg = 0xFF3C4450, activeColor = 0xFFFFFFFF;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    Icon(Context c, int id) {
        super(c);
        this.id = id;
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        setClickable(true);
        setFocusable(true);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h);
        if (active) {
            bg.setColor(activeBg);
            r.set(w / 2 - s * 0.42f, h / 2 - s * 0.42f, w / 2 + s * 0.42f, h / 2 + s * 0.42f);
            c.drawRoundRect(r, s * 0.18f, s * 0.18f, bg);
        }
        float u = s * 0.55f / 24f;
        c.save();
        c.translate(w / 2 - 12 * u, h / 2 - 12 * u);
        c.scale(u, u);
        int col = active ? activeColor : color;
        if (!isEnabled()) col = (col & 0x00FFFFFF) | 0x55000000;
        p.setColor(col);
        p.setStrokeWidth(1.9f);
        p.setStyle(Paint.Style.STROKE);
        glyph(c);
        c.restore();
        if (more) {   // small triangle bottom-right: tap again / long-press for variants
            bg.setColor(col);
            android.graphics.Path t = new android.graphics.Path();
            float x = w / 2 + s * 0.34f, y = h / 2 + s * 0.34f, k = s * 0.1f;
            t.moveTo(x, y); t.lineTo(x - k, y); t.lineTo(x, y - k); t.close();
            c.drawPath(t, bg);
        }
    }

    private void line(Canvas c, float... xy) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        c.drawPath(path, p);
    }

    private void glyph(Canvas c) {
        switch (id) {
            case SELECT: line(c, 6, 3, 6, 19, 10, 15, 13, 21, 15.5f, 20, 12.5f, 14, 18, 14, 6, 3); break;
            case PEN: line(c, 4, 20, 5, 15, 16, 4, 20, 8, 9, 19, 4, 20); line(c, 14, 6, 18, 10); break;
            case HIGHLIGHT: line(c, 5, 15, 13, 7, 17, 11, 9, 19, 5, 15); line(c, 13, 7, 16, 4, 20, 8, 17, 11);
                line(c, 7, 17, 5, 21, 11, 21); break;
            case TEXT: line(c, 5, 6, 5, 4, 19, 4, 19, 6); line(c, 12, 4, 12, 20); line(c, 9, 20, 15, 20); break;
            case BOX: r.set(4, 6, 20, 18); c.drawRect(r, p); break;
            case ELLIPSE: r.set(3, 6, 21, 18); c.drawOval(r, p); break;
            case CLOUD:
                path.reset();
                path.moveTo(6, 17);
                path.cubicTo(2, 17, 2, 11, 6, 11);
                path.cubicTo(6, 6, 12, 5, 13, 9);
                path.cubicTo(16, 6, 20, 8, 19, 12);
                path.cubicTo(23, 13, 22, 17, 18, 17);
                path.close();
                c.drawPath(path, p);
                break;
            case ARROW: line(c, 5, 19, 19, 5); line(c, 11, 5, 19, 5, 19, 13); break;
            case LINE: line(c, 5, 19, 19, 5); break;
            case ERASER: line(c, 9, 20, 3.5f, 14.5f, 13, 5, 20.5f, 12.5f, 13, 20, 9, 20); line(c, 7, 11, 14, 18); line(c, 13, 20, 20, 20); break;
            case UNDO: line(c, 9, 14, 4, 9, 9, 4); path.reset(); path.moveTo(4, 9); path.lineTo(14, 9);
                path.cubicTo(22, 9, 22, 20, 14, 20); path.lineTo(10, 20); c.drawPath(path, p); break;
            case REDO: line(c, 15, 14, 20, 9, 15, 4); path.reset(); path.moveTo(20, 9); path.lineTo(10, 9);
                path.cubicTo(2, 9, 2, 20, 10, 20); path.lineTo(14, 20); c.drawPath(path, p); break;
            case OPEN: line(c, 3, 19, 3, 5, 9, 5, 11, 7, 20, 7, 20, 10); line(c, 3, 19, 6, 11, 22, 11, 19, 19, 3, 19); break;
            case SAVE: line(c, 4, 4, 17, 4, 20, 7, 20, 20, 4, 20, 4, 4); line(c, 8, 4, 8, 9, 15, 9, 15, 4);
                r.set(8, 13, 16, 20); c.drawRect(r, p); break;
            case SHARE: line(c, 12, 15, 12, 3); line(c, 8, 7, 12, 3, 16, 7); line(c, 8, 11, 5, 11, 5, 21, 19, 21, 19, 11, 16, 11); break;
            case DELETE: line(c, 4, 6, 20, 6); line(c, 9, 6, 9, 3, 15, 3, 15, 6); line(c, 6, 6, 7, 21, 17, 21, 18, 6);
                line(c, 10, 10, 10, 17); line(c, 14, 10, 14, 17); break;
            case MORE: p.setStyle(Paint.Style.FILL); c.drawCircle(12, 5, 1.8f, p); c.drawCircle(12, 12, 1.8f, p);
                c.drawCircle(12, 19, 1.8f, p); break;
            case PREV: line(c, 15, 5, 8, 12, 15, 19); break;
            case NEXT: line(c, 9, 5, 16, 12, 9, 19); break;
            default: break;
        }
    }
}
