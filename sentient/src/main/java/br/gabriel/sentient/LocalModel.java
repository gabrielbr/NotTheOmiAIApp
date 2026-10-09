package br.gabriel.sentient;

import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.StatFs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * The on-device model: Qwen2.5 1.5B Instruct (Q4_K_M, Apache-2.0), downloaded on request and
 * checked against the pins in scripts/prepare_llama.py before use. Never bundled in the APK.
 */
final class LocalModel {
    static final String NAME = "Qwen2.5 1.5B";
    static final String URL = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/"
            + "91cad51170dc346986eccefdc2dd33a9da36ead9/qwen2.5-1.5b-instruct-q4_k_m.gguf";
    static final String SHA256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e";
    static final long BYTES = 1_117_320_736L;
    /** Phones below this can't hold the model and the app comfortably (4 GB phones report ~3.6 GB). */
    static final long MIN_RAM = 3_400_000_000L;
    private static final String FILE = "qwen2.5-1.5b-instruct-q4_k_m.gguf", PREFS = "local_model",
            DOWNLOAD_ID = "download_id", WIFI_ONLY = "wifi_only";

    enum State { NONE, DOWNLOADING, VERIFYING, READY, FAILED }

    static final class Status {
        final State state;
        final long done, total;
        final String message;
        Status(State state, long done, long total, String message) {
            this.state = state; this.done = done; this.total = total; this.message = message;
        }
    }

    private LocalModel() {}

    static File file(Context c) { return new File(new File(c.getNoBackupFilesDir(), "models"), FILE); }

    private static File downloadTarget(Context c) { return new File(c.getExternalFilesDir("models"), FILE + ".part"); }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static boolean ready(Context c) { File f = file(c); return f.isFile() && f.length() == BYTES; }

    static boolean wifiOnly(Context c) { return prefs(c).getBoolean(WIFI_ONLY, true); }
    static void setWifiOnly(Context c, boolean only) { prefs(c).edit().putBoolean(WIFI_ONLY, only).apply(); }

    /** Null when this phone can run the model, otherwise why not. */
    static String unsupported(Context c) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(info);
        if (info.totalMem > 0 && info.totalMem < MIN_RAM)
            return "This phone has " + gb(info.totalMem) + " of memory; the on-device model needs about 4 GB.";
        return null;
    }

    /** Starts (or keeps) the download. Throws with a readable message if it can't start. */
    static void download(Context c) throws Exception {
        if (ready(c) || prefs(c).getLong(DOWNLOAD_ID, -1) != -1) return;
        File target = downloadTarget(c);
        if (target.getParentFile() == null) throw new IllegalStateException("No storage for the download.");
        long free = new StatFs(target.getParentFile().getPath()).getAvailableBytes();
        if (free < BYTES * 2 + 200_000_000L)
            throw new IllegalStateException("Needs about " + gb(BYTES * 2) + " free while downloading; " + gb(free) + " is free.");
        if (target.exists() && !target.delete()) throw new IllegalStateException("Couldn't clear an old download.");
        DownloadManager dm = c.getSystemService(DownloadManager.class);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(URL))
                .setTitle("GMind on-device model")
                .setDescription(NAME + " · " + gb(BYTES))
                .setDestinationUri(Uri.fromFile(target))
                .setAllowedOverMetered(!wifiOnly(c))
                .setAllowedOverRoaming(false)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        prefs(c).edit().putLong(DOWNLOAD_ID, dm.enqueue(request)).apply();
    }

    static void cancel(Context c) {
        long id = prefs(c).getLong(DOWNLOAD_ID, -1);
        if (id != -1) c.getSystemService(DownloadManager.class).remove(id);
        prefs(c).edit().remove(DOWNLOAD_ID).apply();
        File part = downloadTarget(c);
        if (part.exists() && !part.delete()) part.deleteOnExit();
    }

    static void delete(Context c) {
        cancel(c);
        LocalBackend.release();
        File f = file(c);
        if (f.exists() && !f.delete()) f.deleteOnExit();
    }

    /**
     * Where things stand. When the download has finished, verifies it (size and SHA-256 against the
     * pin) and moves it into place; call off the main thread, verifying 1.1 GB takes a while.
     */
    static Status poll(Context c) {
        if (ready(c)) return new Status(State.READY, BYTES, BYTES, null);
        long id = prefs(c).getLong(DOWNLOAD_ID, -1);
        if (id == -1) return new Status(State.NONE, 0, BYTES, null);
        DownloadManager dm = c.getSystemService(DownloadManager.class);
        int status;
        long done, total;
        try (Cursor cursor = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                prefs(c).edit().remove(DOWNLOAD_ID).apply();
                return new Status(State.FAILED, 0, BYTES, "The download was removed.");
            }
            status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
        }
        if (status == DownloadManager.STATUS_FAILED) {
            cancel(c);
            return new Status(State.FAILED, 0, BYTES, "The download failed. Try again.");
        }
        if (status != DownloadManager.STATUS_SUCCESSFUL)
            return new Status(State.DOWNLOADING, Math.max(0, done), total > 0 ? total : BYTES,
                    status == DownloadManager.STATUS_PAUSED ? (wifiOnly(c) ? "Waiting for Wi-Fi…" : "Paused…") : null);
        return install(c);
    }

    private static synchronized Status install(Context c) {
        if (ready(c)) return new Status(State.READY, BYTES, BYTES, null);
        File part = downloadTarget(c), dest = file(c);
        try {
            if (part.length() != BYTES || !SHA256.equals(sha256(part)))
                throw new IllegalStateException("The download didn't match the expected model, so it was discarded.");
            File dir = dest.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("No room for the model.");
            File temp = new File(dest.getPath() + ".tmp");
            try (InputStream in = new FileInputStream(part); OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[1 << 20];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
            }
            if (temp.length() != BYTES || !temp.renameTo(dest)) throw new IllegalStateException("Couldn't store the model.");
            cancel(c);
            return new Status(State.READY, BYTES, BYTES, null);
        } catch (Exception failed) {
            cancel(c);
            return new Status(State.FAILED, 0, BYTES, failed.getMessage());
        }
    }

    static String sha256(File f) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buffer = new byte[1 << 20];
            for (int n; (n = in.read(buffer)) > 0; ) digest.update(buffer, 0, n);
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
    }

    static String gb(long bytes) { return String.format(Locale.ROOT, "%.1f GB", bytes / 1e9); }
}
