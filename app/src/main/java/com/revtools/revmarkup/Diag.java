package com.revtools.revmarkup;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small event log (app paused / left / back / exit reasons) to find why the app leaves the screen. */
final class Diag {
    private static File file;

    static void init(Context c) { file = new File(c.getFilesDir(), "events.txt"); }

    static synchronized void log(String s) {
        if (file == null) return;
        try {
            if (file.length() > 48 * 1024) {   // keep the newest half
                byte[] b;
                try (InputStream in = new FileInputStream(file)) { b = in.readAllBytes(); }
                String t = new String(b, StandardCharsets.UTF_8);
                t = t.substring(t.indexOf('\n', t.length() / 2) + 1);
                try (FileOutputStream o = new FileOutputStream(file)) { o.write(t.getBytes(StandardCharsets.UTF_8)); }
            }
            try (FileOutputStream o = new FileOutputStream(file, true)) {
                o.write((new SimpleDateFormat("dd MMM HH:mm:ss.SSS", Locale.US).format(new Date()) + "  " + s + "\n")
                        .getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) { }
    }

    static synchronized String read() {
        if (file == null || !file.exists()) return "(no events yet)";
        try (InputStream in = new FileInputStream(file)) {
            String t = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String[] l = t.split("\n");
            StringBuilder b = new StringBuilder();
            for (int i = l.length - 1; i >= 0 && i >= l.length - 80; i--) b.append(l[i]).append('\n');   // newest first
            return b.toString();
        } catch (Exception e) { return e.toString(); }
    }
}
