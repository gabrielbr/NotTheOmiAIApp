package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Http;
import br.gabriel.sentient.plugin.Json;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Composio's v3.1 REST API, with the user's own key. GMind uses it to connect accounts and to run
 * read tools. Read-only by construction: {@link #execute} runs only the slugs the toolkits declare
 * as reads and refuses everything else before any request is made. Plain Java (host-tested).
 */
public final class ComposioClient {
    public static final String BASE = "https://backend.composio.dev/api/v3.1";

    /** Every tool GMind may run. Nothing that sends, creates, edits or deletes. */
    static final Set<String> READ_TOOLS;
    static {
        Set<String> tools = new HashSet<>();
        for (ComposioToolkit toolkit : ComposioToolkit.ALL) tools.addAll(toolkit.readTools());
        READ_TOOLS = Collections.unmodifiableSet(tools);
    }

    /**
     * The only writes GMind ever makes, each opt-in: creating Todoist tasks you approve. Kept apart
     * from READ_TOOLS and run only through {@link #write}.
     */
    static final Set<String> WRITE_TOOLS = Collections.singleton(TodoistSync.CREATE);

    private final Http http;
    private final String apiKey, base;

    public ComposioClient(Http http, String apiKey, String base) {
        this.http = http;
        this.apiKey = apiKey;
        this.base = base;
    }

    /** A message meant for the person, never containing their content. */
    public static final class ComposioException extends Exception {
        private static final long serialVersionUID = 1L;
        public final int status;
        public ComposioException(int status, String message) { super(message); this.status = status; }
    }

    /** Where to send the person to connect an account, and the account it will become. */
    public static final class Link {
        public final String redirectUrl, connectedAccountId;
        Link(String redirectUrl, String connectedAccountId) {
            this.redirectUrl = redirectUrl; this.connectedAccountId = connectedAccountId;
        }
    }

    /** An existing Composio-managed auth config for the toolkit, or a new one. */
    public String authConfig(String toolkit) throws IOException, ComposioException {
        Object list = call("GET", "/auth_configs?toolkit_slug=" + enc(toolkit), null);
        for (Object item : Json.list(Json.at(list, "items"))) {
            boolean managed = Json.bool(Json.at(item, "is_composio_managed"));
            boolean disabled = Json.bool(Json.at(item, "is_disabled"))
                    || "DISABLED".equalsIgnoreCase(Json.str(Json.at(item, "status")));
            String slug = Json.str(Json.at(item, "toolkit", "slug"));
            String id = Json.str(Json.at(item, "id"));
            if (managed && !disabled && id != null && (slug == null || slug.equalsIgnoreCase(toolkit))) return id;
        }
        Object created = call("POST", "/auth_configs", Json.write(Json.object(
                "toolkit", Json.object("slug", toolkit),
                "auth_config", Json.object("type", "use_composio_managed_auth"))));
        String id = Json.str(Json.at(created, "auth_config", "id"));
        if (id == null) throw new ComposioException(0, "Composio didn't return a connection setup for " + toolkit);
        return id;
    }

    public Link link(String authConfigId, String userId) throws IOException, ComposioException {
        Object created = call("POST", "/connected_accounts/link", Json.write(Json.object(
                "auth_config_id", authConfigId, "user_id", userId)));
        String url = Json.str(Json.at(created, "redirect_url"));
        String account = Json.str(Json.at(created, "connected_account_id"));
        if (url == null || account == null || !url.startsWith("https://"))
            throw new ComposioException(0, "Composio didn't return a connect link");
        return new Link(url, account);
    }

    /** ACTIVE, INITIATED, INITIALIZING, FAILED, EXPIRED…; null if the account no longer exists. */
    public String status(String connectedAccountId) throws IOException, ComposioException {
        try {
            return Json.str(Json.at(call("GET", "/connected_accounts/" + enc(connectedAccountId), null), "status"));
        } catch (ComposioException e) {
            if (e.status == 404) return null;
            throw e;
        }
    }

    /** Removes the connection at Composio (the person's own account; nothing in the service changes). */
    public void disconnect(String connectedAccountId) throws IOException, ComposioException {
        try {
            call("DELETE", "/connected_accounts/" + enc(connectedAccountId), null);
        } catch (ComposioException e) {
            if (e.status != 404) throw e;
        }
    }

    /** Runs one allow-listed read tool and returns its {@code data}. */
    public Object execute(String slug, String userId, String connectedAccountId, Map<String, Object> arguments)
            throws IOException, ComposioException {
        if (!READ_TOOLS.contains(slug)) throw new IllegalArgumentException("Not a read tool: " + slug);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        body.put("connected_account_id", connectedAccountId);
        body.put("arguments", arguments);
        Object result = call("POST", "/tools/execute/" + slug, Json.write(body));
        if (!Json.bool(Json.at(result, "successful")))
            throw new ComposioException(0, slug + " failed at Composio");
        Object data = Json.at(result, "data");
        // Some tools return their payload as a JSON string.
        if (data instanceof String) {
            try { return Json.parse((String) data); } catch (IllegalArgumentException notJson) { return data; }
        }
        return data;
    }

    /** Runs one allow-listed write tool (see WRITE_TOOLS); everything else is refused before any request. */
    Object write(String slug, String userId, String connectedAccountId, Map<String, Object> arguments)
            throws IOException, ComposioException {
        if (!WRITE_TOOLS.contains(slug)) throw new IllegalArgumentException("Not an allowed write: " + slug);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", userId);
        body.put("connected_account_id", connectedAccountId);
        body.put("arguments", arguments);
        Object result = call("POST", "/tools/execute/" + slug, Json.write(body));
        if (!Json.bool(Json.at(result, "successful"))) throw new ComposioException(0, slug + " failed at Composio");
        Object data = Json.at(result, "data");
        if (data instanceof String) {
            try { return Json.parse((String) data); } catch (IllegalArgumentException notJson) { return data; }
        }
        return data;
    }

    private Object call(String method, String path, String body) throws IOException, ComposioException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", apiKey);
        Http.Response response = http.send(method, base + path, headers, body);
        if (!response.ok()) throw new ComposioException(response.status, explain(response.status));
        if (response.body.trim().isEmpty()) return Collections.emptyMap();
        try {
            return Json.parse(response.body);
        } catch (IllegalArgumentException malformed) {
            throw new ComposioException(response.status, "Composio sent a reply GMind couldn't read");
        }
    }

    static String explain(int status) {
        switch (status) {
            case 401: case 403: return "Composio refused the API key. Check it in Connect sources.";
            case 402: return "Composio says the plan's limit is reached.";
            case 404: return "Composio couldn't find that connection. Connect it again.";
            case 410: return "The connection expired. Connect it again in Connect sources.";
            case 429: return "Composio is rate limiting. GMind will retry on the next sync.";
            default: return status >= 500 ? "Composio is having trouble. GMind will retry on the next sync."
                    : "Composio couldn't take the request (" + status + ").";
        }
    }

    static String enc(String value) {
        try { return URLEncoder.encode(value, StandardCharsets.UTF_8.name()); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new IllegalStateException(impossible); }
    }
}
