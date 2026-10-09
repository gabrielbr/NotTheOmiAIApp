package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Http;
import br.gabriel.sentient.plugin.Json;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The few Matrix client-server API calls GMind needs: sign in, read (sync and room history),
 * sign out. Nothing that sends. HTTPS homeservers only. Plain Java (host-tested).
 */
final class MatrixClient {
    static final String DEVICE_NAME = "GMind (read-only)";
    private final Http http;

    MatrixClient(Http http) { this.http = http; }

    /** A signed-in device, saved encrypted as JSON. */
    static final class Session {
        final String homeserver, userId, accessToken;
        Session(String homeserver, String userId, String accessToken) {
            this.homeserver = homeserver; this.userId = userId; this.accessToken = accessToken;
        }
        String toJson() {
            return Json.write(Json.object("homeserver", homeserver, "user_id", userId, "access_token", accessToken));
        }
        static Session fromJson(String json) {
            if (json == null) return null;
            try {
                Object o = Json.parse(json);
                String hs = Json.str(Json.at(o, "homeserver")), user = Json.str(Json.at(o, "user_id")),
                        token = Json.str(Json.at(o, "access_token"));
                return hs == null || user == null || token == null ? null : new Session(hs, user, token);
            } catch (IllegalArgumentException corrupt) {
                return null;
            }
        }
    }

    /** A message for the person; never contains their content or their token. */
    static final class MatrixException extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;
        final String code;
        MatrixException(int status, String code, String message) { super(message); this.status = status; this.code = code; }
        boolean signedOut() { return status == 401 || "M_UNKNOWN_TOKEN".equals(code); }
    }

    /** "@ana:example.org" → "example.org"; "example.org" or "https://example.org/" → "example.org". */
    static String serverName(String userOrServer) {
        String s = userOrServer.trim();
        if (s.startsWith("@") && s.indexOf(':') > 0) return s.substring(s.indexOf(':') + 1);
        s = s.replaceFirst("^https://", "");
        if (s.startsWith("http://")) throw new IllegalArgumentException("Use an https:// homeserver");
        int slash = s.indexOf('/');
        return slash >= 0 ? s.substring(0, slash) : s;
    }

    /** The homeserver's client API base, from .well-known when the server publishes one. */
    String discover(String serverName) throws IOException {
        String fallback = "https://" + serverName;
        try {
            Http.Response r = http.get(fallback + "/.well-known/matrix/client", Collections.emptyMap());
            if (r.ok()) {
                String base = Json.str(Json.at(Json.parse(r.body), "m.homeserver", "base_url"));
                if (base != null && base.startsWith("https://")) return base.replaceAll("/+$", "");
            }
        } catch (IllegalArgumentException malformed) {
            // no usable well-known: the server itself is the homeserver
        }
        return fallback;
    }

    /** Password login; the password is used once and never kept. */
    Session login(String homeserver, String user, String password) throws IOException, MatrixException {
        Object result = call("POST", homeserver, "/_matrix/client/v3/login", null, Json.write(Json.object(
                "type", "m.login.password",
                "identifier", Json.object("type", "m.id.user", "user", user.trim()),
                "password", password,
                "initial_device_display_name", DEVICE_NAME)));
        String token = Json.str(Json.at(result, "access_token")), userId = Json.str(Json.at(result, "user_id"));
        if (token == null || userId == null) throw new MatrixException(0, null, "The homeserver didn't sign you in");
        String wellKnown = Json.str(Json.at(result, "well_known", "m.homeserver", "base_url"));
        String base = wellKnown != null && wellKnown.startsWith("https://") ? wellKnown.replaceAll("/+$", "") : homeserver;
        return new Session(base, userId, token);
    }

    /** Checks a pasted access token and returns its session. */
    Session withToken(String homeserver, String token) throws IOException, MatrixException {
        Object who = call("GET", homeserver, "/_matrix/client/v3/account/whoami", token.trim(), null);
        String userId = Json.str(Json.at(who, "user_id"));
        if (userId == null) throw new MatrixException(0, null, "The homeserver didn't accept that token");
        return new Session(homeserver, userId, token.trim());
    }

    void logout(Session s) throws IOException {
        try { call("POST", s.homeserver, "/_matrix/client/v3/logout", s.accessToken, "{}"); }
        catch (MatrixException alreadyGone) { /* the token is dead either way */ }
    }

    Object sync(Session s, String since, String filter) throws IOException, MatrixException {
        StringBuilder path = new StringBuilder("/_matrix/client/v3/sync?timeout=0&filter=")
                .append(ComposioClient.enc(filter));
        if (since != null) path.append("&since=").append(ComposioClient.enc(since));
        return call("GET", s.homeserver, path.toString(), s.accessToken, null);
    }

    /** Older history of one room, newest first, starting at {@code from}. */
    Object messages(Session s, String roomId, String from, String filter, int limit) throws IOException, MatrixException {
        String path = "/_matrix/client/v3/rooms/" + ComposioClient.enc(roomId) + "/messages?dir=b&limit=" + limit
                + "&from=" + ComposioClient.enc(from) + "&filter=" + ComposioClient.enc(filter);
        return call("GET", s.homeserver, path, s.accessToken, null);
    }

    private Object call(String method, String base, String path, String token, String body)
            throws IOException, MatrixException {
        if (!base.startsWith("https://"))
            throw new IOException("Only HTTPS homeservers");
        Map<String, String> headers = new LinkedHashMap<>();
        if (token != null) headers.put("Authorization", "Bearer " + token);
        Http.Response r = http.send(method, base + path, headers, body);
        Object parsed;
        try { parsed = r.body.trim().isEmpty() ? Collections.emptyMap() : Json.parse(r.body); }
        catch (IllegalArgumentException malformed) {
            throw new MatrixException(r.status, null, "The homeserver sent a reply GMind couldn't read");
        }
        if (!r.ok()) {
            String code = Json.str(Json.at(parsed, "errcode"));
            throw new MatrixException(r.status, code, explain(r.status, code));
        }
        return parsed;
    }

    static String explain(int status, String code) {
        if ("M_FORBIDDEN".equals(code) && status == 403) return "Wrong user or password.";
        if (status == 401 || "M_UNKNOWN_TOKEN".equals(code)) return "Matrix signed GMind out. Sign in again in Connect sources.";
        if (status == 429 || "M_LIMIT_EXCEEDED".equals(code)) return "The homeserver is rate limiting. GMind will retry on the next sync.";
        if (status >= 500) return "The homeserver is having trouble. GMind will retry on the next sync.";
        return "The homeserver refused the request (" + status + (code == null ? "" : " " + code) + ").";
    }
}
