package app.nottheomi.ai;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.function.BooleanSupplier;

/** Copies only the pinned bundled Whisper (medium, small) and VAD models; never downloads or touches recordings. */
public final class ModelInstaller {
    public static final String MODEL_FILE = "ggml-medium-q5_0.bin";
    public static final String MODEL_SHA256 =
            "19fea4b380c3a618ec4723c3eef2eb785ffba0d0538cf43f8f235e7b3b34220f";
    public static final long MODEL_BYTES = 539212467L;
    /** Earlier pinned Whisper weights. Superseded copies only waste private storage. */
    static final String[] SUPERSEDED = {"ggml-small.en-q5_1.bin"};
    /** Whisper small: the quick pass while recording. Medium redoes it while charging. */
    public static final String SMALL_FILE = "ggml-small-q5_1.bin";
    public static final String SMALL_SHA256 =
            "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb";
    public static final long SMALL_BYTES = 190085487L;
    /** Silero voice-activity model: Whisper only hears the parts of each window with speech. */
    public static final String VAD_FILE = "ggml-silero-v5.1.2.bin";
    public static final String VAD_SHA256 =
            "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf";
    public static final long VAD_BYTES = 885098L;
    private ModelInstaller() { }

    public static File prepare(Context context) throws Exception {
        return prepare(context, () -> false);
    }

    public static File prepare(Context context, BooleanSupplier cancelled) throws Exception {
        return prepare(new File(context.getNoBackupFilesDir(), "speech-model"),
                () -> context.getAssets().open(MODEL_FILE), cancelled);
    }

    public static File prepareSmall(Context context, BooleanSupplier cancelled) throws Exception {
        return install(new File(context.getNoBackupFilesDir(), "speech-model"), SMALL_FILE, SMALL_SHA256, SMALL_BYTES,
                new String[0], () -> context.getAssets().open(SMALL_FILE), cancelled);
    }

    public static File prepareVad(Context context, BooleanSupplier cancelled) throws Exception {
        return install(new File(context.getNoBackupFilesDir(), "speech-model"), VAD_FILE, VAD_SHA256, VAD_BYTES,
                new String[0], () -> context.getAssets().open(VAD_FILE), cancelled);
    }

    interface AssetSource { InputStream open() throws IOException; }

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
