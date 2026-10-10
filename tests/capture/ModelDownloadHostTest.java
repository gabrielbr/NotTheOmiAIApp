package app.nottheomi.ai;

import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real production download path (ModelInstaller.download) against a local HTTP server serving the
 * pinned VAD model as the fixture: resume with Range, servers ignoring Range, cut connections,
 * oversized and corrupt bodies, cancellation, no network, and reuse without any request.
 */
public final class ModelDownloadHostTest {
    private static int assertions;
    private static byte[] model;
    private static final AtomicInteger requests = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        model = Files.readAllBytes(new File(args[0]).toPath());
        File temporary = new File(args[1]);
        String sha = ModelInstaller.VAD_SHA256;
        long bytes = ModelInstaller.VAD_BYTES;
        check(model.length == bytes, "fixture is the pinned model");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            String mode = exchange.getRequestURI().getPath().substring(1);
            String range = exchange.getRequestHeaders().getFirst("Range");
            long from = range != null && !mode.startsWith("norange") ? Long.parseLong(range.replaceAll("\\D+(\\d+)-.*", "$1")) : 0;
            byte[] body = model;
            if (mode.equals("bad")) { body = model.clone(); body[body.length / 2] ^= 1; }
            if (mode.equals("big")) { body = new byte[model.length + 10]; System.arraycopy(model, 0, body, 0, model.length); }
            int length = (int) (body.length - from);
            exchange.sendResponseHeaders(from > 0 ? 206 : 200, length);
            try (OutputStream out = exchange.getResponseBody()) {
                // "cut": the first, full request stops halfway (a dropped connection).
                int send = mode.equals("cut") && from == 0 ? length / 2 : length;
                out.write(body, (int) from, send);
                if (send < length) { out.flush(); exchange.getHttpContext(); throw new IOException("cut"); }
            } catch (IOException dropped) {
                exchange.close();
            }
        });
        server.start();
        String root = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try {
            long[] last = {-1};
            ModelInstaller.Progress progress = (done, total) -> { check(done <= total && total == bytes, "progress in range"); last[0] = done; };

            File base = new File(temporary, "fresh");
            File installed = ModelInstaller.download(base, "m.bin", sha, bytes, new String[0], fetcher(root + "ok"), () -> true, () -> false, progress);
            check(ModelInstaller.verify(installed, sha, bytes, () -> false) && last[0] == bytes, "fresh download verified and published");
            check(!new File(base, "m.bin.download").exists(), "no partial file after publish");

            int before = requests.get();
            File again = ModelInstaller.download(base, "m.bin", sha, bytes, new String[0],
                    offset -> { throw new AssertionError("no request for an installed model"); }, () -> false, () -> false, progress);
            check(again.equals(installed) && requests.get() == before, "installed model reused: no network, no network check");

            File cut = new File(temporary, "cut");
            reject(() -> ModelInstaller.download(cut, "m.bin", sha, bytes, new String[0], fetcher(root + "cut"), () -> true, () -> false, progress),
                    IOException.class, "dropped connection fails this try");
            long kept = new File(cut, "m.bin.download").length();
            check(kept > 0 && kept < bytes && !new File(cut, "m.bin").exists(), "partial download kept, nothing published");
            File resumed = ModelInstaller.download(cut, "m.bin", sha, bytes, new String[0], fetcher(root + "cut"), () -> true, () -> false, progress);
            check(ModelInstaller.verify(resumed, sha, bytes, () -> false), "resumed with Range (206) and verified");

            File norange = new File(temporary, "norange");
            check(norange.mkdirs(), "fixture dir");
            Files.write(new File(norange, "m.bin.download").toPath(), java.util.Arrays.copyOf(model, 1000));
            File restarted = ModelInstaller.download(norange, "m.bin", sha, bytes, new String[0], fetcher(root + "norange"), () -> true, () -> false, progress);
            check(ModelInstaller.verify(restarted, sha, bytes, () -> false), "server ignoring Range (200): restarted from zero and verified");

            File stale = new File(temporary, "stale");
            check(stale.mkdirs(), "fixture dir");
            byte[] wrong = java.util.Arrays.copyOf(model, 1000); wrong[10] ^= 1;
            Files.write(new File(stale, "m.bin.download").toPath(), wrong);
            reject(() -> ModelInstaller.download(stale, "m.bin", sha, bytes, new String[0], fetcher(root + "ok"), () -> true, () -> false, progress),
                    IOException.class, "corrupt partial download detected by the hash");
            check(!new File(stale, "m.bin.download").exists() && !new File(stale, "m.bin").exists(), "corrupt partial deleted, nothing published");

            File bad = new File(temporary, "bad");
            reject(() -> ModelInstaller.download(bad, "m.bin", sha, bytes, new String[0], fetcher(root + "bad"), () -> true, () -> false, progress),
                    IOException.class, "wrong bytes rejected");
            check(!new File(bad, "m.bin").exists() && !new File(bad, "m.bin.download").exists(), "wrong bytes neither published nor kept");

            File big = new File(temporary, "big");
            reject(() -> ModelInstaller.download(big, "m.bin", sha, bytes, new String[0], fetcher(root + "big"), () -> true, () -> false, progress),
                    IOException.class, "oversized body rejected");
            check(!new File(big, "m.bin").exists() && !new File(big, "m.bin.download").exists(), "oversized body neither published nor kept");

            File offline = new File(temporary, "offline");
            before = requests.get();
            reject(() -> ModelInstaller.download(offline, "m.bin", sha, bytes, new String[0], fetcher(root + "ok"), () -> false, () -> false, progress),
                    ModelInstaller.WaitingForNetwork.class, "no allowed network: waits");
            check(requests.get() == before, "no request without an allowed network");

            File cancel = new File(temporary, "cancel");
            AtomicInteger polls = new AtomicInteger();
            reject(() -> ModelInstaller.download(cancel, "m.bin", sha, bytes, new String[0], fetcher(root + "ok"), () -> true,
                    () -> polls.incrementAndGet() > 3, progress), InterruptedIOException.class, "cancellation stops the download");
            check(!new File(cancel, "m.bin").exists(), "cancelled download not published");

            File space = new File(temporary, "space");
            reject(() -> ModelInstaller.download(space, "m.bin", sha, Long.MAX_VALUE / 4, new String[0], fetcher(root + "ok"), () -> true, () -> false, (d, t) -> { }),
                    IOException.class, "not enough space: refused before downloading");

            File old = new File(temporary, "superseded");
            check(old.mkdirs(), "fixture dir");
            Files.write(new File(old, "older.bin").toPath(), new byte[]{1});
            Files.write(new File(old, "older.bin.download").toPath(), new byte[]{1});
            ModelInstaller.download(old, "m.bin", sha, bytes, new String[]{"older.bin"}, fetcher(root + "ok"), () -> true, () -> false, progress);
            check(!new File(old, "older.bin").exists() && !new File(old, "older.bin.download").exists(), "superseded model and its partial removed");

            reject(() -> ModelInstaller.https("http://127.0.0.1/model.bin").open(0), IOException.class, "plain HTTP refused by the app's fetcher");
            check(ModelInstaller.MODEL_URL.startsWith("https://huggingface.co/ggerganov/whisper.cpp/resolve/" + ModelInstaller.REVISION + "/")
                    && ModelInstaller.SMALL_URL.endsWith("/" + ModelInstaller.SMALL_FILE) && ModelInstaller.MODEL_URL.endsWith("/" + ModelInstaller.MODEL_FILE),
                    "pinned HTTPS URLs at the pinned revision");
        } finally {
            server.stop(0);
        }
        System.out.println("ModelDownloadHostTest PASS: " + assertions + " assertions, real download path against a local server");
    }

    /** Same request shape as the app's HTTPS fetcher, over local HTTP for the test. */
    private static ModelInstaller.Fetcher fetcher(String address) {
        return offset -> {
            HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(2000); // a dropped body surfaces as a timeout, like on a phone
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int status = connection.getResponseCode();
            if (status != 200 && status != 206) throw new IOException("HTTP " + status);
            return new ModelInstaller.Fetch(status == 206, connection.getInputStream(), connection::disconnect);
        };
    }

    private interface Checked { void run() throws Exception; }
    private static void reject(Checked code, Class<? extends Exception> expected, String label) throws Exception {
        try { code.run(); } catch (Exception failure) {
            if (!expected.isInstance(failure)) throw new AssertionError(label + ": " + failure, failure);
            check(true, label); return;
        }
        throw new AssertionError("Expected rejection: " + label);
    }
    private static synchronized void check(boolean ok, String label) {
        if (!ok) throw new AssertionError(label);
        assertions++;
    }
}
