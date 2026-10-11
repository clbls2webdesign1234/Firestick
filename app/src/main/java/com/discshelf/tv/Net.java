package com.discshelf.tv;

import android.net.Uri;

import java.util.Locale;

/** Address handling: tidy what was typed, and add or remove the login part of a URL. */
final class Net {
    private Net() {}

    /**
     * Accepts \\NAS\Films, //nas/films, nas/films or smb://nas/films and returns
     * smb://nas/films/ with the path safely encoded. Returns null if there is no host.
     */
    static String smbUrl(String typed) {
        if (typed == null) return null;
        String a = typed.trim().replace('\\', '/');
        if (a.toLowerCase(Locale.US).startsWith("smb://")) a = a.substring(6);
        while (a.startsWith("/")) a = a.substring(1);
        if (a.isEmpty()) return null;
        int slash = a.indexOf('/');
        String host = slash < 0 ? a : a.substring(0, slash);
        String path = slash < 0 ? "" : a.substring(slash + 1);
        int at = host.lastIndexOf('@');
        if (at >= 0) host = host.substring(at + 1);
        if (host.isEmpty()) return null;
        String url = "smb://" + host + "/" + Uri.encode(Uri.decode(path), "/");
        return url.endsWith("/") ? url : url + "/";
    }

    static String webUrl(String typed) {
        String a = typed == null ? "" : typed.trim();
        if (a.isEmpty()) return null;
        return a.contains("://") ? a : "http://" + a;
    }

    static String stripLogin(String url) {
        int p = url.indexOf("://");
        if (p < 0) return url;
        String rest = url.substring(p + 3);
        int slash = rest.indexOf('/');
        String auth = slash < 0 ? rest : rest.substring(0, slash);
        int at = auth.lastIndexOf('@');
        if (at < 0) return url;
        return url.substring(0, p + 3) + auth.substring(at + 1) + (slash < 0 ? "" : rest.substring(slash));
    }

    /** Puts user:password@ into the URL. A Windows domain typed as DOMAIN\\user is understood. */
    static String withLogin(String url, String user, String pass) {
        if (Util.empty(user)) return url;
        String clean = stripLogin(url);
        int p = clean.indexOf("://");
        if (p < 0) return url;
        String login = Uri.encode(user.trim().replace('\\', ';'), ";");
        if (!Util.empty(pass)) login += ":" + Uri.encode(pass);
        return clean.substring(0, p + 3) + login + "@" + clean.substring(p + 3);
    }

    static boolean hasScheme(String s) {
        return s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*");
    }
}
