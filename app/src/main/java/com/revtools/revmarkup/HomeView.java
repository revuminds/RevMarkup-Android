package com.revtools.revmarkup;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.revtools.revmarkup.U.px;

/** v2 home screen: dark storage column (Recent · This tablet · accounts) + breadcrumb, search, continue cards, file table. */
final class HomeView extends LinearLayout {
    interface Host {
        void onOpenFile(Uri doc, Uri tree, String folderId, String name, Uri commentsUri, String where);
        void onOpenRecent(Recents.Item r);
        void onAddAccount();
        void onLinkTablet();
        void onOpenOther();
        void onSettings();
        List<Uri> linkedFolders();
        void unlinkFolder(Uri tree);
        Recents recents();
        boolean isCurrent(Recents.Item r);
    }

    static final class Entry {
        String id, name, meta; boolean dir; long modified, size = -1; Uri commentsUri, tree; boolean isComments;
        Recents.Item recent;
    }

    private static final int RECENT = 0, TABLET = 1, FOLDER = 2;

    private final Host host;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int loadGen;

    private final LinearLayout nav, crumbs, cards;
    private final View contSection;
    private final TextView status, linkTabletB;
    private final LinearLayout tableHead;
    private final EditText search;
    private final Adapter adapter = new Adapter();
    private boolean narrow;

    private int mode = RECENT;
    private boolean navTablet;          // highlight "This tablet" while browsing a tablet folder
    private Uri tree;
    private final ArrayList<String[]> stack = new ArrayList<>();   // {docId, name}
    private List<Entry> all = new ArrayList<>(), shown = new ArrayList<>();
    private final Map<String, String> rootNames = new HashMap<>();

    HomeView(Context c, Host host, SharedPreferences prefs) {
        super(c);
        this.host = host;
        this.prefs = prefs;
        setOrientation(HORIZONTAL);
        setBackgroundColor(U.BG);
        setClickable(true);

        // ---- left storage column
        nav = new LinearLayout(c);
        nav.setOrientation(VERTICAL);
        nav.setPadding(px(16), px(24), px(16), px(20));
        ScrollView navScroll = new ScrollView(c);
        navScroll.setFillViewport(true);
        navScroll.setBackgroundColor(U.DARK);
        navScroll.setVerticalScrollBarEnabled(false);
        navScroll.addView(nav, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        addView(navScroll, U.lp(px(264), ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- right
        LinearLayout main = new LinearLayout(c);
        main.setOrientation(VERTICAL);
        main.setPadding(px(32), px(24), px(32), px(20));
        addView(main, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        crumbs = new LinearLayout(c);
        crumbs.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.HorizontalScrollView cs = new android.widget.HorizontalScrollView(c);
        cs.setHorizontalScrollBarEnabled(false);
        cs.addView(crumbs);
        head.addView(cs, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView other = U.pill(c, "Open file…", U.INK, Color.WHITE, v -> host.onOpenOther());
        other.setBackground(U.round(Color.WHITE, 10, U.LINE, 1));
        head.addView(other, U.lp(ViewGroup.LayoutParams.WRAP_CONTENT, px(44), px(12), 0, px(12), 0));
        LinearLayout sb = new LinearLayout(c);
        sb.setGravity(Gravity.CENTER_VERTICAL);
        sb.setPadding(px(12), 0, px(12), 0);
        sb.setBackground(U.round(Color.WHITE, 10, 0xFFDADDE2, 1));
        Icon si = new Icon(c, Icon.SEARCH);
        si.glyph = 0.8f; si.color = U.MUTED; si.setClickable(false);
        sb.addView(si, U.lp(px(22), px(22)));
        search = new EditText(c);
        search.setHint("Search this folder");
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setBackground(null);
        search.setPadding(px(8), 0, 0, 0);
        search.setTextColor(U.INK);
        search.setHintTextColor(U.FAINT);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int d) { applyFilter(); }
            @Override public void afterTextChanged(Editable e) { }
        });
        sb.addView(search, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        head.addView(sb, U.lp(px(240), px(44)));
        main.addView(head, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // continue reviewing
        LinearLayout cont = new LinearLayout(c);
        cont.setOrientation(VERTICAL);
        TextView ch = U.text(c, "CONTINUE REVIEWING", 12, U.MUTED, true);
        ch.setLetterSpacing(0.04f);
        cont.addView(ch, U.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0, 0, px(10)));
        cards = new LinearLayout(c);
        cont.addView(cards, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contSection = cont;
        main.addView(cont, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(22), 0, 0));

        // file table
        LinearLayout table = new LinearLayout(c);
        table.setOrientation(VERTICAL);
        table.setBackground(U.round(Color.WHITE, 14, U.LINE, 1));
        table.setClipToOutline(true);
        tableHead = row(c);
        tableHead.setMinimumHeight(px(42));
        for (String s : new String[]{"NAME", "MODIFIED", "SIZE", "COMMENTS"}) tableHead.addView(U.text(c, s, 12, U.MUTED, true));
        table.addView(tableHead);
        table.addView(U.divider(c, false, U.LINE));
        status = U.text(c, "", 15, U.MUTED, false);
        status.setSingleLine(false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(px(24), px(28), px(24), px(12));
        status.setLineSpacing(0, 1.25f);
        table.addView(status, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        linkTabletB = U.pill(c, "Link a folder on this tablet", Color.WHITE, U.INK, v -> host.onLinkTablet());
        LinearLayout.LayoutParams lt = U.lp(ViewGroup.LayoutParams.WRAP_CONTENT, px(44), 0, 0, 0, px(20));
        lt.gravity = Gravity.CENTER_HORIZONTAL;
        table.addView(linkTabletB, lt);
        ListView list = new ListView(c);
        list.setAdapter(adapter);
        list.setDividerHeight(0);
        list.setOnItemClickListener((p, v, pos, id) -> click(shown.get(pos)));
        list.setOnItemLongClickListener((p, v, pos, id) -> longClick(shown.get(pos)));
        table.addView(list, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        main.addView(table, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        ((LayoutParams) table.getLayoutParams()).topMargin = px(22);
        layoutColumns(tableHead);
    }

    // ------------------------------------------------------------ left column
    private void buildNav() {
        Context c = getContext();
        nav.removeAllViews();
        LinearLayout logo = new LinearLayout(c);
        logo.setGravity(Gravity.CENTER_VERTICAL);
        logo.setPadding(px(8), 0, 0, px(22));
        TextView rev = U.text(c, "Rev", 15, Color.WHITE, true);
        rev.setGravity(Gravity.CENTER);
        rev.setBackground(U.round(U.ACCENT, 10));
        logo.addView(rev, U.lp(px(40), px(40)));
        LinearLayout lc = new LinearLayout(c);
        lc.setOrientation(VERTICAL);
        lc.setPadding(px(12), 0, 0, 0);
        lc.addView(U.text(c, "RevMarkup", 16, Color.WHITE, true));
        TextView tag = U.text(c, "PDF REVIEW", 11, U.DARK_MUTED, false);
        tag.setLetterSpacing(0.09f);
        tag.setPadding(0, px(3), 0, 0);
        lc.addView(tag);
        logo.addView(lc);
        nav.addView(logo);

        navItem(Icon.RECENT, "Recent", null, mode == RECENT, v -> showRecent(), null);
        navItem(Icon.TABLET, "This tablet", null, navTablet, v -> showTablet(), null);
        TextView acc = U.text(c, "ACCOUNTS", 11, U.DARK_MUTED, false);
        acc.setLetterSpacing(0.09f);
        acc.setPadding(px(12), px(20), 0, px(6));
        nav.addView(acc);
        for (Uri t : host.linkedFolders()) {
            if (isTablet(t.getAuthority())) continue;
            String prov = provider(t.getAuthority());
            final Uri ft = t;
            navItem("Google Drive".equals(prov) ? Icon.DRIVE : "OneDrive".equals(prov) ? Icon.CLOUD : Icon.FOLDER, prov,
                    rootName(t), mode == FOLDER && !navTablet && t.equals(tree), v -> openTreeRoot(ft, false),
                    v -> { confirmUnlink(ft); return true; });
        }
        TextView add = U.text(c, "  +   Add account", 14, 0xFFC9CCD3, false);
        add.setGravity(Gravity.CENTER_VERTICAL);
        add.setPadding(px(12), 0, px(12), 0);
        add.setBackground(dashed());
        add.setOnClickListener(v -> host.onAddAccount());
        nav.addView(add, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, px(48), 0, px(8), 0, 0));
        View fill = new View(c);
        nav.addView(fill, new LayoutParams(1, 0, 1));
        navItem(Icon.SETTINGS, "Settings", null, false, v -> host.onSettings(), null);
    }

    private android.graphics.drawable.GradientDrawable dashed() {
        android.graphics.drawable.GradientDrawable g = U.round(Color.TRANSPARENT, 10);
        g.setStroke(px(1), U.DARK_LINE, px(4), px(3));
        return g;
    }

    private void navItem(int icon, String title, String sub, boolean on, OnClickListener l, OnLongClickListener ll) {
        Context c = getContext();
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(12), px(6), px(12), px(6));
        r.setMinimumHeight(px(sub == null ? 48 : 56));
        r.setBackground(U.round(on ? U.DARK_SEL : Color.TRANSPARENT, 10));
        Icon ic = new Icon(c, icon);
        ic.glyph = 0.83f; ic.color = U.DARK_TEXT; ic.setClickable(false); ic.setFocusable(false);
        r.addView(ic, U.lp(px(24), px(24)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(VERTICAL);
        col.setPadding(px(12), 0, 0, 0);
        col.addView(U.text(c, title, 15, on ? Color.WHITE : U.DARK_TEXT, on));
        if (sub != null) { TextView s = U.text(c, sub, 12, U.DARK_MUTED, false); s.setPadding(0, px(3), 0, 0); col.addView(s); }
        r.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.setOnClickListener(l);
        if (ll != null) r.setOnLongClickListener(ll);
        nav.addView(r, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0, px(2), 0, 0));
    }

    private void confirmUnlink(Uri t) {
        new AlertDialog.Builder(getContext()).setTitle(provider(t.getAuthority()) + " · " + rootName(t))
                .setMessage("Remove this folder from RevMarkup? Nothing is deleted from " + provider(t.getAuthority()) + ".")
                .setPositiveButton("Remove", (d, w) -> {
                    host.unlinkFolder(t);
                    if (t.equals(tree)) showRecent(); else refresh();
                })
                .setNegativeButton("Cancel", null).show();
    }

    static String provider(String a) {
        if (a == null) return "Files";
        if (a.startsWith("com.google.android.apps.docs")) return "Google Drive";
        if (a.contains("skydrive") || a.contains("onedrive")) return "OneDrive";
        if (a.contains("dropbox")) return "Dropbox";
        if (isTablet(a)) return "This tablet";
        return "Files";
    }

    static boolean isTablet(String a) {
        return a != null && (a.equals("com.android.externalstorage.documents") || a.equals("com.android.providers.downloads.documents")
                || a.equals("com.android.providers.media.documents"));
    }

    private String rootName(Uri t) {
        String n = rootNames.get(t.toString());
        if (n != null) return n;
        String id = Uri.decode(DocumentsContract.getTreeDocumentId(t));
        n = id.substring(id.lastIndexOf(':') + 1);
        if (n.contains("/")) n = n.substring(n.lastIndexOf('/') + 1);
        return n.isEmpty() ? "Linked folder" : n;
    }

    /** Real folder names of the linked folders (background). */
    private void resolveRootNames() {
        final List<Uri> trees = host.linkedFolders();
        final ContentResolver cr = getContext().getContentResolver();
        io.execute(() -> {
            Map<String, String> m = new HashMap<>();
            for (Uri t : trees) {
                String n = name(cr, DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t)));
                if (n != null) m.put(t.toString(), n);
            }
            ui.post(() -> {
                if (m.equals(rootNames)) return;
                rootNames.putAll(m);
                buildNav();
                if (mode == FOLDER || mode == TABLET) updateCrumbs();
            });
        });
    }

    // ------------------------------------------------------------ navigation
    /** Called whenever the home screen is shown: fresh recents, cards and folder contents. */
    void refresh() {
        buildNav();
        buildCards();
        resolveRootNames();
        if (mode == RECENT) showRecent();
        else if (mode == TABLET) showTablet();
        else if (tree != null && host.linkedFolders().contains(tree)) load();
        else showRecent();
    }

    /** First start: back to the folder used last time, else Recent. */
    void restore() {
        Uri last = Uri.parse(prefs.getString("lastTree", ""));
        String path = prefs.getString("lastPath", "");
        if (host.linkedFolders().contains(last) && !path.isEmpty() && host.recents().list.isEmpty()) {
            List<String[]> p = new ArrayList<>();
            for (String seg : path.split("\n")) { int k = seg.indexOf('\t'); if (k > 0) p.add(new String[]{seg.substring(0, k), seg.substring(k + 1)}); }
            if (!p.isEmpty()) { tree = last; stack.addAll(p); mode = FOLDER; navTablet = isTablet(last.getAuthority()); }
        }
        refresh();
    }

    private void remember() {
        if (tree == null) return;
        StringBuilder b = new StringBuilder();
        for (String[] s : stack) b.append(s[0]).append('\t').append(s[1]).append('\n');
        prefs.edit().putString("lastTree", tree.toString()).putString("lastPath", b.toString()).apply();
    }

    void showRecent() {
        mode = RECENT; navTablet = false; tree = null; stack.clear();
        loadGen++;
        List<Entry> l = new ArrayList<>();
        for (Recents.Item r : host.recents().list) {
            Entry e = new Entry();
            e.recent = r; e.name = r.name; e.modified = r.time; e.meta = r.where;
            l.add(e);
        }
        all = l;
        buildNav();
        updateCrumbs();
        applyFilter();
        linkTabletB.setVisibility(GONE);
        setStatus(l.isEmpty() ? "No drawings opened yet.\nAdd your OneDrive or Google Drive project folder with “+ Add account”, "
                + "link a folder on this tablet, or use “Open file…”." : null);
    }

    void showTablet() {
        List<Uri> tabs = new ArrayList<>();
        for (Uri t : host.linkedFolders()) if (isTablet(t.getAuthority())) tabs.add(t);
        if (tabs.size() == 1) { openTreeRoot(tabs.get(0), true); return; }
        mode = TABLET; navTablet = true; tree = null; stack.clear();
        loadGen++;
        List<Entry> l = new ArrayList<>();
        for (Uri t : tabs) {
            Entry e = new Entry();
            e.dir = true; e.tree = t; e.name = rootName(t); e.meta = "Linked folder · long-press to remove";
            l.add(e);
        }
        all = l;
        buildNav();
        updateCrumbs();
        applyFilter();
        linkTabletB.setVisibility(VISIBLE);
        setStatus(l.isEmpty() ? "No tablet folder linked yet.\nLink the folder that holds your drawings (e.g. Download or Documents) once; "
                + "saving then puts name_comments.pdf next to the drawing automatically." : null);
    }

    void openTreeRoot(Uri t, boolean tablet) {
        tree = t;
        navTablet = tablet;
        stack.clear();
        stack.add(new String[]{DocumentsContract.getTreeDocumentId(t), rootName(t)});
        mode = FOLDER;
        buildNav();
        load();
    }

    /** Back button: one folder up. False when there is nothing to go back to. */
    boolean up() {
        if (mode == FOLDER && stack.size() > 1) { stack.remove(stack.size() - 1); load(); return true; }
        if (mode != RECENT) { showRecent(); return true; }
        return false;
    }

    private void updateCrumbs() {
        Context c = getContext();
        crumbs.removeAllViews();
        List<String> segs = new ArrayList<>();
        if (mode == RECENT) segs.add("Recent");
        else if (mode == TABLET) segs.add("This tablet");
        else {
            segs.add(provider(tree.getAuthority()));
            for (String[] s : stack) segs.add(s[1]);
            if (stack.size() > 0) segs.set(1, rootName(tree));
        }
        for (int i = 0; i < segs.size(); i++) {
            boolean last = i == segs.size() - 1;
            if (i > 0) { TextView sep = U.text(c, "›", 15, U.MUTED, false); sep.setPadding(px(8), 0, px(8), 0); crumbs.addView(sep); }
            TextView t = U.text(c, segs.get(i), 15, last ? U.INK : U.MUTED, last);
            t.setPadding(0, px(10), 0, px(10));
            final int depth = i;   // 0 = provider, 1 = linked folder, ...
            if (!last && mode == FOLDER) t.setOnClickListener(v -> {
                while (stack.size() > Math.max(1, depth)) stack.remove(stack.size() - 1);
                load();
            });
            crumbs.addView(t);
        }
    }

    private void setStatus(String s) {
        status.setVisibility(s == null ? GONE : VISIBLE);
        if (s != null) status.setText(s);
    }

    private void click(Entry e) {
        if (e.recent != null) { host.onOpenRecent(e.recent); return; }
        if (mode == TABLET) { openTreeRoot(e.tree, true); return; }
        if (e.dir) { stack.add(new String[]{e.id, e.name}); load(); return; }
        String where = provider(tree.getAuthority()) + " · " + stack.get(stack.size() - 1)[1];
        if (stack.size() == 1) where = provider(tree.getAuthority()) + " · " + rootName(tree);
        remember();
        host.onOpenFile(DocumentsContract.buildDocumentUriUsingTree(tree, e.id), tree, stack.get(stack.size() - 1)[0], e.name,
                e.commentsUri, where);
    }

    private boolean longClick(Entry e) {
        if (e.recent != null) {
            new AlertDialog.Builder(getContext()).setTitle(e.name).setMessage("Remove from Recent? (The file is not deleted.)")
                    .setPositiveButton("Remove", (d, w) -> { host.recents().remove(e.recent); buildCards(); showRecent(); })
                    .setNegativeButton("Cancel", null).show();
            return true;
        }
        if (mode == TABLET && e.tree != null) { confirmUnlink(e.tree); return true; }
        return false;
    }

    private void load() {
        final Uri t = tree;
        final String folder = stack.get(stack.size() - 1)[0];
        updateCrumbs();
        linkTabletB.setVisibility(GONE);
        all = new ArrayList<>();
        applyFilter();
        setStatus("Loading…");
        remember();
        final int gen = ++loadGen;
        final ContentResolver cr = getContext().getContentResolver();
        io.execute(() -> {
            List<Entry> l = new ArrayList<>();
            String err = null;
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(t, folder);
            String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE};
            try (Cursor c = cr.query(kids, cols, null, null, null)) {
                if (c == null) err = "This folder can't be read.";
                else while (c.moveToNext()) {
                    Entry e = new Entry();
                    e.id = c.getString(0);
                    e.name = c.getString(1) == null ? "" : c.getString(1);
                    String mime = c.getString(2);
                    e.dir = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    e.modified = c.isNull(3) ? 0 : c.getLong(3);
                    e.size = c.isNull(4) ? -1 : c.getLong(4);
                    boolean pdf = "application/pdf".equals(mime) || e.name.toLowerCase(Locale.ROOT).endsWith(".pdf");
                    if (e.dir || pdf) l.add(e);
                }
            } catch (Exception ex) {
                err = "This folder can't be read (" + ex.getClass().getSimpleName() + "). Try adding it again.";
            }
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
                all = l;
                applyFilter();
                setStatus(fe != null ? fe : l.isEmpty() ? "No PDFs or folders here." : null);
            });
        });
    }

    private void applyFilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) shown = all;
        else {
            shown = new ArrayList<>();
            for (Entry e : all) if (e.name.toLowerCase(Locale.ROOT).contains(q)) shown.add(e);
        }
        adapter.notifyDataSetChanged();
    }

    static String name(ContentResolver cr, Uri doc) {
        try (Cursor c = cr.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return null;
    }

    // ------------------------------------------------------------ continue reviewing
    private void buildCards() {
        Context c = getContext();
        cards.removeAllViews();
        List<Recents.Item> rs = host.recents().list;
        contSection.setVisibility(rs.isEmpty() ? GONE : VISIBLE);
        for (int i = 0; i < 3; i++) {
            if (i > 0) cards.addView(new View(c), U.lp(px(16), 1));
            if (i >= rs.size()) { cards.addView(new View(c), new LayoutParams(0, 1, 1)); continue; }
            final Recents.Item r = rs.get(i);
            LinearLayout card = new LinearLayout(c);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(px(14), px(14), px(14), px(14));
            card.setBackground(U.round(Color.WHITE, 14, U.LINE, 1));
            ImageView th = new ImageView(c);
            th.setScaleType(ImageView.ScaleType.CENTER_CROP);
            th.setBackground(U.round(0xFFF7F7F8, 6, U.LINE, 1));
            th.setClipToOutline(true);
            card.addView(th, U.lp(px(64), px(46)));
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            col.setPadding(px(14), 0, 0, 0);
            String nm = r.name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? r.name.substring(0, r.name.length() - 4) : r.name;
            col.addView(U.text(c, nm, 14, U.INK, true));
            String meta = (r.pages > 0 ? "Sheet " + (r.page + 1) + " of " + r.pages : "")
                    + (r.draft && host.isCurrent(r) ? " · unsaved draft" : r.count > 0 ? " · " + r.count + " comment" + (r.count == 1 ? "" : "s") : "");
            if (meta.startsWith(" · ")) meta = meta.substring(3);
            TextView m = U.text(c, meta, 12, U.MUTED, false);
            m.setPadding(0, px(5), 0, 0);
            col.addView(m);
            card.addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            card.setOnClickListener(v -> host.onOpenRecent(r));
            cards.addView(card, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            final java.io.File f = host.recents().thumb(r);
            io.execute(() -> {
                Bitmap b = f.exists() ? BitmapFactory.decodeFile(f.getPath()) : null;
                if (b != null) ui.post(() -> th.setImageBitmap(b));
            });
        }
    }

    // ------------------------------------------------------------ table rows
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        boolean n = w - px(264) < px(760);
        if (n != narrow) { narrow = n; post(() -> { layoutColumns(tableHead); adapter.notifyDataSetChanged(); }); }
    }

    private LinearLayout row(Context c) {
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(20), 0, px(20), 0);
        return r;
    }

    private void layoutColumns(LinearLayout r) {
        r.getChildAt(0).setLayoutParams(new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.getChildAt(1).setLayoutParams(U.lp(px(narrow ? 110 : 150), ViewGroup.LayoutParams.WRAP_CONTENT, px(12), 0, 0, 0));
        r.getChildAt(2).setLayoutParams(U.lp(px(90), ViewGroup.LayoutParams.WRAP_CONTENT, px(12), 0, 0, 0));
        r.getChildAt(2).setVisibility(narrow ? GONE : VISIBLE);
        r.getChildAt(3).setLayoutParams(U.lp(px(narrow ? 120 : 140), ViewGroup.LayoutParams.WRAP_CONTENT, px(12), 0, 0, 0));
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int i, View v, ViewGroup parent) {
            Context c = getContext();
            LinearLayout r;
            if (v == null) {
                r = row(c);
                r.setMinimumHeight(px(58));
                LinearLayout nameCol = new LinearLayout(c);
                nameCol.setGravity(Gravity.CENTER_VERTICAL);
                Icon ic = new Icon(c, Icon.FILE);
                ic.glyph = 0.9f; ic.color = U.MUTED; ic.setClickable(false); ic.setFocusable(false);
                nameCol.addView(ic, U.lp(px(24), px(24)));
                LinearLayout tc = new LinearLayout(c);
                tc.setOrientation(VERTICAL);
                tc.setPadding(px(12), 0, px(8), 0);
                TextView nm = U.text(c, "", 14, U.INK, false);
                nm.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                TextView meta = U.text(c, "", 12, U.MUTED, false);
                meta.setPadding(0, px(3), 0, 0);
                tc.addView(nm); tc.addView(meta);
                nameCol.addView(tc, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                r.addView(nameCol);
                r.addView(U.text(c, "", 14, U.MUTED, false));
                r.addView(U.text(c, "", 14, U.MUTED, false));
                LinearLayout pc = new LinearLayout(c);
                pc.setGravity(Gravity.CENTER_VERTICAL);
                TextView pill = U.text(c, "", 12, U.BLUE, true);
                pill.setGravity(Gravity.CENTER);
                pill.setPadding(px(10), 0, px(10), 0);
                pc.addView(pill, U.lp(ViewGroup.LayoutParams.WRAP_CONTENT, px(26)));
                r.addView(pc);
                r.setBackground(bottomLine());
                r.setTag(new Object[]{ic, nm, meta, r.getChildAt(1), r.getChildAt(2), pill});
            } else r = (LinearLayout) v;
            layoutColumns(r);
            Object[] t = (Object[]) r.getTag();
            Icon ic = (Icon) t[0];
            TextView nm = (TextView) t[1], meta = (TextView) t[2], mod = (TextView) t[3], size = (TextView) t[4], pill = (TextView) t[5];
            Entry e = shown.get(i);
            ic.id = e.dir ? Icon.FOLDER : Icon.FILE;
            ic.invalidate();
            nm.setText(e.name);
            meta.setText(e.meta == null ? "" : e.meta);
            meta.setVisibility(e.meta == null || e.meta.isEmpty() ? GONE : VISIBLE);
            mod.setText(e.dir ? "" : U.when(e.modified));
            size.setText(e.dir || e.recent != null ? "" : U.size(e.size));
            String p = null;
            if (e.recent != null) {
                if (e.recent.draft && host.isCurrent(e.recent)) p = "Unsaved draft";
                else if (e.recent.count > 0) p = e.recent.count + " comment" + (e.recent.count == 1 ? "" : "s");
            } else if (!e.dir) {
                if (e.isComments) p = "Comments file";
                else if (e.commentsUri != null) {
                    Recents.Item rr = host.recents().find(e.commentsUri.toString());
                    p = rr != null && rr.count > 0 ? rr.count + " comment" + (rr.count == 1 ? "" : "s") : "Has comments";
                }
            }
            if (p == null) { pill.setText(e.dir ? "" : "—"); pill.setTextColor(U.MUTED); pill.setBackground(null); pill.setPadding(0, 0, 0, 0); }
            else { pill.setText(p); pill.setTextColor(U.BLUE); pill.setBackground(U.round(U.BLUE_BG, 13)); pill.setPadding(px(10), 0, px(10), 0); }
            return r;
        }
    }

    private android.graphics.drawable.Drawable bottomLine() {
        android.graphics.drawable.ShapeDrawable line = new android.graphics.drawable.ShapeDrawable();
        line.getPaint().setColor(U.LINE2);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                new android.graphics.drawable.ColorDrawable(Color.WHITE), line});
        ld.setLayerGravity(1, Gravity.BOTTOM);
        ld.setLayerHeight(1, px(1));
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x14000000), ld, null);
    }
}
