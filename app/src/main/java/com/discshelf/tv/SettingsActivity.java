package com.discshelf.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import org.videolan.libvlc.LibVLC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Sound, picture and remote-control preferences. */
public class SettingsActivity extends Activity {

    private static final int CHOICE = 0;
    private static final int TOGGLE = 1;
    private static final int MULTI = 2;
    private static final int NUMBER = 3;
    private static final int INFO = 4;

    private static final String[] LANG_CODES = {"", "en", "fr", "de", "es", "it", "nl", "pt", "sv", "da", "no", "fi",
            "pl", "ru", "el", "tr", "ja", "ko", "zh", "hi", "ar", "he"};
    private static final String[] LANG_NAMES = {"", "English", "French", "German", "Spanish", "Italian", "Dutch",
            "Portuguese", "Swedish", "Danish", "Norwegian", "Finnish", "Polish", "Russian", "Greek", "Turkish",
            "Japanese", "Korean", "Chinese", "Hindi", "Arabic", "Hebrew"};

    private static final class Item {
        final int type;
        final String key;
        final String title;
        final String help;
        final String[] values;
        final String[] labels;
        final String fallback;

        Item(int type, String key, String title, String help, String[] values, String[] labels, String fallback) {
            this.type = type;
            this.key = key;
            this.title = title;
            this.help = help;
            this.values = values;
            this.labels = labels;
            this.fallback = fallback;
        }
    }

    private final List<Item> items = new ArrayList<>();
    private final RowAdapter adapter = new RowAdapter();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        prefs = Prefs.get(this);
        ((TextView) findViewById(R.id.heading)).setText("Settings");
        ((TextView) findViewById(R.id.help)).setText(
                "Most of these can also be changed during a film: press the Menu button on the remote.");

        items.add(new Item(CHOICE, Prefs.AUDIO_OUT, "Sound output",
                "Bitstream hands Dolby and DTS to your receiver untouched",
                new String[]{Prefs.OUT_AUTO, Prefs.OUT_FORCE, Prefs.OUT_PCM, Prefs.OUT_STEREO},
                new String[]{"Bitstream, automatic (recommended)", "Bitstream, forced for the formats below",
                        "Multichannel PCM, decoded by the Fire TV", "Stereo, for TV speakers"}, Prefs.OUT_AUTO));
        items.add(new Item(MULTI, Prefs.FORCE_FORMATS, "Formats to force",
                "Only used when sound output is set to forced",
                new String[]{"ac3", "eac3", "dts", "dtshd", "truehd"},
                new String[]{"Dolby Digital", "Dolby Digital Plus (carries Atmos)", "DTS", "DTS-HD", "Dolby TrueHD"}, ""));

        String[] audioLabels = LANG_NAMES.clone();
        audioLabels[0] = "Whatever the disc chooses";
        items.add(new Item(CHOICE, Prefs.AUDIO_LANG, "Preferred soundtrack language", null,
                LANG_CODES, audioLabels, ""));
        String[] subLabels = LANG_NAMES.clone();
        subLabels[0] = "Off unless I turn them on";
        items.add(new Item(CHOICE, Prefs.SUB_LANG, "Subtitles shown by default", null,
                LANG_CODES, subLabels, ""));

        items.add(new Item(CHOICE, Prefs.START_AT, "Discs start at", null,
                new String[]{"menu", "feature"},
                new String[]{"The disc's own menu", "The main title, skipping the menu"}, "menu"));
        items.add(new Item(CHOICE, Prefs.SKIP_KEYS, "Rewind and fast-forward buttons", null,
                new String[]{"chapter", "seek"},
                new String[]{"Skip to the previous or next chapter", "Jump back 10 s or forward 30 s"}, "chapter"));
        items.add(new Item(TOGGLE, Prefs.MATCH_RATE, "Match the TV's refresh rate to the disc",
                "Smoother motion; the screen blanks for a moment when a disc starts", null, null, "true"));
        items.add(new Item(CHOICE, Prefs.DECODE, "DVD picture processing",
                "Try software if a DVD shows comb-like lines on movement",
                new String[]{"hw", "sw"},
                new String[]{"Hardware (recommended)", "Software with de-interlacing"}, "hw"));
        items.add(new Item(NUMBER, Prefs.NET_CACHE, "Network buffer",
                "Raise this if films from a share stutter",
                new String[]{"1500", "3000", "6000", "10000"},
                new String[]{"1.5 seconds", "3 seconds", "6 seconds", "10 seconds"}, "3000"));
        items.add(new Item(TOGGLE, Prefs.SCAN_USB, "Search USB drives automatically", null, null, null, "true"));
        items.add(new Item(INFO, "about", "About", null, null, null, ""));

        ListView list = (ListView) findViewById(R.id.list);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                change(items.get(position));
            }
        });
        list.requestFocus();
    }

    private String current(Item it) {
        switch (it.type) {
            case TOGGLE:
                return prefs.getBoolean(it.key, Boolean.parseBoolean(it.fallback)) ? "On" : "Off";
            case NUMBER:
                return labelFor(it, String.valueOf(prefs.getInt(it.key, Integer.parseInt(it.fallback))));
            case MULTI: {
                Set<String> chosen = Prefs.forceFormats(this);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < it.values.length; i++) {
                    if (!chosen.contains(it.values[i])) continue;
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(it.labels[i]);
                }
                return sb.length() == 0 ? "None" : sb.toString();
            }
            case INFO:
                return about();
            default:
                return labelFor(it, prefs.getString(it.key, it.fallback));
        }
    }

    private static String labelFor(Item it, String value) {
        for (int i = 0; i < it.values.length; i++) if (it.values[i].equals(value)) return it.labels[i];
        return value;
    }

    private String aboutText;

    private String about() {
        if (aboutText != null) return aboutText;
        String app = "";
        try {
            app = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        String engine = "";
        try {
            LibVLC probe = new LibVLC(this, new ArrayList<String>());
            engine = "   ·   playback engine: libVLC " + LibVLC.version();
            probe.release();
        } catch (Throwable ignored) {
        }
        aboutText = "Disc Shelf " + app + engine;
        return aboutText;
    }

    private void change(final Item it) {
        if (it.type == INFO) return;
        if (it.type == TOGGLE) {
            boolean now = prefs.getBoolean(it.key, Boolean.parseBoolean(it.fallback));
            prefs.edit().putBoolean(it.key, !now).apply();
            if (Prefs.SCAN_USB.equals(it.key)) Store.markDirty(this);
            adapter.notifyDataSetChanged();
            return;
        }
        if (it.type == MULTI) {
            final Set<String> chosen = Prefs.forceFormats(this);
            boolean[] checked = new boolean[it.values.length];
            for (int i = 0; i < checked.length; i++) checked[i] = chosen.contains(it.values[i]);
            new AlertDialog.Builder(this)
                    .setTitle(it.title)
                    .setMultiChoiceItems(it.labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                            if (isChecked) chosen.add(it.values[which]);
                            else chosen.remove(it.values[which]);
                        }
                    })
                    .setPositiveButton("Done", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            prefs.edit().putStringSet(it.key, new HashSet<>(chosen)).apply();
                            adapter.notifyDataSetChanged();
                        }
                    })
                    .show();
            return;
        }
        String value = it.type == NUMBER
                ? String.valueOf(prefs.getInt(it.key, Integer.parseInt(it.fallback)))
                : prefs.getString(it.key, it.fallback);
        int selected = 0;
        for (int i = 0; i < it.values.length; i++) if (it.values[i].equals(value)) selected = i;
        new AlertDialog.Builder(this)
                .setTitle(it.title)
                .setSingleChoiceItems(it.labels, selected, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (it.type == NUMBER) prefs.edit().putInt(it.key, Integer.parseInt(it.values[which])).apply();
                        else prefs.edit().putString(it.key, it.values[which]).apply();
                        adapter.notifyDataSetChanged();
                        dialog.dismiss();
                    }
                })
                .show();
    }

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(SettingsActivity.this).inflate(R.layout.item_row, parent, false);
            Item it = items.get(position);
            ((TextView) v.findViewById(R.id.row_title)).setText(it.title);
            String value = current(it);
            if (it.help != null) value = value + "\n" + it.help;
            ((TextView) v.findViewById(R.id.row_value)).setText(value);
            return v;
        }
    }
}
