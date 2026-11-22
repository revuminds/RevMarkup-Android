package com.revtools.revmarkup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity implements PageView.Listener {
    private static final int REQ_OPEN = 1, REQ_SAVE = 2, REQ_TREE = 3, REQ_TREE_SAVE = 4;
    private static final int BAR = 0xFF1E2329, RAIL = 0xFF262C33, ACCENT = 0xFFE53935;
    private static final String[] COLORS = {"#E53935", "#1E88E5", "#43A047", "#FB8C00", "#FDD835", "#212121"};

    private PdfWorker worker;
    private PageView view;
    private TextView title, pageLabel, empty;
    private Icon undoB, redoB, deleteB, prevB, nextB, saveB;
    private final List<Icon> toolIcons = new ArrayList<>();
    private final List<View> swatches = new ArrayList<>();
    private final List<TextView> sizeViews = new ArrayList<>();
    private SharedPreferences prefs;
    private float dp;

    // current file
    private PdfDoc doc;
    private String srcName;
    private Uri srcUri, outUri;
    private File work;       // private working copy of the opened PDF
    private Uri treeUri;     // linked folder the file came from (null: opened from elsewhere)
    private String folderId; // document id of the file's folder inside treeUri
    private FilesPanel files;
    private View anchor;     // where the long-press menu opens

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        installCrashLog();
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("revmarkup", MODE_PRIVATE);
        worker = new PdfWorker();
        File dir = new File(getFilesDir(), "work");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        work = new File(dir, "current.pdf");
        buildUi();
        view.fingerDraw = prefs.getBoolean("fingerDraw", false);
        restoreDraft(() -> handleIntent(getIntent()));
        showCrashIfAny();
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleIntent(i); }

    private void handleIntent(Intent i) {
        if (i == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) u = i.getData();
        else if (Intent.ACTION_SEND.equals(i.getAction())) u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null) { final Uri fu = u; setIntent(new Intent()); confirmDiscard(() -> open(fu, null, null, false)); }
    }

    @Override protected void onPause() { super.onPause(); saveDraft(); }

    // ------------------------------------------------------------ UI
    private int px(float v) { return Math.round(v * dp); }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BAR);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(s.left, s.top, s.right, s.bottom);
            return WindowInsets.CONSUMED;
        });

        // top bar
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(px(10), 0, px(4), 0);
        TextView logo = new TextView(this);
        logo.setText("Rev");
        logo.setTextColor(Color.WHITE);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setTextSize(13);
        logo.setGravity(Gravity.CENTER);
        GradientDrawable lg = new GradientDrawable();
        lg.setColor(ACCENT);
        lg.setCornerRadius(px(6));
        logo.setBackground(lg);
        bar.addView(logo, new LinearLayout.LayoutParams(px(36), px(30)));
        title = new TextView(this);
        title.setTextColor(0xFFE8EAED);
        title.setTextSize(15);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setPadding(px(12), 0, px(8), 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        prevB = barIcon(bar, Icon.PREV, v -> view.showPage(view.page - 1));
        pageLabel = new TextView(this);
        pageLabel.setTextColor(0xFFE8EAED);
        pageLabel.setTextSize(14);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setMinWidth(px(70));
        pageLabel.setOnClickListener(v -> askPage());
        bar.addView(pageLabel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(48)));
        nextB = barIcon(bar, Icon.NEXT, v -> view.showPage(view.page + 1));
        spacer(bar, 16);
        deleteB = barIcon(bar, Icon.DELETE, v -> view.deleteSelected());
        undoB = barIcon(bar, Icon.UNDO, v -> view.undo());
        redoB = barIcon(bar, Icon.REDO, v -> view.redo());
        spacer(bar, 12);
        barIcon(bar, Icon.OPEN, v -> showFiles());
        saveB = barIcon(bar, Icon.SAVE, v -> save(false));
        barIcon(bar, Icon.SHARE, v -> share());
        Icon more = barIcon(bar, Icon.MORE, null);
        more.setOnClickListener(v -> moreMenu(more));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(52)));

        LinearLayout body = new LinearLayout(this);
        // tool rail
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setPadding(0, px(6), 0, px(6));
        int[][] tools = {{Icon.SELECT, PageView.SELECT}, {Icon.PEN, PageView.PEN}, {Icon.HIGHLIGHT, PageView.HIGHLIGHT},
                {Icon.TEXT, PageView.TEXT}, {Icon.BOX, PageView.BOX}, {Icon.ELLIPSE, PageView.ELLIPSE},
                {Icon.CLOUD, PageView.CLOUD}, {Icon.ARROW, PageView.ARROW}, {Icon.LINE, PageView.LINE},
                {Icon.ERASER, PageView.ERASER}};
        for (int[] t : tools) {
            Icon ic = new Icon(this, t[0]);
            final int tool = t[1];
            ic.more = variantNames(tool) != null;
            ic.setOnClickListener(v -> { if (view.tool == tool && ic.more) variantMenu(ic, tool); else setTool(tool); });
            ic.setOnLongClickListener(v -> { setTool(tool); if (ic.more) variantMenu(ic, tool); return true; });
            ic.setContentDescription(toolName(tool));
            ic.setTooltipText(toolName(tool));
            rail.addView(ic, new LinearLayout.LayoutParams(px(60), px(52)));
            toolIcons.add(ic);
        }
        divider(rail);
        for (String c : COLORS) {
            View sw = new View(this);
            sw.setTag(c);
            sw.setOnClickListener(v -> setColor(c));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(30), px(30));
            lp.setMargins(0, px(5), 0, px(5));
            rail.addView(sw, lp);
            swatches.add(sw);
        }
        divider(rail);
        String[] sz = {"S", "M", "L"};
        for (int i = 0; i < 3; i++) {
            TextView t = new TextView(this);
            t.setText(sz[i]);
            t.setGravity(Gravity.CENTER);
            t.setTextSize(14);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            final int idx = i;
            t.setOnClickListener(v -> setSize(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(40), px(36));
            lp.setMargins(0, px(2), 0, px(2));
            rail.addView(t, lp);
            sizeViews.add(t);
        }
        ScrollView railScroll = new ScrollView(this);
        railScroll.setBackgroundColor(RAIL);
        railScroll.setVerticalScrollBarEnabled(false);
        railScroll.addView(rail);
        body.addView(railScroll, new LinearLayout.LayoutParams(px(64), ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout stage = new FrameLayout(this);
        view = new PageView(this, worker);
        view.setListener(this);
        stage.addView(view);
        empty = new TextView(this);
        empty.setText("Open a PDF\n\nfrom OneDrive, Files or any app with “Open with → RevMarkup”");
        empty.setTextColor(0xFF5F6368);
        empty.setTextSize(17);
        empty.setGravity(Gravity.CENTER);
        empty.setOnClickListener(v -> showFiles());
        stage.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        anchor = new View(this);
        stage.addView(anchor, new FrameLayout.LayoutParams(1, 1));
        files = new FilesPanel(this, filesHost);
        files.setVisibility(View.GONE);
        stage.addView(files, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stage.setBackgroundColor(0xFFE4E7EB);
        body.addView(stage, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        hideNavBar();

        view.tool = prefs.getInt("tool", PageView.PEN);
        view.color = prefs.getString("color", COLORS[0]);
        view.sizeIdx = prefs.getInt("size", 1);
        for (int t = 0; t < view.variant.length; t++) view.variant[t] = prefs.getInt("var" + t, 0);
        refreshTools();
        refreshBar();
    }

    /** Hide the gesture bar: a pen or palm near the bottom edge must not send the app Home.
     *  A swipe from the edge shows it again for a moment. */
    private void hideNavBar() {
        android.view.WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsets.Type.navigationBars());
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideNavBar();
    }

    private Icon barIcon(LinearLayout bar, int id, View.OnClickListener l) {
        Icon ic = new Icon(this, id);
        if (l != null) ic.setOnClickListener(l);
        bar.addView(ic, new LinearLayout.LayoutParams(px(48), px(48)));
        return ic;
    }

    private void spacer(LinearLayout bar, int w) { bar.addView(new View(this), new LinearLayout.LayoutParams(px(w), 1)); }

    private void divider(LinearLayout rail) {
        View d = new View(this);
        d.setBackgroundColor(0xFF3C4450);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(36), px(1));
        lp.setMargins(0, px(8), 0, px(8));
        rail.addView(d, lp);
    }

    private static String toolName(int t) {
        switch (t) {
            case PageView.SELECT: return "Select / move (tap a selected text to edit)";
            case PageView.PEN: return "Pen";
            case PageView.HIGHLIGHT: return "Highlighter";
            case PageView.TEXT: return "Text";
            case PageView.BOX: return "Box";
            case PageView.ELLIPSE: return "Ellipse";
            case PageView.CLOUD: return "Revision cloud";
            case PageView.ARROW: return "Arrow";
            case PageView.LINE: return "Line";
            default: return "Eraser (or hold the pen button)";
        }
    }

    private static String[] variantNames(int t) {
        switch (t) {
            case PageView.PEN: return new String[]{"Freehand", "Straight line"};
            case PageView.HIGHLIGHT: return new String[]{"Freehand", "Straight"};
            case PageView.BOX: return new String[]{"Rectangle", "Rounded rectangle"};
            case PageView.ELLIPSE: return new String[]{"Ellipse", "Circle"};
            case PageView.CLOUD: return new String[]{"Rectangle", "Ellipse", "Freehand (follows the pen, closes itself)"};
            case PageView.ARROW: case PageView.LINE:
                return new String[]{"Straight", "Multi-point (tap the last point again to finish)"};
            default: return null;
        }
    }

    private void variantMenu(View anchorView, int tool) {
        String[] names = variantNames(tool);
        if (names == null) return;
        PopupMenu m = new PopupMenu(this, anchorView, Gravity.END);
        for (int i = 0; i < names.length; i++) m.getMenu().add(0, i, i, names[i]).setCheckable(true).setChecked(view.variant[tool] == i);
        m.getMenu().setGroupCheckable(0, true, true);
        m.setOnMenuItemClickListener(it -> {
            view.finishPoly();
            view.variant[tool] = it.getItemId();
            prefs.edit().putInt("var" + tool, it.getItemId()).apply();
            return true;
        });
        m.show();
    }

    private void setTool(int t) {
        view.finishPoly();
        view.tool = t;
        prefs.edit().putInt("tool", t).apply();
        refreshTools();
    }

    private void setColor(String c) {
        view.color = c;
        prefs.edit().putString("color", c).apply();
        if (view.sel != null) view.styleSelected();
        refreshTools();
    }

    private void setSize(int i) {
        view.sizeIdx = i;
        prefs.edit().putInt("size", i).apply();
        if (view.sel != null) view.styleSelected();
        refreshTools();
    }

    private void refreshTools() {
        for (int i = 0; i < toolIcons.size(); i++) { toolIcons.get(i).active = i == view.tool; toolIcons.get(i).invalidate(); }
        for (View sw : swatches) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Color.parseColor((String) sw.getTag()));
            boolean on = sw.getTag().equals(view.color);
            g.setStroke(px(on ? 3 : 1), on ? Color.WHITE : 0xFF5F6368);
            sw.setBackground(g);
        }
        for (int i = 0; i < 3; i++) {
            TextView t = sizeViews.get(i);
            boolean on = i == view.sizeIdx;
            t.setTextColor(on ? Color.WHITE : 0xFF9AA0A6);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(8));
            g.setColor(on ? 0xFF3C4450 : Color.TRANSPARENT);
            t.setBackground(g);
        }
    }

    private void refreshBar() {
        boolean has = doc != null;
        title.setText(has ? (view.dirty ? "● " : "") + srcName : "RevMarkup");
        pageLabel.setText(has ? (view.page + 1) + " / " + doc.pages() : "");
        prevB.setEnabled(has && view.page > 0);
        nextB.setEnabled(has && view.page < doc.pages() - 1);
        undoB.setEnabled(view.canUndo());
        redoB.setEnabled(view.canRedo());
        deleteB.setVisibility(view.sel != null ? View.VISIBLE : View.GONE);
        saveB.setEnabled(has);
        empty.setVisibility(has ? View.GONE : View.VISIBLE);
        for (View v : new View[]{prevB, nextB, undoB, redoB, saveB}) v.invalidate();
    }

    @Override public void onMarksChanged() { refreshBar(); }
    @Override public void onPageChanged() { refreshBar(); }
    @Override public void onSelectionChanged() { refreshBar(); }

    @Override public void onTextRequest(float x, float y, Mk edit) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setMinLines(2);
        if (edit != null) { et.setText(edit.text); et.setSelection(et.length()); }
        et.setHint("Comment text (you can write with the pen)");
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(edit == null ? "Add text" : "Edit text")
                .setView(pad(et))
                .setPositiveButton("OK", (dlg, w) -> {
                    String s = et.getText().toString();
                    if (edit != null) view.replaceText(edit, s);
                    else if (!s.trim().isEmpty()) view.addText(x, y, s);
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.setOnShowListener(x2 -> {
            et.requestFocus();
            InputMethodManager im = getSystemService(InputMethodManager.class);
            et.postDelayed(() -> im.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT), 150);
        });
        d.show();
    }

    private View pad(View v) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(px(20), px(8), px(20), 0);
        f.addView(v);
        return f;
    }

    private void askPage() {
        if (doc == null) return;
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setHint("1 – " + doc.pages());
        new AlertDialog.Builder(this).setTitle("Go to sheet").setView(pad(et))
                .setPositiveButton("Go", (d, w) -> {
                    try { view.showPage(Integer.parseInt(et.getText().toString().trim()) - 1); } catch (NumberFormatException ignored) { }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void moreMenu(View anchor) {
        PopupMenu m = new PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Save as…");
        m.getMenu().add(0, 2, 0, "Your name on comments…");
        m.getMenu().add(0, 3, 0, view.fingerDraw ? "✓ Draw with finger too" : "Draw with finger too");
        m.getMenu().add(0, 4, 0, "Fit sheet");
        m.getMenu().add(0, 5, 0, "About & licences");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: save(true); break;
                case 2: askAuthor(null); break;
                case 3:
                    view.fingerDraw = !view.fingerDraw;
                    prefs.edit().putBoolean("fingerDraw", view.fingerDraw).apply();
                    toast(view.fingerDraw ? "Finger draws; use two fingers to move." : "Only the pen draws; fingers move and zoom.");
                    break;
                case 4: view.fit(); break;
                default: about();
            }
            return true;
        });
        m.show();
    }

    private void about() {
        String lic;
        try (InputStream in = getAssets().open("pdfium_LICENSE.txt")) { lic = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        catch (Exception e) { lic = ""; }
        TextView t = new TextView(this);
        t.setText("RevMarkup for Android · RevTools\n\nPen draws · fingers pan & zoom · double-tap to zoom · swipe to change sheet · "
                + "two-finger tap = undo · pen button or pen end = eraser.\n\nPDF engine: PDFium (BSD-3-Clause / Apache-2.0).\n\n" + lic);
        t.setTextSize(12);
        t.setPadding(px(20), px(12), px(20), px(12));
        ScrollView s = new ScrollView(this);
        s.addView(t);
        new AlertDialog.Builder(this).setTitle("About").setView(s).setPositiveButton("OK", null).show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void confirmDiscard(Runnable then) {
        if (doc == null || !view.dirty) { then.run(); return; }
        new AlertDialog.Builder(this).setTitle("Unsaved markups")
                .setMessage("“" + srcName + "” has markups that are not saved.")
                .setPositiveButton("Save first", (d, w) -> save(false, then))
                .setNegativeButton("Discard", (d, w) -> { view.dirty = false; then.run(); })
                .setNeutralButton("Cancel", null).show();
    }

    // ------------------------------------------------------------ open
    @Override public void onQuickMenu(float sx, float sy, float x, float y) {
        anchor.setX(sx); anchor.setY(sy);
        PopupMenu m = new PopupMenu(this, anchor);
        String[] items = {"Text here", "Cloud", "Pen", "Arrow", "Box", "Select", "Fit sheet"};
        for (int i = 0; i < items.length; i++) m.getMenu().add(0, i, i, items[i]);
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 0: onTextRequest(x, y, null); break;
                case 1: setTool(PageView.CLOUD); break;
                case 2: setTool(PageView.PEN); break;
                case 3: setTool(PageView.ARROW); break;
                case 4: setTool(PageView.BOX); break;
                case 5: setTool(PageView.SELECT); break;
                default: view.fit();
            }
            return true;
        });
        m.show();
    }

    // ------------------------------------------------------------ file list / linked folders
    private List<Uri> linked() {
        List<Uri> l = new ArrayList<>();
        for (String s : prefs.getString("trees", "").split("\n")) if (!s.isEmpty()) l.add(Uri.parse(s));
        return l;
    }

    private void link(Uri tree) {
        List<Uri> l = linked();
        if (!l.contains(tree)) l.add(0, tree);
        StringBuilder b = new StringBuilder();
        for (Uri u : l) b.append(u).append('\n');
        prefs.edit().putString("trees", b.toString()).apply();
    }

    private final FilesPanel.Host filesHost = new FilesPanel.Host() {
        @Override public void onOpenFile(Uri doc, Uri tree, String folder, String name, Uri commentsUri) {
            if (commentsUri == null) { closeFiles(); confirmDiscard(() -> open(doc, tree, folder, false)); return; }
            new AlertDialog.Builder(MainActivity.this).setTitle(commentsName(name) + " exists")
                    .setMessage("This drawing already has a comments file. Continue the comments, or start again from the original?")
                    .setPositiveButton("Continue comments", (d, w) -> { closeFiles(); confirmDiscard(() -> open(commentsUri, tree, folder, false)); })
                    .setNegativeButton("Open original", (d, w) -> { closeFiles(); confirmDiscard(() -> open(doc, tree, folder, false)); })
                    .setNeutralButton("Cancel", null).show();
        }
        @Override public void onLinkFolder() { pickTree(REQ_TREE, null); }
        @Override public void onOtherLocation() { closeFiles(); confirmDiscard(MainActivity.this::pickFile); }
        @Override public void onClosePanel() { closeFiles(); }
        @Override public List<Uri> linkedFolders() { return linked(); }
        @Override public void unlinkFolder(Uri tree) {
            List<Uri> l = linked();
            l.remove(tree);
            StringBuilder b = new StringBuilder();
            for (Uri u : l) b.append(u).append('\n');
            prefs.edit().putString("trees", b.toString()).apply();
            try { getContentResolver().releasePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) { }
        }
    };

    private void showFiles() {
        view.finishPoly();
        if (files.currentTree() == null || !linked().contains(files.currentTree())) {
            Uri last = Uri.parse(prefs.getString("lastTree", ""));
            String path = prefs.getString("lastPath", "");
            if (linked().contains(last) && !path.isEmpty()) {
                List<String[]> p = new ArrayList<>();
                for (String seg : path.split("\n")) { int k = seg.indexOf('\t'); if (k > 0) p.add(new String[]{seg.substring(0, k), seg.substring(k + 1)}); }
                if (p.isEmpty()) files.showRoot(); else files.showFolder(last, p);
            } else files.showRoot();
        }
        files.setVisibility(View.VISIBLE);
    }

    private void closeFiles() {
        if (files.currentTree() != null) {
            StringBuilder b = new StringBuilder();
            for (String[] s : files.currentPath()) b.append(s[0]).append('\t').append(s[1]).append('\n');
            prefs.edit().putString("lastTree", files.currentTree().toString()).putString("lastPath", b.toString()).apply();
        }
        files.setVisibility(View.GONE);
    }

    @Override public void onBackPressed() {
        if (files.getVisibility() == View.VISIBLE) { closeFiles(); return; }
        super.onBackPressed();
    }

    private void pickTree(int req, Uri initial) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (initial != null) i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initial);
        try { startActivityForResult(i, req); } catch (Exception e) { toast("This device can't pick folders."); }
    }

    /** The file's folder inside a linked folder: {tree, folderId}, or null. Worker thread. */
    private String[] folderOf(Uri doc, List<Uri> trees) {
        if (doc == null) return null;
        String id;
        try { id = android.provider.DocumentsContract.getDocumentId(doc); } catch (Exception e) { return null; }
        for (Uri t : trees) {
            if (!String.valueOf(t.getAuthority()).equals(doc.getAuthority())) continue;
            try {
                Uri d = android.provider.DocumentsContract.buildDocumentUriUsingTree(t, id);
                android.provider.DocumentsContract.Path p = android.provider.DocumentsContract.findDocumentPath(getContentResolver(), d);
                if (p != null && p.getPath().size() >= 2) return new String[]{t.toString(), p.getPath().get(p.getPath().size() - 2)};
            } catch (Exception ignored) { }
        }
        return null;
    }

    // ------------------------------------------------------------ crash report
    private void installCrashLog() {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        final File f = new File(getFilesDir(), "crash.txt");
        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        final String v = ver;
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            try (java.io.PrintWriter w = new java.io.PrintWriter(f, "UTF-8")) {
                w.println("RevMarkup " + v + " · Android " + android.os.Build.VERSION.RELEASE + " · " + android.os.Build.MODEL
                        + " · " + new java.util.Date() + " · thread " + t.getName());
                ex.printStackTrace(w);
            } catch (Exception ignored) { }
            if (def != null) def.uncaughtException(t, ex);
        });
    }

    private void showCrashIfAny() {
        File f = new File(getFilesDir(), "crash.txt");
        if (!f.exists()) return;
        String txt;
        try (InputStream in = new FileInputStream(f)) { txt = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        catch (Exception e) { txt = e.toString(); }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        TextView t = new TextView(this);
        t.setText(txt);
        t.setTextSize(11);
        t.setTextIsSelectable(true);
        t.setPadding(px(20), px(8), px(20), px(8));
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        final String report = txt;
        new AlertDialog.Builder(this).setTitle("RevMarkup closed unexpectedly last time")
                .setMessage("Please send a screenshot of this (or tap Copy and paste it) to fix it.")
                .setView(sv)
                .setPositiveButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("RevMarkup crash", report));
                })
                .setNegativeButton("Close", null).show();
    }

    private void pickFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        if (srcUri != null) i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, srcUri);   // start where the last file was
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_OPEN);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) { pendingAfterSave = null; return; }
        Uri u = data.getData();
        keepPermission(u, data.getFlags());
        if (req == REQ_OPEN) open(u, null, null, true);
        else if (req == REQ_SAVE) { outUri = u; doSave(u, true); }
        else if (req == REQ_TREE || req == REQ_TREE_SAVE) {
            try { getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
            catch (Exception e) { toast("This location can't be kept - choose another folder."); pendingAfterSave = null; return; }
            link(u);
            if (req == REQ_TREE) {
                files.showFolder(u, java.util.Collections.singletonList(new String[]{
                        android.provider.DocumentsContract.getTreeDocumentId(u), "Linked folder"}));
                files.setVisibility(View.VISIBLE);
            } else {
                final Uri src = srcUri; final String[][] f = new String[1][];
                final Runnable after = pendingAfterSave;
                worker.job(() -> f[0] = folderOf(src, java.util.Collections.singletonList(u)), err -> {
                    treeUri = u;
                    folderId = f[0] != null ? f[0][1] : android.provider.DocumentsContract.getTreeDocumentId(u);
                    save(false, after);
                });
            }
        }
    }

    private void keepPermission(Uri u, int flags) {
        int f = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(u, f); } catch (Exception ignored) { /* not offered by this app */ }
    }

    private String displayName(Uri u) {
        String n = null;
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) n = c.getString(0);
        } catch (Exception ignored) { }
        if (n == null) n = u.getLastPathSegment();
        if (n == null || n.isEmpty()) n = "drawing.pdf";
        return n.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private void open(Uri u, Uri tree, String folder, boolean fromPicker) {
        final String name = displayName(u);
        final ContentResolver cr = getContentResolver();
        title.setText("Opening " + name + "…");
        final File tmp = new File(work.getParentFile(), "incoming.pdf");
        final PdfDoc[] out = new PdfDoc[1];
        final PdfDoc old = doc;
        doc = null;
        view.setDoc(null, new ArrayList<>(), 0);   // stop drawing the old document before the worker closes it
        worker.job(() -> {
            try (InputStream in = cr.openInputStream(u); OutputStream o = new FileOutputStream(tmp)) {
                if (in == null) throw new Exception("The file could not be read.");
                byte[] buf = new byte[1 << 16];
                for (int r; (r = in.read(buf)) > 0; ) o.write(buf, 0, r);
            }
            if (old != null) old.close();
            if (!tmp.renameTo(work)) throw new Exception("Could not prepare the file.");
            out[0] = PdfDoc.open(work);
        }, err -> {
            if (err != null) {
                view.setDoc(null, new ArrayList<>(), 0);
                doc = null;
                refreshBar();
                toast("Could not open “" + name + "”: " + err.getMessage());
                return;
            }
            doc = out[0];
            srcName = name;
            srcUri = u;
            treeUri = tree;
            folderId = folder;
            // a comments file saves back onto itself (when we may write it)
            outUri = name.toLowerCase().endsWith("_comments.pdf") && (fromPicker || tree != null) ? u : null;
            view.setDoc(doc, doc.loaded, 0);
            if (doc.keptForeign > 0)
                toast(doc.keptForeign + " desktop-only markup(s) (pins, symbols, images, filled shapes, callouts) are kept in the PDF but can't be edited here.");
            saveDraft();
        });
        Pdfium.nCancel();   // stop drawing the old file now
    }

    // ------------------------------------------------------------ save / share
    private Runnable pendingAfterSave;

    private void save(boolean asNew) { save(asNew, null); }

    private void save(boolean asNew, Runnable after) {
        if (doc == null) return;
        if (prefs.getString("author", "").isEmpty()) { askAuthor(() -> save(asNew, after)); return; }
        pendingAfterSave = after;
        if (asNew) { saveAsPicker(); return; }
        if (outUri != null) { doSave(outUri, false); return; }
        if (treeUri != null && folderId != null) { saveIntoFolder(); return; }
        // opened from elsewhere: is it inside a linked folder?
        final Uri src = srcUri; final List<Uri> trees = linked(); final String[][] f = new String[1][];
        worker.job(() -> f[0] = folderOf(src, trees), err -> {
            if (f[0] != null) { treeUri = Uri.parse(f[0][0]); folderId = f[0][1]; saveIntoFolder(); return; }
            new AlertDialog.Builder(this).setTitle("Save next to the original")
                    .setMessage("Android needs your OK once for this folder. In the next screen go to the folder that holds “"
                            + srcName + "” and tap “Use this folder”. After that, saving there is automatic.")
                    .setPositiveButton("Choose folder", (d, w) -> pickTree(REQ_TREE_SAVE, srcUri))
                    .setNegativeButton("Save elsewhere…", (d, w) -> saveAsPicker())
                    .setNeutralButton("Cancel", (d, w) -> pendingAfterSave = null).show();
        });
    }

    private void saveAsPicker() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/pdf");
        i.putExtra(Intent.EXTRA_TITLE, commentsName(srcName));
        if (srcUri != null) i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, srcUri);
        startActivityForResult(i, REQ_SAVE);
    }

    /** name_comments.pdf in the folder the file was opened from: replace (after asking) or create. */
    private void saveIntoFolder() {
        final Uri t = treeUri; final String fid = folderId; final String want = commentsName(srcName);
        final ContentResolver cr = getContentResolver();
        final Uri[] found = new Uri[1];
        title.setText("Saving…");
        worker.job(() -> {
            Uri kids = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(t, fid);
            try (Cursor c = cr.query(kids, new String[]{android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                while (c != null && c.moveToNext())
                    if (want.equalsIgnoreCase(c.getString(1))) { found[0] = android.provider.DocumentsContract.buildDocumentUriUsingTree(t, c.getString(0)); break; }
            }
        }, err -> {
            if (err != null) { refreshBar(); toast("The folder can't be read: " + err.getMessage()); saveAsPicker(); return; }
            if (found[0] != null) {
                new AlertDialog.Builder(this).setTitle("Replace " + want + "?")
                        .setMessage("A comments file with this name is already in the folder. Saving replaces it with this version.\n\n"
                                + "Tip: to add to those comments instead, open " + want + " itself.")
                        .setPositiveButton("Replace", (d, w) -> { outUri = found[0]; doSave(found[0], false); })
                        .setNegativeButton("Save as…", (d, w) -> saveAsPicker())
                        .setNeutralButton("Cancel", (d, w) -> { pendingAfterSave = null; refreshBar(); }).show();
                return;
            }
            final Uri[] made = new Uri[1];
            worker.job(() -> made[0] = android.provider.DocumentsContract.createDocument(cr,
                    android.provider.DocumentsContract.buildDocumentUriUsingTree(t, fid), "application/pdf", want), e2 -> {
                if (e2 != null || made[0] == null) { refreshBar(); toast("Can't create the file in that folder - choose where to save."); saveAsPicker(); return; }
                outUri = made[0];
                doSave(made[0], true);
            });
        });
    }

    static String commentsName(String n) {
        String base = n.toLowerCase().endsWith(".pdf") ? n.substring(0, n.length() - 4) : n;
        return base.toLowerCase().endsWith("_comments") ? base + ".pdf" : base + "_comments.pdf";
    }

    private void doSave(Uri target, boolean chosen) {
        final List<Mk> snap = new ArrayList<>(view.marks);
        final String author = prefs.getString("author", "RevMarkup");
        final PdfDoc d = doc;
        final ContentResolver cr = getContentResolver();
        final File tmp = new File(work.getParentFile(), "saving.pdf");
        title.setText("Saving…");
        worker.job(() -> {
            d.save(snap, author, tmp);
            OutputStream o;
            try { o = cr.openOutputStream(target, "wt"); } catch (Exception e) { o = cr.openOutputStream(target, "w"); }
            if (o == null) throw new Exception("No permission to write there.");
            try (OutputStream os = o; InputStream in = new FileInputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                for (int r; (r = in.read(buf)) > 0; ) os.write(buf, 0, r);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }, err -> {
            if (err != null) {
                refreshBar();
                if (!chosen) {   // the earlier location can no longer be written: ask for a place
                    outUri = null;
                    toast("Could not write to the earlier file - choose where to save.");
                    save(true, pendingAfterSave);
                } else {
                    pendingAfterSave = null;
                    toast("Save failed: " + err.getMessage());
                }
                return;
            }
            if (view.marks == snap || sameList(view.marks, snap)) view.dirty = false;
            refreshBar();
            saveDraft();
            toast("Saved " + displayName(target));
            Runnable r = pendingAfterSave;
            pendingAfterSave = null;
            if (r != null) r.run();
        });
    }

    private static boolean sameList(List<Mk> a, List<Mk> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) if (a.get(i) != b.get(i)) return false;
        return true;
    }

    private void share() {
        if (outUri == null || view.dirty) {
            if (doc != null) save(false, this::share);
            else toast("Open and save a PDF first.");
            return;
        }
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType("application/pdf");
        s.putExtra(Intent.EXTRA_STREAM, outUri);
        s.putExtra(Intent.EXTRA_SUBJECT, "Comments: " + displayName(outUri));
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(s, "Send marked-up PDF")); }
        catch (Exception e) { toast("No app found to share with."); }
    }

    private void askAuthor(Runnable then) {
        EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setText(prefs.getString("author", ""));
        et.setHint("e.g. Raju");
        new AlertDialog.Builder(this).setTitle("Your name").setMessage("Shown as the author of your comments in the PDF.")
                .setView(pad(et))
                .setPositiveButton("OK", (d, w) -> {
                    String s = et.getText().toString().trim();
                    if (s.isEmpty()) return;
                    prefs.edit().putString("author", s.substring(0, Math.min(60, s.length()))).apply();
                    if (then != null) then.run();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ------------------------------------------------------------ draft (survives the app being closed)
    private File draftFile() { return new File(getFilesDir(), "draft.txt"); }

    private void saveDraft() {
        File f = draftFile();
        if (doc == null) { if (!work.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete(); return; }
        File tmp = new File(getFilesDir(), "draft.tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write("rmd2\n" + srcName + "\n" + (srcUri == null ? "" : srcUri) + "\n" + (outUri == null ? "" : outUri)
                    + "\n" + (view.dirty ? 1 : 0) + "\n" + view.page + "\n" + (treeUri == null ? "" : treeUri) + "\n"
                    + (folderId == null ? "" : folderId) + "\n");
            for (Mk m : view.marks) w.write(m.serialize() + "\n");
        } catch (Exception e) { return; }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
    }

    private void restoreDraft(Runnable then) {
        File f = draftFile();
        if (!f.exists() || !work.exists()) { then.run(); return; }
        final String[] head = new String[8];
        final List<Mk> marks = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (int i = 0; i < 6; i++) head[i] = r.readLine();
            if ("rmd2".equals(head[0])) { head[6] = r.readLine(); head[7] = r.readLine(); }
            else if (!"rmd1".equals(head[0])) { then.run(); return; }
            for (String l; (l = r.readLine()) != null; ) { Mk m = Mk.parse(l); if (m != null) marks.add(m); }
        } catch (Exception e) { then.run(); return; }
        final PdfDoc[] out = new PdfDoc[1];
        worker.job(() -> out[0] = PdfDoc.open(work), err -> {
            if (err == null) {
                doc = out[0];
                srcName = head[1];
                srcUri = head[2] == null || head[2].isEmpty() ? null : Uri.parse(head[2]);
                outUri = head[3] == null || head[3].isEmpty() ? null : Uri.parse(head[3]);
                treeUri = head[6] == null || head[6].isEmpty() ? null : Uri.parse(head[6]);
                folderId = head[7] == null || head[7].isEmpty() ? null : head[7];
                int pg = 0;
                try { pg = Integer.parseInt(head[5]); } catch (Exception ignored) { }
                view.setDoc(doc, marks, pg);
                view.dirty = "1".equals(head[4]);
                refreshBar();
            }
            then.run();
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        final PdfDoc d = doc;
        if (d != null && isFinishing()) worker.job(d::close, null);
    }
}
