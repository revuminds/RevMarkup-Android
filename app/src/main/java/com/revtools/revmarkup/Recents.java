package com.revtools.revmarkup;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Recently opened drawings (home screen "Continue reviewing" + Recent list). */
final class Recents {
    static final class Item {
        String uri = "", out = "", name = "", tree = "", folder = "", where = "";
        int page, pages, count;
        long time, size = -1;
        boolean draft;
    }

    private static final int MAX = 20;
    private final SharedPreferences prefs;
    private final File thumbs;
    final List<Item> list = new ArrayList<>();

    Recents(Context c, SharedPreferences prefs) {
        this.prefs = prefs;
        thumbs = new File(c.getFilesDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        thumbs.mkdirs();
        try {
            JSONArray a = new JSONArray(prefs.getString("recent", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item r = new Item();
                r.uri = o.optString("uri"); r.out = o.optString("out"); r.name = o.optString("name");
                r.tree = o.optString("tree"); r.folder = o.optString("folder"); r.where = o.optString("where");
                r.page = o.optInt("page"); r.pages = o.optInt("pages"); r.count = o.optInt("count");
                r.time = o.optLong("time"); r.draft = o.optBoolean("draft"); r.size = o.optLong("size", -1);
                if (!r.uri.isEmpty()) list.add(r);
            }
        } catch (Exception ignored) { }
    }

    Item find(String uri) {
        if (uri == null) return null;
        for (Item r : list) if (r.uri.equals(uri) || (!r.out.isEmpty() && r.out.equals(uri))) return r;
        return null;
    }

    /** Move to the top (adds if new). */
    Item touch(String uri) {
        Item r = find(uri);
        if (r == null) { r = new Item(); r.uri = uri; }
        list.remove(r);
        list.add(0, r);
        r.time = System.currentTimeMillis();
        while (list.size() > MAX) { Item old = list.remove(list.size() - 1); //noinspection ResultOfMethodCallIgnored
            thumb(old).delete(); }
        return r;
    }

    void remove(Item r) {
        list.remove(r);
        //noinspection ResultOfMethodCallIgnored
        thumb(r).delete();
        save();
    }

    File thumb(Item r) { return new File(thumbs, Integer.toHexString(r.uri.hashCode()) + ".png"); }

    void save() {
        JSONArray a = new JSONArray();
        try {
            for (Item r : list) a.put(new JSONObject().put("uri", r.uri).put("out", r.out).put("name", r.name).put("tree", r.tree)
                    .put("folder", r.folder).put("where", r.where).put("page", r.page).put("pages", r.pages)
                    .put("count", r.count).put("time", r.time).put("draft", r.draft).put("size", r.size));
        } catch (Exception ignored) { }
        prefs.edit().putString("recent", a.toString()).apply();
    }
}
