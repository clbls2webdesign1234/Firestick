package com.discshelf.tv;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/** A place the app looks for ISO files. */
final class Source {
    static final String SMB = "smb";
    static final String HTTP = "http";
    static final String LOCAL = "local";

    String id = UUID.randomUUID().toString();
    String type = SMB;
    String name = "";
    String address = "";
    String user = "";
    String pass = "";

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("type", type);
        o.put("name", name);
        o.put("address", address);
        o.put("user", user);
        o.put("pass", pass);
        return o;
    }

    static Source fromJson(JSONObject o) {
        Source s = new Source();
        s.id = o.optString("id", s.id);
        s.type = o.optString("type", SMB);
        s.name = o.optString("name", "");
        s.address = o.optString("address", "");
        s.user = o.optString("user", "");
        s.pass = o.optString("pass", "");
        return s;
    }

    String typeLabel() {
        if (HTTP.equals(type)) return "Web address";
        if (LOCAL.equals(type)) return "USB or local folder";
        return "Network share";
    }

    String displayName() {
        return Util.empty(name) ? address : name;
    }
}
