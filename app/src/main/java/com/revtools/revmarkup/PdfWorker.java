package com.revtools.revmarkup;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.LinkedBlockingDeque;

/**
 * The one thread that talks to PDFium. Jobs (open/save) run first, then the newest render request;
 * older render requests are dropped and a running render is cancelled when something newer comes in.
 */
final class PdfWorker {
    interface Job { void run() throws Exception; }
    interface Done { void done(Exception error); }

    private final LinkedBlockingDeque<Runnable> jobs = new LinkedBlockingDeque<>();
    private final Object lock = new Object();
    private Runnable render;          // newest pending render (only one kept)
    private final Handler ui = new Handler(Looper.getMainLooper());

    PdfWorker() {
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        Thread t = new Thread(() -> {
            try { Pdfium.ready(); } catch (Throwable e) { android.util.Log.e("RevMarkup", "PDFium failed to load", e); }
            finally { ready.countDown(); }
            loop();
        }, "pdfium");
        t.setDaemon(true);
        t.start();
        // PDFium is initialised on its own thread before anything else touches it
        try { ready.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Open / save etc. Result is posted to the UI thread. */
    void job(Job j, Done done) {
        // a running render is allowed to finish (so the sheet on screen is never left half drawn)
        jobs.add(() -> {
            Exception err = null;
            try { j.run(); } catch (Exception e) { err = e; } catch (Throwable e) { err = new Exception(e.toString(), e); }
            final Exception fe = err;
            if (done != null) ui.post(() -> done.done(fe));
        });
        synchronized (lock) { lock.notifyAll(); }
    }

    /** Replace any pending render with this one and stop the running one. */
    void render(Runnable r) {
        Pdfium.nCancel();     // first: the new render must not be the one that gets cancelled
        synchronized (lock) { render = r; lock.notifyAll(); }
    }

    void post(Runnable r) { ui.post(r); }

    boolean hasJobs() { return !jobs.isEmpty(); }

    private void loop() {
        while (true) {
            Runnable next;
            synchronized (lock) {
                while ((next = jobs.poll()) == null && render == null) {
                    try { lock.wait(); } catch (InterruptedException e) { return; }
                }
                if (next == null) { next = render; render = null; }
            }
            try { next.run(); } catch (Throwable t) { android.util.Log.e("RevMarkup", "worker", t); }
        }
    }
}
