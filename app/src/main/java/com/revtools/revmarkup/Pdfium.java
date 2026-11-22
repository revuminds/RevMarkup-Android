package com.revtools.revmarkup;

import android.graphics.Bitmap;

/** PDFium bindings. Call ONLY from {@link PdfWorker}'s thread, except {@link #nCancel()}. */
final class Pdfium {
    static {
        System.loadLibrary("pdfium");
        System.loadLibrary("revpdf");
        nInit();
    }

    static final int ANNOT_SQUARE = 5, ANNOT_CIRCLE = 6, ANNOT_STAMP = 13, ANNOT_INK = 15;

    private Pdfium() {}

    /** Loads + initialises the library (first use runs the static block). */
    static void ready() { }

    static native void nInit();
    static native void nCancel();
    static native long nOpen(String path, String password);   // 0 = failed (see nLastError); valid handles may be negative
    static native int nLastError();
    static native void nClose(long doc);
    static native float[] nPageSizes(long doc);
    static native double[] nPageMap(long doc, int page);
    static native int nRender(long doc, int page, Bitmap bmp, float scale, int offX, int offY);
    static native String[] nReadOwn(long doc, int page, int[] idxOut);
    static native int nAnnotCount(long doc, int page);
    static native void nRemoveAnnots(long doc, int page, int[] idx);
    static native void nDeletePage(long doc, int page);
    static native boolean nAddShape(long doc, int page, int subtype, float[] rect, int color, int fill,
                                    float width, float[][] inks, String ap, String[] kv);
    static native boolean nAddText(long doc, int page, float[] rect, float[] frame, int frameColor, int bgColor,
                                   float frameW, String[] lines, float[] origins, float[] mtx, float fontSize,
                                   int textColor, String[] kv);
    static native int nSave(long doc, String path);

    static String errorText(int code) {
        switch (code) {
            case 2: return "File not found or could not be opened.";
            case 3: return "This is not a valid PDF (or it is damaged).";
            case 4: return "This PDF is password protected.";
            case 5: return "This PDF's security settings are not supported.";
            default: return "Could not open the PDF (error " + code + ").";
        }
    }
}
