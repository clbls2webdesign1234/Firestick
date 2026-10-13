package discshelf.win;

import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cover art for the shelf. A picture saved beside the disc wins (The Matrix.jpg next to
 * The Matrix.iso, or cover.jpg / folder.jpg / poster.jpg inside a disc folder); otherwise
 * the title is looked up in Apple's iTunes film and TV catalogue, which needs no account.
 * Covers found online are kept in %APPDATA%\DiscShelf\art, so each is fetched once.
 */
final class Art {
    private static final String[] COVER_NAMES = {"cover", "folder", "poster", "front"};
    private static final String[] IMAGE_EXTS = {".jpg", ".jpeg", ".png"};
    /** A title with no match is tried again after this long, in case the catalogue gains it. */
    private static final long MISS_RETRY_MS = 14L * 24 * 3600 * 1000;
    /** Apple allows about 20 searches a minute. */
    private static final long LOOKUP_GAP_MS = 3500;
    private static final int MAX_IN_MEMORY = 400;

    private static final Preferences P = Preferences.userRoot().node("discshelf/art");

    private final File dir;
    private final Runnable changed;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "covers");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Set<String> pending = new HashSet<>();
    private final Set<String> failed = new HashSet<>();
    private final Map<String, BufferedImage> memory = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) {
            return size() > MAX_IN_MEMORY;
        }
    };
    private long lastLookup;

    /** changed runs on the Swing thread whenever a cover arrives. */
    Art(File libraryDir, Runnable changed) {
        this.dir = new File(libraryDir, "art");
        this.changed = changed;
    }

    static boolean online() {
        return P.getBoolean("online", true);
    }

    static void setOnline(boolean on) {
        P.putBoolean("online", on);
    }

    /** The cover if it is ready; otherwise null, and it is fetched in the background. Swing thread only. */
    BufferedImage get(Disc d) {
        String key = key(d);
        BufferedImage b = memory.get(key);
        if (b != null || failed.contains(key) || !pending.add(key)) return b;
        worker.execute(() -> {
            BufferedImage found = null;
            try {
                found = load(d, key);
            } catch (Exception ignored) {
            }
            BufferedImage result = found;
            SwingUtilities.invokeLater(() -> {
                pending.remove(key);
                if (result != null) {
                    memory.put(key, result);
                    changed.run();
                } else {
                    failed.add(key);
                }
            });
        });
        return null;
    }

    /** "Try a different cover": the next catalogue match replaces this one. */
    void next(Disc d) {
        String key = key(d);
        P.putInt("n_" + key, P.getInt("n_" + key, 0) + 1);
        P.remove("none_" + key);
        P.remove("miss_" + key);
        forget(key);
    }

    /** "Show no cover" for a disc whose matches are all wrong. */
    void hide(Disc d) {
        String key = key(d);
        P.putBoolean("none_" + key, true);
        forget(key);
    }

    /** Called on Rescan, so pictures added beside the discs show up. */
    void retryMissing() {
        failed.clear();
        memory.clear();
    }

    private void forget(String key) {
        new File(dir, key + ".jpg").delete();
        memory.remove(key);
        failed.remove(key);
        changed.run();
    }

    private static String key(Disc d) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(d.id().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : h) sb.append(String.format(Locale.ROOT, "%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(d.id().hashCode());
        }
    }

    // ------------------------------------------------------------ background work

    private BufferedImage load(Disc d, String key) throws Exception {
        if (P.getBoolean("none_" + key, false)) return null;

        File own = besideDisc(d);
        if (own != null) return decode(own);

        File cached = new File(dir, key + ".jpg");
        if (cached.isFile()) return decode(cached);

        if (!online()) return null;
        long miss = P.getLong("miss_" + key, 0);
        if (miss > 0 && System.currentTimeMillis() - miss < MISS_RETRY_MS) return null;

        String url = lookup(d.title, P.getInt("n_" + key, 0));
        if (url == null) {
            P.putLong("miss_" + key, System.currentTimeMillis());
            return null;
        }
        byte[] img = fetch(url);
        if (img == null) return null;
        dir.mkdirs();
        Files.write(cached.toPath(), img);
        return decode(cached);
    }

    /** A picture the owner saved next to the disc, on any drive or share. */
    private static File besideDisc(Disc d) {
        File disc = new File(d.path);
        if (d.folder) {
            for (String n : COVER_NAMES) {
                for (String ext : IMAGE_EXTS) {
                    File f = Disc.child(disc, n + ext);
                    if (f != null && f.isFile()) return f;
                }
            }
            return null;
        }
        String base = disc.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        for (String ext : IMAGE_EXTS) {
            File f = new File(disc.getParentFile(), base + ext);
            if (f.isFile()) return f;
        }
        return null;
    }

    /** Read and shrink to tile size, so hundreds of covers stay light. */
    private static BufferedImage decode(File f) throws IOException {
        BufferedImage src = ImageIO.read(f);
        if (src == null) return null;
        int w = 320;
        if (src.getWidth() <= w) return src;
        int h = Math.max(1, src.getHeight() * w / src.getWidth());
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    // ------------------------------------------------------------ catalogue lookup

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
    private String lookup(String title, int n) throws Exception {
        String t = title.replaceAll("\\[[^\\]]*\\]", " ").replace('-', ' ');
        /* "ANewDayLive" becomes "A New Day Live". */
        t = t.replaceAll("([a-z])([A-Z])", "$1 $2");
        int year = 0;
        int latest = Calendar.getInstance().get(Calendar.YEAR) + 1;
        Matcher y = YEAR.matcher(t);
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
        if (t.length() < 2) return null;

        List<String> covers = season > 0 ? tvCovers(t, season) : movieCovers(t, year);
        if (covers.isEmpty()) covers = season > 0 ? movieCovers(t, year) : tvCovers(t, 0);
        /* Concerts and music DVDs are in the music catalogue. */
        if (covers.isEmpty()) covers = albumCovers(t);
        /* Names with words run together ("CELINE DION Anewdayliveinlasvegas") match on their first words. */
        String[] words = t.split(" ");
        for (int keep = Math.min(words.length - 1, 3); covers.isEmpty() && keep >= 2; keep--) {
            StringBuilder shorter = new StringBuilder();
            for (int i = 0; i < keep; i++) shorter.append(i == 0 ? "" : " ").append(words[i]);
            covers = movieCovers(shorter.toString(), year);
            if (covers.isEmpty()) covers = albumCovers(shorter.toString());
        }
        if (covers.isEmpty()) return null;
        return covers.get(n % covers.size());
    }

    private List<String> movieCovers(String term, int year) throws Exception {
        JSONArray results = search(term, "movie", "movie");
        List<String> close = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String art = big(r.optString("artworkUrl100", ""));
            if (art == null) continue;
            int ry = yearOf(r.optString("releaseDate", ""));
            if (year > 0 && ry > 0 && Math.abs(ry - year) <= 1) close.add(art);
            else rest.add(art);
        }
        close.addAll(rest);
        return close;
    }

    private List<String> albumCovers(String term) throws Exception {
        JSONArray results = search(term, "music", "album");
        List<String> out = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            String art = big(results.getJSONObject(i).optString("artworkUrl100", ""));
            if (art != null) out.add(art);
        }
        return out;
    }

    private List<String> tvCovers(String term, int season) throws Exception {
        JSONArray results = search(season > 0 ? term + " season " + season : term, "tvShow", "tvSeason");
        List<String> exact = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        Pattern want = Pattern.compile("(?i)\\bseason\\s*0*" + season + "\\b");
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String art = big(r.optString("artworkUrl100", ""));
            if (art == null) continue;
            if (season > 0 && want.matcher(r.optString("collectionName", "")).find()) exact.add(art);
            else rest.add(art);
        }
        exact.addAll(rest);
        return exact;
    }

    private JSONArray search(String term, String media, String entity) throws Exception {
        String country = Locale.getDefault().getCountry();
        if (country.length() != 2) country = "AU";
        JSONArray a = searchIn(term, media, entity, country);
        if (a.length() == 0 && !"US".equalsIgnoreCase(country)) a = searchIn(term, media, entity, "US");
        return a;
    }

    private JSONArray searchIn(String term, String media, String entity, String country) throws Exception {
        long wait = lastLookup + LOOKUP_GAP_MS - System.currentTimeMillis();
        if (wait > 0) Thread.sleep(wait);
        lastLookup = System.currentTimeMillis();
        String url = "https://itunes.apple.com/search?media=" + media + "&entity=" + entity
                + "&limit=10&country=" + country.toLowerCase(Locale.ROOT)
                + "&term=" + URLEncoder.encode(term, StandardCharsets.UTF_8);
        byte[] body = fetch(url);
        if (body == null) return new JSONArray();
        JSONArray a = new JSONObject(new String(body, StandardCharsets.UTF_8)).optJSONArray("results");
        return a == null ? new JSONArray() : a;
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

    private byte[] fetch(String url) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15)).header("User-Agent", "DiscShelf").build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            int code = res.statusCode();
            if (code == 403 || code == 429) {
                /* Asked too often: wait a minute and try once more. */
                Thread.sleep(60000);
                continue;
            }
            return code == 200 ? res.body() : null;
        }
        throw new IOException("catalogue busy");
    }
}
