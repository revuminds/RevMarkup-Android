package com.revtools.revmarkup;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shows one sheet; stylus draws, fingers pan / zoom / flip sheets. */
final class PageView extends View {
    static final int SELECT = 0, PEN = 1, HIGHLIGHT = 2, TEXT = 3, BOX = 4, ELLIPSE = 5, CLOUD = 6, ARROW = 7, LINE = 8, ERASER = 9;

    interface Listener {
        void onMarksChanged();
        void onPageChanged();
        void onSelectionChanged();
        void onTextRequest(float x, float y, Mk edit);   // x,y in sheet points (edit == null: new text)
    }

    private final PdfWorker worker;
    private Listener listener;
    PdfDoc doc;
    int page;

    // editing state
    List<Mk> marks = new ArrayList<>();
    private final ArrayList<List<Mk>> undo = new ArrayList<>(), redo = new ArrayList<>();
    boolean dirty;
    int tool = PEN, sizeIdx = 1;
    String color = "#E53935";
    boolean fingerDraw;
    Mk sel;

    // view transform: screen = pt * scale + t
    private float scale = 1, tx, ty, fitScale = 1;
    private boolean needFit = true;

    // rendering
    private static final int MAX_BASE_PX = 3400, CACHE = 3;
    private final Map<Integer, Bitmap> cache = new LinkedHashMap<>(8, 0.75f, true);
    private final Map<Integer, Float> cacheScale = new java.util.HashMap<>();
    private int docGen;                         // bumped when a new document is set
    private Bitmap tile; private Bitmap spareTile;
    private final RectF tileRect = new RectF(); // sheet points
    private int tilePage = -1, tileGen;
    private final Runnable tileTask = this::requestTile;

    // input
    private static final int NONE = 0, DRAW = 1, PAN = 2;
    private int mode = NONE, drawId = -1, activeTool;
    private float lastX, lastY, downX, downY;
    private Mk.FloatList cur;
    private Mk preview;
    private float moveDx, moveDy;
    private boolean moving, panMoved, scaled;
    private long twoDownAt;
    private float hoverX = -1, hoverY;
    private final ScaleGestureDetector scaler;
    private final GestureDetector gestures;

    // paints
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fillP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ui = new Paint(Paint.ANTI_ALIAS_FLAG), textP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<Mk, Path[]> pathCache = new IdentityHashMap<>();
    private final float dp;

    PageView(Context c, PdfWorker worker) {
        super(c);
        this.worker = worker;
        dp = c.getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fillP.setStyle(Paint.Style.FILL);
        textP.setTypeface(Typeface.SANS_SERIF);
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                zoomAt(d.getFocusX(), d.getFocusY(), d.getScaleFactor());
                scaled = true;
                return true;
            }
        });
        scaler.setQuickScaleEnabled(false);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (scale > fitScale * 1.2f) fit(); else zoomAt(e.getX(), e.getY(), 2.5f);
                return true;
            }
            @Override public boolean onFling(MotionEvent a, MotionEvent b, float vx, float vy) {
                if (doc == null || scaled || scale > fitScale * 1.03f) return false;
                if (Math.abs(vx) > 1200 * dp / 2.6f && Math.abs(vx) > 2 * Math.abs(vy)) {
                    showPage(page + (vx < 0 ? 1 : -1));
                    return true;
                }
                return false;
            }
        });
        setBackgroundColor(0xFFE4E7EB);
        setFocusable(true);
    }

    void setListener(Listener l) { listener = l; }

    // ------------------------------------------------------------ document
    void setDoc(PdfDoc d, List<Mk> loaded, int startPage) {
        docGen++;
        synchronized (cache) { cache.clear(); cacheScale.clear(); }
        tilePage = -1;
        doc = d;
        marks = new ArrayList<>(loaded);
        undo.clear(); redo.clear();
        pathCache.clear();
        dirty = false;
        sel = null;
        page = d == null ? 0 : Math.max(0, Math.min(startPage, d.pages() - 1));
        needFit = true;
        if (d != null) loadPage();
        invalidate();
        if (listener != null) { listener.onPageChanged(); listener.onMarksChanged(); listener.onSelectionChanged(); }
    }

    void showPage(int p) {
        if (doc == null || p < 0 || p >= doc.pages() || p == page) return;
        page = p;
        setSel(null);
        needFit = true;
        loadPage();
        invalidate();
        if (listener != null) listener.onPageChanged();
    }

    float pw() { return doc.pw(page); }
    float ph() { return doc.ph(page); }
    double unit() { return Mk.unit(pw(), ph()); }

    private float baseScaleFor(int p) {
        float fit = Math.min(getWidth() / doc.pw(p), getHeight() / doc.ph(p));
        return Math.min(MAX_BASE_PX / Math.max(doc.pw(p), doc.ph(p)), Math.max(fit, 0.5f) * 1.25f);
    }

    private void loadPage() {
        if (doc == null || getWidth() == 0) return;
        final PdfDoc d = doc; final int gen = docGen, want = page;
        final int[] order = {want, want + 1, want - 1};
        final float[] scales = new float[3];
        for (int i = 0; i < 3; i++) scales[i] = order[i] >= 0 && order[i] < d.pages() ? baseScaleFor(order[i]) : 0;
        worker.render(() -> {
            for (int i = 0; i < 3; i++) {
                int p = order[i];
                if (scales[i] == 0 || d.closed || (i > 0 && worker.hasJobs())) continue;
                synchronized (cache) { if (cache.containsKey(p)) continue; }
                int w = Math.max(1, Math.round(d.pw(p) * scales[i])), h = Math.max(1, Math.round(d.ph(p) * scales[i]));
                Bitmap b;
                try { b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); } catch (OutOfMemoryError e) { trim(); return; }
                int r = Pdfium.nRender(d.h, p, b, scales[i], 0, 0);
                if (r != 1) { b.recycle(); return; }   // cancelled: something newer is waiting
                final float sc = scales[i];
                worker.post(() -> {
                    if (gen != docGen) { b.recycle(); return; }
                    synchronized (cache) {
                        cache.put(p, b); cacheScale.put(p, sc);
                        trimLocked();
                    }
                    if (p == page) { invalidate(); scheduleTile(); }
                });
            }
        });
    }

    private void trim() { synchronized (cache) { trimLocked(); } }

    private void trimLocked() {
        while (cache.size() > CACHE) {
            Integer oldest = null;
            for (Integer k : cache.keySet()) if (k != page) { oldest = k; break; }
            if (oldest == null) break;
            cache.remove(oldest); cacheScale.remove(oldest);   // not recycled: may still be drawn this frame
        }
    }

    /** Sharp render of what is on screen when zoomed in further than the cached sheet. */
    private void scheduleTile() {
        removeCallbacks(tileTask);
        postDelayed(tileTask, 180);
    }

    private void requestTile() {
        if (doc == null || getWidth() == 0) return;
        Float bs; synchronized (cache) { bs = cacheScale.get(page); }
        if (bs == null || scale <= bs * 1.05f) { tilePage = -1; return; }
        final int vw = getWidth(), vh = getHeight();
        final float sc = scale;
        final int offX = Math.round(-tx), offY = Math.round(-ty);
        final PdfDoc d = doc; final int p = page, gen = ++tileGen, dg = docGen;
        if (spareTile == null || spareTile.getWidth() != vw || spareTile.getHeight() != vh)
            spareTile = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888);
        final Bitmap b = spareTile;
        worker.render(() -> {
            if (d.closed) return;
            int r = Pdfium.nRender(d.h, p, b, sc, offX, offY);
            if (r != 1) return;
            worker.post(() -> {
                if (gen != tileGen || dg != docGen || p != page) return;
                spareTile = tile;
                tile = b;
                tilePage = p;
                tileRect.set(offX / sc, offY / sc, (offX + vw) / sc, (offY + vh) / sc);
                invalidate();
            });
        });
    }

    // ------------------------------------------------------------ view transform
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        needFit = true;
        if (doc != null) { synchronized (cache) { cache.clear(); cacheScale.clear(); } loadPage(); }
    }

    void fit() {
        if (doc == null || getWidth() == 0) return;
        fitScale = Math.min(getWidth() / pw(), getHeight() / ph()) * 0.97f;
        scale = fitScale;
        tx = (getWidth() - pw() * scale) / 2;
        ty = (getHeight() - ph() * scale) / 2;
        needFit = false;
        scheduleTile();
        invalidate();
    }

    private void zoomAt(float fx, float fy, float f) {
        float ns = Math.max(fitScale * 0.5f, Math.min(fitScale * 40, scale * f));
        f = ns / scale;
        tx = fx - (fx - tx) * f;
        ty = fy - (fy - ty) * f;
        scale = ns;
        clamp();
        scheduleTile();
        invalidate();
    }

    private void clamp() {
        float w = pw() * scale, h = ph() * scale, m = 0.15f;
        if (w <= getWidth()) tx = (getWidth() - w) / 2;
        else tx = Math.max(getWidth() * (1 - m) - w, Math.min(getWidth() * m, tx));
        if (h <= getHeight()) ty = (getHeight() - h) / 2;
        else ty = Math.max(getHeight() * (1 - m) - h, Math.min(getHeight() * m, ty));
    }

    private float px(float sx) { return (sx - tx) / scale; }
    private float py(float sy) { return (sy - ty) / scale; }

    // ------------------------------------------------------------ editing
    private void commit(List<Mk> next) {
        undo.add(marks);
        if (undo.size() > 200) undo.remove(0);
        redo.clear();
        marks = next;
        dirty = true;
        changed();
    }

    private void changed() {
        if (pathCache.size() > marks.size() * 2 + 50) pathCache.keySet().retainAll(new java.util.HashSet<>(marks));
        invalidate();
        if (listener != null) listener.onMarksChanged();
    }

    boolean canUndo() { return !undo.isEmpty(); }
    boolean canRedo() { return !redo.isEmpty(); }

    void undo() {
        if (undo.isEmpty()) return;
        redo.add(marks);
        marks = undo.remove(undo.size() - 1);
        setSel(null);
        dirty = true;
        jumpToChange();
        changed();
    }

    void redo() {
        if (redo.isEmpty()) return;
        undo.add(marks);
        marks = redo.remove(redo.size() - 1);
        setSel(null);
        dirty = true;
        jumpToChange();
        changed();
    }

    /** If undo/redo changed only another sheet, show that sheet so the change is visible. */
    private void jumpToChange() {
        List<Mk> other = undo.isEmpty() ? null : undo.get(undo.size() - 1);
        java.util.Set<Mk> a = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        a.addAll(marks);
        boolean here = false; int there = -1;
        List<Mk> diff = new ArrayList<>();
        if (other != null) for (Mk m : other) if (!a.contains(m)) diff.add(m);
        java.util.Set<Mk> b = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        if (other != null) b.addAll(other);
        for (Mk m : marks) if (!b.contains(m)) diff.add(m);
        for (Mk m : diff) { if (m.page == page) here = true; else there = m.page; }
        if (!here && there >= 0) showPage(there);
    }

    void deleteSelected() {
        if (sel == null) return;
        List<Mk> n = new ArrayList<>(marks);
        n.remove(sel);
        setSel(null);
        commit(n);
    }

    void addText(float x, float y, String text) {
        Mk m = newMark(Mk.TEXT);
        m.p = new float[]{x, y};
        m.text = text;
        List<Mk> n = new ArrayList<>(marks);
        n.add(m);
        commit(n);
    }

    void replaceText(Mk old, String text) {
        int i = marks.indexOf(old);
        if (i < 0) return;
        List<Mk> n = new ArrayList<>(marks);
        if (text.trim().isEmpty()) n.remove(i);
        else { Mk m = old.copy(); m.text = text; n.set(i, m); setSel(m); }
        if (text.trim().isEmpty()) setSel(null);
        commit(n);
    }

    /** Apply the current colour / size to the selected markup. */
    void styleSelected() {
        if (sel == null) return;
        Mk m = sel.copy();
        m.color = color;
        m.size = sizeIdx;
        m.applySize(unit());
        int i = marks.indexOf(sel);
        if (i < 0) return;
        List<Mk> n = new ArrayList<>(marks);
        n.set(i, m);
        setSel(m);
        commit(n);
    }

    private void setSel(Mk m) {
        if (sel == m) return;
        sel = m;
        invalidate();
        if (listener != null) listener.onSelectionChanged();
    }

    private Mk newMark(int kind) {
        Mk m = new Mk();
        m.k = kind;
        m.page = page;
        m.color = color;
        m.size = sizeIdx;
        int s = 0;
        for (Mk o : marks) s = Math.max(s, o.seq);
        m.seq = s + 1;
        m.applySize(unit());
        return m;
    }

    private Mk topHit(float x, float y, float tolPx) {
        float tol = tolPx / scale;
        for (int i = marks.size() - 1; i >= 0; i--) {
            Mk m = marks.get(i);
            if (m.page == page && m.hit(x, y, tol)) return m;
        }
        return null;
    }

    // ------------------------------------------------------------ input
    private static boolean isPen(MotionEvent e, int i) {
        int t = e.getToolType(i);
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER || t == MotionEvent.TOOL_TYPE_MOUSE;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (doc == null) return false;
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            mode = NONE;
            if (isPen(e, 0) || fingerDraw) startDraw(e, 0);
            else startPan(e);
            return true;
        }
        if (mode == DRAW) {
            int i = e.findPointerIndex(drawId);
            if (act == MotionEvent.ACTION_CANCEL) { cancelDraw(); return true; }
            if (i < 0) return true;
            if (act == MotionEvent.ACTION_MOVE) {
                for (int h = 0; h < e.getHistorySize(); h++) moveDraw(e.getHistoricalX(i, h), e.getHistoricalY(i, h));
                moveDraw(e.getX(i), e.getY(i));
            } else if (act == MotionEvent.ACTION_UP || (act == MotionEvent.ACTION_POINTER_UP && e.getActionIndex() == i)) {
                if ((e.getFlags() & MotionEvent.FLAG_CANCELED) != 0) cancelDraw();
                else { moveDraw(e.getX(i), e.getY(i)); endDraw(e.getX(i), e.getY(i)); }
                mode = NONE;
            }
            return true;   // other pointers (palm, fingers) are ignored while drawing
        }
        if (mode == PAN) {
            if (act == MotionEvent.ACTION_POINTER_DOWN && isPen(e, e.getActionIndex())) {
                // the pen arrived while a palm/finger was down: the pen wins
                startDraw(e, e.getActionIndex());
                return true;
            }
            scaler.onTouchEvent(e);
            gestures.onTouchEvent(e);
            if (act == MotionEvent.ACTION_POINTER_DOWN && e.getPointerCount() == 2) twoDownAt = SystemClock.uptimeMillis();
            if (act == MotionEvent.ACTION_MOVE && !scaler.isInProgress()) {
                float x = avgX(e), y = avgY(e);
                if (Math.hypot(x - downX, y - downY) > 12 * dp) panMoved = true;
                if (panMoved) { tx += x - lastX; ty += y - lastY; clamp(); invalidate(); scheduleTile(); }
                lastX = x; lastY = y;
            }
            if (act == MotionEvent.ACTION_POINTER_DOWN || act == MotionEvent.ACTION_POINTER_UP) {
                // re-anchor so the page does not jump when fingers are added / lifted
                int skip = act == MotionEvent.ACTION_POINTER_UP ? e.getActionIndex() : -1;
                lastX = avgX(e, skip); lastY = avgY(e, skip);
                if (act == MotionEvent.ACTION_POINTER_UP && e.getPointerCount() == 2 && !panMoved && !scaled
                        && SystemClock.uptimeMillis() - twoDownAt < 300) {
                    undo();   // two-finger tap = undo
                    twoDownAt = 0;
                }
            }
            if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) mode = NONE;
            return true;
        }
        return true;
    }

    private float avgX(MotionEvent e) { return avgX(e, -1); }
    private float avgY(MotionEvent e) { return avgY(e, -1); }
    private float avgX(MotionEvent e, int skip) {
        float s = 0; int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) if (i != skip) { s += e.getX(i); n++; }
        return n == 0 ? e.getX() : s / n;
    }
    private float avgY(MotionEvent e, int skip) {
        float s = 0; int n = 0;
        for (int i = 0; i < e.getPointerCount(); i++) if (i != skip) { s += e.getY(i); n++; }
        return n == 0 ? e.getY() : s / n;
    }

    private void startPan(MotionEvent e) {
        mode = PAN;
        panMoved = false; scaled = false;
        lastX = downX = e.getX(); lastY = downY = e.getY();
        scaler.onTouchEvent(e);
        gestures.onTouchEvent(e);
    }

    private void startDraw(MotionEvent e, int i) {
        mode = DRAW;
        drawId = e.getPointerId(i);
        requestUnbufferedDispatch(e);   // lowest latency for the pen
        boolean eraserEnd = e.getToolType(i) == MotionEvent.TOOL_TYPE_ERASER;
        boolean button = (e.getButtonState() & (MotionEvent.BUTTON_STYLUS_PRIMARY | MotionEvent.BUTTON_SECONDARY)) != 0;
        activeTool = eraserEnd || button ? ERASER : tool;
        float x = px(e.getX(i)), y = py(e.getY(i));
        downX = x; downY = y;
        cur = null; preview = null; moving = false; moveDx = moveDy = 0;
        switch (activeTool) {
            case PEN: case HIGHLIGHT:
                cur = new Mk.FloatList();
                cur.add(x, y);
                preview = newMark(activeTool == PEN ? Mk.PEN : Mk.HIGHLIGHT);
                break;
            case SELECT: {
                Mk h = topHit(x, y, 14 * dp);
                tapSameText = h != null && h == sel && h.k == Mk.TEXT;   // tap again on selected text = edit
                setSel(h);
                moving = h != null;
                break;
            }
            case ERASER: erase(x, y); break;
            default: break;
        }
        invalidate();
    }

    private void moveDraw(float sx, float sy) {
        float x = px(sx), y = py(sy);
        switch (activeTool) {
            case PEN: case HIGHLIGHT: {
                float[] a = cur.a; int n = cur.n;
                float min = 0.6f / scale;   // ~0.6 screen px
                if (Math.abs(a[n - 2] - x) < min && Math.abs(a[n - 1] - y) < min) return;
                cur.add(x, y);
                preview.p = cur.toArray();
                pathCache.remove(preview);
                break;
            }
            case BOX: case ELLIPSE: case CLOUD: case ARROW: case LINE: {
                int kind = activeTool == BOX ? Mk.BOX : activeTool == ELLIPSE ? Mk.ELLIPSE : activeTool == CLOUD ? Mk.CLOUD : Mk.ARROW;
                if (preview == null) {
                    if (Math.hypot(x - downX, y - downY) * scale < 6 * dp) return;
                    preview = newMark(kind);
                    if (activeTool == LINE) preview.heads = 0;
                }
                preview.p = new float[]{downX, downY, x, y};
                pathCache.remove(preview);
                break;
            }
            case SELECT:
                if (moving) { moveDx = x - downX; moveDy = y - downY; }
                break;
            case ERASER: erase(x, y); break;
            default: break;
        }
        invalidate();
    }

    private void endDraw(float sx, float sy) {
        float x = px(sx), y = py(sy);
        Mk done = null;
        switch (activeTool) {
            case PEN: case HIGHLIGHT: done = preview; break;
            case BOX: case ELLIPSE: case CLOUD: case ARROW: case LINE: done = preview; break;
            case TEXT: {
                Mk h = topHit(x, y, 10 * dp);
                if (listener != null) listener.onTextRequest(x, y, h != null && h.k == Mk.TEXT ? h : null);
                break;
            }
            case SELECT:
                if (moving && sel != null && Math.hypot(moveDx, moveDy) * scale > 3 * dp) {
                    Mk m = sel.moved(moveDx, moveDy);
                    int i = marks.indexOf(sel);
                    if (i >= 0) { List<Mk> n = new ArrayList<>(marks); n.set(i, m); sel = m; commit(n); }
                } else if (tapSameText && sel != null && listener != null) {
                    listener.onTextRequest(sel.x(0), sel.y(0), sel);
                }
                break;
            default: break;
        }
        if (activeTool == ERASER && erased != null) { commit(erased); erased = null; }
        preview = null; cur = null; moving = false; moveDx = moveDy = 0;
        if (done != null) {
            done.p = done.p.clone();
            List<Mk> n = new ArrayList<>(marks);
            n.add(done);
            commit(n);
        }
        invalidate();
    }

    private boolean tapSameText;
    private List<Mk> erased;   // list being erased in this stroke (one undo step)

    private void erase(float x, float y) {
        Mk h = null;
        List<Mk> src = erased != null ? erased : marks;
        float tol = 8 * dp / scale;
        for (int i = src.size() - 1; i >= 0; i--) {
            Mk m = src.get(i);
            if (m.page == page && m.hit(x, y, tol)) { h = m; break; }
        }
        if (h == null) return;
        if (erased == null) erased = new ArrayList<>(marks);
        erased.remove(h);
        if (h == sel) setSel(null);
        invalidate();
    }

    private void cancelDraw() {
        preview = null; cur = null; moving = false; erased = null; mode = NONE;
        invalidate();
    }

    @Override public boolean onHoverEvent(MotionEvent e) {
        if (isPen(e, 0) && e.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) {
            if (e.getActionMasked() == MotionEvent.ACTION_HOVER_EXIT) hoverX = -1;
            else { hoverX = e.getX(); hoverY = e.getY(); }
            invalidate();
            return true;
        }
        return super.onHoverEvent(e);
    }

    // ------------------------------------------------------------ drawing
    @Override protected void onDraw(Canvas c) {
        if (doc == null) return;
        if (needFit) fit();
        float w = pw() * scale, h = ph() * scale;
        ui.setStyle(Paint.Style.FILL);
        ui.setColor(0x22000000);
        c.drawRect(tx + 2 * dp, ty + 2 * dp, tx + w + 2 * dp, ty + h + 2 * dp, ui);
        ui.setColor(Color.WHITE);
        c.drawRect(tx, ty, tx + w, ty + h, ui);
        Bitmap base; Float bs;
        synchronized (cache) { base = cache.get(page); bs = cacheScale.get(page); }
        if (base != null && !base.isRecycled()) {
            c.save();
            c.translate(tx, ty);
            c.scale(scale / bs, scale / bs);
            c.drawBitmap(base, 0, 0, bmpPaint);
            c.restore();
        } else {
            ui.setColor(0xFF9AA0A6);
            ui.setTextSize(15 * dp);
            ui.setTextAlign(Paint.Align.CENTER);
            c.drawText("Drawing sheet…", tx + w / 2, ty + h / 2, ui);
            ui.setTextAlign(Paint.Align.LEFT);
        }
        if (tile != null && tilePage == page && base != null) {
            RectF dst = new RectF(tileRect.left * scale + tx, tileRect.top * scale + ty,
                    tileRect.right * scale + tx, tileRect.bottom * scale + ty);
            c.drawBitmap(tile, null, dst, bmpPaint);
        }
        c.save();
        c.translate(tx, ty);
        c.scale(scale, scale);
        List<Mk> list = erased != null ? erased : marks;
        for (Mk m : list) {
            if (m.page != page) continue;
            if (m == sel && moving && (moveDx != 0 || moveDy != 0)) {
                c.save(); c.translate(moveDx, moveDy); drawMark(c, m); c.restore();
            } else drawMark(c, m);
        }
        if (preview != null) drawMark(c, preview);
        c.restore();
        if (sel != null && sel.page == page) {
            RectF b = sel.bounds();
            b.offset(moveDx, moveDy);
            float pad = 5 * dp;
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(1.5f * dp);
            ui.setColor(0xFF1E88E5);
            ui.setPathEffect(new DashPathEffect(new float[]{6 * dp, 4 * dp}, 0));
            c.drawRect(b.left * scale + tx - pad, b.top * scale + ty - pad, b.right * scale + tx + pad, b.bottom * scale + ty + pad, ui);
            ui.setPathEffect(null);
        }
        if (hoverX >= 0 && mode != DRAW) {
            float r = tool == ERASER ? 8 * dp : Math.max(2 * dp, (float) ((tool == HIGHLIGHT ? Mk.HL_W : Mk.PEN_W)[sizeIdx] * unit() * scale / 2));
            ui.setStyle(Paint.Style.STROKE);
            ui.setStrokeWidth(1 * dp);
            ui.setColor(0x99000000);
            c.drawCircle(hoverX, hoverY, r, ui);
        }
    }

    private void drawMark(Canvas c, Mk m) {
        int col = m.argb();
        if (m.k == Mk.TEXT) {
            RectF b = m.textBox();
            if (!m.noBox()) {
                fillP.setColor(Color.WHITE);
                c.drawRect(b, fillP);
                stroke.setColor(col);
                stroke.setStrokeWidth((float) m.frameW());
                stroke.setStrokeCap(Paint.Cap.SQUARE);
                c.drawRect(b, stroke);
                stroke.setStrokeCap(Paint.Cap.ROUND);
            }
            textP.setColor(col);
            textP.setTextSize((float) m.font);
            String[] lines = m.lines();
            for (int k = 0; k < lines.length; k++)
                c.drawText(lines[k], (float) (b.left + m.padX()), (float) (b.top + m.padY() + k * m.lineH() + m.font * 0.95), textP);
            return;
        }
        Path[] p = pathCache.get(m);
        if (p == null) {
            p = new Path[]{new Path(), new Path()};
            for (Mk.Sub s : m.build()) {
                Path t = s.fill ? p[1] : p[0];
                t.moveTo(s.pts[0], s.pts[1]);
                for (int i = 2; i < s.pts.length; i += 2) t.lineTo(s.pts[i], s.pts[i + 1]);
                if (s.closed) t.close();
            }
            pathCache.put(m, p);
        }
        boolean hl = m.k == Mk.HIGHLIGHT;
        stroke.setColor(hl ? (col & 0x00FFFFFF) | (100 << 24) : col);
        stroke.setStrokeWidth((float) m.w);
        c.drawPath(p[0], stroke);
        if (!p[1].isEmpty()) {
            fillP.setColor(col);
            c.drawPath(p[1], fillP);
            c.drawPath(p[1], stroke);
        }
    }
}
