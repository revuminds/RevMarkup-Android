package com.revtools.revmarkup;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The app's own file list: linked folders (OneDrive etc.), sub-folders and PDFs, newest first. */
final class FilesPanel extends LinearLayout {
    interface Host {
        void onOpenFile(Uri doc, Uri tree, String folderId, String name, Uri commentsUri);
        void onLinkFolder();
        void onOtherLocation();
        void onClosePanel();
        List<Uri> linkedFolders();
        void unlinkFolder(Uri tree);
    }

    static final class Entry {
        String id, name; boolean dir; long modified; Uri commentsUri; boolean isComments;
    }

    private final Host host;
    private final float dp;
    private final TextView title, status;
    private final View upB;
    private final Adapter adapter = new Adapter();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int loadGen;

    // location: tree == null -> list of linked folders
    private Uri tree;
    private final ArrayList<String[]> stack = new ArrayList<>();   // {docId, name}
    private List<Entry> entries = new ArrayList<>();

    FilesPanel(Context c, Host host) {
        super(c);
        this.host = host;
        dp = c.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(0xFFF6F7F9);
        setClickable(true);   // swallow touches so the sheet below is not drawn on

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackgroundColor(Color.WHITE);
        head.setPadding(px(8), 0, px(12), 0);
        upB = button(head, "‹", 26, v -> up());
        title = new TextView(c);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(0xFF202124);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.START);
        title.setPadding(px(8), 0, px(8), 0);
        head.addView(title, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        pill(head, "+ Link folder", v -> host.onLinkFolder());
        pill(head, "Other location…", v -> host.onOtherLocation());
        button(head, "✕", 22, v -> host.onClosePanel());
        addView(head, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(60)));

        status = new TextView(c);
        status.setTextColor(0xFF5F6368);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(px(24), px(24), px(24), px(24));
        addView(status, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ListView list = new ListView(c);
        list.setAdapter(adapter);
        list.setDividerHeight(0);
        list.setOnItemClickListener((p, v, pos, id) -> click(entries.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            if (tree != null) return false;
            Entry e = entries.get(pos);
            new android.app.AlertDialog.Builder(getContext()).setTitle(e.name)
                    .setMessage("Remove this folder from the list? (Nothing is deleted.)")
                    .setPositiveButton("Remove", (d, w) -> { host.unlinkFolder(Uri.parse(e.id)); showRoot(); })
                    .setNegativeButton("Cancel", null).show();
            return true;
        });
        addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private int px(float v) { return Math.round(v * dp); }

    private View button(LinearLayout row, String t, int size, OnClickListener l) {
        TextView b = new TextView(getContext());
        b.setText(t);
        b.setTextSize(size);
        b.setTextColor(0xFF3C4043);
        b.setGravity(Gravity.CENTER);
        b.setOnClickListener(l);
        row.addView(b, new LayoutParams(px(48), px(48)));
        return b;
    }

    private void pill(LinearLayout row, String t, OnClickListener l) {
        TextView b = new TextView(getContext());
        b.setText(t);
        b.setTextSize(14);
        b.setTextColor(0xFF1E2329);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(14), 0, px(14), 0);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(px(18));
        g.setColor(0xFFECEFF3);
        b.setBackground(g);
        b.setOnClickListener(l);
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(36));
        lp.setMargins(px(4), 0, px(4), 0);
        row.addView(b, lp);
    }

    // ------------------------------------------------------------ navigation
    void showRoot() {
        tree = null;
        stack.clear();
        List<Entry> l = new ArrayList<>();
        for (Uri t : host.linkedFolders()) {
            Entry e = new Entry();
            e.id = t.toString();
            e.dir = true;
            String id = DocumentsContract.getTreeDocumentId(t);
            String nm = Uri.decode(id);
            e.name = nm.substring(nm.lastIndexOf(':') + 1);
            if (e.name.contains("/")) e.name = e.name.substring(e.name.lastIndexOf('/') + 1);
            if (e.name.isEmpty()) e.name = nm;
            l.add(e);
        }
        loadGen++;
        entries = l;
        adapter.notifyDataSetChanged();
        title.setText("Folders");
        upB.setVisibility(INVISIBLE);
        status.setVisibility(l.isEmpty() ? VISIBLE : GONE);
        status.setText("Link your OneDrive project folder once with “+ Link folder”.\n"
                + "PDFs then open from here, and Save puts name_comments.pdf in the same folder automatically.");
        // resolve real folder names in the background
        final int gen = loadGen;
        final ContentResolver cr = getContext().getContentResolver();
        io.execute(() -> {
            Map<String, String> names = new HashMap<>();
            for (Entry e : l) {
                Uri t = Uri.parse(e.id);
                String n = name(cr, DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t)));
                if (n != null) names.put(e.id, n);
            }
            ui.post(() -> {
                if (gen != loadGen) return;
                for (Entry e : l) if (names.containsKey(e.id)) e.name = names.get(e.id);
                adapter.notifyDataSetChanged();
            });
        });
    }

    /** Show a folder; stack = path below the linked root. */
    void showFolder(Uri t, List<String[]> path) {
        tree = t;
        stack.clear();
        stack.addAll(path);
        load();
    }

    Uri currentTree() { return tree; }
    List<String[]> currentPath() { return new ArrayList<>(stack); }

    private void up() {
        if (tree == null) return;
        if (stack.size() <= 1) { showRoot(); return; }
        stack.remove(stack.size() - 1);
        load();
    }

    private void click(Entry e) {
        if (tree == null) {   // a linked folder
            Uri t = Uri.parse(e.id);
            showFolder(t, java.util.Collections.singletonList(new String[]{DocumentsContract.getTreeDocumentId(t), e.name}));
        } else if (e.dir) {
            stack.add(new String[]{e.id, e.name});
            load();
        } else {
            host.onOpenFile(DocumentsContract.buildDocumentUriUsingTree(tree, e.id), tree, stack.get(stack.size() - 1)[0],
                    e.name, e.commentsUri);
        }
    }

    private void load() {
        final Uri t = tree;
        final String folder = stack.get(stack.size() - 1)[0];
        StringBuilder path = new StringBuilder();
        for (String[] s : stack) path.append(path.length() == 0 ? "" : " › ").append(s[1]);
        title.setText(path);
        upB.setVisibility(VISIBLE);
        entries = new ArrayList<>();
        adapter.notifyDataSetChanged();
        status.setVisibility(VISIBLE);
        status.setText("Loading…");
        final int gen = ++loadGen;
        final ContentResolver cr = getContext().getContentResolver();
        io.execute(() -> {
            List<Entry> l = new ArrayList<>();
            String err = null;
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(t, folder);
            String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
            try (Cursor c = cr.query(kids, cols, null, null, null)) {
                if (c == null) err = "This folder can't be read.";
                else while (c.moveToNext()) {
                    Entry e = new Entry();
                    e.id = c.getString(0);
                    e.name = c.getString(1) == null ? "" : c.getString(1);
                    String mime = c.getString(2);
                    e.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    e.modified = c.isNull(3) ? 0 : c.getLong(3);
                    boolean pdf = "application/pdf".equals(mime) || e.name.toLowerCase(Locale.ROOT).endsWith(".pdf");
                    if (e.dir || pdf) l.add(e);
                }
            } catch (Exception ex) {
                err = "This folder can't be read (" + ex.getClass().getSimpleName() + "). Try linking it again.";
            }
            // mark drawings that already have a _comments file
            Map<String, Entry> byName = new HashMap<>();
            for (Entry e : l) if (!e.dir) byName.put(e.name.toLowerCase(Locale.ROOT), e);
            for (Entry e : l) {
                if (e.dir) continue;
                e.isComments = e.name.toLowerCase(Locale.ROOT).endsWith("_comments.pdf");
                if (!e.isComments) {
                    Entry c2 = byName.get(MainActivity.commentsName(e.name).toLowerCase(Locale.ROOT));
                    if (c2 != null) e.commentsUri = DocumentsContract.buildDocumentUriUsingTree(t, c2.id);
                }
            }
            l.sort((a, b) -> a.dir != b.dir ? (a.dir ? -1 : 1)
                    : a.dir ? a.name.compareToIgnoreCase(b.name) : Long.compare(b.modified, a.modified));
            final String fe = err;
            ui.post(() -> {
                if (gen != loadGen) return;
                entries = l;
                adapter.notifyDataSetChanged();
                status.setVisibility(fe != null || l.isEmpty() ? VISIBLE : GONE);
                status.setText(fe != null ? fe : "No PDFs or folders here.");
            });
        });
    }

    static String name(ContentResolver cr, Uri doc) {
        try (Cursor c = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    // ------------------------------------------------------------ rows
    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int i) { return entries.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            LinearLayout row;
            TextView icon, name, meta, tag;
            if (v == null) {
                Context c = getContext();
                row = new LinearLayout(c);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(px(20), px(10), px(20), px(10));
                row.setMinimumHeight(px(60));
                icon = new TextView(c);
                icon.setGravity(Gravity.CENTER);
                icon.setTextSize(11);
                icon.setTypeface(Typeface.DEFAULT_BOLD);
                icon.setTextColor(Color.WHITE);
                row.addView(icon, new LayoutParams(px(38), px(38)));
                LinearLayout col = new LinearLayout(c);
                col.setOrientation(VERTICAL);
                col.setPadding(px(14), 0, px(8), 0);
                name = new TextView(c);
                name.setTextSize(16);
                name.setTextColor(0xFF202124);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                meta = new TextView(c);
                meta.setTextSize(13);
                meta.setTextColor(0xFF80868B);
                col.addView(name);
                col.addView(meta);
                row.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                tag = new TextView(c);
                tag.setTextSize(12);
                tag.setTextColor(0xFFE53935);
                tag.setTypeface(Typeface.DEFAULT_BOLD);
                row.addView(tag);
                row.setTag(new TextView[]{icon, name, meta, tag});
            } else row = (LinearLayout) v;
            TextView[] t = (TextView[]) row.getTag();
            icon = t[0]; name = t[1]; meta = t[2]; tag = t[3];
            Entry e = entries.get(i);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(8));
            g.setColor(e.dir ? 0xFF5F6B7A : e.isComments ? 0xFFE53935 : 0xFF3C4450);
            icon.setBackground(g);
            icon.setText(e.dir ? "▤" : "PDF");
            icon.setTextSize(e.dir ? 18 : 11);
            name.setText(e.name);
            meta.setText(e.dir ? (tree == null ? "Linked folder · long-press to remove" : "Folder")
                    : e.modified > 0 ? DateUtils.getRelativeTimeSpanString(e.modified).toString() : "");
            tag.setText(e.isComments ? "COMMENTS" : e.commentsUri != null ? "● has comments" : "");
            return row;
        }
    }
}
