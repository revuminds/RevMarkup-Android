package com.revtools.revmarkup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.revtools.revmarkup.U.px;

public final class MainActivity extends Activity implements PageView.Listener {
    private static final int REQ_OPEN = 1, REQ_SAVE = 2, REQ_TREE = 3, REQ_TREE_SAVE = 4, REQ_TREE_TABLET = 5;

    private PdfWorker worker;
    private PageView view;
    private SharedPreferences prefs;
    private Tools tools;
    private Recents recents;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    // screens
    private HomeView home;
    private FrameLayout editor;
    private boolean editorShown;

    // editor chrome
    private TextView titleT, whereT, pageLabel, chipT;
    private View titleCol, chip;
    private LinearLayout toolCard, row2;
    private Icon undoB, redoB, prevB, nextB, saveB;
    private final List<Icon> toolIcons = new ArrayList<>();
    private View anchor;
    private boolean cardLeft;   // narrow screen: tool card next to the back button instead of centred

    // current file
    private PdfDoc doc;
    private String srcName, where = "";
    private Uri srcUri, outUri;
    private File work;       // private working copy of the opened PDF
    private Uri treeUri;     // linked folder the file came from (null: opened from elsewhere)
    private String folderId; // document id of the file's folder inside treeUri
    private long draftAt, savedAt;

    // diagnostics: last touch before the app left the screen
    private String lastDown = "-";
    private long lastDownAt;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        installCrashLog();
        U.dp = getResources().getDisplayMetrics().density;
        Diag.init(this);
        logExitReason();
        Diag.log("start " + version());
        prefs = getSharedPreferences("revmarkup", MODE_PRIVATE);
        tools = new Tools(prefs);
        recents = new Recents(this, prefs);
        worker = new PdfWorker();
        File dir = new File(getFilesDir(), "work");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        work = new File(dir, "current.pdf");
        buildUi();
        view.fingerDraw = prefs.getBoolean("fingerDraw", false);
        restoreDraft(() -> { home.restore(); handleIntent(getIntent()); });
        showCrashIfAny();
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); handleIntent(i); }

    private void handleIntent(Intent i) {
        if (i == null) return;
        Uri u = null;
        if (Intent.ACTION_VIEW.equals(i.getAction())) u = i.getData();
        else if (Intent.ACTION_SEND.equals(i.getAction())) u = i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (u != null) {
            final Uri fu = u;
            setIntent(new Intent());
            confirmDiscard(() -> open(fu, null, null, false, HomeView.provider(fu.getAuthority())));
        }
    }

    @Override protected void onResume() { super.onResume(); Diag.log("resume"); }

    @Override protected void onPause() {
        super.onPause();
        Diag.log("pause · last touch " + lastDown + " " + (System.currentTimeMillis() - lastDownAt) + " ms before");
        saveDraft();
        saveThumb();
    }

    @Override protected void onStop() { super.onStop(); Diag.log("stop"); }

    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        Diag.log("user left the app (Home / recents / other app) · last touch " + lastDown + " "
                + (System.currentTimeMillis() - lastDownAt) + " ms before");
    }

    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); Diag.log("trim memory " + level); }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        Diag.log("configuration changed: " + c.orientation + " " + c.screenWidthDp + "x" + c.screenHeightDp + "dp");
    }

    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            int i = e.getActionIndex(), t = e.getToolType(i);
            float x = e.getX(i), y = e.getY(i);
            View r = getWindow().getDecorView();
            String edge = x < px(24) ? " LEFT EDGE" : x > r.getWidth() - px(24) ? " RIGHT EDGE"
                    : y > r.getHeight() - px(24) ? " BOTTOM EDGE" : y < px(24) ? " TOP EDGE" : "";
            lastDown = (t == MotionEvent.TOOL_TYPE_STYLUS ? "pen" : t == MotionEvent.TOOL_TYPE_ERASER ? "pen-eraser"
                    : t == MotionEvent.TOOL_TYPE_FINGER ? (e.getTouchMajor(i) > px(60) ? "palm" : "finger") : "tool" + t)
                    + " at " + Math.round(x / U.dp) + "," + Math.round(y / U.dp) + "dp" + edge;
            lastDownAt = System.currentTimeMillis();
        }
        return super.dispatchTouchEvent(e);
    }

    private String version() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    private void logExitReason() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
            List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(null, 0, 1);
            if (l.isEmpty()) return;
            android.app.ApplicationExitInfo x = l.get(0);
            SharedPreferences p = getSharedPreferences("revmarkup", MODE_PRIVATE);
            if (p.getLong("exitTs", 0) == x.getTimestamp()) return;
            p.edit().putLong("exitTs", x.getTimestamp()).apply();
            Diag.log("previous run ended: reason " + x.getReason() + " (" + x.getDescription() + ") at "
                    + new SimpleDateFormat("dd MMM HH:mm:ss", Locale.US).format(new Date(x.getTimestamp())));
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ UI
    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(U.BG);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(s.left, s.top, s.right, s.bottom);
            return WindowInsets.CONSUMED;
        });

        home = new HomeView(this, homeHost, prefs);
        root.addView(home, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        editor = new FrameLayout(this);
        editor.setBackgroundColor(U.CANVAS);
        view = new PageView(this, worker);
        view.setListener(this);
        // pen strokes that start near the left / right edge must not become the system Back gesture
        view.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) ->
                v.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, r - l, bt - t))));
        editor.addView(view);
        anchor = new View(this);
        editor.addView(anchor, new FrameLayout.LayoutParams(1, 1));
        buildTopLeft();
        buildToolCard();
        buildRightColumn();
        buildBottom();
        editor.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> v.post(() -> adjustTop(v.getWidth())));
        toolCard.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> editor.post(() -> adjustTop(editor.getWidth())));
        editor.setVisibility(View.GONE);
        root.addView(editor, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applyBars();

        tools.apply(view);
        refreshToolIcons();
        buildRow2();
        refreshBar();
    }

    private void buildTopLeft() {
        LinearLayout tl = new LinearLayout(this);
        tl.setGravity(Gravity.CENTER_VERTICAL);
        Icon back = new Icon(this, Icon.BACK);
        back.glyph = 0.4f; back.color = U.INK;
        U.card(back, 16, 4);
        back.setContentDescription("Back to files");
        back.setOnClickListener(v -> goHome());
        tl.addView(back, U.lp(px(56), px(56)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(px(14), 0, 0, 0);
        titleT = U.text(this, "", 15, U.INK, true);
        titleT.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        whereT = U.text(this, "", 12, U.MUTED, false);
        whereT.setPadding(0, px(3), 0, 0);
        col.addView(titleT);
        col.addView(whereT);
        titleCol = col;
        tl.addView(col);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        p.setMargins(px(16), px(16), 0, 0);
        editor.addView(tl, p);
    }

    private void buildToolCard() {
        toolCard = new LinearLayout(this);
        toolCard.setOrientation(LinearLayout.VERTICAL);
        U.card(toolCard, 20, 6);
        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        row1.setPadding(px(10), 0, px(10), 0);
        for (int t = 0; t < Tools.N; t++) {
            Icon ic = new Icon(this, Tools.ICONS[t]);
            final int tool = t;
            ic.setContentDescription(Tools.NAMES[t]);
            ic.setTooltipText(Tools.NAMES[t]);
            ic.setOnClickListener(v -> selectTool(tool));
            row1.addView(ic, U.lp(px(48), px(48), px(2), 0, px(2), 0));
            toolIcons.add(ic);
        }
        toolCard.addView(row1, U.lp(ViewGroup.LayoutParams.WRAP_CONTENT, px(60)));
        toolCard.addView(U.divider(this, false, U.LINE2));
        row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        row2.setPadding(px(14), 0, px(14), 0);
        toolCard.addView(row2, U.lp(ViewGroup.LayoutParams.WRAP_CONTENT, px(56)));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        p.topMargin = px(16);
        editor.addView(toolCard, p);
    }

    private void buildRightColumn() {
        LinearLayout rc = new LinearLayout(this);
        rc.setOrientation(LinearLayout.VERTICAL);
        rc.setGravity(Gravity.CENTER_HORIZONTAL);
        rc.setPadding(0, px(8), 0, px(8));
        U.card(rc, 20, 6);
        saveB = colButton(rc, Icon.SAVE, "Save", v -> save(false));
        saveB.active = true; saveB.activeBg = U.ACCENT; saveB.activeColor = Color.WHITE;
        undoB = colButton(rc, Icon.UNDO, "Undo", v -> view.undo());
        redoB = colButton(rc, Icon.REDO, "Redo", v -> view.redo());
        colButton(rc, Icon.LIST, "Comment list", v -> commentList());
        colButton(rc, Icon.SHEETS, "Sheets", v -> sheetList());
        colButton(rc, Icon.SHARE, "Share", v -> share());
        Icon more = colButton(rc, Icon.MORE, "More", null);
        more.setOnClickListener(v -> moreMenu(more));
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(px(64), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
        p.setMargins(0, px(16), px(16), 0);
        editor.addView(rc, p);
    }

    private Icon colButton(LinearLayout col, int id, String name, View.OnClickListener l) {
        Icon ic = new Icon(this, id);
        ic.glyph = 0.42f;
        ic.setContentDescription(name);
        ic.setTooltipText(name);
        if (l != null) ic.setOnClickListener(l);
        col.addView(ic, U.lp(px(48), px(48), 0, px(2), 0, px(2)));
        return ic;
    }

    private void buildBottom() {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(px(12), 0, px(12), 0);
        c.setBackground(U.round(0xE6FFFFFF, 16));
        View dot = new View(this);
        dot.setBackground(U.round(U.GREEN, 4));
        c.addView(dot, U.lp(px(8), px(8), 0, 0, px(8), 0));
        chipT = U.text(this, "", 12, 0xFF4A4F59, false);
        c.addView(chipT);
        chip = c;
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(32), Gravity.BOTTOM | Gravity.START);
        p.setMargins(px(16), 0, 0, px(14));
        editor.addView(c, p);

        LinearLayout pg = new LinearLayout(this);
        pg.setGravity(Gravity.CENTER_VERTICAL);
        pg.setPadding(px(4), 0, px(4), 0);
        pg.setBackground(U.round(U.DARK, 12));
        prevB = darkIcon(pg, Icon.PREV, "Previous sheet", v -> view.showPage(view.page - 1));
        pageLabel = U.text(this, "", 13, U.DARK_TEXT, false);
        pageLabel.setPadding(px(6), 0, px(6), 0);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setOnClickListener(v -> askPage());
        pg.addView(pageLabel, U.lp(ViewGroup.LayoutParams.WRAP_CONTENT, px(44)));
        nextB = darkIcon(pg, Icon.NEXT, "Next sheet", v -> view.showPage(view.page + 1));
        View d = new View(this);
        d.setBackgroundColor(0xFF3A3D44);
        pg.addView(d, U.lp(px(1), px(24), px(4), 0, px(4), 0));
        TextView fit = U.text(this, "Fit", 13, U.DARK_TEXT, false);
        fit.setGravity(Gravity.CENTER);
        fit.setPadding(px(12), 0, px(12), 0);
        fit.setOnClickListener(v -> view.fit());
        pg.addView(fit, U.lp(ViewGroup.LayoutParams.WRAP_CONTENT, px(40)));
        FrameLayout.LayoutParams q = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(44), Gravity.BOTTOM | Gravity.END);
        q.setMargins(0, 0, px(16), px(12));
        editor.addView(pg, q);
    }

    private Icon darkIcon(LinearLayout row, int id, String name, View.OnClickListener l) {
        Icon ic = new Icon(this, id);
        ic.glyph = 0.42f; ic.color = U.DARK_TEXT;
        ic.setContentDescription(name);
        ic.setOnClickListener(l);
        row.addView(ic, U.lp(px(40), px(40)));
        return ic;
    }

    /** Keep file name, tool card and right column apart on narrow (portrait) screens. */
    private void adjustTop(int w) {
        if (w <= 0) return;
        int cw = toolCard.getWidth();
        if (cw <= 0) return;
        int leftOfCard = (w - cw) / 2;
        boolean left = leftOfCard < px(16 + 56 + 12);
        if (left != cardLeft) {
            cardLeft = left;
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) toolCard.getLayoutParams();
            p.gravity = Gravity.TOP | (left ? Gravity.START : Gravity.CENTER_HORIZONTAL);
            p.leftMargin = left ? px(16 + 56 + 12) : 0;
            toolCard.setLayoutParams(p);
        }
        int room = left ? 0 : leftOfCard - px(16 + 56 + 14 + 12);
        boolean showTitle = room >= px(90);
        int want = showTitle ? Math.min(px(260), room) : 0;
        if ((titleCol.getVisibility() == View.VISIBLE) != showTitle || (showTitle && titleT.getMaxWidth() != want)) {
            titleCol.setVisibility(showTitle ? View.VISIBLE : View.GONE);
            if (showTitle) { titleT.setMaxWidth(want); whereT.setMaxWidth(want); }
        }
    }

    /** Hide the gesture bar (a pen or palm near the bottom edge must not send the app Home); in the editor the
     *  status bar too. A swipe from the edge shows them again for a moment. */
    private void applyBars() {
        if (Build.VERSION.SDK_INT < 30) return;
        android.view.WindowInsetsController c = getWindow().getInsetsController();
        if (c == null) return;
        c.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        c.hide(WindowInsets.Type.navigationBars());
        if (editorShown) c.hide(WindowInsets.Type.statusBars()); else c.show(WindowInsets.Type.statusBars());
        c.setSystemBarsAppearance(android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyBars();
    }

    private void showEditor() {
        if (doc == null) return;
        editorShown = true;
        home.setVisibility(View.GONE);
        editor.setVisibility(View.VISIBLE);
        applyBars();
        refreshBar();
    }

    private void goHome() {
        view.finishPoly();
        saveDraft();
        saveThumb();
        editorShown = false;
        editor.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        applyBars();
        home.refresh();
    }

    @Override public void onBackPressed() {
        Diag.log("back pressed (" + (editorShown ? "editor" : "home") + ") · last touch " + lastDown);
        if (editorShown) { goHome(); return; }
        if (home.up()) return;
        moveTaskToBack(true);   // keep the open drawing; never lose work to a stray back gesture
    }

    // ------------------------------------------------------------ tools
    private void selectTool(int t) {
        if (t == tools.current && t != Tools.LASSO) return;
        tools.select(t);
        tools.apply(view);
        refreshToolIcons();
        buildRow2();
        view.invalidate();
    }

    private void refreshToolIcons() {
        for (int i = 0; i < toolIcons.size(); i++) {
            Icon ic = toolIcons.get(i);
            ic.active = i == tools.current;
            ic.bar = i == Tools.ERASER || i == Tools.LASSO ? 0 : U.parse(tools.s[i].color(), 0);
            ic.invalidate();
        }
    }

    private void toolChanged() {
        tools.save(tools.current);
        tools.apply(view);
        refreshToolIcons();
        buildRow2();
        view.invalidate();
    }

    private double unit() { return doc != null && view.doc != null ? view.unit() : 1; }

    /** Row 2: the settings of the active tool. */
    private void buildRow2() {
        row2.removeAllViews();
        int t = tools.current;
        Tools.S s = tools.cur();
        switch (t) {
            case Tools.PEN: case Tools.FINE: case Tools.PENCIL:
                addLineStyle(s); sep(); addColors(s); sep(); addSizes(t, s);
                break;
            case Tools.HL:
                addColors(s); sep(); addSizes(t, s); sep();
                addToggle("Straight", s.type == 1, v -> { s.type = s.type == 1 ? 0 : 1; toolChanged(); });
                break;
            case Tools.ERASER:
                addSizes(t, s); sep();
                addToggle("Whole stroke", s.erase == 0, v -> { s.erase = 0; toolChanged(); });
                addToggle("Partial · soon", false, v -> toast("Partial erasing comes in a later update. For now a touch removes the whole markup."));
                break;
            case Tools.TEXT: case Tools.NOTE:
                addColors(s); sep(); addSizes(t, s); sep();
                addToggle("Box border", s.border, v -> { s.border = !s.border; toolChanged(); });
                break;
            case Tools.SHAPES:
                addTypeChip(Tools.SHAPE_ICONS[s.type], null, Tools.SHAPE_NAMES, s); sep();
                addColors(s); sep(); addSizes(t, s); sep(); addLineStyle(s);
                break;
            case Tools.CLOUD:
                addTypeChip(0, Tools.CLOUD_NAMES[Math.min(2, s.type)], Tools.CLOUD_NAMES, s); sep();
                addColors(s); sep(); addSizes(t, s);
                break;
            default: buildLassoRow();
        }
    }

    private void sep() { row2.addView(U.divider(this, true, U.LINE)); }

    private void addColors(Tools.S s) {
        for (int i = 0; i < 3; i++) {
            final int k = i;
            U.Swatch w = swatch(U.Swatch.COLOR);
            w.color = U.parse(s.colors[i], Color.RED);
            w.on = s.colorSel == i;
            w.setContentDescription("Colour " + (i + 1) + " (long-press to change)");
            w.setOnClickListener(v -> { s.colorSel = k; toolChanged(); });
            w.setOnLongClickListener(v -> { palette("Replace colour " + (k + 1), c -> { s.colors[k] = c; s.colorSel = k; toolChanged(); }); return true; });
        }
        if (!s.custom.isEmpty()) {
            U.Swatch w = swatch(U.Swatch.COLOR);
            w.color = U.parse(s.custom, Color.RED);
            w.on = s.colorSel == 3;
            w.setContentDescription("Custom colour");
            w.setOnClickListener(v -> { s.colorSel = 3; toolChanged(); });
        }
        U.Swatch plus = swatch(U.Swatch.PLUS);
        plus.setContentDescription("Custom colour");
        plus.setOnClickListener(v -> palette("Custom colour", c -> { s.custom = c; s.colorSel = 3; toolChanged(); }));
    }

    private void addSizes(int t, Tools.S s) {
        String[] names = {"Fine", "Medium", "Thick"};
        for (int i = 0; i < 3; i++) {
            final int k = i;
            U.Swatch w = swatch(U.Swatch.SIZE);
            w.dot = Tools.sizeDot(t, i);
            w.on = s.size == i;
            w.color = t == Tools.HL ? U.parse(s.color(), 0) : 0;
            w.setContentDescription(names[i]);
            w.setOnClickListener(v -> { s.size = k; toolChanged(); });
        }
        String lab = Tools.sizeLabel(t, s.size, unit());
        if (!lab.isEmpty()) {
            TextView l = U.text(this, lab, 12, U.MUTED, false);
            l.setPadding(px(4), 0, px(2), 0);
            row2.addView(l);
        }
    }

    private U.Swatch swatch(int kind) {
        U.Swatch w = new U.Swatch(this, kind);
        row2.addView(w, U.lp(px(38), px(40), px(1), 0, px(1), 0));
        return w;
    }

    private void addToggle(String label, boolean on, View.OnClickListener l) {
        TextView t = U.text(this, label, 13, on ? U.BLUE : U.INK2, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(12), 0, px(12), 0);
        t.setBackground(on ? U.round(U.BLUE_BG, 12, U.BLUE, 1.5f) : U.round(U.ACTIVE, 12));
        t.setOnClickListener(l);
        row2.addView(t, U.lp(ViewGroup.LayoutParams.WRAP_CONTENT, px(38), px(3), 0, px(3), 0));
    }

    private void addTypeChip(int icon, String label, String[] names, Tools.S s) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(px(8), 0, px(6), 0);
        c.setBackground(U.round(U.ACTIVE, 12));
        if (label == null) {
            Icon ic = new Icon(this, icon);
            ic.glyph = 0.9f; ic.color = U.INK; ic.setClickable(false);
            c.addView(ic, U.lp(px(24), px(24)));
        } else c.addView(U.text(this, label, 13, U.INK, true));
        Icon ch = new Icon(this, Icon.CHEVRON);
        ch.glyph = 0.8f; ch.color = U.INK; ch.setClickable(false);
        c.addView(ch, U.lp(px(16), px(16), px(4), 0, 0, 0));
        c.setContentDescription("Type");
        c.setOnClickListener(v -> {
            PopupMenu m = new PopupMenu(this, c);
            for (int i = 0; i < names.length; i++) m.getMenu().add(0, i, i, names[i]).setCheckable(true).setChecked(s.type == i);
            m.getMenu().setGroupCheckable(0, true, true);
            m.setOnMenuItemClickListener(it -> { s.type = it.getItemId(); toolChanged(); return true; });
            m.show();
        });
        row2.addView(c, U.lp(ViewGroup.LayoutParams.WRAP_CONTENT, px(40), 0, 0, px(2), 0));
    }

    private void addLineStyle(Tools.S s) {
        LinearLayout c = new LinearLayout(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(px(10), 0, px(8), 0);
        c.setBackground(U.round(U.ACTIVE, 12));
        LinePreview lp = new LinePreview(this, s.line, 2);
        c.addView(lp, U.lp(px(30), px(16)));
        Icon ch = new Icon(this, Icon.CHEVRON);
        ch.glyph = 0.8f; ch.color = U.INK; ch.setClickable(false);
        c.addView(ch, U.lp(px(14), px(14), px(6), 0, 0, 0));
        c.setContentDescription("Line style");
        c.setOnClickListener(v -> lineStylePopover(c, s));
        row2.addView(c, U.lp(ViewGroup.LayoutParams.WRAP_CONTENT, px(40), 0, 0, px(2), 0));
    }

    private void buildLassoRow() {
        Mk sel = view.sel;
        if (sel == null) {
            TextView h = U.text(this, "Tap a markup to select it · drag to move it", 13, U.MUTED, false);
            h.setPadding(px(4), 0, px(4), 0);
            row2.addView(h);
            return;
        }
        Tools.S s = tools.cur();
        for (int i = 0; i < 3; i++) {
            final String col = s.colors[i];
            U.Swatch w = swatch(U.Swatch.COLOR);
            w.color = U.parse(col, Color.RED);
            w.on = col.equalsIgnoreCase(sel.color);
            w.setContentDescription("Colour " + (i + 1) + " (long-press to change)");
            w.setOnClickListener(v -> styleSel(col, -1));
            final int k = i;
            w.setOnLongClickListener(v -> { palette("Replace colour " + (k + 1), c -> { s.colors[k] = c; tools.save(Tools.LASSO); styleSel(c, -1); }); return true; });
        }
        U.Swatch plus = swatch(U.Swatch.PLUS);
        plus.setOnClickListener(v -> palette("Colour", c -> styleSel(c, -1)));
        sep();
        int t = sel.k == Mk.HIGHLIGHT ? Tools.HL : sel.k == Mk.TEXT ? Tools.TEXT : Tools.PEN;
        for (int i = 0; i < 3; i++) {
            final int k = i;
            U.Swatch w = swatch(U.Swatch.SIZE);
            w.dot = Tools.sizeDot(t, i);
            w.on = sel.size == i;
            w.setOnClickListener(v -> styleSel(null, k));
        }
        sep();
        if (sel.k == Mk.TEXT) addToggle("Edit text", false, v -> onTextRequest(sel.x(0), sel.y(0), sel));
        TextView del = U.text(this, "Delete", 13, U.ACCENT, true);
        del.setGravity(Gravity.CENTER);
        del.setPadding(px(12), 0, px(12), 0);
        del.setBackground(U.round(0xFFFBEAEA, 12));
        del.setOnClickListener(v -> view.deleteSelected());
        row2.addView(del, U.lp(ViewGroup.LayoutParams.WRAP_CONTENT, px(38), px(3), 0, 0, 0));
    }

    private void styleSel(String color, int size) {
        Mk sel = view.sel;
        if (sel == null) return;
        String c0 = view.color; int s0 = view.sizeIdx;
        view.color = color != null ? color : sel.color;
        view.sizeIdx = size >= 0 ? size : sel.size;
        view.styleSelected();
        view.color = c0; view.sizeIdx = s0;
        buildRow2();
    }

    interface ColorPick { void pick(String c); }

    private void palette(String title, ColorPick cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), px(12), px(20), 0);
        GridLayout g = new GridLayout(this);
        g.setColumnCount(6);
        final AlertDialog[] d = new AlertDialog[1];
        for (String c : Tools.PALETTE) {
            U.Swatch w = new U.Swatch(this, U.Swatch.COLOR);
            w.color = U.parse(c, Color.RED);
            w.setContentDescription(c);
            w.setOnClickListener(v -> { cb.pick(c); d[0].dismiss(); });
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            p.width = px(44); p.height = px(44);
            g.addView(w, p);
        }
        box.addView(g);
        EditText hex = new EditText(this);
        hex.setHint("or type a colour, e.g. #D3262F");
        hex.setSingleLine(true);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(hex);
        d[0] = new AlertDialog.Builder(this).setTitle(title).setView(box)
                .setPositiveButton("Use typed colour", (dl, w) -> {
                    String s = hex.getText().toString().trim();
                    if (!s.startsWith("#")) s = "#" + s;
                    if (Tools.valid(s)) cb.pick(s.toUpperCase(Locale.ROOT)); else toast("Type a colour like #D3262F.");
                })
                .setNegativeButton("Cancel", null).create();
        d[0].show();
    }

    /** Line style popover: Variable / Fixed / Dashed / Dotted, stabilization, hold to snap. */
    private void lineStylePopover(View anchorV, Tools.S s) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(px(16), px(16), px(16), px(16));
        U.card(c, 16, 8);
        final PopupWindow[] pw = new PopupWindow[1];
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int k = 0; k < 2; k++) {
                final int st = r * 2 + k;
                boolean on = s.line == st;
                LinearLayout tile = new LinearLayout(this);
                tile.setOrientation(LinearLayout.VERTICAL);
                tile.setGravity(Gravity.CENTER);
                tile.setBackground(on ? U.round(0xFFEEF3FC, 12, U.BLUE, 2) : U.round(0xFFF3F4F6, 12));
                tile.addView(new LinePreview(this, st, 2), U.lp(px(40), px(14)));
                TextView l = U.text(this, Tools.LINE_NAMES[st], 13, U.INK, false);
                l.setPadding(0, px(4), 0, 0);
                tile.addView(l);
                tile.setOnClickListener(v -> { s.line = st; tools.save(tools.current); pw[0].dismiss(); buildRow2(); });
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, px(64), 1);
                p.setMargins(k == 0 ? 0 : px(5), r == 0 ? 0 : px(10), k == 0 ? px(5) : 0, 0);
                row.addView(tile, p);
            }
            c.addView(row, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        c.addView(U.divider(this, false, U.LINE), U.lp(ViewGroup.LayoutParams.MATCH_PARENT, px(1), 0, px(14), 0, px(12)));
        LinearLayout sh = new LinearLayout(this);
        sh.addView(U.text(this, "Stabilization", 14, U.INK, true), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView sv = U.text(this, String.format(Locale.US, "%.1f", s.stab), 14, U.INK, true);
        sh.addView(sv);
        c.addView(sh);
        SeekBar sb = new SeekBar(this);
        sb.setMax(10);
        sb.setProgress(Math.round(s.stab * 10));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int p, boolean user) { s.stab = p / 10f; sv.setText(String.format(Locale.US, "%.1f", s.stab)); }
            @Override public void onStartTrackingTouch(SeekBar b) { }
            @Override public void onStopTrackingTouch(SeekBar b) { tools.save(tools.current); }
        });
        c.addView(sb, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, px(40)));
        Switch snap = new Switch(this);
        snap.setText("Hold to snap shape");
        snap.setTextSize(14);
        snap.setTextColor(U.INK);
        snap.setChecked(s.snap);
        snap.setOnCheckedChangeListener((b, on) -> { s.snap = on; tools.save(tools.current); });
        c.addView(snap, U.lp(ViewGroup.LayoutParams.MATCH_PARENT, px(44)));
        TextView note = U.text(this, "Fixed lines are drawn today. Variable, dashed and dotted lines, stabilization and "
                + "snapping are kept with this tool and start working in the next update.", 12, U.MUTED, false);
        note.setSingleLine(false);
        note.setPadding(0, px(6), 0, 0);
        c.addView(note);
        PopupWindow w = new PopupWindow(c, px(300), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.setOutsideTouchable(true);
        w.setElevation(px(8));
        pw[0] = w;
        w.showAsDropDown(anchorV, 0, px(14));
    }

    /** Little line sample: 0 variable, 1 fixed, 2 dashed, 3 dotted. */
    static final class LinePreview extends View {
        private final int style; private final float width;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        LinePreview(android.content.Context c, int style, float widthDp) {
            super(c);
            this.style = style; this.width = widthDp;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(U.INK);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            path.reset();
            path.moveTo(w * 0.05f, h * 0.7f);
            path.cubicTo(w * 0.3f, -h * 0.2f, w * 0.45f, h * 0.2f, w * 0.5f, h * 0.55f);
            path.cubicTo(w * 0.6f, h * 1.1f, w * 0.85f, h * 0.9f, w * 0.95f, h * 0.25f);
            p.setPathEffect(style == 2 ? new DashPathEffect(new float[]{px(5), px(3)}, 0)
                    : style == 3 ? new DashPathEffect(new float[]{0.1f, px(3.5f)}, 0) : null);
            if (style == 0) {   // thin to thick
                Path a = new Path(), b = new Path();
                a.moveTo(w * 0.05f, h * 0.7f); a.cubicTo(w * 0.3f, -h * 0.2f, w * 0.45f, h * 0.2f, w * 0.5f, h * 0.55f);
                b.moveTo(w * 0.5f, h * 0.55f); b.cubicTo(w * 0.6f, h * 1.1f, w * 0.85f, h * 0.9f, w * 0.95f, h * 0.25f);
                p.setStrokeWidth(px(width * 0.5f)); c.drawPath(a, p);
                p.setStrokeWidth(px(width * 1.5f)); c.drawPath(b, p);
            } else {
                p.setStrokeWidth(px(style == 3 ? width * 1.2f : width));
                c.drawPath(path, p);
            }
        }
    }

    // ------------------------------------------------------------ bars / listener
    private void refreshBar() {
        boolean has = doc != null;
        titleT.setText(has ? (view.dirty ? "● " : "") + srcName : "");
        whereT.setText(where == null ? "" : where);
        whereT.setVisibility(where == null || where.isEmpty() ? View.GONE : View.VISIBLE);
        pageLabel.setText(has ? "Sheet " + (view.page + 1) + " / " + doc.pages() : "");
        prevB.setEnabled(has && view.page > 0);
        nextB.setEnabled(has && view.page < doc.pages() - 1);
        undoB.setEnabled(view.canUndo());
        redoB.setEnabled(view.canRedo());
        saveB.setEnabled(has);
        for (View v : new View[]{prevB, nextB, undoB, redoB, saveB}) v.invalidate();
        String hm = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(view.dirty ? draftAt : savedAt));
        chipT.setText(!has ? "" : view.dirty ? (draftAt > 0 ? "Draft autosaved " + hm : "Not saved yet")
                : savedAt > 0 ? "Saved " + hm : "No changes");
        chip.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    private final Runnable autosave = this::saveDraft;

    @Override public void onMarksChanged() {
        refreshBar();
        handler.removeCallbacks(autosave);
        handler.postDelayed(autosave, 2500);
        if (tools.current == Tools.LASSO) buildRow2();
    }

    @Override public void onPageChanged() {
        refreshBar();
        if (tools.current != Tools.LASSO && tools.current != Tools.ERASER) buildRow2();   // mm label depends on sheet size
    }

    @Override public void onSelectionChanged() {
        if (view.sel != null && tools.current != Tools.LASSO) {   // selected with the v1 select gesture: show its actions
            tools.select(Tools.LASSO);
            tools.apply(view);
            refreshToolIcons();
        }
        if (tools.current == Tools.LASSO) buildRow2();
    }

    @Override public void onTextRequest(float x, float y, Mk edit) {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        et.setMinLines(2);
        if (edit != null) { et.setText(edit.text); et.setSelection(et.length()); }
        et.setHint("Comment text (you can write with the pen)");
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(edit == null ? (tools.current == Tools.NOTE ? "Add comment note" : "Add text") : "Edit text")
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

    @Override public void onQuickMenu(float sx, float sy, float x, float y) {
        anchor.setX(sx); anchor.setY(sy);
        PopupMenu m = new PopupMenu(this, anchor);
        String[] items = {"Text here", "Revision cloud", "Pen", "Arrow", "Box", "Lasso select", "Fit sheet"};
        for (int i = 0; i < items.length; i++) m.getMenu().add(0, i, i, items[i]);
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 0: onTextRequest(x, y, null); break;
                case 1: selectTool(Tools.CLOUD); break;
                case 2: selectTool(Tools.PEN); break;
                case 3: tools.s[Tools.SHAPES].type = 5; tools.save(Tools.SHAPES); tools.current = -1; selectTool(Tools.SHAPES); break;
                case 4: tools.s[Tools.SHAPES].type = 0; tools.save(Tools.SHAPES); tools.current = -1; selectTool(Tools.SHAPES); break;
                case 5: selectTool(Tools.LASSO); break;
                default: view.fit();
            }
            return true;
        });
        m.show();
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

    private static String kindName(Mk m) {
        switch (m.k) {
            case Mk.PEN: return "Pen";
            case Mk.HIGHLIGHT: return "Highlight";
            case Mk.TEXT: return "Text";
            case Mk.ARROW: return m.heads == 0 ? "Line" : "Arrow";
            case Mk.BOX: return "Box";
            case Mk.CLOUD: return "Cloud";
            case Mk.ELLIPSE: return "Ellipse";
            default: return "Markup";
        }
    }

    private void commentList() {
        if (doc == null) return;
        final List<Mk> l = new ArrayList<>(view.marks);
        if (l.isEmpty()) { toast("No markups yet."); return; }
        l.sort((a, b) -> a.page != b.page ? Integer.compare(a.page, b.page) : Integer.compare(a.seq, b.seq));
        CharSequence[] items = new CharSequence[l.size()];
        for (int i = 0; i < l.size(); i++) {
            Mk m = l.get(i);
            String t = m.text == null ? "" : m.text.replace('\n', ' ').trim();
            if (t.length() > 70) t = t.substring(0, 70) + "…";
            SpannableString s = new SpannableString("●  Sheet " + (m.page + 1) + " · " + kindName(m) + (t.isEmpty() ? "" : ": " + t));
            s.setSpan(new ForegroundColorSpan(m.argb() | 0xFF000000), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            items[i] = s;
        }
        new AlertDialog.Builder(this).setTitle(l.size() + " markup" + (l.size() == 1 ? "" : "s")
                        + (doc.keptForeign > 0 ? " (+" + doc.keptForeign + " desktop-only)" : ""))
                .setItems(items, (d, w) -> { selectTool(Tools.LASSO); view.select(l.get(w)); })
                .setNegativeButton("Close", null).show();
    }

    private void sheetList() {
        if (doc == null) return;
        int n = doc.pages();
        int[] count = new int[n];
        for (Mk m : view.marks) if (m.page >= 0 && m.page < n) count[m.page]++;
        CharSequence[] items = new CharSequence[n];
        for (int i = 0; i < n; i++)
            items[i] = (i == view.page ? "▸ " : "   ") + "Sheet " + (i + 1) + (count[i] > 0 ? "   ·   " + count[i] + " markup" + (count[i] == 1 ? "" : "s") : "");
        new AlertDialog.Builder(this).setTitle("Sheets").setItems(items, (d, w) -> view.showPage(w))
                .setNegativeButton("Close", null).show();
    }

    private void moreMenu(View a) {
        PopupMenu m = new PopupMenu(this, a);
        m.getMenu().add(0, 1, 0, "Save as…");
        m.getMenu().add(0, 2, 0, "Go to sheet…");
        m.getMenu().add(0, 3, 0, "Fit sheet");
        m.getMenu().add(0, 4, 0, "Settings…");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: save(true); break;
                case 2: askPage(); break;
                case 3: view.fit(); break;
                default: settings();
            }
            return true;
        });
        m.show();
    }

    private void settings() {
        String name = prefs.getString("author", "");
        CharSequence[] items = {
                "Your name on comments:  " + (name.isEmpty() ? "not set" : name),
                "Draw with finger too:  " + (view.fingerDraw ? "On" : "Off (only the pen draws)"),
                "Diagnostics (why the app left the screen)",
                "About & licences"};
        new AlertDialog.Builder(this).setTitle("Settings").setItems(items, (d, w) -> {
            switch (w) {
                case 0: askAuthor(this::settings); break;
                case 1:
                    view.fingerDraw = !view.fingerDraw;
                    prefs.edit().putBoolean("fingerDraw", view.fingerDraw).apply();
                    settings();
                    break;
                case 2: diagnostics(); break;
                default: about();
            }
        }).setNegativeButton("Close", null).show();
    }

    private void diagnostics() {
        final String log = Diag.read();
        TextView t = new TextView(this);
        t.setText(log);
        t.setTextSize(11);
        t.setTextIsSelectable(true);
        t.setPadding(px(20), px(8), px(20), px(8));
        ScrollView s = new ScrollView(this);
        s.addView(t);
        new AlertDialog.Builder(this).setTitle("Diagnostics · newest first")
                .setMessage("If RevMarkup leaves the screen while you draw, send a screenshot or copy of this.")
                .setView(s)
                .setPositiveButton("Copy", (d, w) -> {
                    android.content.ClipboardManager cm = getSystemService(android.content.ClipboardManager.class);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("RevMarkup diagnostics", log));
                })
                .setNegativeButton("Close", null).show();
    }

    private void about() {
        String lic;
        try (InputStream in = getAssets().open("pdfium_LICENSE.txt")) { lic = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        catch (Exception e) { lic = ""; }
        TextView t = new TextView(this);
        t.setText("RevMarkup " + version() + " for Android · RevTools\n\nPen draws · fingers pan & zoom · double-tap = fit · "
                + "swipe at fit = next sheet · two-finger tap = undo · long-press = quick menu · pen button or pen end = eraser.\n\n"
                + "PDF engine: PDFium (BSD-3-Clause / Apache-2.0).\n\n" + lic);
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

    // ------------------------------------------------------------ home screen / linked folders
    private List<Uri> linked() {
        List<Uri> l = new ArrayList<>();
        for (String s : prefs.getString("trees", "").split("\n")) if (!s.isEmpty()) l.add(Uri.parse(s));
        return l;
    }

    private void storeLinked(List<Uri> l) {
        StringBuilder b = new StringBuilder();
        for (Uri u : l) b.append(u).append('\n');
        prefs.edit().putString("trees", b.toString()).apply();
    }

    private void link(Uri tree) {
        List<Uri> l = linked();
        if (!l.contains(tree)) l.add(tree);
        storeLinked(l);
    }

    private boolean isCurrent(Recents.Item r) {
        if (doc == null || srcUri == null || r == null) return false;
        String s = srcUri.toString();
        return s.equals(r.uri) || s.equals(r.out);
    }

    private final HomeView.Host homeHost = new HomeView.Host() {
        @Override public void onOpenFile(Uri d, Uri tree, String folder, String name, Uri commentsUri, String w) {
            if (doc != null && srcUri != null && (srcUri.equals(d) || srcUri.equals(commentsUri))) { showEditor(); return; }
            if (commentsUri == null) { confirmDiscard(() -> open(d, tree, folder, false, w)); return; }
            new AlertDialog.Builder(MainActivity.this).setTitle(commentsName(name) + " exists")
                    .setMessage("This drawing already has a comments file. Continue the comments, or start again from the original?")
                    .setPositiveButton("Continue comments", (dl, x) -> confirmDiscard(() -> open(commentsUri, tree, folder, false, w)))
                    .setNegativeButton("Open original", (dl, x) -> confirmDiscard(() -> open(d, tree, folder, false, w)))
                    .setNeutralButton("Cancel", null).show();
        }
        @Override public void onOpenRecent(Recents.Item r) {
            if (isCurrent(r)) { showEditor(); return; }
            final Uri u = Uri.parse(r.out.isEmpty() ? r.uri : r.out);
            final Uri tree = r.tree.isEmpty() ? null : Uri.parse(r.tree);
            final String folder = r.folder.isEmpty() ? null : r.folder;
            confirmDiscard(() -> open(u, tree, folder, false, r.where));
        }
        @Override public void onAddAccount() {
            new AlertDialog.Builder(MainActivity.this).setTitle("Add account")
                    .setMessage("In the next screen open the menu (☰), choose OneDrive or Google Drive, go to your project "
                            + "folder and tap “Use this folder”.\n\nRevMarkup then lists its PDFs here and saves name_comments.pdf "
                            + "next to each drawing. No password is given to RevMarkup.")
                    .setPositiveButton("Continue", (d, w) -> pickTree(REQ_TREE, null))
                    .setNegativeButton("Cancel", null).show();
        }
        @Override public void onLinkTablet() { pickTree(REQ_TREE_TABLET, null); }
        @Override public void onOpenOther() { confirmDiscard(MainActivity.this::pickFile); }
        @Override public void onSettings() { settings(); }
        @Override public List<Uri> linkedFolders() { return linked(); }
        @Override public void unlinkFolder(Uri tree) {
            List<Uri> l = linked();
            l.remove(tree);
            storeLinked(l);
            try { getContentResolver().releasePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) { }
        }
        @Override public Recents recents() { return recents; }
        @Override public boolean isCurrent(Recents.Item r) { return MainActivity.this.isCurrent(r); }
    };

    private void pickTree(int req, Uri initial) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (initial != null) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        try { startActivityForResult(i, req); } catch (Exception e) { toast("This device can't pick folders."); }
    }

    /** The file's folder inside a linked folder: {tree, folderId}, or null. Worker thread. */
    private String[] folderOf(Uri doc, List<Uri> trees) {
        if (doc == null) return null;
        String id;
        try { id = DocumentsContract.getDocumentId(doc); } catch (Exception e) { return null; }
        for (Uri t : trees) {
            if (!String.valueOf(t.getAuthority()).equals(doc.getAuthority())) continue;
            try {
                Uri d = DocumentsContract.buildDocumentUriUsingTree(t, id);
                DocumentsContract.Path p = DocumentsContract.findDocumentPath(getContentResolver(), d);
                if (p != null && p.getPath().size() >= 2) return new String[]{t.toString(), p.getPath().get(p.getPath().size() - 2)};
            } catch (Exception ignored) { }
        }
        return null;
    }

    // ------------------------------------------------------------ crash report
    private void installCrashLog() {
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        final File f = new File(getFilesDir(), "crash.txt");
        final String v = version();
        Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
            try (java.io.PrintWriter w = new java.io.PrintWriter(f, "UTF-8")) {
                w.println("RevMarkup " + v + " · Android " + Build.VERSION.RELEASE + " · " + Build.MODEL
                        + " · " + new Date() + " · thread " + t.getName());
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
        Diag.log("crash report shown: " + txt.split("\n")[0]);
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
        if (srcUri != null) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, srcUri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i, REQ_OPEN);
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) { pendingAfterSave = null; return; }
        Uri u = data.getData();
        keepPermission(u, data.getFlags());
        if (req == REQ_OPEN) open(u, null, null, true, HomeView.provider(u.getAuthority()));
        else if (req == REQ_SAVE) { outUri = u; doSave(u, true); }
        else {
            try { getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
            catch (Exception e) { toast("This location can't be kept - choose another folder."); pendingAfterSave = null; return; }
            link(u);
            if (req == REQ_TREE || req == REQ_TREE_TABLET) {
                home.openTreeRoot(u, HomeView.isTablet(u.getAuthority()));
            } else {
                final Uri src = srcUri; final String[][] f = new String[1][];
                final Runnable after = pendingAfterSave;
                worker.job(() -> f[0] = folderOf(src, Collections.singletonList(u)), err -> {
                    treeUri = u;
                    folderId = f[0] != null ? f[0][1] : DocumentsContract.getTreeDocumentId(u);
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

    private void open(Uri u, Uri tree, String folder, boolean fromPicker, String whereLabel) {
        final ContentResolver cr = getContentResolver();
        final File tmp = new File(work.getParentFile(), "incoming.pdf");
        final PdfDoc[] out = new PdfDoc[1];
        final String[] nm = new String[1];
        final PdfDoc old = doc;
        saveThumb();
        doc = null;
        view.setDoc(null, new ArrayList<>(), 0);   // stop drawing the old document before the worker closes it
        Toast.makeText(this, "Opening…", Toast.LENGTH_SHORT).show();
        worker.job(() -> {
            nm[0] = displayName(u);
            try (InputStream in = cr.openInputStream(u); OutputStream o = new FileOutputStream(tmp)) {
                if (in == null) throw new Exception("The file could not be read.");
                byte[] buf = new byte[1 << 16];
                for (int r; (r = in.read(buf)) > 0; ) o.write(buf, 0, r);
            }
            if (old != null) old.close();
            if (!tmp.renameTo(work)) throw new Exception("Could not prepare the file.");
            out[0] = PdfDoc.open(work);
        }, err -> {
            String name = nm[0] != null ? nm[0] : "the file";
            if (err != null) {
                view.setDoc(null, new ArrayList<>(), 0);
                doc = null;
                refreshBar();
                if (editorShown) goHome(); else home.refresh();
                toast("Could not open “" + name + "”: " + err.getMessage());
                return;
            }
            doc = out[0];
            srcName = name;
            srcUri = u;
            treeUri = tree;
            folderId = folder;
            where = whereLabel == null ? "" : whereLabel;
            draftAt = 0; savedAt = 0;
            // a comments file saves back onto itself (when we may write it)
            outUri = name.toLowerCase(Locale.ROOT).endsWith("_comments.pdf") && (fromPicker || tree != null) ? u : null;
            Recents.Item r = recents.touch(u.toString());
            r.name = name; r.tree = tree == null ? "" : tree.toString(); r.folder = folder == null ? "" : folder; r.where = where;
            if (r.page >= doc.pages()) r.page = 0;
            int startPage = r.page;
            view.setDoc(doc, doc.loaded, startPage);
            if (doc.keptForeign > 0)
                toast(doc.keptForeign + " desktop-only markup(s) (pins, symbols, images, filled shapes, callouts) are kept in the PDF but can't be edited here.");
            saveDraft();
            Diag.log("opened " + name);
            showEditor();
            tools.apply(view);
            buildRow2();
        });
        Pdfium.nCancel();   // stop drawing the old file now
    }

    // ------------------------------------------------------------ save / share
    private Runnable pendingAfterSave;

    private void save(boolean asNew) { save(asNew, null); }

    private void save(boolean asNew, Runnable after) {
        if (doc == null) return;
        view.finishPoly();
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
        if (srcUri != null) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, srcUri);
        startActivityForResult(i, REQ_SAVE);
    }

    /** name_comments.pdf in the folder the file was opened from: replace (after asking) or create. */
    private void saveIntoFolder() {
        final Uri t = treeUri; final String fid = folderId; final String want = commentsName(srcName);
        final ContentResolver cr = getContentResolver();
        final Uri[] found = new Uri[1];
        chipT.setText("Saving…");
        worker.job(() -> {
            Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(t, fid);
            try (Cursor c = cr.query(kids, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                while (c != null && c.moveToNext())
                    if (want.equalsIgnoreCase(c.getString(1))) { found[0] = DocumentsContract.buildDocumentUriUsingTree(t, c.getString(0)); break; }
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
            worker.job(() -> made[0] = DocumentsContract.createDocument(cr,
                    DocumentsContract.buildDocumentUriUsingTree(t, fid), "application/pdf", want), e2 -> {
                if (e2 != null || made[0] == null) { refreshBar(); toast("Can't create the file in that folder - choose where to save."); saveAsPicker(); return; }
                outUri = made[0];
                doSave(made[0], true);
            });
        });
    }

    static String commentsName(String n) {
        String base = n.toLowerCase(Locale.ROOT).endsWith(".pdf") ? n.substring(0, n.length() - 4) : n;
        return base.toLowerCase(Locale.ROOT).endsWith("_comments") ? base + ".pdf" : base + "_comments.pdf";
    }

    private void doSave(Uri target, boolean chosen) {
        final List<Mk> snap = new ArrayList<>(view.marks);
        final String author = prefs.getString("author", "RevMarkup");
        final PdfDoc d = doc;
        final ContentResolver cr = getContentResolver();
        final File tmp = new File(work.getParentFile(), "saving.pdf");
        chipT.setText("Saving…");
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
            savedAt = System.currentTimeMillis();
            Recents.Item r = recents.find(srcUri.toString());
            if (r != null && !target.equals(srcUri)) r.out = target.toString();
            saveDraft();
            refreshBar();
            toast("Saved " + displayName(target));
            Runnable run = pendingAfterSave;
            pendingAfterSave = null;
            if (run != null) run.run();
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

    // ------------------------------------------------------------ draft (survives the app being closed) + recents
    private File draftFile() { return new File(getFilesDir(), "draft.txt"); }

    private void saveDraft() {
        handler.removeCallbacks(autosave);
        File f = draftFile();
        if (doc == null) { if (!work.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete(); return; }
        File tmp = new File(getFilesDir(), "draft.tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write("rmd3\n" + srcName + "\n" + (srcUri == null ? "" : srcUri) + "\n" + (outUri == null ? "" : outUri)
                    + "\n" + (view.dirty ? 1 : 0) + "\n" + view.page + "\n" + (treeUri == null ? "" : treeUri) + "\n"
                    + (folderId == null ? "" : folderId) + "\n" + (where == null ? "" : where.replace('\n', ' ')) + "\n");
            for (Mk m : view.marks) w.write(m.serialize() + "\n");
        } catch (Exception e) { return; }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(f);
        if (view.dirty) draftAt = System.currentTimeMillis();
        updateRecent();
        refreshBar();
    }

    private void updateRecent() {
        if (doc == null || srcUri == null) return;
        Recents.Item r = recents.find(srcUri.toString());
        if (r == null) { r = recents.touch(srcUri.toString()); r.name = srcName; r.where = where; }
        for (Recents.Item o : recents.list) if (o != r) o.draft = false;
        r.page = view.page;
        r.pages = doc.pages();
        r.count = view.marks.size() + doc.keptForeign;
        r.draft = view.dirty;
        if (outUri != null && !outUri.equals(srcUri)) r.out = outUri.toString();
        recents.save();
    }

    private void saveThumb() {
        if (doc == null || srcUri == null) return;
        Recents.Item r = recents.find(srcUri.toString());
        if (r == null) return;
        final Bitmap b = view.thumb(240);
        if (b == null) return;
        final File f = recents.thumb(r);
        bg.execute(() -> {
            File tmp = new File(f.getPath() + ".tmp");
            try (OutputStream o = new FileOutputStream(tmp)) { b.compress(Bitmap.CompressFormat.PNG, 100, o); }
            catch (Exception e) { return; }
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(f);
        });
    }

    private void restoreDraft(Runnable then) {
        File f = draftFile();
        if (!f.exists() || !work.exists()) { then.run(); return; }
        final String[] head = new String[9];
        final List<Mk> marks = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            for (int i = 0; i < 6; i++) head[i] = r.readLine();
            if ("rmd2".equals(head[0]) || "rmd3".equals(head[0])) { head[6] = r.readLine(); head[7] = r.readLine(); }
            if ("rmd3".equals(head[0])) head[8] = r.readLine();
            else if (!"rmd1".equals(head[0]) && !"rmd2".equals(head[0])) { then.run(); return; }
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
                where = head[8] != null ? head[8] : srcUri != null ? HomeView.provider(srcUri.getAuthority()) : "";
                int pg = 0;
                try { pg = Integer.parseInt(head[5]); } catch (Exception ignored) { }
                view.setDoc(doc, marks, pg);
                view.dirty = "1".equals(head[4]);
                if (view.dirty) draftAt = f.lastModified();
                updateRecent();
                refreshBar();
            }
            then.run();
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        Diag.log("destroy" + (isFinishing() ? " (finishing)" : ""));
        final PdfDoc d = doc;
        if (d != null && isFinishing()) worker.job(d::close, null);
    }
}
