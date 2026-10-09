package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Json;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vault.Writer into a "GMind vault" folder in your Google Drive, through the connected Composio
 * Google Drive account, so Claude can read it with its Drive connector. Only changed notes are
 * uploaded, at most {@code maxWrites} per export (the rest go next time). It only ever edits or
 * deletes files it created itself, listed in a manifest kept in the encrypted store, never in Drive.
 * Plain Java (host-tested).
 */
public final class DriveVault implements Vault.Writer {
    static final String CREATE_FOLDER = "GOOGLEDRIVE_CREATE_FOLDER", CREATE_FILE = "GOOGLEDRIVE_CREATE_FILE_FROM_TEXT",
            EDIT_FILE = "GOOGLEDRIVE_EDIT_FILE", DELETE_FILE = "GOOGLEDRIVE_DELETE_FILE";
    static final String STATE = "vault.drive", FOLDER = "GMind vault";
    static final int MAX_WRITES = 300;

    /** The calls this writer makes; ComposioClient.write in the app, a fake in tests. */
    public interface Drive { Object write(String slug, Map<String, Object> arguments) throws Exception; }

    private final Db db;
    private final Drive drive;
    private final int maxWrites;
    private final Map<String, Object> state;
    private int writes;
    /** Notes left for the next export because the write cap was reached. */
    public int deferred;

    public DriveVault(Db db, Drive drive, int maxWrites) throws Exception {
        this.db = db;
        this.drive = drive;
        this.maxWrites = maxWrites;
        String saved = Meta.get(db, STATE);
        Map<String, Object> s = null;
        if (saved != null) try { s = new LinkedHashMap<>(Json.obj(Json.parse(saved))); } catch (IllegalArgumentException corrupt) { s = null; }
        state = s != null ? s : new LinkedHashMap<>();
        state.put("folders", new LinkedHashMap<>(Json.obj(state.get("folders"))));
        state.put("files", new LinkedHashMap<>(Json.obj(state.get("files"))));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> files() { return (Map<String, Object>) state.get("files"); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> folders() { return (Map<String, Object>) state.get("folders"); }

    /** Vault.export asks for its manifest: answer with the notes this writer put in Drive. */
    @Override public String read(String path) {
        if (Vault.MANIFEST.equals(path)) return files().isEmpty() ? null : Json.write(Json.object("files", new ArrayList<>(files().keySet())));
        return null; // nothing is downloaded from Drive
    }

    @Override public void write(String path, String content) throws Exception {
        if (Vault.MANIFEST.equals(path)) { save(); return; } // the manifest lives in the encrypted store
        if (!Vault.safe(path)) throw new IllegalArgumentException("Not a vault note");
        String hash = sha(content);
        Object known = files().get(path);
        if (hash.equals(Json.str(Json.at(known, "hash")))) return;
        if (writes >= maxWrites) { deferred++; return; }
        String id = Json.str(Json.at(known, "id"));
        if (id != null) {
            writes++;
            drive.write(EDIT_FILE, Json.object("file_id", id, "content", content, "mime_type", "text/markdown"));
        } else {
            String parent = folder(parent(path));
            if (writes >= maxWrites) { deferred++; return; }
            writes++;
            id = id(drive.write(CREATE_FILE, Json.object("file_name", name(path), "text_content", content,
                    "mime_type", "text/markdown", "parent_id", parent)));
        }
        files().put(path, Json.object("id", id, "hash", hash));
        save();
    }

    @Override public void delete(String path) throws Exception {
        Object known = files().get(path);
        String id = Json.str(Json.at(known, "id"));
        if (id == null || !Vault.safe(path)) return; // only what GMind created
        if (writes >= maxWrites) { deferred++; return; }
        writes++;
        drive.write(DELETE_FILE, Json.object("fileId", id));
        files().remove(path);
        save();
    }

    /** The Drive id of "GMind vault" (dir "") or one of its subfolders, created on first use. */
    private String folder(String dir) throws Exception {
        String root = Json.str(state.get("root"));
        if (root == null) {
            writes++;
            root = id(drive.write(CREATE_FOLDER, Json.object("name", FOLDER)));
            state.put("root", root);
            save();
        }
        if (dir.isEmpty()) return root;
        String known = Json.str(folders().get(dir));
        if (known != null) return known;
        writes++;
        String id = id(drive.write(CREATE_FOLDER, Json.object("name", dir, "parent_id", root)));
        folders().put(dir, id);
        save();
        return id;
    }

    private void save() throws Exception { Meta.set(db, STATE, Json.write(state)); }

    /** Forgets what was uploaded (after you stop exporting to Drive); the files stay in Drive. */
    public static void forget(Db db) throws Exception { Meta.set(db, STATE, null); }

    static String id(Object data) throws ComposioClient.ComposioException {
        String id = ComposioToolkit.field(data, "id", "file_id", "folder_id");
        if (id == null) id = Json.str(Json.at(data, "file", "id"));
        if (id == null) id = Json.str(Json.at(data, "folder", "id"));
        if (id == null) throw new ComposioClient.ComposioException(0, "Google Drive didn't return the new file");
        return id;
    }

    private static String parent(String path) {
        int slash = path.indexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static String name(String path) { return path.substring(path.indexOf('/') + 1); }

    static String sha(String content) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) hex.append(String.format(java.util.Locale.ROOT, "%02x", d[i] & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
