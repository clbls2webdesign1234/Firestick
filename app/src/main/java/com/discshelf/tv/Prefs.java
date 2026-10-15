package com.discshelf.tv;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Setting keys and their defaults. */
final class Prefs {
    private Prefs() {}

    static final String AUDIO_OUT = "audio_out";
    static final String FORCE_FORMATS = "force_formats";
    static final String AUDIO_LANG = "audio_lang";
    static final String SUB_LANG = "sub_lang";
    static final String SKIP_KEYS = "skip_keys";
    static final String MATCH_RATE = "match_rate";
    static final String DECODE = "decode";
    static final String START_AT = "start_at";
    static final String SCAN_USB = "scan_usb";
    static final String NET_CACHE = "net_cache";
    static final String SCALE = "scale";
    static final String ART_ONLINE = "art_online";

    static final String OUT_AUTO = "auto";
    static final String OUT_FORCE = "force";
    static final String OUT_PCM = "pcm";
    static final String OUT_STEREO = "stereo";

    static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static String audioOut(Context c) {
        return get(c).getString(AUDIO_OUT, OUT_AUTO);
    }

    static Set<String> forceFormats(Context c) {
        Set<String> def = new HashSet<>(Arrays.asList("ac3", "eac3", "dts"));
        return new HashSet<>(get(c).getStringSet(FORCE_FORMATS, def));
    }

    static String audioLang(Context c) {
        return get(c).getString(AUDIO_LANG, "");
    }

    static String subLang(Context c) {
        return get(c).getString(SUB_LANG, "");
    }

    /** True when the remote's rewind and fast-forward buttons skip chapters. */
    static boolean skipKeysAreChapters(Context c) {
        return "chapter".equals(get(c).getString(SKIP_KEYS, "chapter"));
    }

    static boolean matchRate(Context c) {
        return get(c).getBoolean(MATCH_RATE, true);
    }

    static boolean softwareDecode(Context c) {
        return "sw".equals(get(c).getString(DECODE, "hw"));
    }

    static boolean startAtMenu(Context c, boolean bluray) {
        String at = get(c).getString(START_AT, "menu");
        return "menu".equals(at) || ("dvd".equals(at) && !bluray);
    }

    static boolean scanUsb(Context c) {
        return get(c).getBoolean(SCAN_USB, true);
    }

    static int netCache(Context c) {
        return get(c).getInt(NET_CACHE, 3000);
    }

    static String scale(Context c) {
        return get(c).getString(SCALE, "SURFACE_BEST_FIT");
    }

    static boolean artOnline(Context c) {
        return get(c).getBoolean(ART_ONLINE, true);
    }
}
