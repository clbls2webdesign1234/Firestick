package com.discshelf.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cover art for the shelf. A picture saved beside the disc wins (The Matrix.jpg next to
 * The Matrix.iso, or cover.jpg / folder.jpg / poster.jpg inside a disc folder); otherwise
 * the title is looked up in Apple's iTunes film and TV catalogue, which needs no account.
 * Covers found online are kept in the app's own storage, so each is fetched once.
 */
final class Art {
    private Art() {}

    interface Listener {
        void onArt();
    }

    private static final String[] COVER_NAMES = {"cover", "folder", "poster", "front"};
    private static final String[] IMAGE_EXTS = {".jpg", ".jpeg", ".png"};
    /** A title with no match is tried again after this long, in case the catalogue gains it. */
    private static final long MISS_RETRY_MS = 14L * 24 * 3600 * 1000;
    /** Apple allows about 20 searches a minute. */
    private static final long LOOKUP_GAP_MS = 3500;

    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Set<String> pending = new HashSet<>();
    private static final Set<String> failed = new HashSet<>();
    private static final LruCache<String, Bitmap> memory =
            new LruCache<String, Bitmap>((int) Math.min(Runtime.getRuntime().maxMemory() / 8, 48L * 1024 * 1024)) {
                @Override
                protected int sizeOf(String key, Bitmap b) {
                    return b.getByteCount();
                }
            };
    private static Listener listener;
    private static long lastLookup;
    /** Why the last cover could not be fetched, shown on the shelf; null once one arrives. */
    static volatile String problem;
    static volatile int found;
    /** While a test runs, every step is written here for the owner to read. */
    private static volatile StringBuilder trace;

    private static void note(String line) {
        StringBuilder t = trace;
        if (t != null) t.append(line).append('\n');
    }

    interface TestResult {
        void done(String report);
    }

    /** Settings, "Test cover lookup": looks up one disc step by step and reports what happened. */
    static void test(Context c, final List<Disc> discs, final TestResult result) {
        final Context app = c.getApplicationContext();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                StringBuilder t = new StringBuilder();
                trace = t;
                note("Device country: " + Locale.getDefault().getCountry());
                for (Disc d : discs) {
                    try {
                        note("");
                        note("Disc: " + d.title);
                        String url = lookup(d.title, sp(app).getInt("n_" + d.id, 0));
                        if (url == null) {
                            note("No match in the catalogue.");
                        } else {
                            note("Cover: " + url);
                            byte[] img = fetch(url);
                            note("Downloaded " + (img == null ? 0 : img.length) + " bytes");
                            if (img != null) {
                                Bitmap b = BitmapFactory.decodeByteArray(img, 0, img.length);
                                note(b == null ? "The picture could not be read." : "Picture " + b.getWidth() + " x " + b.getHeight() + ": OK");
                            }
                        }
                    } catch (Throwable e) {
                        note("Failed: " + e.getClass().getName() + ": " + e.getMessage());
                        if (e.getCause() != null) note("Cause: " + e.getCause());
                    }
                }
                trace = null;
                final String report = t.toString().trim();
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        result.done(report);
                    }
                });
            }
        });
    }

    static void setListener(Listener l) {
        listener = l;
    }

    /** The cover if it is ready; otherwise null, and it is fetched in the background. */
    static Bitmap get(Context c, Disc d) {
        Bitmap b = memory.get(d.id);
        if (b != null || failed.contains(d.id)) return b;
        synchronized (pending) {
            if (pending.contains(d.id)) return null;
            pending.add(d.id);
        }
        final Context app = c.getApplicationContext();
        final Disc disc = d;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap got = null;
                try {
                    got = load(app, disc);
                } catch (Throwable t) {
                    problem = describe(t);
                }
                final Bitmap result = got;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        synchronized (pending) {
                            pending.remove(disc.id);
                        }
                        if (result != null) {
                            memory.put(disc.id, result);
                            found++;
                            problem = null;
                        } else {
                            failed.add(disc.id);
                        }
                        if (listener != null) listener.onArt();
                    }
                });
            }
        });
        return null;
    }

    /** "Try a different cover": the next catalogue match replaces this one. */
    static void next(Context c, Disc d) {
        SharedPreferences sp = sp(c);
        sp.edit().putInt("n_" + d.id, sp.getInt("n_" + d.id, 0) + 1)
                .remove("none_" + d.id).remove("miss_" + d.id).apply();
        forget(c, d);
    }

    /** "Show no cover" for a disc whose matches are all wrong. */
    static void hide(Context c, Disc d) {
        sp(c).edit().putBoolean("none_" + d.id, true).apply();
        forget(c, d);
    }

    /** Called on Rescan, so pictures added beside the discs show up and titles not found are searched again. */
    static void retryMissing(Context c) {
        failed.clear();
        memory.evictAll();
        problem = null;
        SharedPreferences sp = sp(c);
        SharedPreferences.Editor e = sp.edit();
        for (String k : sp.getAll().keySet()) if (k.startsWith("miss_")) e.remove(k);
        e.apply();
    }

    private static String describe(Throwable t) {
        if (t instanceof java.net.UnknownHostException) return "the Fire TV couldn't find Apple's catalogue on the internet";
        if (t instanceof javax.net.ssl.SSLException) return "a secure connection to Apple's catalogue failed (" + t.getMessage() + ")";
        if (t instanceof java.net.SocketTimeoutException) return "Apple's catalogue didn't answer in time";
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static void forget(Context c, Disc d) {
        file(c, d).delete();
        memory.remove(d.id);
        failed.remove(d.id);
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("art", Context.MODE_PRIVATE);
    }

    private static File file(Context c, Disc d) {
        return new File(new File(c.getFilesDir(), "art"), d.id + ".v3.jpg");
    }

    // ---- Background work -------------------------------------------------------------

    private static Bitmap load(Context c, Disc d) throws Exception {
        if (sp(c).getBoolean("none_" + d.id, false)) return null;

        File own = besideDisc(d);
        if (own != null) return decode(own);

        File cached = file(c, d);
        if (cached.exists()) return decode(cached);

        if (!Prefs.artOnline(c)) return null;
        long miss = sp(c).getLong("miss_" + d.id, 0);
        if (miss > 0 && System.currentTimeMillis() - miss < MISS_RETRY_MS) return null;

        String url = lookup(d.title, sp(c).getInt("n_" + d.id, 0));
        if (url == null) {
            sp(c).edit().putLong("miss_" + d.id, System.currentTimeMillis()).apply();
            return null;
        }
        byte[] img = fetch(url);
        if (img == null) return null;
        save(cached, img);
        return decode(cached);
    }

    private static void save(File f, byte[] data) throws Exception {
        f.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    /** A picture the owner saved next to the disc, for discs on USB or the Fire TV itself. */
    private static File besideDisc(Disc d) {
        if (!d.isLocalPath()) return null;
        File disc = new File(d.location);
        if (d.folder) {
            for (String n : COVER_NAMES) {
                for (String ext : IMAGE_EXTS) {
                    File f = Util.child(disc, n + ext);
                    if (f != null && f.isFile()) return f;
                }
            }
            return null;
        }
        String base = disc.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        for (String ext : IMAGE_EXTS) {
            File f = Util.child(disc.getParentFile(), base + ext);
            if (f != null && f.isFile()) return f;
        }
        return null;
    }

    private static Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        if (o.outWidth <= 0) return null;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 360) sample *= 2;
        BitmapFactory.Options real = new BitmapFactory.Options();
        real.inSampleSize = sample;
        real.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getAbsolutePath(), real);
    }

    // ---- Catalogue lookup ------------------------------------------------------------

    private static final Pattern YEAR = Pattern.compile("(?:^|[\\s(\\[])((?:19|20)\\d\\d)(?:$|[\\s)\\]])");
    private static final Pattern SEASON = Pattern.compile(
            "(?i)\\b(?:season\\s*(\\d{1,2})|series\\s*(\\d{1,2})|s(\\d{1,2})(?:\\s*d\\d+|e\\d+)?)\\b");
    private static final Pattern NOISE = Pattern.compile(
            "(?i)\\b(?:disc|disk|dvd|cd|d)\\s*\\d+\\b|\\b(?:dvd\\d?|dvdr|dvd9|dvd5|blu-?ray|bluray|bd|bdrip|iso|pal|ntsc"
                    + "|1080p|1080i|720p|480p|576p|2160p|4k|uhd|hdr|remux|ws|widescreen|fullscreen|fs"
                    + "|special edition|collectors edition|collector's edition|extended( edition| cut)?"
                    + "|directors cut|director's cut|unrated|remastered|region \\d|r\\d|x264|x265|h264|hevc"
                    + "|ac3|dts|dd5 1|truehd|atmos)\\b");

    /** The URL of the n-th best cover for this title, or null. */
    private static String lookup(String title, int n) throws Exception {
        String t = title.replaceAll("\\[[^\\]]*\\]", " ").replace('-', ' ');
        /* "ANewDayLive" becomes "A New Day Live". */
        t = t.replaceAll("([a-z])([A-Z])", "$1 $2");
        int year = 0;
        Matcher y = YEAR.matcher(t);
        int latest = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) + 1;
        /* A number that opens the title ("2001 A Space Odyssey") or lies ahead ("Blade Runner 2049") is part of the name. */
        while (y.find()) {
            int v = Integer.parseInt(y.group(1));
            if (y.start(1) > 0 && v <= latest) year = v;
        }
        if (year > 0) t = t.replaceAll("\\b" + year + "\\b", " ");

        int season = 0;
        Matcher s = SEASON.matcher(t);
        if (s.find()) {
            for (int g = 1; g <= 3; g++) if (s.group(g) != null) season = Integer.parseInt(s.group(g));
            t = t.substring(0, s.start());
        }
        t = NOISE.matcher(t).replaceAll(" ").replaceAll("[()]", " ").replaceAll("\\s+", " ").trim();
        note("Search words: \"" + t + "\"" + (year > 0 ? ", year " + year : "") + (season > 0 ? ", season " + season : ""));
        if (t.length() < 2) return null;

        /* Wikipedia first: it has the real poster or DVD cover for most films, series and concerts. */
        List<String> covers = wikiCovers(t, year, season);
        if (covers.isEmpty()) covers = season > 0 ? tvCovers(t, season, t) : movieCovers(t, year, t);
        if (covers.isEmpty()) covers = season > 0 ? movieCovers(t, year, t) : tvCovers(t, 0, t);
        /* Concerts and music DVDs are in the music catalogue. */
        if (covers.isEmpty()) covers = albumCovers(t, t);
        /* Names with words run together ("CELINE DION Anewdayliveinlasvegas") match on their first words. */
        String[] words = t.split(" ");
        for (int keep = Math.min(words.length - 1, 3); covers.isEmpty() && keep >= 2; keep--) {
            StringBuilder shorter = new StringBuilder();
            for (int i = 0; i < keep; i++) shorter.append(i == 0 ? "" : " ").append(words[i]);
            /* Results must still match the whole name, so a shorter search can't bring back a random cover. */
            covers = movieCovers(shorter.toString(), year, t);
            if (covers.isEmpty()) covers = albumCovers(shorter.toString(), t);
        }
        if (covers.isEmpty()) return null;
        return covers.get(n % covers.size());
    }

    private static List<String> movieCovers(String term, int year, String want) throws Exception {
        JSONArray results = search(term, "movie", "movie");
        List<String> close = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String art = big(r.optString("artworkUrl100", ""));
            if (art == null || !sameTitle(want, r.optString("trackName", "") + " " + r.optString("artistName", ""))) continue;
            int ry = yearOf(r.optString("releaseDate", ""));
            if (year > 0 && ry > 0 && Math.abs(ry - year) <= 1) close.add(art);
            else rest.add(art);
        }
        close.addAll(rest);
        return close;
    }

    private static List<String> albumCovers(String term, String want) throws Exception {
        JSONArray results = search(term, "music", "album");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String art = big(r.optString("artworkUrl100", ""));
            if (art != null && sameTitle(want, r.optString("artistName", "") + " " + r.optString("collectionName", ""))) out.add(art);
        }
        return out;
    }

    private static List<String> tvCovers(String term, int season, String want) throws Exception {
        JSONArray results = search(season > 0 ? term + " season " + season : term, "tvShow", "tvSeason");
        List<String> exact = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        Pattern seasonName = Pattern.compile("(?i)\\bseason\\s*0*" + season + "\\b");
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String art = big(r.optString("artworkUrl100", ""));
            if (art == null || !sameTitle(want, r.optString("collectionName", "") + " " + r.optString("artistName", ""))) continue;
            if (season > 0 && seasonName.matcher(r.optString("collectionName", "")).find()) exact.add(art);
            else rest.add(art);
        }
        exact.addAll(rest);
        return exact;
    }

    /** Covers from the pictures of Wikipedia articles whose name matches the disc. */
    private static List<String> wikiCovers(String term, int year, int season) throws Exception {
        String q = term + (season > 0 ? " season " + season : "") + (year > 0 ? " " + year : "");
        long wait = lastLookup + LOOKUP_GAP_MS - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        lastLookup = System.currentTimeMillis();
        String url = "https://en.wikipedia.org/w/api.php?action=query&format=json&formatversion=2"
                + "&generator=search&gsrlimit=6&prop=pageimages%7Cdescription&piprop=thumbnail&pithumbsize=600"
                + "&pilicense=any&gsrsearch=" + Uri.encode(q);
        byte[] body = fetch(url);
        List<String> close = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        JSONObject query = body == null ? null : new JSONObject(new String(body, "UTF-8").trim()).optJSONObject("query");
        JSONArray pages = query == null ? null : query.optJSONArray("pages");
        int n = pages == null ? 0 : pages.length();
        /* The search's own order is in "index"; the list itself is not sorted. */
        JSONObject[] ordered = new JSONObject[n];
        for (int i = 0; i < n; i++) ordered[i] = pages.getJSONObject(i);
        java.util.Arrays.sort(ordered, new java.util.Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return a.optInt("index", 99) - b.optInt("index", 99);
            }
        });
        for (JSONObject p : ordered) {
            JSONObject thumb = p.optJSONObject("thumbnail");
            if (thumb == null || thumb.optString("source", "").isEmpty()) continue;
            String name = p.optString("title", "") + " " + p.optString("description", "");
            if (!sameTitle(term, name)) continue;
            if (year > 0 && name.contains(String.valueOf(year))) close.add(thumb.getString("source"));
            else rest.add(thumb.getString("source"));
        }
        close.addAll(rest);
        note("Searched Wikipedia for \"" + q + "\": " + close.size() + " matching");
        return close;
    }

    private static JSONArray search(String term, String media, String entity) throws Exception {
        String country = Locale.getDefault().getCountry();
        if (country.length() != 2) country = "AU";
        JSONArray a = searchIn(term, media, entity, country);
        if (a.length() == 0 && !"US".equalsIgnoreCase(country)) a = searchIn(term, media, entity, "US");
        return a;
    }

    private static JSONArray searchIn(String term, String media, String entity, String country) throws Exception {
        long wait = lastLookup + LOOKUP_GAP_MS - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        lastLookup = System.currentTimeMillis();
        String url = "https://itunes.apple.com/search?media=" + media + "&entity=" + entity
                + "&limit=10&country=" + country.toLowerCase(Locale.US) + "&term=" + Uri.encode(term);
        byte[] body = fetch(url);
        if (body == null) return new JSONArray();
        JSONArray a = new JSONObject(new String(body, "UTF-8").trim()).optJSONArray("results");
        note("Searched " + entity + " (" + country + ") for \"" + term + "\": " + (a == null ? 0 : a.length()) + " found");
        return a == null ? new JSONArray() : a;
    }

    private static final Set<String> SMALL_WORDS = new HashSet<>(java.util.Arrays.asList(
            "the", "a", "an", "and", "of", "in", "on", "at", "to", "for", "with", "live"));

    /**
     * True when the catalogue's name is really this disc: most of the disc's words appear in it.
     * Words run together on the disc ("Anewdayliveinlasvegas") count when the joined-up name contains them.
     */
    static boolean sameTitle(String disc, String found) {
        String f = plain(found);
        Set<String> foundWords = new HashSet<>(java.util.Arrays.asList(f.split(" ")));
        String joined = f.replace(" ", "");
        int words = 0, hits = 0;
        for (String w : plain(disc).split(" ")) {
            if (w.isEmpty() || SMALL_WORDS.contains(w)) continue;
            words++;
            if (foundWords.contains(w) || (w.length() >= 4 && joined.contains(w))) hits++;
        }
        return words > 0 && hits * 10 >= words * 7;
    }

    /** "Céline Dion: A New Day..." becomes "celine dion a new day". */
    private static String plain(String s) {
        String n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** iTunes artwork URLs name their size; asking for 600 gives a sharp poster. */
    private static String big(String art) {
        if (art.isEmpty()) return null;
        return art.replaceAll("/\\d+x\\d+bb\\.", "/600x600bb.");
    }

    private static int yearOf(String date) {
        if (date.length() < 4) return 0;
        try {
            return Integer.parseInt(date.substring(0, 4));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static byte[] fetch(String url) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            con.setRequestProperty("User-Agent", "DiscShelf/1.0 (https://github.com/clbls2webdesign1234/Firestick)");
            try {
                int code = con.getResponseCode();
                if (code != 200) note("  HTTP " + code);
                if (code == 403 || code == 429) {
                    /* Asked too often: wait a minute and try once more. */
                    Thread.sleep(60000);
                    continue;
                }
                if (code != 200) throw new java.io.IOException("Apple's catalogue answered " + code + " " + con.getResponseMessage());
                InputStream in = con.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                in.close();
                return out.toByteArray();
            } finally {
                con.disconnect();
            }
        }
        throw new java.io.IOException("catalogue busy");
    }
}
