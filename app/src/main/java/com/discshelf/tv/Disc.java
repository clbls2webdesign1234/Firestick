package com.discshelf.tv;

import org.json.JSONException;
import org.json.JSONObject;

/** One disc in the library: an ISO file, or a disc copied as a folder (VIDEO_TS or BDMV). */
final class Disc {
    String id = "";
    String title = "";
    /** Absolute path for local files, otherwise a URL without any login in it. */
    String location = "";
    String sourceId = "";
    String sourceName = "";
    /** "DVD", "Blu-ray", or "" until the disc has been opened once. */
    String kind = "";
    long size = -1;
    /** True when location is a folder holding VIDEO_TS or BDMV rather than an ISO file. */
    boolean folder;

    static Disc of(String location, String title, Source src, String sourceName, long size) {
        Disc d = new Disc();
        d.location = location;
        d.id = Util.sha1(location);
        d.title = Util.empty(title) ? Util.prettyTitle(location) : title.trim();
        d.sourceId = src == null ? "" : src.id;
        d.sourceName = sourceName;
        d.size = size;
        return d;
    }

    static Disc folder(java.io.File dir, String kind, Source src, String sourceName) {
        Disc d = of(dir.getAbsolutePath(), dir.getName(), src, sourceName, -1);
        d.title = Util.prettyTitle(dir.getName());
        d.kind = kind;
        d.folder = true;
        return d;
    }

    boolean isLocalPath() {
        return location.startsWith("/");
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("title", title);
        o.put("location", location);
        o.put("sourceId", sourceId);
        o.put("sourceName", sourceName);
        o.put("kind", kind);
        o.put("size", size);
        o.put("folder", folder);
        return o;
    }

    static Disc fromJson(JSONObject o) {
        Disc d = new Disc();
        d.id = o.optString("id", "");
        d.title = o.optString("title", "");
        d.location = o.optString("location", "");
        d.sourceId = o.optString("sourceId", "");
        d.sourceName = o.optString("sourceName", "");
        d.kind = o.optString("kind", "");
        d.size = o.optLong("size", -1);
        d.folder = o.optBoolean("folder", false);
        return d;
    }
}
