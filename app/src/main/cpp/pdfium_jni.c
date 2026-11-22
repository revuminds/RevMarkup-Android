// Thin JNI bridge to PDFium. All calls must come from ONE thread (PdfWorker) -
// PDFium is not thread-safe. Only nCancel() may be called from any thread.
#include <jni.h>
#include <android/bitmap.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "fpdfview.h"
#include "fpdf_annot.h"
#include "fpdf_edit.h"
#include "fpdf_save.h"
#include "fpdf_progressive.h"

#define FN(name) Java_com_revtools_revmarkup_Pdfium_##name
#define KEY "ArtMarkup"

static atomic_int g_gen = 0;   // bumped by nCancel(): running renders stop
static int g_myGen = 0;

static int need_pause(IFSDK_PAUSE *p) { (void)p; return atomic_load(&g_gen) != g_myGen; }

JNIEXPORT void JNICALL FN(nInit)(JNIEnv *e, jclass c) {
    FPDF_LIBRARY_CONFIG cfg; memset(&cfg, 0, sizeof cfg);
    cfg.version = 2;
    FPDF_InitLibraryWithConfig(&cfg);
}

JNIEXPORT void JNICALL FN(nCancel)(JNIEnv *e, jclass c) { atomic_fetch_add(&g_gen, 1); }

JNIEXPORT jlong JNICALL FN(nOpen)(JNIEnv *e, jclass c, jstring path, jstring pw) {
    const char *p = (*e)->GetStringUTFChars(e, path, NULL);
    const char *w = pw ? (*e)->GetStringUTFChars(e, pw, NULL) : NULL;
    FPDF_DOCUMENT d = FPDF_LoadDocument(p, w);
    (*e)->ReleaseStringUTFChars(e, path, p);
    if (w) (*e)->ReleaseStringUTFChars(e, pw, w);
    if (!d) return -(jlong)FPDF_GetLastError();
    return (jlong)(intptr_t)d;
}

JNIEXPORT void JNICALL FN(nClose)(JNIEnv *e, jclass c, jlong d) {
    if (d > 0) FPDF_CloseDocument((FPDF_DOCUMENT)(intptr_t)d);
}

// [w0,h0,w1,h1,...] display size of every page in points (rotation applied)
JNIEXPORT jfloatArray JNICALL FN(nPageSizes)(JNIEnv *e, jclass c, jlong d) {
    FPDF_DOCUMENT doc = (FPDF_DOCUMENT)(intptr_t)d;
    int n = FPDF_GetPageCount(doc);
    jfloat *buf = malloc(sizeof(jfloat) * 2 * (n > 0 ? n : 1));
    if (!buf) return NULL;
    for (int i = 0; i < n; i++) {
        FPDF_PAGE pg = FPDF_LoadPage(doc, i);
        buf[2 * i] = pg ? FPDF_GetPageWidthF(pg) : 595;
        buf[2 * i + 1] = pg ? FPDF_GetPageHeightF(pg) : 842;
        if (pg) FPDF_ClosePage(pg);
    }
    jfloatArray r = (*e)->NewFloatArray(e, 2 * n);
    if (r) (*e)->SetFloatArrayRegion(e, r, 0, 2 * n, buf);
    free(buf);
    return r;
}

// display (pt, y down) -> page space affine [A,B,C,D,E,F]: px=A*x+C*y+E, py=B*x+D*y+F (same as desktop)
JNIEXPORT jdoubleArray JNICALL FN(nPageMap)(JNIEnv *e, jclass c, jlong d, jint i) {
    FPDF_PAGE pg = FPDF_LoadPage((FPDF_DOCUMENT)(intptr_t)d, i);
    if (!pg) return NULL;
    int sx = (int)(FPDF_GetPageWidthF(pg) * 100 + 0.5), sy = (int)(FPDF_GetPageHeightF(pg) * 100 + 0.5);
    double x0, y0, x1, y1, x2, y2;
    FPDF_DeviceToPage(pg, 0, 0, sx, sy, 0, 0, 0, &x0, &y0);
    FPDF_DeviceToPage(pg, 0, 0, sx, sy, 0, 100000, 0, &x1, &y1);
    FPDF_DeviceToPage(pg, 0, 0, sx, sy, 0, 0, 100000, &x2, &y2);
    FPDF_ClosePage(pg);
    jdouble m[6] = { (x1 - x0) / 1000.0, (y1 - y0) / 1000.0, (x2 - x0) / 1000.0, (y2 - y0) / 1000.0, x0, y0 };
    jdoubleArray r = (*e)->NewDoubleArray(e, 6);
    if (r) (*e)->SetDoubleArrayRegion(e, r, 0, 6, m);
    return r;
}

// Render page i at `scale` px/pt into bitmap (ARGB_8888); bitmap pixel (0,0) = page px (offX,offY).
// Returns 1 done, 0 cancelled, -1 error.
JNIEXPORT jint JNICALL FN(nRender)(JNIEnv *e, jclass c, jlong d, jint i, jobject bitmap,
                                   jfloat scale, jint offX, jint offY) {
    g_myGen = atomic_load(&g_gen);
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(e, bitmap, &info) < 0 || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return -1;
    FPDF_PAGE pg = FPDF_LoadPage((FPDF_DOCUMENT)(intptr_t)d, i);
    if (!pg) return -1;
    void *px = NULL;
    if (AndroidBitmap_lockPixels(e, bitmap, &px) < 0) { FPDF_ClosePage(pg); return -1; }
    FPDF_BITMAP bmp = FPDFBitmap_CreateEx((int)info.width, (int)info.height, FPDFBitmap_BGRA, px, (int)info.stride);
    int res = -1;
    if (bmp) {
        FPDFBitmap_FillRect(bmp, 0, 0, (int)info.width, (int)info.height, 0xFFFFFFFF);
        int fw = (int)(FPDF_GetPageWidthF(pg) * scale + 0.5), fh = (int)(FPDF_GetPageHeightF(pg) * scale + 0.5);
        IFSDK_PAUSE pause; memset(&pause, 0, sizeof pause);
        pause.version = 1; pause.NeedToPauseNow = need_pause;
        int st = FPDF_RenderPageBitmap_Start(bmp, pg, -offX, -offY, fw, fh, 0,
                                             FPDF_ANNOT | FPDF_REVERSE_BYTE_ORDER, &pause);
        while (st == FPDF_RENDER_TOBECONTINUED && !need_pause(NULL)) st = FPDF_RenderPage_Continue(pg, &pause);
        res = st == FPDF_RENDER_DONE ? 1 : (st == FPDF_RENDER_TOBECONTINUED ? 0 : -1);
        FPDF_RenderPage_Close(pg);
        FPDFBitmap_Destroy(bmp);
    }
    AndroidBitmap_unlockPixels(e, bitmap);
    FPDF_ClosePage(pg);
    return res;
}

static jstring annot_string(JNIEnv *e, FPDF_ANNOTATION a, const char *key) {
    unsigned long len = FPDFAnnot_GetStringValue(a, key, NULL, 0);   // bytes incl. terminator
    if (len < 2 || len > 64u * 1024 * 1024) return (*e)->NewStringUTF(e, "");
    unsigned short *buf = malloc(len);
    if (!buf) return NULL;
    FPDFAnnot_GetStringValue(a, key, buf, len);
    jstring s = (*e)->NewString(e, (const jchar *)buf, (jsize)(len / 2 - 1));
    free(buf);
    return s;
}

// Every RevMarkup comment of page i: [value0, author0, value1, author1, ...] in annotation index order.
// Annotation indices are returned in idxOut (int[] of the same length / 2, caller-sized via nAnnotCount).
JNIEXPORT jobjectArray JNICALL FN(nReadOwn)(JNIEnv *e, jclass c, jlong d, jint i, jintArray idxOut) {
    FPDF_PAGE pg = FPDF_LoadPage((FPDF_DOCUMENT)(intptr_t)d, i);
    jclass sc = (*e)->FindClass(e, "java/lang/String");
    if (!pg) return (*e)->NewObjectArray(e, 0, sc, NULL);
    int n = FPDFPage_GetAnnotCount(pg), cnt = 0, cap = (*e)->GetArrayLength(e, idxOut);
    jobjectArray tmp = (*e)->NewObjectArray(e, 2 * (n > 0 ? n : 0), sc, NULL);
    jint *idx = malloc(sizeof(jint) * (n > 0 ? n : 1));
    for (int k = 0; k < n && tmp && idx; k++) {
        FPDF_ANNOTATION a = FPDFPage_GetAnnot(pg, k);
        if (!a) continue;
        if (FPDFAnnot_HasKey(a, KEY)) {
            jstring v = annot_string(e, a, KEY), t = annot_string(e, a, "T");
            (*e)->SetObjectArrayElement(e, tmp, 2 * cnt, v);
            (*e)->SetObjectArrayElement(e, tmp, 2 * cnt + 1, t);
            if (v) (*e)->DeleteLocalRef(e, v);
            if (t) (*e)->DeleteLocalRef(e, t);
            idx[cnt++] = k;
        }
        FPDFPage_CloseAnnot(a);
    }
    FPDF_ClosePage(pg);
    if (idx) { (*e)->SetIntArrayRegion(e, idxOut, 0, cnt < cap ? cnt : cap, idx); free(idx); }
    jobjectArray r = (*e)->NewObjectArray(e, 2 * cnt, sc, NULL);
    for (int k = 0; r && tmp && k < 2 * cnt; k++) {
        jobject o = (*e)->GetObjectArrayElement(e, tmp, k);
        (*e)->SetObjectArrayElement(e, r, k, o);
        if (o) (*e)->DeleteLocalRef(e, o);
    }
    return r;
}

JNIEXPORT jint JNICALL FN(nAnnotCount)(JNIEnv *e, jclass c, jlong d, jint i) {
    FPDF_PAGE pg = FPDF_LoadPage((FPDF_DOCUMENT)(intptr_t)d, i);
    if (!pg) return 0;
    int n = FPDFPage_GetAnnotCount(pg);
    FPDF_ClosePage(pg);
    return n;
}

// Remove the given annotation indices of page i (any order).
JNIEXPORT void JNICALL FN(nRemoveAnnots)(JNIEnv *e, jclass c, jlong d, jint i, jintArray idxs) {
    FPDF_PAGE pg = FPDF_LoadPage((FPDF_DOCUMENT)(intptr_t)d, i);
    if (!pg) return;
    int n = (*e)->GetArrayLength(e, idxs);
    jint *v = (*e)->GetIntArrayElements(e, idxs, NULL);
    // highest index first so the remaining indices stay valid
    for (int a = 0; a < n; a++) for (int b = a + 1; b < n; b++) if (v[b] > v[a]) { jint t = v[a]; v[a] = v[b]; v[b] = t; }
    for (int k = 0; k < n; k++) FPDFPage_RemoveAnnot(pg, v[k]);
    (*e)->ReleaseIntArrayElements(e, idxs, v, JNI_ABORT);
    FPDF_ClosePage(pg);
}

JNIEXPORT void JNICALL FN(nDeletePage)(JNIEnv *e, jclass c, jlong d, jint i) {
    FPDFPage_Delete((FPDF_DOCUMENT)(intptr_t)d, i);
}

static FPDF_WCHAR *wstr(JNIEnv *e, jstring s) {
    jsize n = (*e)->GetStringLength(e, s);
    FPDF_WCHAR *w = malloc(sizeof(FPDF_WCHAR) * (n + 1));
    if (!w) return NULL;
    (*e)->GetStringRegion(e, s, 0, n, (jchar *)w);
    w[n] = 0;
    return w;
}

static void set_strings(JNIEnv *e, FPDF_ANNOTATION a, jobjectArray kv) {
    int n = kv ? (*e)->GetArrayLength(e, kv) : 0;
    for (int k = 0; k + 1 < n; k += 2) {
        jstring ks = (*e)->GetObjectArrayElement(e, kv, k), vs = (*e)->GetObjectArrayElement(e, kv, k + 1);
        if (ks && vs) {
            const char *key = (*e)->GetStringUTFChars(e, ks, NULL);
            FPDF_WCHAR *w = wstr(e, vs);
            if (w) { FPDFAnnot_SetStringValue(a, key, w); free(w); }
            (*e)->ReleaseStringUTFChars(e, ks, key);
        }
        if (ks) (*e)->DeleteLocalRef(e, ks);
        if (vs) (*e)->DeleteLocalRef(e, vs);
    }
}

static void argb(jint c, unsigned *r, unsigned *g, unsigned *b, unsigned *a) {
    *a = ((unsigned)c >> 24) & 255; *r = ((unsigned)c >> 16) & 255; *g = ((unsigned)c >> 8) & 255; *b = (unsigned)c & 255;
}

// Ink / Square / Circle comment with an appearance stream (page space).
// rect = [l,b,r,t]; inks = float[][] flat x,y (page space) or null; fill = 0 for none.
JNIEXPORT jboolean JNICALL FN(nAddShape)(JNIEnv *e, jclass c, jlong d, jint i, jint subtype, jfloatArray rect,
                                         jint color, jint fill, jfloat width, jobjectArray inks,
                                         jstring ap, jobjectArray kv) {
    FPDF_PAGE pg = FPDF_LoadPage((FPDF_DOCUMENT)(intptr_t)d, i);
    if (!pg) return JNI_FALSE;
    FPDF_ANNOTATION a = FPDFPage_CreateAnnot(pg, subtype);
    jboolean ok = JNI_FALSE;
    if (a) {
        jfloat r[4]; (*e)->GetFloatArrayRegion(e, rect, 0, 4, r);
        FS_RECTF fr = { r[0], r[3], r[2], r[1] };   // left, top, right, bottom
        FPDFAnnot_SetRect(a, &fr);
        int ni = inks ? (*e)->GetArrayLength(e, inks) : 0;
        for (int k = 0; k < ni; k++) {
            jfloatArray s = (*e)->GetObjectArrayElement(e, inks, k);
            int m = (*e)->GetArrayLength(e, s) / 2;
            if (m > 0) {
                jfloat *f = (*e)->GetFloatArrayElements(e, s, NULL);
                FS_POINTF *pts = malloc(sizeof(FS_POINTF) * m);
                if (pts) {
                    for (int q = 0; q < m; q++) { pts[q].x = f[2 * q]; pts[q].y = f[2 * q + 1]; }
                    FPDFAnnot_AddInkStroke(a, pts, (size_t)m);
                    free(pts);
                }
                (*e)->ReleaseFloatArrayElements(e, s, f, JNI_ABORT);
            }
            (*e)->DeleteLocalRef(e, s);
        }
        unsigned cr, cg, cb, ca; argb(color, &cr, &cg, &cb, &ca);
        FPDFAnnot_SetColor(a, FPDFANNOT_COLORTYPE_Color, cr, cg, cb, ca);
        if (fill) { argb(fill, &cr, &cg, &cb, &ca); FPDFAnnot_SetColor(a, FPDFANNOT_COLORTYPE_InteriorColor, cr, cg, cb, 255); }
        FPDFAnnot_SetBorder(a, 0, 0, width);
        set_strings(e, a, kv);
        FPDFAnnot_SetFlags(a, FPDF_ANNOT_FLAG_PRINT);
        FPDF_WCHAR *w = wstr(e, ap);
        if (w) { FPDFAnnot_SetAP(a, FPDF_ANNOT_APPEARANCEMODE_NORMAL, w); free(w); }
        FPDFPage_CloseAnnot(a);
        ok = JNI_TRUE;
    }
    FPDF_ClosePage(pg);
    return ok;
}

// Text comment as a Stamp built from page objects (like the desktop app).
// frame = 8 floats (4 corners, page space) or null for no box; lines + origins (baseline start, page space);
// mtx = [a,b,c,d] unit text direction (right, up) in page space.
JNIEXPORT jboolean JNICALL FN(nAddText)(JNIEnv *e, jclass c, jlong d, jint i, jfloatArray rect, jfloatArray frame,
                                        jint frameColor, jint bgColor, jfloat frameW, jobjectArray lines,
                                        jfloatArray origins, jfloatArray mtx, jfloat fontSize, jint textColor,
                                        jobjectArray kv) {
    FPDF_DOCUMENT doc = (FPDF_DOCUMENT)(intptr_t)d;
    FPDF_PAGE pg = FPDF_LoadPage(doc, i);
    if (!pg) return JNI_FALSE;
    FPDF_ANNOTATION a = FPDFPage_CreateAnnot(pg, FPDF_ANNOT_STAMP);
    jboolean ok = JNI_FALSE;
    if (a) {
        unsigned r, g, b, al;
        jfloat rc[4]; (*e)->GetFloatArrayRegion(e, rect, 0, 4, rc);
        FS_RECTF fr = { rc[0], rc[3], rc[2], rc[1] };
        FPDFAnnot_SetRect(a, &fr);   // before AppendObject (sets the BBox)
        if (frame) {
            jfloat f[8]; (*e)->GetFloatArrayRegion(e, frame, 0, 8, f);
            FPDF_PAGEOBJECT p = FPDFPageObj_CreateNewPath(f[0], f[1]);
            FPDFPath_LineTo(p, f[2], f[3]); FPDFPath_LineTo(p, f[4], f[5]); FPDFPath_LineTo(p, f[6], f[7]);
            FPDFPath_Close(p);
            argb(bgColor, &r, &g, &b, &al); FPDFPageObj_SetFillColor(p, r, g, b, 255);
            argb(frameColor, &r, &g, &b, &al); FPDFPageObj_SetStrokeColor(p, r, g, b, 255);
            FPDFPageObj_SetStrokeWidth(p, frameW);
            FPDFPath_SetDrawMode(p, FPDF_FILLMODE_WINDING, 1);
            FPDFAnnot_AppendObject(a, p);
        }
        jfloat m[4]; (*e)->GetFloatArrayRegion(e, mtx, 0, 4, m);
        int nl = (*e)->GetArrayLength(e, lines);
        jfloat *org = (*e)->GetFloatArrayElements(e, origins, NULL);
        argb(textColor, &r, &g, &b, &al);
        for (int k = 0; k < nl; k++) {
            jstring s = (*e)->GetObjectArrayElement(e, lines, k);
            if (!s) continue;
            if ((*e)->GetStringLength(e, s) > 0) {
                FPDF_PAGEOBJECT t = FPDFPageObj_NewTextObj(doc, "Helvetica", fontSize);
                FPDF_WCHAR *w = wstr(e, s);
                if (t && w) {
                    FPDFText_SetText(t, w);
                    FPDFPageObj_SetFillColor(t, r, g, b, 255);
                    FPDFPageObj_Transform(t, m[0], m[1], m[2], m[3], org[2 * k], org[2 * k + 1]);
                    FPDFAnnot_AppendObject(a, t);
                }
                free(w);
            }
            (*e)->DeleteLocalRef(e, s);
        }
        (*e)->ReleaseFloatArrayElements(e, origins, org, JNI_ABORT);
        argb(frameColor, &r, &g, &b, &al);
        FPDFAnnot_SetColor(a, FPDFANNOT_COLORTYPE_Color, r, g, b, 255);
        set_strings(e, a, kv);
        FPDFAnnot_SetFlags(a, FPDF_ANNOT_FLAG_PRINT);
        FPDFPage_CloseAnnot(a);
        ok = JNI_TRUE;
    }
    FPDF_ClosePage(pg);
    return ok;
}

typedef struct { FPDF_FILEWRITE fw; FILE *f; int err; } Writer;
static int write_block(FPDF_FILEWRITE *p, const void *data, unsigned long size) {
    Writer *w = (Writer *)p;
    if (fwrite(data, 1, size, w->f) != size) { w->err = 1; return 0; }
    return 1;
}

// Full save to path. Returns 1 ok, 0 failed.
JNIEXPORT jint JNICALL FN(nSave)(JNIEnv *e, jclass c, jlong d, jstring path) {
    FPDF_DOCUMENT doc = (FPDF_DOCUMENT)(intptr_t)d;
    const char *p = (*e)->GetStringUTFChars(e, path, NULL);
    FILE *f = fopen(p, "wb");
    (*e)->ReleaseStringUTFChars(e, path, p);
    if (!f) return 0;
    Writer w; memset(&w, 0, sizeof w);
    w.fw.version = 1; w.fw.WriteBlock = write_block; w.f = f;
    FPDF_DWORD flags = FPDF_GetSecurityHandlerRevision(doc) != -1 ? FPDF_REMOVE_SECURITY : 0;
    int ok = FPDF_SaveAsCopy(doc, &w.fw, flags);
    if (fflush(f) != 0) w.err = 1;
    if (fclose(f) != 0) w.err = 1;
    return ok && !w.err ? 1 : 0;
}
