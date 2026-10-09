package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Http over HttpURLConnection: no extra dependency. HTTPS only (the manifest also forbids
 * cleartext); tests against a local fake server pass {@code allowPlainLocalhost}.
 * Errors name the problem, never the URL's query, the request body or the response body.
 */
final class UrlHttp implements Http {
    static final int TIMEOUT_MS = 30_000;
    static final int MAX_BODY = 16 * 1024 * 1024;
    private final boolean allowPlainLocalhost;

    UrlHttp() { this(false); }

    UrlHttp(boolean allowPlainLocalhost) { this.allowPlainLocalhost = allowPlainLocalhost; }

    @Override public Response send(String method, String url, Map<String, String> headers, String jsonBody)
            throws IOException {
        URL target = new URL(url);
        boolean local = "localhost".equals(target.getHost()) || "127.0.0.1".equals(target.getHost());
        if (!"https".equals(target.getProtocol()) && !(allowPlainLocalhost && local))
            throw new IOException("Only HTTPS is allowed");
        HttpURLConnection connection = (HttpURLConnection) target.openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (headers != null) for (Map.Entry<String, String> h : headers.entrySet())
                connection.setRequestProperty(h.getKey(), h.getValue());
            if (jsonBody != null) {
                byte[] body = jsonBody.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            }
            int status = connection.getResponseCode();
            InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new Response(status, in == null ? "" : read(in));
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = stream.read(buffer)) != -1) {
                if (out.size() + n > MAX_BODY) throw new IOException("Response too large");
                out.write(buffer, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
