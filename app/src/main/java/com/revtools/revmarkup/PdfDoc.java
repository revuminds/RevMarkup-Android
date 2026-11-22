package com.revtools.revmarkup;

import android.graphics.RectF;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/** An open PDF. All methods run on the PdfWorker thread. */
final class PdfDoc {
    static final String KEY = "ArtMarkup", SUMMARY = "summary";

    final long h;
    final float[] sizes;           // display size per page (w,h)
    final List<Mk> loaded = new ArrayList<>();   // editable markups taken out of the file
    int keptForeign;                             // desktop-only markups left in the file (pins, images...)

    private PdfDoc(long h, float[] sizes) { this.h = h; this.sizes = sizes; }

    int pages() { return sizes.length / 2; }
    float pw(int i) { return sizes[2 * i]; }
    float ph(int i) { return sizes[2 * i + 1]; }

    static PdfDoc open(File f) throws Exception {
        long h = Pdfium.nOpen(f.getAbsolutePath(), null);
        if (h == 0) throw new Exception(Pdfium.errorText(Pdfium.nLastError()));
        try {
            int n = Pdfium.nPageSizes(h).length / 2;
            // 1. old summary pages (desktop adds them at the end) are removed; they are made again on save
            List<Integer> summary = new ArrayList<>();
            Map<Integer, String[]> own = new HashMap<>();
            Map<Integer, int[]> ownIdx = new HashMap<>();
            for (int i = 0; i < n; i++) {
                int[] idx = new int[Pdfium.nAnnotCount(h, i)];
                String[] v = Pdfium.nReadOwn(h, i, idx);
                if (v.length == 0) continue;
                boolean isSummary = false;
                for (int k = 0; k < v.length; k += 2) if (SUMMARY.equals(v[k])) isSummary = true;
                if (isSummary) summary.add(i); else { own.put(i, v); ownIdx.put(i, idx); }
            }
            for (int j = summary.size() - 1; j >= 0; j--) Pdfium.nDeletePage(h, summary.get(j));
            float[] sizes = Pdfium.nPageSizes(h);
            PdfDoc d = new PdfDoc(h, sizes);
            // 2. take out the markups we can edit (indices: summary pages are only ever after the sheets)
            for (Map.Entry<Integer, String[]> e : own.entrySet()) {
                int page = e.getKey();
                if (page >= d.pages()) continue;
                String[] v = e.getValue();
                int[] idx = ownIdx.get(page);
                List<Integer> take = new ArrayList<>();
                for (int k = 0; k < v.length / 2; k++) {
                    Mk m = Mk.parse(v[2 * k]);
                    if (m != null && m.editableHere()) { m.page = page; d.loaded.add(m); take.add(idx[k]); }
                    else d.keptForeign++;
                }
                int[] t = new int[take.size()];
                for (int k = 0; k < t.length; k++) t[k] = take.get(k);
                if (t.length > 0) Pdfium.nRemoveAnnots(h, page, t);
            }
            d.loaded.sort((a, b) -> Integer.compare(a.seq, b.seq));
            return d;
        } catch (Exception | Error ex) {
            Pdfium.nClose(h);
            throw ex;
        }
    }

    /** Set on the worker thread; renders check it so a closed handle is never used. */
    volatile boolean closed;

    void close() { if (!closed) { closed = true; Pdfium.nClose(h); } }

    /** Write all markups as standard PDF comments to {@code out}; the document is left as it was. */
    void save(List<Mk> marks, String author, File out) throws Exception {
        Map<Integer, List<Mk>> byPage = new HashMap<>();
        for (Mk m : marks) if (m.page >= 0 && m.page < pages()) byPage.computeIfAbsent(m.page, x -> new ArrayList<>()).add(m);
        Map<Integer, Integer> before = new HashMap<>();
        String now = pdfDate();
        try {
            for (Map.Entry<Integer, List<Mk>> e : byPage.entrySet()) {
                int pg = e.getKey();
                before.put(pg, Pdfium.nAnnotCount(h, pg));
                double[] map = Pdfium.nPageMap(h, pg);
                if (map == null) throw new Exception("Sheet " + (pg + 1) + " could not be read.");
                for (Mk m : e.getValue()) write(pg, map, m, author, now);
            }
            if (Pdfium.nSave(h, out.getAbsolutePath()) != 1) throw new Exception("Writing the PDF failed (storage full?).");
        } finally {
            // take the comments out again so the screen does not draw them twice
            for (Map.Entry<Integer, Integer> e : before.entrySet()) {
                int n = Pdfium.nAnnotCount(h, e.getKey());
                int[] idx = new int[Math.max(0, n - e.getValue())];
                for (int k = 0; k < idx.length; k++) idx[k] = e.getValue() + k;
                if (idx.length > 0) Pdfium.nRemoveAnnots(h, e.getKey(), idx);
            }
        }
    }

    private void write(int pg, double[] mp, Mk m, String author, String now) {
        String subj;
        switch (m.k) {
            case Mk.PEN: subj = "Pen"; break;
            case Mk.HIGHLIGHT: subj = "Highlight"; break;
            case Mk.ARROW: subj = m.heads == 0 ? "Line" : "Arrow"; break;
            case Mk.BOX: subj = "Box"; break;
            case Mk.ELLIPSE: subj = "Ellipse"; break;
            case Mk.CLOUD: subj = "Cloud"; break;
            default: subj = "Text";
        }
        String[] kv = {"T", author, "Subj", subj, "Contents", m.k == Mk.TEXT ? m.text : "",
                "NM", "artmarkup-" + UUID.randomUUID().toString().replace("-", ""),
                "M", now, "CreationDate", now, KEY, m.serialize()};
        int color = m.argb();
        if (m.k == Mk.TEXT) { writeText(pg, mp, m, kv, color); return; }

        StringBuilder ap = new StringBuilder();
        String rgb = rgb(color);
        ap.append(rgb).append(" RG ").append(rgb).append(" rg ").append(f(m.w)).append(" w 1 J 1 j\n");
        float[] dash = m.dash();
        if (dash != null) ap.append('[').append(f(dash[0])).append(' ').append(f(dash[1])).append("] 0 d\n");
        List<float[]> inks = new ArrayList<>();
        RectF b = new RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        if (m.variable()) {
            // pressure line: pieces of equal width (the Ink list keeps the whole stroke for other viewers)
            inks.add(toPage(mp, m.p, b));
            for (float[] run : m.widthRuns()) {
                float[] pts = java.util.Arrays.copyOfRange(run, 1, run.length);
                float[] q = toPage(mp, pts, b);
                ap.append(f(m.w * run[0])).append(" w ");
                for (int i = 0; i < q.length; i += 2) ap.append(f(q[i])).append(' ').append(f(q[i + 1])).append(i == 0 ? " m " : " l ");
                ap.append("S\n");
            }
        } else for (Mk.Sub s : m.build()) {
            float[] q = toPage(mp, s.pts, b);
            if (s.fill && dash != null) ap.append("[] 0 d\n");   // arrow heads stay solid
            for (int i = 0; i < q.length; i += 2) ap.append(f(q[i])).append(' ').append(f(q[i + 1])).append(i == 0 ? " m " : " l ");
            if (s.closed) ap.append("h ");
            ap.append(s.fill ? "b\n" : (s.closed ? "s\n" : "S\n"));
            inks.add(q);
        }
        boolean ink = m.k != Mk.BOX && m.k != Mk.ELLIPSE;
        double wMax = m.w;
        if (m.variable()) for (float v : m.pw) wMax = Math.max(wMax, m.w * v);
        float pad = (float) (ink ? wMax * 2 + 1 : wMax / 2 + 0.5);
        float[] rect = {b.left - pad, b.top - pad, b.right + pad, b.bottom + pad};   // l, b, r, t (page space, y up)
        int subtype = m.k == Mk.BOX ? Pdfium.ANNOT_SQUARE : m.k == Mk.ELLIPSE ? Pdfium.ANNOT_CIRCLE : Pdfium.ANNOT_INK;
        color = (color & 0x00FFFFFF) | (m.alpha() << 24);   // opacity -> /CA (PDFium adds it to the appearance)
        Pdfium.nAddShape(h, pg, subtype, rect, color, 0, (float) m.w, ink ? inks.toArray(new float[0][]) : null, ap.toString(), kv);
    }

    private static float[] toPage(double[] mp, float[] pts, RectF b) {
        float[] q = new float[pts.length];
        for (int i = 0; i + 1 < pts.length; i += 2) {
            q[i] = (float) (mp[0] * pts[i] + mp[2] * pts[i + 1] + mp[4]);
            q[i + 1] = (float) (mp[1] * pts[i] + mp[3] * pts[i + 1] + mp[5]);
            b.union(q[i], q[i + 1]);
        }
        return q;
    }

    private void writeText(int pg, double[] mp, Mk m, String[] kv, int color) {
        RectF box = m.textBox();
        float[] fr = {box.left, box.top, box.right, box.top, box.right, box.bottom, box.left, box.bottom};
        float[] q = new float[8];
        RectF b = new RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE);
        for (int i = 0; i < 8; i += 2) {
            q[i] = (float) (mp[0] * fr[i] + mp[2] * fr[i + 1] + mp[4]);
            q[i + 1] = (float) (mp[1] * fr[i] + mp[3] * fr[i + 1] + mp[5]);
            b.union(q[i], q[i + 1]);
        }
        float pad = (float) m.frameW() + 1;
        float[] rect = {b.left - pad, b.top - pad, b.right + pad, b.bottom + pad};
        String[] lines = m.lines();
        float[] org = new float[lines.length * 2];
        for (int k = 0; k < lines.length; k++) {
            double dx = box.left + m.padX(), dy = box.top + m.padY() + k * m.lineH() + m.font * 0.95;
            org[2 * k] = (float) (mp[0] * dx + mp[2] * dy + mp[4]);
            org[2 * k + 1] = (float) (mp[1] * dx + mp[3] * dy + mp[5]);
        }
        // text direction: display right -> (A,B), display up -> -(C,D); scaled to unit length
        double sr = Math.hypot(mp[0], mp[1]), su = Math.hypot(mp[2], mp[3]);
        float[] mtx = {(float) (mp[0] / sr), (float) (mp[1] / sr), (float) (-mp[2] / su), (float) (-mp[3] / su)};
        Pdfium.nAddText(h, pg, rect, m.noBox() ? null : q, color, 0xFFFFFFFF, (float) m.frameW(), lines, org, mtx,
                (float) m.font, color, kv);
    }

    private static String rgb(int c) {
        return f(((c >> 16) & 255) / 255.0) + " " + f(((c >> 8) & 255) / 255.0) + " " + f((c & 255) / 255.0);
    }

    private static String f(double v) { return String.format(Locale.ROOT, "%.3f", v); }

    private static String pdfDate() {
        Date d = new Date();
        TimeZone tz = TimeZone.getDefault();
        int off = tz.getOffset(d.getTime()) / 60000;
        String s = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(d);
        return String.format(Locale.ROOT, "D:%s%c%02d'%02d'", s, off >= 0 ? '+' : '-', Math.abs(off) / 60, Math.abs(off) % 60);
    }
}
