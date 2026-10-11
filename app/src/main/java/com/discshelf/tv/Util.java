package com.discshelf.tv;

import android.content.Context;

import java.security.MessageDigest;
import java.util.Locale;

/** Small formatting helpers shared by every screen. */
final class Util {
    private Util() {}

    static String sha1(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    static String time(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    static String size(long bytes) {
        if (bytes <= 0) return "";
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1) return String.format(Locale.US, "%.1f GB", gb);
        return String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0));
    }

    /** "The_Matrix.1999.iso" becomes "The Matrix 1999". */
    static String prettyTitle(String fileName) {
        String t = fileName;
        int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\'));
        if (slash >= 0) t = t.substring(slash + 1);
        if (t.toLowerCase(Locale.US).endsWith(".iso")) t = t.substring(0, t.length() - 4);
        t = t.replace('_', ' ').replace('.', ' ').trim();
        while (t.contains("  ")) t = t.replace("  ", " ");
        return t.isEmpty() ? fileName : t;
    }

    static boolean isIso(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        int q = n.indexOf('?');
        if (q >= 0) n = n.substring(0, q);
        return n.endsWith(".iso");
    }

    /**
     * "DVD" or "Blu-ray" when the folder is a disc copied as files (a VIDEO_TS or BDMV
     * folder inside it), otherwise null.
     */
    static String discFolderKind(java.io.File dir) {
        if (child(dir, "VIDEO_TS") != null && child(child(dir, "VIDEO_TS"), "VIDEO_TS.IFO") != null) return "DVD";
        if (child(dir, "BDMV") != null && child(child(dir, "BDMV"), "index.bdmv") != null) return "Blu-ray";
        return null;
    }

    /** The entry in dir with this name, ignoring upper and lower case. */
    static java.io.File child(java.io.File dir, String name) {
        if (dir == null) return null;
        String[] names = dir.list();
        if (names == null) return null;
        for (String n : names) if (n.equalsIgnoreCase(name)) return new java.io.File(dir, n);
        return null;
    }

    static boolean isDiscFolderName(String name) {
        return "VIDEO_TS".equalsIgnoreCase(name) || "BDMV".equalsIgnoreCase(name);
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static boolean empty(String s) {
        return s == null || s.trim().isEmpty();
    }
}
