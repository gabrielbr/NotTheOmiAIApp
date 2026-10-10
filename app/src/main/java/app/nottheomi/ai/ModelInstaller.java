package app.nottheomi.ai;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

/**
 * Installs the pinned Whisper models; never touches recordings. Whisper small and medium are
 * downloaded once (HTTPS, a pinned Hugging Face revision, verified by size and SHA-256) so app
 * updates stay small; the tiny VAD model is copied from the APK. Only model files are fetched.
 */
public final class ModelInstaller {
    /** The pinned Hugging Face revision of ggerganov/whisper.cpp (same as scripts/prepare_whisper.py). */
    public static final String REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1";
    private static final String BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/" + REVISION + "/";
    public static final String MODEL_FILE = "ggml-medium-q5_0.bin";
    public static final String MODEL_SHA256 =
            "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f";
    public static final long MODEL_BYTES = 539212467L;
    public static final String MODEL_URL = BASE_URL + "ggml-medium-q5_0.bin";
    /** Earlier pinned Whisper weights. Superseded copies only waste private storage. */
    static final String[] SUPERSEDED = {"ggml-small.en-q5_1.bin"};
    /** Whisper small: the quick pass while recording. Medium redoes it while charging. */
    public static final String SMALL_FILE = "ggml-small-q5_1.bin";
    public static final String SMALL_SHA256 =
            "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb";
    public static final long SMALL_BYTES = 190085487L;
    public static final String SMALL_URL = BASE_URL + "ggml-small-q5_1.bin";
    /** Silero voice-activity model: Whisper only hears the parts of each window with speech. */
    public static final String VAD_FILE = "ggml-silero-v5.1.2.bin";
    public static final String VAD_SHA256 =
            "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf";
    public static final long VAD_BYTES = 885098L;
    private ModelInstaller() { }

    /** No network the user allows for model downloads (by default: Wi-Fi or another unmetered one). */
    public static final class WaitingForNetwork extends IOException {
        WaitingForNetwork(String model) { super("Waiting for a network to download " + model); }
    }

    /** Download progress: bytes on disk out of the model's size. */
    public interface Progress { void update(long done, long total); }

    static File directory(Context context) { return new File(context.getNoBackupFilesDir(), "speech-model"); }

    /** Whisper medium (the accurate pass), downloading it on an allowed network if needed. */
    public static File prepare(Context context) throws Exception {
        return prepare(context, () -> false, true, (done, total) -> { });
    }

    public static File prepare(Context context, BooleanSupplier cancelled, boolean metered, Progress progress) throws Exception {
        return download(directory(context), MODEL_FILE, MODEL_SHA256, MODEL_BYTES, SUPERSEDED,
                https(MODEL_URL), () -> networkAllowed(context, metered), cancelled, progress);
    }

    /** Whisper small (the quick pass), downloading it on an allowed network if needed. */
    public static File prepareSmall(Context context, BooleanSupplier cancelled, boolean metered, Progress progress) throws Exception {
        return download(directory(context), SMALL_FILE, SMALL_SHA256, SMALL_BYTES, new String[0],
                https(SMALL_URL), () -> networkAllowed(context, metered), cancelled, progress);
    }

    /** True when the verified model is already on the phone (no download needed). Hashes the file. */
    static boolean installed(Context context, String name, String sha256, long bytes) {
        try { return verify(new File(directory(context), name), sha256, bytes, () -> false); }
        catch (Exception unreadable) { return false; }
    }

    /** Bytes of a model already downloaded (a finished file or a partial one), for display. */
    static long downloaded(Context context, String name, long bytes) {
        File file = new File(directory(context), name);
        if (file.length() == bytes) return bytes;
        return Math.min(bytes, new File(directory(context), name + ".download").length());
    }

    /** An internet-capable network the user allows: unmetered, or any when mobile data is allowed. */
    static boolean networkAllowed(Context context, boolean metered) {
        try {
            ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
            Network network = connectivity == null ? null : connectivity.getActiveNetwork();
            NetworkCapabilities caps = network == null ? null : connectivity.getNetworkCapabilities(network);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false;
            return metered || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        } catch (RuntimeException unavailable) { return false; }
    }

    public static File prepareVad(Context context, BooleanSupplier cancelled) throws Exception {
        return install(new File(context.getNoBackupFilesDir(), "speech-model"), VAD_FILE, VAD_SHA256, VAD_BYTES,
                new String[0], () -> context.getAssets().open(VAD_FILE), cancelled);
    }

    interface AssetSource { InputStream open() throws IOException; }

    /** One response body: from the requested offset ({@code partial}) or the whole file. */
    static final class Fetch implements Closeable {
        final boolean partial;
        final InputStream body;
        private final Closeable connection;
        Fetch(boolean partial, InputStream body, Closeable connection) {
            this.partial = partial; this.body = body; this.connection = connection;
        }
        @Override public void close() throws IOException {
            try { body.close(); } finally { connection.close(); }
        }
    }

    /** Opens the model's bytes from {@code offset} (or from 0 if the server can't). */
    interface Fetcher { Fetch open(long offset) throws IOException; }

    /** HTTPS only, including every redirect; a plain-HTTP hop fails the download. */
    static Fetcher https(String address) {
        return offset -> {
            URL url = new URL(address);
            if (!"https".equals(url.getProtocol())) throw new IOException("Model downloads use HTTPS only");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(60_000);
            connection.setUseCaches(false);
            // Java follows https->https redirects (Hugging Face sends files to its CDN), never https->http.
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int status = connection.getResponseCode();
            if (!"https".equals(connection.getURL().getProtocol())) {
                connection.disconnect();
                throw new IOException("Model download left HTTPS");
            }
            if (status != 200 && status != 206) {
                connection.disconnect();
                throw new IOException("Model download failed (HTTP " + status + ")");
            }
            return new Fetch(status == 206, connection.getInputStream(), connection::disconnect);
        };
    }

    /**
     * Downloads a pinned model into place: resumes a partial download (re-hashing what's on disk),
     * never accepts more than {@code bytes}, and publishes only after the size and SHA-256 match.
     * A verified installed copy is reused without any network access. Network failures keep the
     * partial file for the next try; corrupt content is deleted.
     */
    static synchronized File download(File base, String name, String sha256, long bytes, String[] superseded,
                                      Fetcher fetcher, BooleanSupplier networkAllowed, BooleanSupplier cancelled,
                                      Progress progress) throws Exception {
        checkCancelled(cancelled);
        if (Files.isSymbolicLink(base.toPath())) throw new IOException("Unsafe model directory");
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Model directory unavailable");
        File installed = new File(base, name);
        File partial = new File(base, name + ".download");
        if (verify(installed, sha256, bytes, cancelled)) return installed;
        Files.deleteIfExists(new File(base, name + ".installing").toPath()); // a bundled copy cut short
        for (String old : superseded) {
            Files.deleteIfExists(new File(base, old).toPath());
            Files.deleteIfExists(new File(base, old + ".installing").toPath());
            Files.deleteIfExists(new File(base, old + ".download").toPath());
        }
        if (!networkAllowed.getAsBoolean()) throw new WaitingForNetwork(name);
        if (Files.isSymbolicLink(partial.toPath()) || (partial.exists() && !partial.isFile())
                || partial.length() > bytes) Files.deleteIfExists(partial.toPath());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long have = 0;
        byte[] buffer = new byte[64 * 1024];
        if (partial.isFile()) {
            try (InputStream in = new BufferedInputStream(new FileInputStream(partial))) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    digest.update(buffer, 0, count);
                    have += count;
                }
            }
        }
        if (base.getUsableSpace() < bytes - have + 8L * 1024 * 1024)
            throw new IOException("Insufficient space for the speech model");
        progress.update(have, bytes);
        while (have < bytes) {
            checkCancelled(cancelled);
            long before = have;
            try (Fetch fetch = fetcher.open(have)) {
                boolean append = have > 0 && fetch.partial;
                if (!append && have > 0) { digest.reset(); have = 0; } // the server sent the whole file
                try (FileOutputStream out = new FileOutputStream(partial, append)) {
                    int count;
                    long reported = have;
                    while ((count = fetch.body.read(buffer)) != -1) {
                        checkCancelled(cancelled);
                        if (have + count > bytes) {
                            Files.deleteIfExists(partial.toPath());
                            throw new IOException("Oversized model download");
                        }
                        digest.update(buffer, 0, count);
                        out.write(buffer, 0, count);
                        have += count;
                        if (have - reported >= 1024 * 1024 || have == bytes) { progress.update(have, bytes); reported = have; }
                    }
                    out.getFD().sync();
                }
            }
            if (have == before) throw new IOException("Model download made no progress");
        }
        if (!sha256.equals(hex(digest.digest()))) {
            Files.deleteIfExists(partial.toPath());
            throw new IOException("Downloaded model checksum mismatch");
        }
        checkCancelled(cancelled);
        Files.move(partial.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        progress.update(bytes, bytes);
        return installed;
    }

    static File prepare(File base, AssetSource source, BooleanSupplier cancelled) throws Exception {
        return install(base, MODEL_FILE, MODEL_SHA256, MODEL_BYTES, SUPERSEDED, source, cancelled);
    }

    static synchronized File install(File base, String name, String sha256, long bytes, String[] superseded,
                                     AssetSource source, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        if (Files.isSymbolicLink(base.toPath())) throw new IOException("Unsafe model directory");
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Model directory unavailable");
        File installed = new File(base, name);
        File staging = new File(base, name + ".installing");
        // Fully hash existing content on every preparation, rather than trusting a marker.
        if (verify(installed, sha256, bytes, cancelled)) return installed;
        Files.deleteIfExists(staging.toPath()); // Unlinks symlinks, never follows them.
        // Free the old model's space before checking for room. Only these exact model
        // files (and their staging copies) are removed; recordings live elsewhere.
        for (String old : superseded) {
            Files.deleteIfExists(new File(base, old).toPath());
            Files.deleteIfExists(new File(base, old + ".installing").toPath());
        }
        if (base.getUsableSpace() < bytes + 8L * 1024 * 1024)
            throw new IOException("Insufficient space for offline model");
        boolean published = false;
        try {
            if (!staging.createNewFile()) throw new IOException("Cannot stage offline model");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long length = 0;
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = new BufferedInputStream(source.open());
                 FileOutputStream out = new FileOutputStream(staging)) {
                int count;
                while ((count = in.read(buffer)) != -1) {
                    checkCancelled(cancelled);
                    length += count;
                    if (length > bytes) throw new IOException("Oversized bundled model");
                    digest.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                }
                if (length != bytes || !sha256.equals(hex(digest.digest())))
                    throw new IOException("Bundled model checksum mismatch");
                out.getFD().sync();
            }
            checkCancelled(cancelled);
            Files.move(staging.toPath(), installed.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            published = true;
            checkCancelled(cancelled);
            return installed;
        } finally {
            if (!published) Files.deleteIfExists(staging.toPath());
        }
    }

    static boolean verify(File file, BooleanSupplier cancelled) throws Exception {
        return verify(file, MODEL_SHA256, MODEL_BYTES, cancelled);
    }

    static boolean verify(File file, String sha256, long bytes, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                || file.length() != bytes) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int count;
            while ((count = in.read(buffer)) != -1) {
                checkCancelled(cancelled);
                digest.update(buffer, 0, count);
            }
        }
        checkCancelled(cancelled);
        return sha256.equals(hex(digest.digest()));
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) text.append(Character.forDigit((value >>> 4) & 15, 16))
                .append(Character.forDigit(value & 15, 16));
        return text.toString();
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
            throw new InterruptedIOException("Model preparation cancelled");
    }
}
