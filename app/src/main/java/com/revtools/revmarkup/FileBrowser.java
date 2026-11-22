package com.revtools.revmarkup;

import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pop-up file browser over the app: the folders the user added (Drive / OneDrive / tablet), their subfolders and PDFs.
 * Close with ✕, Back or a touch outside. New places are added once through Android's picker ("+ Add folder").
 */
final class FileBrowser {

    interface Host {
        List<Uri> folders();
        void open(Uri doc, Uri tree, String folderId, String where);
        void addFolder();
        void browseOther();
    }

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    private final Activity a;
    private final Host host;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Dialog dlg;
    private final LinearLayout list;
    private final TextView title, up;
    private final ScrollView scroll;

    private Uri tree;                                   // null = list of added folders
    private final List<String[]> stack = new ArrayList<>();   // {documentId, name} from the folder root down
    private int gen;

    private static final class Item { String id, name; boolean dir; long modified, size = -1; }

    FileBrowser(Activity a, Host host) {
        this.a = a;
        this.host = host;
        dlg = new Dialog(a);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCanceledOnTouchOutside(true);

        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(U.round(Color.WHITE, 18));

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(px(8), px(6), px(8), px(6));
        up = U.text(a, "‹", 26, U.INK, true);
        up.setGravity(Gravity.CENTER);
        up.setContentDescription("Up one folder");
        up.setOnClickListener(v -> goUp());
        head.addView(up, U.lp(px(44), px(44)));
        title = U.text(a, "Open a drawing", 17, U.INK, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.START);
        head.addView(title, new LinearLayout.LayoutParams(0, px(44), 1));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).leftMargin = px(4);
        title.setGravity(Gravity.CENTER_VERTICAL);
        TextView close = U.text(a, "✕", 18, U.INK, false);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("Close");
        close.setBackground(U.round(U.ACTIVE, 22));
        close.setOnClickListener(v -> dlg.dismiss());
        head.addView(close, U.lp(px(44), px(44)));
        root.addView(head);
        root.addView(U.divider(a, false, U.LINE), U.lp(ViewGroup.LayoutParams.MATCH_PARENT, px(1)));

        scroll = new ScrollView(a);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(px(8), px(4), px(8), px(8));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            int sw = a.getResources().getDisplayMetrics().widthPixels, sh = a.getResources().getDisplayMetrics().heightPixels;
            w.setLayout(Math.min(px(560), sw - px(32)), Math.min(px(680), sh - px(64)));
        }
        dlg.setOnKeyListener((d, code, ev) -> {
            if (code == android.view.KeyEvent.KEYCODE_BACK && ev.getAction() == android.view.KeyEvent.ACTION_UP && tree != null) {
                goUp();
                return true;
            }
            return false;
        });
    }

    private int px(float v) { return U.px(v); }

    void show() { showRoots(); dlg.show(); }

    /** After "+ Add folder": show the new folder straight away. */
    void showFolder(Uri t) {
        tree = t;
        stack.clear();
        stack.add(new String[]{DocumentsContract.getTreeDocumentId(t), rootName(t)});
        load();
        if (!dlg.isShowing()) dlg.show();
    }

    boolean showing() { return dlg.isShowing(); }

    // ------------------------------------------------------------ added folders
    private void showRoots() {
        gen++;
        tree = null;
        stack.clear();
        title.setText("Open a drawing");
        up.setVisibility(View.INVISIBLE);
        list.removeAllViews();
        List<Uri> fs = host.folders();
        if (fs.isEmpty()) {
            TextView t = U.text(a, "No folders yet. Add your OneDrive or Drive project folder once - after that it opens here.",
                    14, U.MUTED, false);
            t.setSingleLine(false);
            t.setPadding(px(10), px(14), px(10), px(10));
            list.addView(t);
        }
        for (Uri t : fs) {
            String prov = HomeView.isTablet(t.getAuthority()) ? "This tablet" : HomeView.provider(t.getAuthority());
            String acc = HomeView.account(t);
            row(prov.equals("OneDrive") || prov.equals("Google Drive") ? "☁" : "▣", rootName(t),
                    prov + (acc.isEmpty() ? "" : " · account " + acc), true, v -> showFolder(t));
        }
        list.addView(U.divider(a, false, U.LINE), U.lp(ViewGroup.LayoutParams.MATCH_PARENT, px(1), 0, px(8), 0, px(4)));
        row("+", "Add folder…", "Pick a Drive / OneDrive folder once in Android's screen", false, v -> { dlg.dismiss(); host.addFolder(); });
        row("⋯", "Browse other…", "Any file, in Android's screen", false, v -> { dlg.dismiss(); host.browseOther(); });
    }

    private String rootName(Uri t) {
        String n = HomeView.name(a.getContentResolver(),
                DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t)));
        return n == null || n.isEmpty() ? "Folder" : n;
    }

    // ------------------------------------------------------------ inside a folder
    private void goUp() {
        if (tree == null) return;
        if (stack.size() > 1) { stack.remove(stack.size() - 1); load(); }
        else showRoots();
    }

    private void load() {
        final int g = ++gen;
        final Uri t = tree;
        final String folder = stack.get(stack.size() - 1)[0];
        StringBuilder path = new StringBuilder();
        for (String[] s : stack) { if (path.length() > 0) path.append(" › "); path.append(s[1]); }
        title.setText(path);
        up.setVisibility(View.VISIBLE);
        list.removeAllViews();
        TextView wait = U.text(a, "Loading…", 14, U.MUTED, false);
        wait.setPadding(px(10), px(14), px(10), px(10));
        list.addView(wait);
        final ContentResolver cr = a.getContentResolver();
        IO.execute(() -> {
            List<Item> l = new ArrayList<>();
            String err = null;
            String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE};
            try (Cursor c = cr.query(DocumentsContract.buildChildDocumentsUriUsingTree(t, folder), cols, null, null, null)) {
                if (c == null) err = "This folder can't be read.";
                else while (c.moveToNext()) {
                    Item e = new Item();
                    e.id = c.getString(0);
                    e.name = c.getString(1) == null ? "" : c.getString(1);
                    String mime = c.getString(2);
                    e.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    e.modified = c.isNull(3) ? 0 : c.getLong(3);
                    e.size = c.isNull(4) ? -1 : c.getLong(4);
                    if (e.dir || "application/pdf".equals(mime) || e.name.toLowerCase(Locale.ROOT).endsWith(".pdf")) l.add(e);
                }
            } catch (Exception ex) {
                err = "This folder can't be read (" + ex.getClass().getSimpleName() + "). Remove it on the home screen and add it again.";
            }
            Collections.sort(l, (x, y) -> x.dir != y.dir ? (x.dir ? -1 : 1) : x.name.compareToIgnoreCase(y.name));
            final String fe = err;
            ui.post(() -> { if (g == gen) fill(l, fe); });
        });
    }

    private void fill(List<Item> l, String err) {
        list.removeAllViews();
        if (err != null || l.isEmpty()) {
            TextView t = U.text(a, err != null ? err : "No PDFs or folders here.", 14, U.MUTED, false);
            t.setSingleLine(false);
            t.setPadding(px(10), px(14), px(10), px(10));
            list.addView(t);
            return;
        }
        final Uri t = tree;
        final String folder = stack.get(stack.size() - 1)[0];
        final String where = (HomeView.isTablet(t.getAuthority()) ? "This tablet" : HomeView.provider(t.getAuthority()))
                + " · " + stack.get(stack.size() - 1)[1];
        for (Item e : l) {
            if (e.dir) row("▸", e.name, "Folder", true, v -> { stack.add(new String[]{e.id, e.name}); load(); });
            else row("▤", e.name, (e.modified > 0 ? U.when(e.modified) : "") + (e.size >= 0 ? "  ·  " + U.size(e.size) : ""), false, v -> {
                dlg.dismiss();
                host.open(DocumentsContract.buildDocumentUriUsingTree(t, e.id), t, folder, where);
            });
        }
        scroll.scrollTo(0, 0);
    }

    private void row(String icon, String name, String sub, boolean chevron, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(8), px(6), px(8), px(6));
        r.setBackground(U.round(Color.TRANSPARENT, 10));
        android.util.TypedValue tv = new android.util.TypedValue();
        if (a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) r.setForeground(a.getDrawable(tv.resourceId));
        TextView ic = U.text(a, icon, 16, U.BLUE, true);
        ic.setGravity(Gravity.CENTER);
        ic.setBackground(U.round(U.ACTIVE, 10));
        r.addView(ic, U.lp(px(36), px(36), 0, 0, px(12), 0));
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView n = U.text(a, name, 15, U.INK, false);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(n);
        if (sub != null && !sub.isEmpty()) {
            TextView s = U.text(a, sub, 12, U.MUTED, false);
            s.setSingleLine(true);
            s.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(s);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (chevron) r.addView(U.text(a, "›", 20, U.MUTED, false));
        r.setOnClickListener(l);
        list.addView(r, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, px(58)));
    }
}
