package br.gabriel.sentient;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Host-only Db over plain SQLite (xerial JDBC), so tests run the store's real SQL. */
final class JdbcDb implements Db {
    private final Connection connection;

    JdbcDb(Connection connection) { this.connection = connection; }

    private PreparedStatement prepare(String sql, Object... args) throws Exception {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
        return statement;
    }

    @Override public void exec(String sql, Object... args) throws Exception {
        try (PreparedStatement s = prepare(sql, args)) { s.execute(); }
    }

    @Override public long insert(String sql, Object... args) throws Exception {
        try (PreparedStatement s = prepare(sql, args)) {
            if (s.executeUpdate() == 0) return -1;
        }
        try (Statement s = connection.createStatement(); ResultSet r = s.executeQuery("SELECT last_insert_rowid()")) {
            r.next();
            return r.getLong(1);
        }
    }

    @Override public int update(String sql, Object... args) throws Exception {
        try (PreparedStatement s = prepare(sql, args)) { return s.executeUpdate(); }
    }

    @Override public List<Object[]> query(String sql, Object... args) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        try (PreparedStatement s = prepare(sql, args); ResultSet r = s.executeQuery()) {
            int columns = r.getMetaData().getColumnCount();
            while (r.next()) {
                Object[] row = new Object[columns];
                for (int i = 0; i < columns; i++) {
                    Object value = r.getObject(i + 1);
                    // Match Android cursors: SQLite integers come back as Long.
                    row[i] = value instanceof Integer ? Long.valueOf((Integer) value) : value;
                }
                rows.add(row);
            }
        }
        return rows;
    }

    @Override public <T> T transaction(Work<T> work) throws Exception {
        connection.setAutoCommit(false);
        try {
            T result = work.run();
            connection.commit();
            return result;
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }
}
