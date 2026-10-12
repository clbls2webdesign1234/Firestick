package com.discshelf.tv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The shelf: every disc image found, as a grid to pick from with the remote. */
public class MainActivity extends Activity implements Scanner.Listener, Art.Listener {

    private static final int REQ_STORAGE = 7;
    private static final int[][] TILE_COLOURS = {
            {0xFF27364B, 0xFF18212E}, {0xFF3A2C4D, 0xFF211A2D}, {0xFF214540, 0xFF152B28},
            {0xFF4A3324, 0xFF2B1F17}, {0xFF46283A, 0xFF291923}, {0xFF2D3F2A, 0xFF1B2619}};

    private GridView grid;
    private View empty;
    private TextView status;
    private final DiscAdapter adapter = new DiscAdapter();
    private List<Disc> discs = new ArrayList<>();
    private Map<String, Store.Resume> resumes = new HashMap<>();
    private Scanner scanner;
    private boolean askedPermission;
    /** After a Rescan press, anything the scan skipped is shown in full, not cut off in the status line. */
    private boolean explainAfterScan;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        grid = (GridView) findViewById(R.id.grid);
        empty = findViewById(R.id.empty);
        status = (TextView) findViewById(R.id.status);

        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                open(discs.get(position));
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                discOptions(discs.get(position));
                return true;
            }
        });

        findViewById(R.id.btn_rescan).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                explainAfterScan = true;
                Art.retryMissing(MainActivity.this);
                rescan();
            }
        });
        View.OnClickListener toSources = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SourcesActivity.class));
            }
        };
        findViewById(R.id.btn_sources).setOnClickListener(toSources);
        findViewById(R.id.btn_add_source).setOnClickListener(toSources);
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        Art.setListener(this);
        show(Store.discs(this));
        Store.takeDirty(this);
        if (needsStoragePermission()) {
            askedPermission = true;
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
        } else {
            rescan();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Store.takeDirty(this)) {
            if (needsStoragePermission() && !askedPermission) {
                askedPermission = true;
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE);
            } else {
                rescan();
            }
        } else {
            /* Coming back from the player: refresh progress bars and disc types. */
            if (scanner == null) show(Store.discs(this));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanner != null) scanner.cancel();
        Art.setListener(null);
    }

    /** A cover arrived; redraw the tiles on screen. */
    @Override
    public void onArt() {
        adapter.notifyDataSetChanged();
        /* Say why covers are missing, so the cause can be fixed. */
        if (scanner == null && Art.problem != null && Art.found == 0) {
            status.setText("Covers: " + Art.problem);
        }
    }

    private boolean needsStoragePermission() {
        if (Util.canReadStorage(this)) return false;
        if (Prefs.scanUsb(this)) return true;
        for (Source s : Store.sources(this)) if (Source.LOCAL.equals(s.type)) return true;
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_STORAGE) rescan();
    }

    private void rescan() {
        if (scanner != null) scanner.cancel();
        status.setText("Looking for discs…");
        scanner = Scanner.start(this, this);
    }

    @Override
    public void onProgress(String message) {
        status.setText(message);
    }

    @Override
    public void onDone(List<Disc> found, List<String> notes) {
        scanner = null;
        show(found);
        StringBuilder sb = new StringBuilder();
        sb.append(found.size()).append(found.size() == 1 ? " disc" : " discs");
        for (String n : notes) sb.append("   ·   ").append(n);
        status.setText(sb.toString());
        if (found.isEmpty()) {
            explainEmpty(notes);
        } else if (explainAfterScan && !notes.isEmpty()) {
            StringBuilder all = new StringBuilder();
            for (String n : notes) all.append(all.length() == 0 ? "" : "\n\n").append(n);
            new AlertDialog.Builder(this)
                    .setTitle(found.size() + (found.size() == 1 ? " disc found" : " discs found") + ", with notes")
                    .setMessage(all.toString())
                    .setPositiveButton("OK", null)
                    .show();
        }
        explainAfterScan = false;
    }

    /** The status line is one short line, so an empty shelf spells out what each source reported. */
    private void explainEmpty(List<String> notes) {
        TextView title = (TextView) findViewById(R.id.empty_title);
        TextView detail = (TextView) findViewById(R.id.empty_detail);
        Button add = (Button) findViewById(R.id.btn_add_source);
        if (Store.sources(this).isEmpty() && notes.isEmpty()) {
            title.setText("No discs on the shelf yet");
            detail.setText("Plug in a USB drive holding .iso files, or add a network share or web address where your DVD and Blu-ray images are kept.");
            add.setText("Add a source");
            return;
        }
        title.setText("No discs found");
        StringBuilder sb = new StringBuilder();
        for (String n : notes) sb.append(sb.length() == 0 ? "" : "\n").append(n);
        if (sb.length() == 0) sb.append("Your sources were searched but held no .iso files or disc folders.");
        sb.append("\n\nCheck the address, user name and password under Sources, then press Rescan.");
        detail.setText(sb.toString());
        add.setText("Check sources");
    }

    private void show(List<Disc> list) {
        int selected = grid.getSelectedItemPosition();
        discs = list;
        resumes = Store.resumes(this);
        adapter.notifyDataSetChanged();
        boolean none = list.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        grid.setVisibility(none ? View.GONE : View.VISIBLE);
        if (none) {
            findViewById(R.id.btn_add_source).requestFocus();
        } else {
            if (selected >= 0 && selected < list.size()) grid.setSelection(selected);
            if (getCurrentFocus() == null || getCurrentFocus().getId() == R.id.btn_add_source) grid.requestFocus();
        }
        if (status.getText().length() == 0) status.setText(list.size() + (list.size() == 1 ? " disc" : " discs"));
    }

    private void open(final Disc d) {
        final Store.Resume r = Store.resume(this, d.id);
        if (r == null) {
            play(d, false);
            return;
        }
        String[] items = {"Resume from " + Util.time(r.time), "Start from the beginning"};
        new AlertDialog.Builder(this)
                .setTitle(d.title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        play(d, which == 0);
                    }
                })
                .show();
    }

    private void play(Disc d, boolean resume) {
        if (!resume) Store.saveResume(this, d.id, null);
        Intent i = new Intent(this, PlayerActivity.class);
        i.putExtra(PlayerActivity.EXTRA_DISC, d.id);
        i.putExtra(PlayerActivity.EXTRA_RESUME, resume);
        startActivity(i);
    }

    private void discOptions(final Disc d) {
        String[] items = {"Play from the beginning", "Forget where I stopped", "Try a different cover", "Show no cover"};
        new AlertDialog.Builder(this)
                .setTitle(d.title)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            play(d, false);
                        } else if (which == 1) {
                            Store.saveResume(MainActivity.this, d.id, null);
                            show(discs);
                        } else if (which == 2) {
                            Art.next(MainActivity.this, d);
                            show(discs);
                        } else {
                            Art.hide(MainActivity.this, d);
                            show(discs);
                        }
                    }
                })
                .show();
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU && grid.hasFocus()) {
            int pos = grid.getSelectedItemPosition();
            if (pos >= 0 && pos < discs.size()) {
                discOptions(discs.get(pos));
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private final class DiscAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return discs.size();
        }

        @Override
        public Object getItem(int position) {
            return discs.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(MainActivity.this).inflate(R.layout.item_disc, parent, false);
            Disc d = discs.get(position);

            int[] c = TILE_COLOURS[Math.abs(d.title.hashCode() % TILE_COLOURS.length)];
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, c);
            bg.setCornerRadius(Util.dp(MainActivity.this, 10));
            v.setBackground(bg);
            /* Rounded corners for the cover too. */
            v.setClipToOutline(true);

            Bitmap art = Art.get(MainActivity.this, d);
            ImageView cover = (ImageView) v.findViewById(R.id.cover);
            cover.setImageBitmap(art);
            cover.setVisibility(art != null ? View.VISIBLE : View.GONE);
            v.findViewById(R.id.scrim).setVisibility(art != null ? View.VISIBLE : View.GONE);

            TextView title = (TextView) v.findViewById(R.id.title);
            title.setText(d.title);
            /* The cover carries the name; the text stays for discs without one. */
            title.setVisibility(art != null ? View.INVISIBLE : View.VISIBLE);
            TextView kind = (TextView) v.findViewById(R.id.kind);
            kind.setText((Util.empty(d.kind) ? "ISO" : d.kind.toUpperCase()) + (d.folder ? " FOLDER" : ""));
            ((TextView) v.findViewById(R.id.size)).setText(Util.size(d.size));
            ((TextView) v.findViewById(R.id.source)).setText(d.sourceName);

            ProgressBar bar = (ProgressBar) v.findViewById(R.id.progress);
            Store.Resume r = resumes.get(d.id);
            if (r != null && r.length > 0) {
                bar.setVisibility(View.VISIBLE);
                bar.setProgress((int) (r.time * 100 / r.length));
            } else {
                bar.setVisibility(View.INVISIBLE);
            }
            return v;
        }
    }
}
