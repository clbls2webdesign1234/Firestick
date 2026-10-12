package com.discshelf.tv;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.videolan.libvlc.Dialog;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.util.MediaBrowser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the disc list from every source: USB and local folders, web folders and
 * list files, then network shares. Local and web work runs on a background thread;
 * network shares are walked with libVLC's own browser, which must run on the main thread.
 */
final class Scanner {

    interface Listener {
        void onProgress(String message);

        void onDone(List<Disc> discs, List<String> notes);
    }

    private static final int MAX_FOLDER_DEPTH = 5;
    private static final int MAX_SHARE_DEPTH = 8;
    private static final int MAX_WEB_DEPTH = 3;
    private static final int MAX_FOLDERS = 3000;
    private static final Pattern HREF = Pattern.compile("href\\s*=\\s*[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE);

    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Disc> found = Collections.synchronizedList(new ArrayList<Disc>());
    private final List<String> notes = Collections.synchronizedList(new ArrayList<String>());
    private volatile boolean cancelled;
    private LibVLC vlc;
    /** The share being walked, so a login request from the engine can be answered. */
    private ShareWalk walking;

    private Scanner(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    static Scanner start(Context ctx, Listener listener) {
        final Scanner s = new Scanner(ctx, listener);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    s.scanLocalAndWeb();
                } catch (Throwable t) {
                    s.notes.add("Scan problem: " + t);
                }
                s.main.post(new Runnable() {
                    @Override
                    public void run() {
                        s.scanShares();
                    }
                });
            }
        }, "disc-scan").start();
        return s;
    }

    void cancel() {
        cancelled = true;
    }

    private void progress(final String msg) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!cancelled) listener.onProgress(msg);
            }
        });
    }

    // ---------------------------------------------------------------- local and USB

    private void scanLocalAndWeb() {
        List<Source> sources = Store.sources(ctx);
        boolean canRead = Util.canReadStorage(ctx);

        if (Prefs.scanUsb(ctx)) {
            if (canRead) {
                progress("Checking USB and local storage…");
                String internal = Environment.getExternalStorageDirectory().getAbsolutePath();
                for (File root : storageRoots()) {
                    String label = root.getAbsolutePath().equals(internal) ? "Internal storage" : "USB drive";
                    walk(root, 0, null, label, new int[1]);
                }
            } else {
                notes.add("USB drives: storage permission has not been allowed");
            }
        }

        for (Source s : sources) {
            if (cancelled) return;
            if (Source.LOCAL.equals(s.type)) {
                progress("Scanning " + s.displayName() + "…");
                File f = new File(s.address.trim());
                int before = found.size();
                if (f.isDirectory()) {
                    walk(f, 0, s, s.displayName(), new int[1]);
                } else if (f.isFile()) {
                    try {
                        parseList(readFile(f), f.getParent(), false, s);
                    } catch (IOException e) {
                        notes.add(s.displayName() + ": " + e.getMessage());
                    }
                } else {
                    notes.add(s.displayName() + ": folder not found" + (canRead ? "" : " (storage permission not allowed)"));
                    continue;
                }
                if (found.size() == before) notes.add(s.displayName() + ": no ISO files or disc folders found");
            } else if (Source.HTTP.equals(s.type)) {
                progress("Scanning " + s.displayName() + "…");
                int before = found.size();
                String url = Net.webUrl(s.address);
                try {
                    if (url == null) throw new IOException("no address");
                    crawl(s, url, 0, new HashSet<String>());
                    if (found.size() == before) notes.add(s.displayName() + ": no ISO files found");
                } catch (Exception e) {
                    notes.add(s.displayName() + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            }
        }
    }

    /** Every storage volume the system exposes, including plugged-in USB drives. */
    private List<File> storageRoots() {
        Set<String> roots = new LinkedHashSet<>();
        File[] dirs = ctx.getExternalFilesDirs(null);
        if (dirs != null) {
            for (File d : dirs) {
                if (d == null) continue;
                String p = d.getAbsolutePath();
                int cut = p.indexOf("/Android/");
                if (cut > 0) roots.add(p.substring(0, cut));
            }
        }
        File[] mounted = new File("/storage").listFiles();
        if (mounted != null) {
            for (File m : mounted) {
                String n = m.getName();
                if (m.isDirectory() && !"emulated".equals(n) && !"self".equals(n)) roots.add(m.getAbsolutePath());
            }
        }
        List<File> out = new ArrayList<>();
        for (String r : roots) out.add(new File(r));
        return out;
    }

    private void walk(File dir, int depth, Source src, String label, int[] folders) {
        if (cancelled || folders[0]++ > MAX_FOLDERS) return;
        /* A source pointed straight at a VIDEO_TS or BDMV folder means the disc around it. */
        if (depth == 0 && Util.isDiscFolderName(dir.getName()) && dir.getParentFile() != null) {
            dir = dir.getParentFile();
        }
        String kind = Util.discFolderKind(dir);
        if (kind != null) {
            found.add(Disc.folder(dir, kind, src, label));
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith(".")) continue;
            if (f.isDirectory()) {
                if (depth < MAX_FOLDER_DEPTH && !"Android".equals(name) && !"LOST.DIR".equals(name)) {
                    walk(f, depth + 1, src, label, folders);
                }
            } else if (Util.isIso(name)) {
                found.add(Disc.of(f.getAbsolutePath(), null, src, label, f.length()));
            }
        }
    }

    private static String readFile(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            return readAll(in, 4 * 1024 * 1024);
        } finally {
            in.close();
        }
    }

    private static String readAll(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0 && out.size() < limit) out.write(buf, 0, n);
        return out.toString("UTF-8");
    }

    // ---------------------------------------------------------------- web folders and lists

    private static final class Page {
        String body;
        String url;
        boolean html;
    }

    private Page fetch(String url, Source s) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        if (!Util.empty(s.user)) {
            String token = Base64.encodeToString((s.user + ":" + s.pass).getBytes("UTF-8"), Base64.NO_WRAP);
            c.setRequestProperty("Authorization", "Basic " + token);
        }
        try {
            int code = c.getResponseCode();
            if (code == 401 || code == 403) throw new IOException("login refused (HTTP " + code + ")");
            if (code >= 400) throw new IOException("server answered HTTP " + code);
            Page p = new Page();
            InputStream in = c.getInputStream();
            try {
                p.body = readAll(in, 4 * 1024 * 1024);
            } finally {
                in.close();
            }
            p.url = c.getURL().toString();
            String type = c.getContentType();
            p.html = (type != null && type.toLowerCase(Locale.US).contains("html"))
                    || p.body.trim().startsWith("<");
            return p;
        } finally {
            c.disconnect();
        }
    }

    private void crawl(Source s, String url, int depth, Set<String> seen) throws IOException {
        if (cancelled || !seen.add(url) || seen.size() > MAX_FOLDERS) return;
        Page p = fetch(url, s);
        if (!p.html) {
            parseList(p.body, p.url, true, s);
            return;
        }
        String base = p.url.substring(0, p.url.lastIndexOf('/') + 1);
        Matcher m = HREF.matcher(p.body);
        while (m.find() && !cancelled) {
            String href = m.group(1).replace("&amp;", "&").trim();
            if (href.isEmpty() || href.startsWith("?") || href.startsWith("javascript:") || href.startsWith("mailto:")) continue;
            URL abs;
            try {
                abs = new URL(new URL(p.url), href);
            } catch (Exception e) {
                continue;
            }
            String link = abs.toString();
            String path = abs.getPath() == null ? "" : abs.getPath();
            if (Util.isIso(path)) {
                String file = Uri.decode(path.substring(path.lastIndexOf('/') + 1));
                found.add(Disc.of(link, Util.prettyTitle(file), s, s.displayName(), -1));
            } else if (path.endsWith("/") && depth < MAX_WEB_DEPTH && link.startsWith(base) && link.length() > base.length()) {
                try {
                    crawl(s, link, depth + 1, seen);
                } catch (IOException ignored) {
                    /* A sub-folder we cannot open should not hide the rest. */
                }
            }
        }
    }

    private void parseList(String body, String base, boolean webBase, Source s) {
        for (ListFile.Entry e : ListFile.parse(body, base, webBase)) {
            File dir = e.location.startsWith("/") ? new File(e.location) : null;
            if (dir != null && dir.isDirectory()) {
                if (Util.isDiscFolderName(dir.getName()) && dir.getParentFile() != null) dir = dir.getParentFile();
                String kind = Util.discFolderKind(dir);
                if (kind == null) {
                    notes.add(s.displayName() + ": " + e.location + " is a folder with no VIDEO_TS or BDMV in it");
                    continue;
                }
                Disc d = Disc.folder(dir, kind, s, s.displayName());
                if (!Util.empty(e.title) && !e.title.equals(Util.prettyTitle(e.location))) d.title = e.title.trim();
                found.add(d);
                continue;
            }
            long size = e.location.startsWith("/") ? new File(e.location).length() : -1;
            found.add(Disc.of(e.location, e.title, s, s.displayName(), size <= 0 ? -1 : size));
        }
    }

    // ---------------------------------------------------------------- network shares

    private void scanShares() {
        final List<Source> shares = new ArrayList<>();
        for (Source s : Store.sources(ctx)) if (Source.SMB.equals(s.type)) shares.add(s);
        if (cancelled || shares.isEmpty()) {
            finish();
            return;
        }
        try {
            vlc = new LibVLC(ctx, new ArrayList<String>());
            Dialog.setCallbacks(vlc, dialogs);
        } catch (Throwable t) {
            notes.add("Network shares: the player engine could not start");
            finish();
            return;
        }
        nextShare(shares, 0);
    }

    private void nextShare(final List<Source> shares, final int index) {
        if (cancelled || index >= shares.size()) {
            if (vlc != null) {
                try {
                    Dialog.setCallbacks(vlc, null);
                } catch (Throwable ignored) {
                }
                vlc.release();
                vlc = null;
            }
            finish();
            return;
        }
        Source s = shares.get(index);
        String root = Net.smbUrl(s.address);
        Runnable then = new Runnable() {
            @Override
            public void run() {
                nextShare(shares, index + 1);
            }
        };
        if (root == null) {
            notes.add(s.displayName() + ": the address is empty");
            then.run();
            return;
        }
        listener.onProgress("Scanning " + s.displayName() + "…");
        walking = new ShareWalk(s, root, then);
        walking.next();
    }

    /**
     * The engine asks for a login, or reports an error, through dialogs. Nobody sees
     * them during a scan, so answer a login with the saved one (once), and turn
     * everything else into a note. Unanswered, the browse would hang until the timeout.
     */
    private final Dialog.Callbacks dialogs = new Dialog.Callbacks() {
        @Override
        public void onDisplay(Dialog.ErrorMessage dialog) {
            ShareWalk w = walking;
            if (w != null && dialog.getText() != null) w.problem(dialog.getText());
        }

        @Override
        public void onDisplay(Dialog.LoginDialog dialog) {
            ShareWalk w = walking;
            if (w != null && !w.triedLogin && !Util.empty(w.source.user)) {
                w.triedLogin = true;
                dialog.postLogin(w.source.user.trim(), w.source.pass, false);
                return;
            }
            dialog.dismiss();
            if (w != null) {
                w.problem(Util.empty(w.source.user)
                        ? "the share asks for a user name and password. Add them under Sources"
                        : "the share refused the user name or password");
            }
        }

        @Override
        public void onDisplay(Dialog.QuestionDialog dialog) {
            dialog.dismiss();
        }

        @Override
        public void onDisplay(Dialog.ProgressDialog dialog) {
        }

        @Override
        public void onCanceled(Dialog dialog) {
        }

        @Override
        public void onProgressUpdate(Dialog.ProgressDialog dialog) {
        }
    };

    /** Walks one share folder by folder. */
    private final class ShareWalk implements MediaBrowser.EventListener {
        final Source source;
        private final Runnable then;
        private final ArrayDeque<String[]> queue = new ArrayDeque<>();
        private final Set<String> seen = new HashSet<>();
        private final MediaBrowser browser;
        private int depth;
        private int count;
        private int folderDiscs;
        boolean triedLogin;
        private boolean noted;
        private boolean finished;

        private int answered;
        private int slow;

        private final Runnable timeout = new Runnable() {
            @Override
            public void run() {
                /* One slow folder should not end the whole share: skip it and go on,
                   unless the share has not answered at all. */
                if (answered > 0) {
                    slow++;
                    next();
                    return;
                }
                if (count == 0 && !noted) {
                    notes.add(source.displayName() + ": no answer from the share. Check the address includes the shared folder"
                            + " (for example 192.168.1.20/Films) and that file sharing (SMB) is turned on");
                    noted = true;
                }
                done();
            }
        };

        /** A login or error the engine reported: note it once and stop this share. */
        void problem(String text) {
            if (finished) return;
            if (!noted) notes.add(source.displayName() + ": " + text);
            noted = true;
            done();
        }

        ShareWalk(Source source, String root, Runnable then) {
            this.source = source;
            this.then = then;
            this.browser = new MediaBrowser(vlc, this);
            queue.add(new String[]{root, "0"});
        }

        void next() {
            main.removeCallbacks(timeout);
            if (finished) return;
            if (cancelled || queue.isEmpty() || seen.size() > MAX_FOLDERS) {
                done();
                return;
            }
            String[] entry = queue.poll();
            depth = Integer.parseInt(entry[1]);
            seen.add(entry[0]);
            browser.browse(Uri.parse(Net.withLogin(entry[0], source.user, source.pass)), 0);
            main.postDelayed(timeout, 25000);
        }

        @Override
        public void onMediaAdded(int index, IMedia media) {
            Uri uri = media.getUri();
            if (uri == null) return;
            String url = Net.stripLogin(uri.toString());
            String name = uri.getLastPathSegment();
            if (name == null || name.startsWith(".") || name.endsWith("$")) return;
            if (Util.isIso(name)) {
                found.add(Disc.of(url, Util.prettyTitle(name), source, source.displayName(), -1));
                count++;
            } else if (media.getType() == IMedia.Type.Directory && Util.isDiscFolderName(name)) {
                /* Disc menus need the folder on the stick itself; see README. */
                folderDiscs++;
            } else if (media.getType() == IMedia.Type.Directory && depth < MAX_SHARE_DEPTH) {
                String dir = url.endsWith("/") ? url : url + "/";
                if (!seen.contains(dir)) queue.add(new String[]{dir, String.valueOf(depth + 1)});
            }
        }

        @Override
        public void onMediaRemoved(int index, IMedia media) {
        }

        @Override
        public void onBrowseEnd() {
            answered++;
            next();
        }

        private void done() {
            if (finished) return;
            finished = true;
            main.removeCallbacks(timeout);
            browser.release();
            if (slow > 0) {
                notes.add(source.displayName() + ": " + slow + (slow == 1 ? " folder" : " folders")
                        + " did not answer in time and were skipped. Rescan to try again");
            }
            if (seen.size() > MAX_FOLDERS) {
                notes.add(source.displayName() + ": stopped after " + MAX_FOLDERS + " folders. Point the source at the folder holding your discs");
            }
            if (folderDiscs > 0) {
                notes.add(source.displayName() + ": " + folderDiscs + (folderDiscs == 1 ? " disc folder" : " disc folders")
                        + " (VIDEO_TS or BDMV) skipped. Over a network share discs need to be ISO files;"
                        + " folders play from a USB drive.");
            }
            if (walking == this) walking = null;
            if (count == 0 && !cancelled && !this.noted) {
                boolean noted = false;
                for (String n : notes) if (n.startsWith(source.displayName() + ":")) noted = true;
                if (!noted) notes.add(source.displayName() + ": no ISO files found. Check the address and login");
            }
            then.run();
        }
    }

    // ---------------------------------------------------------------- result

    private void finish() {
        if (cancelled) return;
        Map<String, Disc> unique = new LinkedHashMap<>();
        synchronized (found) {
            for (Disc d : found) if (!unique.containsKey(d.id)) unique.put(d.id, d);
        }
        List<Disc> discs = new ArrayList<>(unique.values());
        Collections.sort(discs, new Comparator<Disc>() {
            @Override
            public int compare(Disc a, Disc b) {
                return a.title.compareToIgnoreCase(b.title);
            }
        });
        Store.saveDiscs(ctx, discs);
        listener.onDone(discs, new ArrayList<>(notes));
    }
}
