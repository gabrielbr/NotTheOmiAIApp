package br.gabriel.sentient;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * The folder the user picked for the Markdown vault (Android's folder picker, a persisted grant),
 * whether to refresh it after each daily sync, and the last export's result. Writes through the
 * Storage Access Framework, so any folder works (local, SD card, a synced app's folder).
 */
final class VaultFolder {
    private static final String PREFS = "vault", URI = "tree", AUTO = "auto", STATUS = "status";

    private VaultFolder() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Uri folder(Context c) {
        String uri = prefs(c).getString(URI, null);
        return uri == null ? null : Uri.parse(uri);
    }

    /** Keeps access to the picked folder across restarts. */
    static void choose(Context c, Uri tree) {
        c.getContentResolver().takePersistableUriPermission(tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        prefs(c).edit().putString(URI, tree.toString()).putBoolean(AUTO, true).putBoolean("drive", false).remove(STATUS).apply();
    }

    /** Stops exporting; the files already written stay where they are. */
    static void forget(Context c) {
        Uri tree = folder(c);
        if (tree != null) {
            try {
                c.getContentResolver().releasePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException alreadyGone) { /* nothing to release */ }
        }
        prefs(c).edit().clear().apply();
    }

    static boolean auto(Context c) { return prefs(c).getBoolean(AUTO, true); }

    /** Export to the "GMind vault" folder in Google Drive (through Composio) instead of a phone folder. */
    static boolean drive(Context c) { return prefs(c).getBoolean("drive", false); }

    static void setDrive(Context c, boolean on) {
        if (on) forgetFolderOnly(c);
        prefs(c).edit().putBoolean("drive", on).putBoolean(AUTO, true).remove(STATUS).apply();
    }

    /** A phone folder or Drive is set up. */
    static boolean enabled(Context c) { return folder(c) != null || drive(c); }

    private static void forgetFolderOnly(Context c) {
        Uri tree = folder(c);
        if (tree == null) return;
        try {
            c.getContentResolver().releasePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException alreadyGone) { /* nothing to release */ }
        prefs(c).edit().remove(URI).apply();
    }
    static void setAuto(Context c, boolean on) { prefs(c).edit().putBoolean(AUTO, on).apply(); }
    static String status(Context c) { return prefs(c).getString(STATUS, null); }

    /** A readable name for the folder, e.g. "Documents/Obsidian/GMind". */
    static String label(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.isEmpty() ? "the folder you picked" : path;
    }

    /** Exports now; returns the status line shown under the folder. Call off the main thread. */
    static String export(Context c, Db db, long now) {
        if (drive(c)) return exportToDrive(c, db, now);
        Uri tree = folder(c);
        if (tree == null) return null;
        String status;
        try {
            int notes = Vault.export(db, new Writer(c.getContentResolver(), tree), now, ZoneId.systemDefault());
            status = "Exported " + notes + " notes · " + android.text.format.DateUtils.formatDateTime(c, now,
                    android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_TIME
                            | android.text.format.DateUtils.FORMAT_ABBREV_MONTH);
        } catch (IllegalStateException refused) {
            status = refused.getMessage();
        } catch (SecurityException | FileNotFoundException lostAccess) {
            status = "GMind can't reach the folder anymore. Pick it again.";
        } catch (Exception failed) {
            status = "Export failed (" + failed.getClass().getSimpleName() + "). It'll try again after the next sync.";
        }
        prefs(c).edit().putString(STATUS, status).apply();
        return status;
    }

    /** Exports into "GMind vault" in Google Drive through the connected Composio Google Drive account. */
    private static String exportToDrive(Context c, Db db, long now) {
        String status;
        try {
            ComposioToolkit drive = ComposioToolkit.forSlug("googledrive");
            String key = SecretStore.COMPOSIO.read(c);
            if (drive == null || !Connections.CONNECTED.equals(Connections.state(c, drive)) || key == null)
                throw new IllegalStateException("Connect Google Drive in Connect sources to export there.");
            ComposioClient client = new ComposioClient(new UrlHttp(), key, ComposioClient.BASE);
            String user = Connections.composioUser(c), account = Connections.account(c, drive);
            DriveVault writer = new DriveVault(db, (slug, args) -> client.write(slug, user, account, args), DriveVault.MAX_WRITES);
            int notes = Vault.export(db, writer, now, ZoneId.systemDefault());
            status = "Exported " + notes + " notes to \"" + DriveVault.FOLDER + "\" in Google Drive"
                    + (writer.deferred > 0 ? " · " + writer.deferred + " more next sync" : "") + " · "
                    + android.text.format.DateUtils.formatDateTime(c, now, android.text.format.DateUtils.FORMAT_SHOW_DATE
                            | android.text.format.DateUtils.FORMAT_SHOW_TIME | android.text.format.DateUtils.FORMAT_ABBREV_MONTH);
        } catch (IllegalStateException setup) {
            status = setup.getMessage();
        } catch (ComposioClient.ComposioException refused) {
            status = refused.getMessage();
        } catch (java.io.IOException offline) {
            status = "Couldn't reach Composio; the vault goes up after the next sync.";
        } catch (Exception failed) {
            status = "Export failed (" + failed.getClass().getSimpleName() + "). It'll try again after the next sync.";
        }
        prefs(c).edit().putString(STATUS, status).apply();
        return status;
    }

    /** Vault.Writer over a document tree. Folders are created as needed; listings are cached per export. */
    static final class Writer implements Vault.Writer {
        private final ContentResolver resolver;
        private final Uri tree;
        private final Map<String, Map<String, Uri>> listings = new HashMap<>();
        private final Map<String, Uri> folders = new HashMap<>();

        Writer(ContentResolver resolver, Uri tree) {
            this.resolver = resolver;
            this.tree = tree;
            folders.put("", DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)));
        }

        @Override public void write(String path, String content) throws Exception {
            String dir = parent(path), name = name(path);
            Uri folder = folder(dir, true);
            Uri file = children(dir, folder).get(name);
            if (file == null) {
                // octet-stream keeps the ".md" name exactly as given on every provider
                file = DocumentsContract.createDocument(resolver, folder, "application/octet-stream", name);
                if (file == null) throw new FileNotFoundException("Couldn't create a note");
                children(dir, folder).put(name, file);
            }
            try (OutputStream out = resolver.openOutputStream(file, "wt")) {
                if (out == null) throw new FileNotFoundException("Couldn't write a note");
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
        }

        @Override public void delete(String path) throws Exception {
            String dir = parent(path);
            Uri folder = folder(dir, false);
            if (folder == null) return;
            Uri file = children(dir, folder).remove(name(path));
            if (file != null) DocumentsContract.deleteDocument(resolver, file);
        }

        @Override public String read(String path) throws Exception {
            String dir = parent(path);
            Uri folder = folder(dir, false);
            if (folder == null) return null;
            Uri file = children(dir, folder).get(name(path));
            if (file == null) return null;
            try (InputStream in = resolver.openInputStream(file)) {
                if (in == null) return null;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }

        /** The Uri of a first-level folder ("People"), created when asked to. */
        private Uri folder(String dir, boolean create) throws Exception {
            Uri known = folders.get(dir);
            if (known != null) return known;
            Uri root = folders.get("");
            Uri found = children("", root).get(dir);
            if (found == null && create) {
                found = DocumentsContract.createDocument(resolver, root, DocumentsContract.Document.MIME_TYPE_DIR, dir);
                if (found == null) throw new FileNotFoundException("Couldn't create a folder");
                children("", root).put(dir, found);
            }
            if (found != null) folders.put(dir, found);
            return found;
        }

        private Map<String, Uri> children(String dir, Uri folder) {
            Map<String, Uri> listing = listings.get(dir);
            if (listing != null) return listing;
            listing = new HashMap<>();
            Uri query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(folder));
            try (Cursor c = resolver.query(query, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                while (c != null && c.moveToNext())
                    listing.put(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)));
            }
            listings.put(dir, listing);
            return listing;
        }

        private static String parent(String path) {
            int slash = path.indexOf('/');
            return slash < 0 ? "" : path.substring(0, slash);
        }

        private static String name(String path) { return path.substring(path.indexOf('/') + 1); }
    }
}
