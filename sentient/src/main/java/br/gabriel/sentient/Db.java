package br.gabriel.sentient;

import java.util.List;

/**
 * The few SQLite operations the knowledge store needs. Android uses SQLCipher
 * (SqlCipherDb); host tests use plain SQLite over JDBC, so the same SQL runs in both.
 */
public interface Db {
    void exec(String sql, Object... args) throws Exception;

    /** Row id of the inserted row, or -1 when nothing was inserted (INSERT OR IGNORE). */
    long insert(String sql, Object... args) throws Exception;

    /** Number of rows changed. */
    int update(String sql, Object... args) throws Exception;

    /** All rows, each as column values (Long, Double, String, byte[] or null). */
    List<Object[]> query(String sql, Object... args) throws Exception;

    /** Runs {@code work} in one transaction; rolls back if it throws. Not reentrant. */
    <T> T transaction(Work<T> work) throws Exception;

    interface Work<T> { T run() throws Exception; }
}
