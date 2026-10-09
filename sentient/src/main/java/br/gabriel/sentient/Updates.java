package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Http;
import br.gabriel.sentient.plugin.Json;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the newest release of GMind and GVoice on GitHub: version, APK download URLs and the
 * published SHA-256 of each file. Read-only and unauthenticated; installing is UpdateInstaller's job.
 * Plain Java (host-tested).
 */
public final class Updates {
    public static final String LATEST = "https://api.github.com/repos/gabrielbr/NotTheOmiAIApp/releases/latest";
    public static final String GMIND = "br.gabriel.sentient", GVOICE = "br.gabriel.omitarefas";
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    private Updates() {}

    /** One app's file in a release. */
    public static final class Asset {
        public final String name, url, sha256;
        public final long bytes;
        Asset(String name, String url, long bytes, String sha256) {
            this.name = name; this.url = url; this.bytes = bytes; this.sha256 = sha256;
        }
    }

    public static final class Release {
        public final String version, notes, page;
        /** Package name → APK. */
        public final Map<String, Asset> apks;
        Release(String version, String notes, String page, Map<String, Asset> apks) {
            this.version = version; this.notes = notes; this.page = page; this.apks = Collections.unmodifiableMap(apks);
        }
    }

    /** The latest release, or an exception whose message is shown to the person. */
    public static Release latest(Http http) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("User-Agent", "GMind");
        Http.Response r = http.get(LATEST, headers);
        if (r.status == 403 || r.status == 429) throw new IOException("GitHub is rate limiting; try again in an hour.");
        if (!r.ok()) throw new IOException("GitHub didn't answer (" + r.status + ").");
        Object release;
        try { release = Json.parse(r.body); } catch (IllegalArgumentException malformed) { throw new IOException("GitHub sent a reply GMind couldn't read."); }
        String sumsUrl = null;
        for (Object a : Json.list(Json.at(release, "assets")))
            if ("SHA256SUMS.txt".equals(Json.str(Json.at(a, "name")))) sumsUrl = Json.str(Json.at(a, "browser_download_url"));
        Map<String, String> sums = Collections.emptyMap();
        if (sumsUrl != null && sumsUrl.startsWith("https://")) {
            Http.Response s = http.get(sumsUrl, Collections.singletonMap("User-Agent", "GMind"));
            if (s.ok()) sums = sums(s.body);
        }
        return parse(release, sums);
    }

    static Release parse(Object release, Map<String, String> sums) throws IOException {
        String version = version(Json.str(Json.at(release, "tag_name")));
        if (version == null) throw new IOException("The latest release has no version.");
        Map<String, Asset> apks = new LinkedHashMap<>();
        for (Object a : Json.list(Json.at(release, "assets"))) {
            String name = Json.str(Json.at(a, "name")), url = Json.str(Json.at(a, "browser_download_url"));
            Long size = Json.num(Json.at(a, "size"));
            if (name == null || url == null || !url.startsWith("https://") || !name.endsWith(".apk")) continue;
            String pkg = name.startsWith("GMind-") ? GMIND
                    : name.startsWith("GVoice-") && name.contains("arm64-v8a") ? GVOICE : null;
            if (pkg == null || !sums.containsKey(name)) continue; // no published checksum, no install
            apks.put(pkg, new Asset(name, url, size == null ? 0 : size, sums.get(name)));
        }
        String notes = Json.str(Json.at(release, "body"));
        return new Release(version, notes == null ? "" : notes, Json.str(Json.at(release, "html_url")), apks);
    }

    /** "45ca…  GVoice-0.5.24-arm64-v8a.apk" lines → file name → lowercase hex. */
    static Map<String, String> sums(String text) {
        Map<String, String> sums = new HashMap<>();
        for (String line : text.split("\n")) {
            String[] parts = line.trim().split("\\s+\\*?");
            if (parts.length == 2 && parts[0].matches("(?i)[0-9a-f]{64}")) sums.put(parts[1].trim(), parts[0].toLowerCase(Locale.ROOT));
        }
        return sums;
    }

    /** "v0.5.24" or "0.5.24-beta" → "0.5.24"; null if there's no x.y.z in it. */
    static String version(String tag) {
        if (tag == null) return null;
        Matcher m = VERSION.matcher(tag);
        return m.find() ? m.group(1) + "." + m.group(2) + "." + m.group(3) : null;
    }

    /** True when {@code latest} is a higher x.y.z than {@code installed} (null installed: not installed, so no). */
    public static boolean newer(String latest, String installed) {
        String a = version(latest), b = version(installed);
        if (a == null || b == null) return false;
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int cmp = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            if (cmp != 0) return cmp > 0;
        }
        return false;
    }

    /** Copies {@code in} to {@code out} and returns the SHA-256 of what was copied, as lowercase hex. */
    public static String copyHashing(java.io.InputStream in, java.io.OutputStream out, java.util.function.LongConsumer progress,
                                     java.util.function.BooleanSupplier cancelled) throws IOException {
        java.security.MessageDigest sha;
        try { sha = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        byte[] buffer = new byte[256 * 1024];
        for (int n; (n = in.read(buffer)) > 0; ) {
            if (cancelled.getAsBoolean()) throw new IOException("Update cancelled.");
            out.write(buffer, 0, n);
            sha.update(buffer, 0, n);
            progress.accept(n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }

    /** "584 MB" for the update button. */
    public static String size(long bytes) {
        return bytes >= 1_000_000_000L ? String.format(Locale.ROOT, "%.1f GB", bytes / 1e9) : Math.max(1, Math.round(bytes / 1e6)) + " MB";
    }
}
