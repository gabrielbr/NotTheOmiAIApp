package br.gabriel.sentient;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;

/**
 * Downloads an update APK for GMind or GVoice, checks it against the release's SHA-256 and against
 * the signing key of the apps already installed, and hands it to Android's PackageInstaller, which
 * asks you to confirm. Nothing is installed without that confirmation, and only one update runs at once.
 */
final class UpdateInstaller {
    private static final String PREFS = "updates", LATEST = "latest", NOTES = "notes", PAGE = "page", CHECKED = "checked";
    static final String ACTION_RESULT = "br.gabriel.sentient.UPDATE_RESULT";

    enum State { IDLE, DOWNLOADING, VERIFYING, WAITING, FAILED }

    /** What the Updates screen shows for the update in progress (one at a time). */
    static volatile State state = State.IDLE;
    static volatile String working, message;
    static volatile long done, total;
    private static volatile boolean cancelled;

    private UpdateInstaller() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Remembers the latest release seen (for the home line and the Updates screen). */
    static void remember(Context c, Updates.Release r, long now) {
        SharedPreferences.Editor e = prefs(c).edit().putString(LATEST, r.version).putString(NOTES, r.notes)
                .putString(PAGE, r.page).putLong(CHECKED, now);
        for (java.util.Map.Entry<String, Updates.Asset> a : r.apks.entrySet())
            e.putString(a.getKey() + ".name", a.getValue().name).putString(a.getKey() + ".url", a.getValue().url)
                    .putLong(a.getKey() + ".bytes", a.getValue().bytes).putString(a.getKey() + ".sha", a.getValue().sha256);
        e.apply();
    }

    static String latest(Context c) { return prefs(c).getString(LATEST, null); }
    static String notes(Context c) { return prefs(c).getString(NOTES, ""); }
    static long checkedAt(Context c) { return prefs(c).getLong(CHECKED, 0); }

    static Updates.Asset asset(Context c, String pkg) {
        SharedPreferences p = prefs(c);
        String url = p.getString(pkg + ".url", null), sha = p.getString(pkg + ".sha", null);
        if (url == null || sha == null) return null;
        return new Updates.Asset(p.getString(pkg + ".name", ""), url, p.getLong(pkg + ".bytes", 0), sha);
    }

    /** Installed version name, or null when the app isn't installed. */
    static String installed(Context c, String pkg) {
        try { return c.getPackageManager().getPackageInfo(pkg, 0).versionName; }
        catch (PackageManager.NameNotFoundException missing) { return null; }
    }

    /** Apps with a newer release than what's installed. */
    static java.util.List<String> available(Context c) {
        java.util.List<String> apps = new java.util.ArrayList<>();
        String latest = latest(c);
        for (String pkg : new String[]{Updates.GMIND, Updates.GVOICE})
            if (latest != null && asset(c, pkg) != null && Updates.newer(latest, installed(c, pkg))) apps.add(pkg);
        return apps;
    }

    static boolean mayInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    static Intent allowInstallsSettings(Context c) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()));
    }

    static void cancel() { cancelled = true; }

    /** Runs the whole update on a background thread; progress is in the static fields. */
    static synchronized boolean start(Context context, String pkg) {
        if (state == State.DOWNLOADING || state == State.VERIFYING) return false;
        Updates.Asset asset = asset(context, pkg);
        if (asset == null) return false;
        Context app = context.getApplicationContext();
        cancelled = false;
        working = pkg;
        message = null;
        done = 0;
        total = asset.bytes;
        state = State.DOWNLOADING;
        new Thread(() -> {
            try {
                File apk = download(app, asset);
                state = State.VERIFYING;
                checkSigner(app, apk, pkg);
                install(app, apk);
                state = State.WAITING;
            } catch (Exception failure) {
                state = State.FAILED;
                message = failure instanceof IOException || failure instanceof SecurityException ? failure.getMessage()
                        : "The update couldn't be installed (" + failure.getClass().getSimpleName() + ").";
            }
        }, "gmind-update").start();
        return true;
    }

    private static File download(Context c, Updates.Asset asset) throws Exception {
        File dir = new File(c.getNoBackupFilesDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("No room to download the update.");
        for (File old : dir.listFiles() == null ? new File[0] : dir.listFiles()) old.delete();
        File part = new File(dir, "update.apk.part"), apk = new File(dir, "update.apk");
        String digest;
        if (c.getNoBackupFilesDir().getUsableSpace() < asset.bytes + 200_000_000L)
            throw new IOException("Not enough free space for the " + Updates.size(asset.bytes) + " download.");
        HttpURLConnection connection = (HttpURLConnection) new URL(asset.url).openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "GMind");
        try {
            if (!"https".equals(connection.getURL().getProtocol())) throw new IOException("Only HTTPS downloads.");
            int status = connection.getResponseCode();
            if (!"https".equals(connection.getURL().getProtocol())) throw new IOException("Only HTTPS downloads.");
            if (status != 200) throw new IOException("The download failed (" + status + ").");
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                digest = Updates.copyHashing(in, out, n -> done += n, () -> cancelled);
            }
        } catch (java.net.UnknownHostException | java.net.SocketTimeoutException offline) {
            throw new IOException("Couldn't reach GitHub. Check your internet connection.");
        } finally {
            connection.disconnect();
        }
        if (!digest.equals(asset.sha256)) {
            part.delete();
            throw new IOException("The download didn't match the release's checksum, so it wasn't installed.");
        }
        if (!part.renameTo(apk)) throw new IOException("Couldn't save the download.");
        return apk;
    }

    /** The APK must be signed with the same key as GMind (both apps share it) and as the installed app. */
    private static void checkSigner(Context c, File apk, String pkg) throws Exception {
        PackageManager pm = c.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getPath(), signingFlag());
        if (archive == null || !pkg.equals(archive.packageName)) throw new SecurityException("That file isn't the expected app.");
        Signature[] update = signers(archive), own = signers(pm.getPackageInfo(c.getPackageName(), signingFlag()));
        if (update == null || !Arrays.equals(update, own)) throw new SecurityException("The update isn't signed with this app's key, so it wasn't installed.");
        try {
            Signature[] target = signers(pm.getPackageInfo(pkg, signingFlag()));
            if (!Arrays.equals(update, target)) throw new SecurityException("The update's key doesn't match the installed app.");
        } catch (PackageManager.NameNotFoundException notInstalled) {
            // a fresh install of the other app; GMind's own key already matched
        }
    }

    @SuppressWarnings("deprecation")
    private static int signingFlag() {
        return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        return info.signatures;
    }

    private static void install(Context c, File apk) throws Exception {
        PackageInstaller installer = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setSize(apk.length());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("update.apk", 0, apk.length())) {
                byte[] buffer = new byte[256 * 1024];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                session.fsync(out);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent result = PendingIntent.getBroadcast(c, id,
                    new Intent(c, Receiver.class).setAction(ACTION_RESULT), flags);
            session.commit(result.getIntentSender());
        }
    }

    /** PackageInstaller's answer: show Android's confirmation, or record success or failure. */
    public static final class Receiver extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @SuppressWarnings("deprecation") Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            }
            if (status == PackageInstaller.STATUS_SUCCESS) { state = State.IDLE; message = "Updated."; }
            else {
                state = State.FAILED;
                message = status == PackageInstaller.STATUS_FAILURE_ABORTED ? "Update cancelled."
                        : status == PackageInstaller.STATUS_FAILURE_STORAGE ? "Not enough storage to install."
                        : "Android didn't install the update (" + status + ").";
            }
            File apk = new File(new File(context.getNoBackupFilesDir(), "updates"), "update.apk");
            if (apk.exists()) apk.delete();
        }
    }
}
