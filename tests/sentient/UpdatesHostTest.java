package br.gabriel.sentient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** In-app updates: reading GitHub's latest release, checksums, versions and the hashing copy. */
public final class UpdatesHostTest {
    private static int checks;
    static final String GMIND_SHA = "e42c7e1745e262028e7623da7158677d7068a2443cbfffdadb9b56782a9032c4",
            GVOICE_SHA = "45caa672aa9b99ced288f62aff9b31ba848b94a590806cabfcf8ce0b2dcd6964";

    static String release(String tag, boolean withSums) {
        return "{\"tag_name\":\"" + tag + "\",\"html_url\":\"https://github.com/gabrielbr/NotTheOmiAIApp/releases/tag/" + tag + "\","
                + "\"body\":\"GVoice 0.5.25 notes\",\"assets\":["
                + "{\"name\":\"GMind-0.5.25.apk\",\"size\":31932064,\"browser_download_url\":\"https://github.com/x/GMind-0.5.25.apk\"},"
                + "{\"name\":\"GVoice-0.5.25-arm64-v8a.apk\",\"size\":584190430,\"browser_download_url\":\"https://github.com/x/GVoice-0.5.25-arm64-v8a.apk\"},"
                + "{\"name\":\"GVoice-0.5.25-x86_64.apk\",\"size\":1,\"browser_download_url\":\"https://github.com/x/GVoice-0.5.25-x86_64.apk\"},"
                + "{\"name\":\"Evil.apk\",\"size\":1,\"browser_download_url\":\"http://evil/Evil.apk\"}"
                + (withSums ? ",{\"name\":\"SHA256SUMS.txt\",\"size\":362,\"browser_download_url\":\"https://github.com/x/SHA256SUMS.txt\"}" : "")
                + "]}";
    }

    public static void main(String[] args) throws Exception {
        SourcesHostTest.FakeHttp http = new SourcesHostTest.FakeHttp()
                .on("GET", Updates.LATEST, 200, release("v0.5.25", true))
                .on("GET", "https://github.com/x/SHA256SUMS.txt", 200,
                        GMIND_SHA + "  GMind-0.5.25.apk\n" + GVOICE_SHA.toUpperCase() + " *GVoice-0.5.25-arm64-v8a.apk\nnot a line\n");
        Updates.Release r = Updates.latest(http);
        check("0.5.25".equals(r.version) && r.notes.contains("notes") && r.page.endsWith("v0.5.25"), "release read");
        check(r.apks.size() == 2 && r.apks.get(Updates.GMIND).sha256.equals(GMIND_SHA), "GMind apk with its checksum");
        check(r.apks.get(Updates.GVOICE).name.contains("arm64-v8a") && r.apks.get(Updates.GVOICE).sha256.equals(GVOICE_SHA)
                && r.apks.get(Updates.GVOICE).bytes == 584190430L, "GVoice arm64 apk, checksum lowercased, '*' binary marker read");
        check("GMind".equals(http.requests.get(0).headers.get("User-Agent")), "identifies itself to GitHub");

        Updates.Release noSums = Updates.parse(br.gabriel.sentient.plugin.Json.parse(release("v0.5.25", false)),
                java.util.Collections.emptyMap());
        check(noSums.apks.isEmpty(), "no published checksum, nothing to install");
        try { Updates.latest(new SourcesHostTest.FakeHttp().on("GET", Updates.LATEST, 403, "{}")); check(false, "rate limit explained"); }
        catch (IOException expected) { check(expected.getMessage().contains("rate limiting"), "rate limit explained"); }
        try { Updates.latest(new SourcesHostTest.FakeHttp().on("GET", Updates.LATEST, 200, "{\"tag_name\":\"latest\"}")); check(false, "versionless tag refused"); }
        catch (IOException expected) { check(true, "versionless tag refused"); }

        check(Updates.newer("0.5.25", "0.5.24") && Updates.newer("v1.0.0", "0.9.99") && Updates.newer("0.10.0", "0.9.9"), "newer versions");
        check(!Updates.newer("0.5.24", "0.5.24") && !Updates.newer("0.5.23", "0.5.24") && !Updates.newer("0.5.25", null), "not newer");
        check("0.5.24".equals(Updates.version("v0.5.24-beta")) && Updates.version("v1") == null, "version from tag");
        check("584 MB".equals(Updates.size(584190430L)) && "1.2 GB".equals(Updates.size(1_200_000_000L)), "sizes");

        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long[] progress = {0};
        String sha = Updates.copyHashing(new ByteArrayInputStream(data), out, n -> progress[0] += n, () -> false);
        check(sha.equals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824") && progress[0] == 5
                && out.size() == 5, "copy and SHA-256");
        try { Updates.copyHashing(new ByteArrayInputStream(data), new ByteArrayOutputStream(), n -> { }, () -> true); check(false, "cancel"); }
        catch (IOException expected) { check(expected.getMessage().contains("cancelled"), "cancel stops the download"); }
        System.out.println("PASS_UPDATES_HOST_CHECKS " + checks);
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        checks++;
    }
}
