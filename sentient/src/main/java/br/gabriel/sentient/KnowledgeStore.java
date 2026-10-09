package br.gabriel.sentient;

import android.content.Context;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import java.io.File;
import java.util.Arrays;

/** The one encrypted knowledge database (SQLCipher + FTS5), migrated on first open. */
final class KnowledgeStore {
    private static Db instance;

    private KnowledgeStore() {}

    /** Opens on first use. Call off the main thread: Keystore and migrations can be slow. */
    static synchronized Db get(Context context) throws Exception {
        if (instance != null) return instance;
        Context app = context.getApplicationContext();
        System.loadLibrary("sqlcipher");
        byte[] key = KeyVault.storeKey(app);
        File file = app.getDatabasePath("knowledge.db");
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IllegalStateException("No database directory");
        SQLiteDatabase database;
        try {
            database = SQLiteDatabase.openOrCreateDatabase(file, key, null, null, null);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
        Db db = new SqlCipherDb(database);
        db.exec("PRAGMA foreign_keys = ON");
        Schema.migrate(db);
        instance = db;
        return db;
    }
}
