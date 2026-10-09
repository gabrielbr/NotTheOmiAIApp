package br.gabriel.sentient.plugin;

import java.io.IOException;
import java.util.Map;

/**
 * The host's HTTP client for network plugins. HTTPS only. Exceptions carry no request or
 * response bodies, since those hold the user's content and secrets.
 */
public interface Http {
    Response send(String method, String url, Map<String, String> headers, String jsonBody) throws IOException;

    default Response get(String url, Map<String, String> headers) throws IOException {
        return send("GET", url, headers, null);
    }

    default Response post(String url, Map<String, String> headers, String jsonBody) throws IOException {
        return send("POST", url, headers, jsonBody);
    }

    final class Response {
        public final int status;
        public final String body;
        public Response(int status, String body) { this.status = status; this.body = body == null ? "" : body; }
        public boolean ok() { return status >= 200 && status < 300; }
    }
}
