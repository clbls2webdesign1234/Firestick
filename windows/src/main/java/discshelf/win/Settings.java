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
}
