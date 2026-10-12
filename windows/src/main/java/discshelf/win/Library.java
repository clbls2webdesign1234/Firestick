package discshelf.win;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sources (folders, drives, network shares and list files), the disc list from the
 * last scan, and where each disc was stopped. Saved in %APPDATA%\DiscShelf.
 */
final class Library {
    private static final int MAX_DEPTH = 6;
    private static final int MAX_FOLDERS = 5000;

    static final class Resume {
        int title = -1;
        long time;
    }

    static final class Result {
        final List<Disc> discs;
        final List<String> notes;

        Result(List<Disc> discs, List<String> notes) {
            this.discs = discs;
            this.notes = notes;
        }
    }

    interface Progress {
        void folder(String path);
    }

    private final File dir;
    private final List<String> sources = new ArrayList<>();
    private List<Disc> discs = new ArrayList<>();
    private final Map<String, Resume> resumes = new LinkedHashMap<>();

    Library() {
        String appData = System.getenv("APPDATA");
        dir = new File(appData != null ? appData : System.getProperty("user.home"), "DiscShelf");
        dir.mkdirs();
        load();
    }

    // ------------------------------------------------------------ storage

    private File file() {
        return new File(dir, "library.json");
    }

    private void load() {
        try {
            JSONObject o = new JSONObject(Files.readString(file().toPath(), StandardCharsets.UTF_8));
            JSONArray s = o.optJSONArray("sources");
            if (s != null) for (int i = 0; i < s.length(); i++) sources.add(s.getString(i));
            JSONArray d = o.optJSONArray("discs");
            if (d != null) for (int i = 0; i < d.length(); i++) discs.add(Disc.fromJson(d.getJSONObject(i)));
            JSONObject r = o.optJSONObject("resume");
            if (r != null) {
                for (String k : r.keySet()) {
                    JSONObject e = r.getJSONObject(k);
                    Resume x = new Resume();
                    x.title = e.optInt("title", -1);
                    x.time = e.optLong("time");
                    resumes.put(k, x);
                }
            }
        } catch (Exception ignored) {
            /* First run, or an unreadable file: start empty. */
        }
    }

    synchronized void save() {
        JSONObject o = new JSONObject();
        o.put("sources", new JSONArray(sources));
        JSONArray d = new JSONArray();
        for (Disc x : discs) d.put(x.toJson());
        o.put("discs", d);
        JSONObject r = new JSONObject();
        for (Map.Entry<String, Resume> e : resumes.entrySet()) {
            r.put(e.getKey(), new JSONObject().put("title", e.getValue().title).put("time", e.getValue().time));
        }
        o.put("resume", r);
        try {
            File tmp = new File(dir, "library.json.tmp");
            Files.writeString(tmp.toPath(), o.toString(1), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file().toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    synchronized List<String> sources() {
        return new ArrayList<>(sources);
    }

    synchronized void setSources(List<String> list) {
        sources.clear();
        sources.addAll(list);
        save();
    }

    synchronized List<Disc> discs() {
        return new ArrayList<>(discs);
    }

    synchronized Resume resume(Disc d) {
        return resumes.get(d.id());
    }

    synchronized void setResume(Disc d, Resume r) {
        if (r == null) resumes.remove(d.id());
        else resumes.put(d.id(), r);
        save();
    }

    synchronized void setKind(Disc d, String kind) {
        for (Disc x : discs) if (x.id().equals(d.id())) x.kind = kind;
        d.kind = kind;
        save();
    }

    // ------------------------------------------------------------ scanning

    /** Runs on a background thread. */
    Result scan(Progress progress) {
        List<Disc> found = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String s : sources()) {
            File f = new File(s.trim());
            int before = found.size();
            if (f.isDirectory()) {
                walk(f, 0, found, new int[1], progress);
            } else if (f.isFile()) {
                readList(f, found, notes);
            } else {
                notes.add(s + ": not found. For a network share, open it once in File Explorer"
                        + " and tick \"Remember my credentials\"");
                continue;
            }
            if (found.size() == before) notes.add(s + ": no ISO files or disc folders found");
        }

        Map<String, Disc> unique = new LinkedHashMap<>();
        synchronized (this) {
            Map<String, String> kinds = new LinkedHashMap<>();
            for (Disc old : discs) if (!old.kind.isEmpty()) kinds.put(old.id(), old.kind);
            for (Disc d : found) {
                if (d.kind.isEmpty() && kinds.containsKey(d.id())) d.kind = kinds.get(d.id());
                unique.putIfAbsent(d.id(), d);
            }
            discs = new ArrayList<>(unique.values());
            discs.sort(Comparator.comparing(x -> x.title.toLowerCase(Locale.ROOT)));
            save();
        }
        return new Result(discs(), notes);
    }

    private void walk(File dir, int depth, List<Disc> found, int[] folders, Progress progress) {
        if (folders[0]++ > MAX_FOLDERS) return;
        if (depth == 0 && Disc.isDiscFolderName(dir.getName()) && dir.getParentFile() != null) {
            dir = dir.getParentFile();
        }
        String kind = Disc.folderKind(dir);
        if (kind != null) {
            found.add(Disc.folder(dir, kind));
            return;
        }
        if (progress != null) progress.folder(dir.getPath());
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith(".") || n.startsWith("$")) continue;
            if (f.isDirectory()) {
                if (depth < MAX_DEPTH && !n.equalsIgnoreCase("System Volume Information")) {
                    walk(f, depth + 1, found, folders, progress);
                }
            } else if (n.toLowerCase(Locale.ROOT).endsWith(".iso")) {
                found.add(Disc.iso(f));
            }
        }
    }

    /**
     * A list file: one disc per line, either a path or "Title | path". Paths may be
     * relative to the list file. Lines starting with # are ignored; M3U titles are read.
     */
    private void readList(File list, List<Disc> found, List<String> notes) {
        List<String> lines;
        try {
            lines = Files.readAllLines(list.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            notes.add(list + ": could not be read");
            return;
        }
        String pending = null;
        for (String raw : lines) {
            String line = raw.replace("\uFEFF", "").trim();
            if (line.isEmpty()) continue;
            if (line.toUpperCase(Locale.ROOT).startsWith("#EXTINF")) {
                int c = line.indexOf(',');
                pending = c >= 0 ? line.substring(c + 1).trim() : null;
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
            if (loc.toLowerCase(Locale.ROOT).startsWith("file:///")) loc = loc.substring(8);
            File f = new File(loc);
            if (!f.isAbsolute()) f = new File(list.getParentFile(), loc);
            Disc d = null;
            if (f.isDirectory()) {
                File dir = Disc.isDiscFolderName(f.getName()) ? f.getParentFile() : f;
                String kind = Disc.folderKind(dir);
                if (kind != null) d = Disc.folder(dir, kind);
                else notes.add(loc + ": a folder with no VIDEO_TS or BDMV in it");
            } else if (f.isFile()) {
                d = Disc.iso(f);
            } else {
                notes.add(loc + ": not found");
            }
            if (d == null) continue;
            if (title != null && !title.isEmpty()) d.title = title;
            found.add(d);
        }
    }
}
