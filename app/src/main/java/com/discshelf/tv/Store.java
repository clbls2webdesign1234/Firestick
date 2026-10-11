package com.discshelf.tv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Saved sources, the disc list from the last scan, and where each disc was stopped. */
final class Store {
    private Store() {}

    static final class Resume {
        int title = -1;
        long time;
        long length;
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("library", Context.MODE_PRIVATE);
    }

    static List<Source> sources(Context c) {
        List<Source> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("sources", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Source.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    static void saveSources(Context c, List<Source> list) {
        try {
            JSONArray a = new JSONArray();
            for (Source s : list) a.put(s.toJson());
            sp(c).edit().putString("sources", a.toString()).putBoolean("dirty", true).apply();
        } catch (Exception ignored) {
        }
    }

    static Source source(Context c, String id) {
        for (Source s : sources(c)) if (s.id.equals(id)) return s;
        return null;
    }

    /** True once after sources or scan settings change, so the library knows to rescan. */
    static boolean takeDirty(Context c) {
        boolean d = sp(c).getBoolean("dirty", false);
        if (d) sp(c).edit().putBoolean("dirty", false).apply();
        return d;
    }

    static void markDirty(Context c) {
        sp(c).edit().putBoolean("dirty", true).apply();
    }

    static List<Disc> discs(Context c) {
        List<Disc> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("discs", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(Disc.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    static void saveDiscs(Context c, List<Disc> list) {
        try {
            /* Keep what earlier playback learned about each disc. */
            Map<String, String> kinds = new HashMap<>();
            for (Disc old : discs(c)) if (!Util.empty(old.kind)) kinds.put(old.id, old.kind);
            JSONArray a = new JSONArray();
            for (Disc d : list) {
                if (Util.empty(d.kind) && kinds.containsKey(d.id)) d.kind = kinds.get(d.id);
                a.put(d.toJson());
            }
            sp(c).edit().putString("discs", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static Disc disc(Context c, String id) {
        for (Disc d : discs(c)) if (d.id.equals(id)) return d;
        return null;
    }

    static void setKind(Context c, String id, String kind) {
        try {
            JSONArray a = new JSONArray(sp(c).getString("discs", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (id.equals(o.optString("id"))) o.put("kind", kind);
            }
            sp(c).edit().putString("discs", a.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    static Resume resume(Context c, String discId) {
        try {
            JSONObject all = new JSONObject(sp(c).getString("resume", "{}"));
            JSONObject o = all.optJSONObject(discId);
            if (o == null) return null;
            Resume r = new Resume();
            r.title = o.optInt("title", -1);
            r.time = o.optLong("time", 0);
            r.length = o.optLong("length", 0);
            return r.time > 0 ? r : null;
        } catch (Exception e) {
            return null;
        }
    }

    static Map<String, Resume> resumes(Context c) {
        Map<String, Resume> out = new HashMap<>();
        try {
            JSONObject all = new JSONObject(sp(c).getString("resume", "{}"));
            java.util.Iterator<String> keys = all.keys();
            while (keys.hasNext()) {
                String id = keys.next();
                JSONObject o = all.optJSONObject(id);
                if (o == null) continue;
                Resume r = new Resume();
                r.title = o.optInt("title", -1);
                r.time = o.optLong("time", 0);
                r.length = o.optLong("length", 0);
                if (r.time > 0) out.put(id, r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static void saveResume(Context c, String discId, Resume r) {
        try {
            JSONObject all = new JSONObject(sp(c).getString("resume", "{}"));
            if (r == null) {
                all.remove(discId);
            } else {
                JSONObject o = new JSONObject();
                o.put("title", r.title);
                o.put("time", r.time);
                o.put("length", r.length);
                all.put(discId, o);
            }
            sp(c).edit().putString("resume", all.toString()).apply();
        } catch (Exception ignored) {
        }
    }
}
