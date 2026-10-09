package br.gabriel.sentient;

import android.test.AndroidTestCase;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import br.gabriel.sentient.plugin.RawItem;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Device tests on real SQLCipher: FTS5 with accent folding, encryption at rest, wrong key.
 * Uses a fresh synthetic database and a test key; the production store is never opened. */
@SuppressWarnings("deprecation")
public final class SqlCipherStoreTest extends AndroidTestCase {
    private static final byte[] KEY = rawKey('1'), WRONG = rawKey('2');
    private File file;

    /** minSdk 26: no String.repeat or List.of. */
    private static byte[] rawKey(char digit) {
        StringBuilder key = new StringBuilder("x'");
        for (int i = 0; i < 64; i++) key.append(digit);
        return key.append('\'').toString().getBytes(StandardCharsets.US_ASCII);
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        System.loadLibrary("sqlcipher");
        file = new File(getContext().getCacheDir(), "knowledge-test-" + UUID.randomUUID() + ".db");
    }

    @Override protected void tearDown() throws Exception {
        for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) new File(file.getPath() + suffix).delete();
        super.tearDown();
    }

    public void testFtsSearchAndEncryptionAtRest() throws Exception {
        SQLiteDatabase database = SQLiteDatabase.openOrCreateDatabase(file, KEY, null, null, null);
        Db db = new SqlCipherDb(database);
        Schema.migrate(db);
        assertEquals(Schema.latest(), Schema.version(db));
        RawItem item = RawItem.builder(OmiTranscripts.ID, "s1").kind(RawItem.TRANSCRIPT).timestamp(1000)
                .text("Reunião amanhã sobre o orçamento da viagem").conversation("s1", "Manhã", "meeting").build();
        Ingest.Stats stats = db.transaction(() -> Ingest.upsert(db, Collections.singletonList(item), 2000));
        assertEquals(1, stats.added);
        List<Search.Hit> hits = Search.find(db, "reuniao orcamento", 10);
        assertEquals(1, hits.size());
        assertEquals("Manhã", hits.get(0).conversation);
        database.close();

        String bytes = new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1);
        assertFalse("plaintext on disk", bytes.contains("viagem") || bytes.contains("SQLite format 3"));

        try {
            SQLiteDatabase wrong = SQLiteDatabase.openOrCreateDatabase(file, WRONG, null, null, null);
            new SqlCipherDb(wrong).query("SELECT COUNT(*) FROM items");
            wrong.close();
            fail("wrong key must not open the store");
        } catch (RuntimeException expected) {
            // SQLiteException: file is not a database
        }
    }
}
