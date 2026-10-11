package com.discshelf.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;

/** Where the app looks for disc images: network shares, web addresses, extra folders. */
public class SourcesActivity extends Activity {

    private static final String[] ADD_TYPES = {Source.SMB, Source.HTTP, Source.LOCAL};
    private static final String[] ADD_LABELS = {
            "Add a network share (NAS or PC)",
            "Add a web address (folder or list file)",
            "Add another USB or local folder"};
    private static final String[] ADD_HELP = {
            "A shared folder on a NAS or Windows/Mac computer",
            "A web folder listing, or a text file with one disc per line",
            "USB drives are searched automatically; use this for a folder they miss"};

    private List<Source> sources;
    private final RowAdapter adapter = new RowAdapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_list);
        ((TextView) findViewById(R.id.heading)).setText("Sources");
        ((TextView) findViewById(R.id.help)).setText(
                "Every .iso file, and every disc copied as a folder (VIDEO_TS or BDMV) on USB or local storage, appears on the shelf. Sub-folders are included.");
        sources = Store.sources(this);
        ListView list = (ListView) findViewById(R.id.list);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < sources.size()) {
                    sourceOptions(sources.get(position));
                } else {
                    Source s = new Source();
                    s.type = ADD_TYPES[position - sources.size()];
                    edit(s, true);
                }
            }
        });
        list.requestFocus();
    }

    private void sourceOptions(final Source s) {
        new AlertDialog.Builder(this)
                .setTitle(s.displayName())
                .setItems(new String[]{"Edit", "Remove"}, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            edit(s, false);
                        } else {
                            sources.remove(s);
                            Store.saveSources(SourcesActivity.this, sources);
                            adapter.notifyDataSetChanged();
                        }
                    }
                })
                .show();
    }

    private EditText field(LinearLayout form, String label, String value, String hintText, int inputType) {
        TextView caption = new TextView(this);
        caption.setText(label);
        caption.setTextSize(13);
        caption.setTextColor(getColor(R.color.text_dim));
        caption.setPadding(0, Util.dp(this, 10), 0, 0);
        form.addView(caption);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setText(value);
        e.setHint(hintText);
        e.setTextSize(17);
        form.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private void edit(final Source s, final boolean isNew) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Util.dp(this, 24);
        form.setPadding(pad, Util.dp(this, 4), pad, 0);

        int plain = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        int uri = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
        String addressLabel;
        String addressHint;
        if (Source.HTTP.equals(s.type)) {
            addressLabel = "Web address";
            addressHint = "http://192.168.1.20:8080/discs/  or  …/discs.txt";
        } else if (Source.LOCAL.equals(s.type)) {
            addressLabel = "Folder path, or a list file";
            addressHint = "/storage/1A2B-3C4D/Films";
        } else {
            addressLabel = "Server and shared folder";
            addressHint = "192.168.1.20/Films/Discs";
        }
        final EditText address = field(form, addressLabel, s.address, addressHint, uri);
        final EditText name = field(form, "Name shown on the shelf (optional)", s.name, "Lounge NAS", plain);
        final EditText user;
        final EditText pass;
        if (Source.LOCAL.equals(s.type)) {
            user = null;
            pass = null;
        } else {
            user = field(form, "User name (leave empty for guest access)", s.user, "", plain);
            pass = field(form, "Password", s.pass, "",
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }

        new AlertDialog.Builder(this)
                .setTitle(isNew ? "New source: " + s.typeLabel().toLowerCase() : "Edit " + s.typeLabel().toLowerCase())
                .setView(form)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String a = address.getText().toString().trim();
                        if (a.isEmpty()) return;
                        s.address = a;
                        s.name = name.getText().toString().trim();
                        if (user != null) s.user = user.getText().toString().trim();
                        if (pass != null) s.pass = pass.getText().toString();
                        if (isNew) sources.add(s);
                        Store.saveSources(SourcesActivity.this, sources);
                        adapter.notifyDataSetChanged();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        address.requestFocus();
    }

    private final class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return sources.size() + ADD_TYPES.length;
        }

        @Override
        public Object getItem(int position) {
            return position;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(SourcesActivity.this).inflate(R.layout.item_row, parent, false);
            TextView title = (TextView) v.findViewById(R.id.row_title);
            TextView value = (TextView) v.findViewById(R.id.row_value);
            if (position < sources.size()) {
                Source s = sources.get(position);
                title.setText(s.displayName());
                value.setText(s.typeLabel() + "   ·   " + s.address
                        + (Util.empty(s.user) ? "" : "   ·   signs in as " + s.user));
            } else {
                int i = position - sources.size();
                title.setText("+  " + ADD_LABELS[i]);
                value.setText(ADD_HELP[i]);
            }
            return v;
        }
    }
}
