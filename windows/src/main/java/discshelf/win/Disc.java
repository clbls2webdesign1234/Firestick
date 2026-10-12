package discshelf.win;

import org.json.JSONObject;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** One disc in the library: an ISO file, or a disc copied as a folder (VIDEO_TS or BDMV). */
final class Disc {
    static final String DVD = "DVD";
    static final String BLURAY = "Blu-ray";

    String path = "";
    String title = "";
    /** DVD, Blu-ray, or "" for an ISO not yet opened. */
    String kind = "";
    boolean folder;
    long size = -1;

    String id() {
        return path.toLowerCase(Locale.ROOT);
    }

    static Disc iso(File f) {
        Disc d = new Disc();
        d.path = f.getAbsolutePath();
        d.title = prettyTitle(f.getName());
        d.size = f.length();
        return d;
    }

    static Disc folder(File dir, String kind) {
        Disc d = new Disc();
        d.path = dir.getAbsolutePath();
        d.title = prettyTitle(dir.getName());
        d.kind = kind;
        d.folder = true;
        return d;
    }

    /**
     * What VLC is given to open. A disc folder is opened as a disc ("dvd:" or "bluray:")
     * so its menus and titles work; an ISO is opened as a file and VLC finds the disc inside.
     */
    String mrl() {
        if (!folder) return path;
        String scheme = BLURAY.equals(kind) ? "bluray://" : "dvd://";
        String p = path.replace('\\', '/');
        // C:/Films/Alien -> dvd:///C:/Films/Alien    //nas/share/Alien -> dvd://nas/share/Alien
        String body = p.startsWith("//") ? p.substring(2) : "/" + p;
        String[] parts = body.split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].matches("[A-Za-z]:")) {
                parts[i] = URLEncoder.encode(parts[i], StandardCharsets.UTF_8).replace("+", "%20");
            }
        }
        return scheme + String.join("/", parts);
    }

    String badge() {
        String k = kind.isEmpty() ? "ISO" : kind.toUpperCase(Locale.ROOT);
        return folder ? k + " FOLDER" : k;
    }

    JSONObject toJson() {
        return new JSONObject().put("path", path).put("title", title).put("kind", kind)
                .put("folder", folder).put("size", size);
    }

    static Disc fromJson(JSONObject o) {
        Disc d = new Disc();
        d.path = o.optString("path");
        d.title = o.optString("title");
        d.kind = o.optString("kind");
        d.folder = o.optBoolean("folder");
        d.size = o.optLong("size", -1);
        return d;
    }

    /** "The_Matrix.1999.iso" becomes "The Matrix 1999". */
    static String prettyTitle(String name) {
        String t = name;
        if (t.toLowerCase(Locale.ROOT).endsWith(".iso")) t = t.substring(0, t.length() - 4);
        t = t.replace('_', ' ').replace('.', ' ').trim().replaceAll(" {2,}", " ");
        return t.isEmpty() ? name : t;
    }

    /** DVD or Blu-ray when the folder holds a copied disc, otherwise null. */
    static String folderKind(File dir) {
        File vts = child(dir, "VIDEO_TS");
        if (vts != null && child(vts, "VIDEO_TS.IFO") != null) return DVD;
        File bdmv = child(dir, "BDMV");
        if (bdmv != null && child(bdmv, "index.bdmv") != null) return BLURAY;
        return null;
    }

    static boolean isDiscFolderName(String name) {
        return "VIDEO_TS".equalsIgnoreCase(name) || "BDMV".equalsIgnoreCase(name);
    }

    static File child(File dir, String name) {
        String[] names = dir == null ? null : dir.list();
        if (names == null) return null;
        for (String n : names) if (n.equalsIgnoreCase(name)) return new File(dir, n);
        return null;
    }

    static String size(long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024 * 1024);
        return gb >= 1 ? String.format(Locale.ROOT, "%.1f GB", gb)
                : String.format(Locale.ROOT, "%.0f MB", bytes / (1024.0 * 1024));
    }
}
