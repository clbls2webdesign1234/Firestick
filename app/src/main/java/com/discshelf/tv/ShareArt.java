package com.discshelf.tv;

import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/**
 * Reads a cover picture saved next to a disc on a network share (The Matrix.jpg beside
 * The Matrix.iso), such as one chosen in Disc Shelf for Windows. The playback engine
 * can't fetch plain files from a share, so this talks to the share directly.
 */
final class ShareArt {
    private ShareArt() {}

    private static final String[] EXTS = {".jpg", ".jpeg", ".png"};
    private static final int MAX_BYTES = 12 * 1024 * 1024;

    private static final Map<String, CIFSContext> contexts = new HashMap<>();
    /** Lower-case file names in each folder, read once per scan. */
    private static final Map<String, Set<String>> folders = new HashMap<>();

    /** Forget folder listings, so pictures added since the last look are found. */
    static synchronized void refresh() {
        folders.clear();
    }

    /** The picture's bytes, or null when the disc is not on a share or has no picture beside it. */
    static byte[] beside(Disc d, Source src) throws Exception {
        if (src == null || !d.location.toLowerCase(Locale.US).startsWith("smb://") || d.folder) return null;
        String path = Uri.decode(d.location);
        int slash = path.lastIndexOf('/');
        if (slash < "smb://".length()) return null;
        String dir = path.substring(0, slash + 1);
        String base = path.substring(slash + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);

        CIFSContext ctx = context(src);
        Set<String> names = list(dir, ctx);
        for (String ext : EXTS) {
            String want = (base + ext).toLowerCase(Locale.US);
            if (names.contains(want)) return read(new SmbFile(dir + realName(dir, want), ctx));
        }
        return null;
    }

    private static final Map<String, String> realNames = new HashMap<>();

    private static synchronized String realName(String dir, String lower) {
        String n = realNames.get(dir + lower);
        return n == null ? lower : n;
    }

    private static synchronized Set<String> list(String dir, CIFSContext ctx) throws Exception {
        Set<String> names = folders.get(dir);
        if (names != null) return names;
        names = new HashSet<>();
        for (String n : new SmbFile(dir, ctx).list()) {
            String lower = n.toLowerCase(Locale.US);
            names.add(lower);
            realNames.put(dir + lower, n);
        }
        folders.put(dir, names);
        return names;
    }

    private static byte[] read(SmbFile f) throws Exception {
        InputStream in = f.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            int r;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                if (out.size() > MAX_BYTES) return null;
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static synchronized CIFSContext context(Source src) throws Exception {
        String key = src.id + "|" + src.user + "|" + src.pass;
        CIFSContext c = contexts.get(key);
        if (c != null) return c;
        Properties p = new Properties();
        p.setProperty("jcifs.smb.client.minVersion", "SMB1");
        p.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        p.setProperty("jcifs.smb.client.connTimeout", "6000");
        p.setProperty("jcifs.smb.client.responseTimeout", "10000");
        p.setProperty("jcifs.resolveOrder", "DNS,BCAST");
        CIFSContext base = new BaseContext(new PropertyConfiguration(p));
        if (Util.empty(src.user)) {
            c = base.withGuestCrendentials();
        } else {
            /* DOMAIN\\user is understood, as it is when adding the share. */
            String user = src.user.trim();
            String domain = "";
            int bs = user.indexOf('\\');
            if (bs > 0) {
                domain = user.substring(0, bs);
                user = user.substring(bs + 1);
            }
            c = base.withCredentials(new NtlmPasswordAuthenticator(domain, user, src.pass == null ? "" : src.pass));
        }
        contexts.put(key, c);
        return c;
    }
}
