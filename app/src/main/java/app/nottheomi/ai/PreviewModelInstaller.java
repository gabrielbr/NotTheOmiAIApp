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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs only the bundled, pinned Portuguese live-draft model. Never downloads anything. */
public final class PreviewModelInstaller {
    public static final String ARCHIVE_SHA256 =
            "6e1ce909032e1afa7a88e68a3d628ecafff302bdf195befab308826c395e93b7";
    static final String ROOT = "vosk-model-small-pt-0.3";
    private static final long INSTALL_SPACE = 100L * 1024L * 1024L;
    private static final Map<String, Expected> FILES = new LinkedHashMap<>();

    static {
        expected("README", 29, "85353085fa2096ef12cac654fa9534b186bef9b56a61f544da3ebfbd006b78bb");
        expected("final.mdl", 13549849, "3e10e43ec01cf8d968bcc24c626a2eae2bfa77863bee82f36a554854afa1f147");
        expected("disambig_tid.int", 54, "ca8970bc50b8ae2452749f75f5a12049e511075f9fc3f63acc1526c280a33748");
        expected("phones.txt", 1923, "dc207b906747b59e9e3da0af30e8f8d6712c9897ad7df06929d4b2cae7ee13f1");
        expected("Gr.fst", 16007119, "d81023936f5557c06930802b1db4880f56d6ac51b16ed4e5060ceba06895442c");
        expected("ivector/global_cmvn.stats", 547, "8848c2dcf9919fbb3db574cbce59f980c3b11107c29adfa1fc42a4faed27e1a8");
        expected("ivector/online_cmvn.conf", 95, "a2f3571754b64297cb7efb2e7ca3df61995c5a45fcbb97188f90613552bb2dfe");
        expected("ivector/splice.conf", 35, "9f0c5f7c82d18eaf25d8bce470efa9f7741f88411fe428774bc0a9bb69a24756");
        expected("ivector/final.ie", 8288887, "9345786636c358c9539591da71fb685ae82d8a96ce5de533fb8ac5fdafa965f0");
        expected("ivector/final.dubm", 168048, "28dcdc395b13a2085d20f7a2e850ef7e5216ba33ded76391b9690dd60f5143de");
        expected("ivector/final.mat", 22575, "e310571390a3183718fce914bb022d297777c7ed860892a1812b576a698ec420");
        expected("HCLr.fst", 15510922, "8b45be1fa72913d61e37ac0b411177803848f4cda7728f74139b39acd1c0cf36");
        expected("mfcc.conf", 153, "d3d4c517da7e6d02ed85803eebf77bca3f7f48c9ab6dc035013250b36187f274");
        expected("word_boundary.int", 2481, "b8f981d84d8765fb15003ac54ec0f9599efa65a00d6d4d60086e5969ad45ff01");
    }

    private PreviewModelInstaller() { }

    /** Call on a worker. Thread interruption cancels before a model is returned. */
    public static File prepare(Context context) throws Exception {
        return prepare(context, () -> false);
    }

    /** Cooperative cancellation also covers an explicit Stop during preparation. */
    public static File prepare(Context context, BooleanSupplier cancelled) throws Exception {
        return prepare(new File(context.getNoBackupFilesDir(), "speech-model"),
                () -> context.getAssets().open("model.zip"), cancelled);
    }

    interface AssetSource { InputStream open() throws IOException; }

    // Package-private entry point permits host testing with the actual bundled asset.
    static synchronized File prepare(File base, AssetSource archive, BooleanSupplier cancelled)
            throws Exception {
        checkCancelled(cancelled);
        if (Files.isSymbolicLink(base.toPath())) throw new IOException("Unsafe model directory");
        if (!base.isDirectory() && !base.mkdirs()) throw new IOException("Model directory unavailable");
        File installed = new File(base, ROOT);
        File staging = new File(base, ROOT + ".installing");
        if (verify(installed, cancelled)) return installed;
        removeTree(staging);
        if (base.getUsableSpace() < INSTALL_SPACE) throw new IOException("Insufficient space for offline model");
        // Check the ENTIRE compressed asset, not only the ZIP member payloads.
        try (InputStream input = archive.open()) {
            if (!ARCHIVE_SHA256.equals(hash(input, cancelled))) {
                throw new IOException("Bundled model checksum mismatch");
            }
        }
        checkCancelled(cancelled);
        if (!staging.mkdir()) throw new IOException("Cannot stage offline model");
        boolean published = false;
        try {
            try (InputStream input = archive.open()) {
                extractChecked(input, staging, cancelled);
            }
            if (!verify(staging, cancelled)) throw new IOException("Incomplete offline model");
            checkCancelled(cancelled);
            // Only invalid installs are removed. A partially extracted tree is never published.
            removeTree(installed);
            Files.move(staging.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE);
            published = true;
            checkCancelled(cancelled);
            return installed;
        } finally {
            if (!published) removeTree(staging);
        }
    }

    static void extractChecked(InputStream input, File staging, BooleanSupplier cancelled)
            throws Exception {
        Set<String> seen = new HashSet<>();
        byte[] buffer = new byte[64 * 1024];
        String prefix = staging.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                checkCancelled(cancelled);
                String name = entry.getName();
                if (!name.startsWith(ROOT + "/") || name.contains("\\")
                        || name.contains("\u0000") || name.startsWith("/")
                        || name.contains("//")) throw new IOException("Unsafe model archive path");
                String relative = name.substring(ROOT.length() + 1);
                for (String part : relative.split("/", -1)) {
                    if (part.equals("..") || part.equals(".")) throw new IOException("Unsafe model archive path");
                }
                File output = new File(staging, relative);
                if (!output.getCanonicalPath().startsWith(prefix)
                        && !(entry.isDirectory() && relative.isEmpty())) {
                    throw new IOException("Model archive path escapes staging directory");
                }
                if (entry.isDirectory()) {
                    String directory = relative.endsWith("/")
                            ? relative.substring(0, relative.length() - 1) : relative;
                    if (!directory.isEmpty() && FILES.keySet().stream()
                            .noneMatch(path -> path.startsWith(directory + "/"))) {
                        throw new IOException("Unexpected model directory");
                    }
                    if (!output.isDirectory() && !output.mkdirs()) throw new IOException("Cannot create model directory");
                    continue;
                }
                Expected expected = FILES.get(relative);
                if (expected == null || !seen.add(relative)) throw new IOException("Unexpected or duplicate model file");
                if (entry.getSize() >= 0 && entry.getSize() != expected.length) throw new IOException("Model file length mismatch");
                File parent = output.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create model directory");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long length = 0;
                try (FileOutputStream out = new FileOutputStream(output)) {
                    int count;
                    while ((count = zip.read(buffer)) != -1) {
                        checkCancelled(cancelled);
                        length += count;
                        if (length > expected.length) throw new IOException("Oversized model file");
                        digest.update(buffer, 0, count);
                        out.write(buffer, 0, count);
                    }
                    if (length != expected.length || !expected.sha256.equals(hex(digest.digest()))) {
                        throw new IOException("Extracted model checksum mismatch");
                    }
                    out.getFD().sync();
                }
                zip.closeEntry();
            }
        }
        if (!seen.equals(FILES.keySet())) throw new IOException("Incomplete model archive");
    }

    static boolean verify(File model, BooleanSupplier cancelled) throws Exception {
        checkCancelled(cancelled);
        if (!Files.isDirectory(model.toPath(), LinkOption.NOFOLLOW_LINKS)) return false;
        if (!auditTree(model, model, cancelled)) return false;
        for (Map.Entry<String, Expected> entry : FILES.entrySet()) {
            checkCancelled(cancelled);
            File file = new File(model, entry.getKey());
            if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
                    || file.length() != entry.getValue().length) return false;
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                if (!entry.getValue().sha256.equals(hash(in, cancelled))) return false;
            }
        }
        return true;
    }

    private static boolean auditTree(File root, File current, BooleanSupplier cancelled) throws IOException {
        checkCancelled(cancelled);
        File[] children = current.listFiles();
        if (children == null) return false;
        for (File child : children) {
            checkCancelled(cancelled);
            if (Files.isSymbolicLink(child.toPath())) return false;
            String relative = root.toPath().relativize(child.toPath()).toString().replace(File.separatorChar, '/');
            if (child.isDirectory()) {
                if (FILES.keySet().stream().noneMatch(path -> path.startsWith(relative + "/"))
                        || !auditTree(root, child, cancelled)) return false;
            } else if (!FILES.containsKey(relative)) return false;
        }
        return true;
    }

    private static String hash(InputStream in, BooleanSupplier cancelled) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = in.read(buffer)) != -1) {
            checkCancelled(cancelled);
            digest.update(buffer, 0, count);
        }
        checkCancelled(cancelled);
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(Character.forDigit((value >>> 4) & 15, 16))
                .append(Character.forDigit(value & 15, 16));
        return out.toString();
    }

    private static void checkCancelled(BooleanSupplier cancelled) throws InterruptedIOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Model preparation cancelled");
        }
    }

    private static void removeTree(File file) throws IOException {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Cannot inspect model staging directory");
            for (File child : children) removeTree(child);
        }
        Files.delete(file.toPath());
    }

    private static void expected(String path, long length, String sha256) {
        FILES.put(path, new Expected(length, sha256));
    }

    private static final class Expected {
        final long length;
        final String sha256;
        Expected(long length, String sha256) { this.length = length; this.sha256 = sha256; }
    }
}