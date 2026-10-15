package discshelf.win;

import java.util.prefs.Preferences;

/** Player preferences, kept in the Windows registry under the current user. */
final class Settings {
    private Settings() {}

    private static final Preferences P = Preferences.userRoot().node("discshelf");

    /** How sound leaves the PC. */
    enum Sound {
        HDMI("Bitstream over HDMI: the receiver decodes DTS, Dolby, TrueHD and DTS-HD MA"),
        SPDIF("Bitstream over optical/S/PDIF: Dolby Digital and core DTS only"),
        PCM("Decoded by the PC: multichannel PCM to your speakers or receiver");

        final String label;

        Sound(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    static Sound sound() {
        try {
            return Sound.valueOf(P.get("sound", Sound.HDMI.name()));
        } catch (IllegalArgumentException e) {
            return Sound.HDMI;
        }
    }

    static void setSound(Sound s) {
        P.put("sound", s.name());
    }

    static boolean menus() {
        return P.getBoolean("menus", true);
    }

    static void setMenus(boolean on) {
        P.putBoolean("menus", on);
    }

    /** Preferred soundtrack and subtitle languages, for example "en" or "eng". Empty means the disc's default. */
    static String audioLang() {
        return P.get("audioLang", "");
    }

    static void setAudioLang(String v) {
        P.put("audioLang", v.trim());
    }

    static String subLang() {
        return P.get("subLang", "");
    }

    static void setSubLang(String v) {
        P.put("subLang", v.trim());
    }

    static boolean startFullScreen() {
        return P.getBoolean("fullScreen", true);
    }

    static void setStartFullScreen(boolean on) {
        P.putBoolean("fullScreen", on);
    }

    /** The shelf fills the screen with large tiles and is run with arrows, OK and Back, like a TV app. */
    static boolean tvMode() {
        return P.getBoolean("tvMode", true);
    }

    static void setTvMode(boolean on) {
        P.putBoolean("tvMode", on);
    }

    /** A remote-control web page for phones on the home network. */
    static boolean phoneRemote() {
        return P.getBoolean("phoneRemote", true);
    }

    static void setPhoneRemote(boolean on) {
        P.putBoolean("phoneRemote", on);
    }

    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    /** The installed DiscShelf.exe, or null when run some other way (for example from an IDE). */
    static String exe() {
        String p = System.getProperty("jpackage.app-path");
        return p != null && !p.isEmpty() ? p : null;
    }

    /** Whether Windows opens Disc Shelf when the PC starts, so the TV needs no keyboard or mouse. */
    static boolean startWithWindows() {
        try {
            Process p = new ProcessBuilder("reg", "query", RUN_KEY, "/v", "DiscShelf").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    static void setStartWithWindows(boolean on) {
        String exe = exe();
        try {
            ProcessBuilder pb = on && exe != null
                    ? new ProcessBuilder("reg", "add", RUN_KEY, "/v", "DiscShelf", "/t", "REG_SZ", "/d", "\"" + exe + "\"", "/f")
                    : new ProcessBuilder("reg", "delete", RUN_KEY, "/v", "DiscShelf", "/f");
            Process p = pb.redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            p.waitFor();
        } catch (Exception ignored) {
        }
    }
}
