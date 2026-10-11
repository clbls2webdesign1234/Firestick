package com.discshelf.tv;

import android.net.Uri;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads a list file: plain text with one disc per line. A line may be
 *
 *   an address on its own          smb://nas/films/Alien.iso
 *   a title, a bar, an address     Alien (1979) | smb://nas/films/Alien.iso
 *   M3U style                      #EXTINF:-1,Alien (1979)   then the address on the next line
 *
 * Addresses can be full URLs, Windows share paths (\\nas\films\Alien.iso), absolute
 * paths on the stick, or paths relative to wherever the list file itself lives.
 * Lines starting with # are otherwise ignored.
 */
final class ListFile {
    private ListFile() {}

    static final class Entry {
        final String location;
        final String title;

        Entry(String location, String title) {
            this.location = location;
            this.title = title;
        }
    }

    /**
     * @param base    the list's own URL when it came from the web, otherwise the folder it sits in
     * @param webBase true when base is a URL
     */
    static List<Entry> parse(String body, String base, boolean webBase) {
        List<Entry> out = new ArrayList<>();
        String pending = null;
        for (String raw : body.split("\r?\n")) {
            String line = raw.replace(String.valueOf((char) 0xFEFF), "").trim();
            if (line.isEmpty()) continue;
            if (line.toUpperCase(Locale.US).startsWith("#EXTINF")) {
                int comma = line.indexOf(',');
                pending = comma >= 0 ? line.substring(comma + 1).trim() : null;
                continue;
            }
            if (line.startsWith("#")) continue;
            String title = pending;
            pending = null;
            String loc = line;
            int bar = line.indexOf('|');
            if (bar > 0) {
                title = line.substring(0, bar).trim();
                loc = line.substring(bar + 1).trim();
            }
            if (loc.isEmpty()) continue;
            String resolved = resolve(loc, base, webBase);
            if (resolved == null) continue;
            if (Util.empty(title)) title = Util.prettyTitle(Uri.decode(resolved));
            out.add(new Entry(resolved, title));
        }
        return out;
    }

    static String resolve(String loc, String base, boolean webBase) {
        if (loc.startsWith("\\\\")) {
            String u = Net.smbUrl(loc);
            return u == null ? null : u.substring(0, u.length() - 1);
        }
        if (Net.hasScheme(loc)) {
            if (loc.toLowerCase(Locale.US).startsWith("file://")) return Uri.decode(loc.substring(7));
            return loc.replace(" ", "%20");
        }
        if (webBase) {
            try {
                return new URL(new URL(base), loc.replace(" ", "%20")).toString();
            } catch (Exception e) {
                return null;
            }
        }
        if (loc.startsWith("/")) return loc;
        return base == null ? null : new File(base, loc).getPath();
    }
}
