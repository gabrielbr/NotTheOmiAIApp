package br.gabriel.sentient;

import android.database.Cursor;

import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteStatement;

import java.util.ArrayList;
import java.util.List;

/** Db over an open SQLCipher database. */
final class SqlCipherDb implements Db {
    private final SQLiteDatabase db;

    SqlCipherDb(SQLiteDatabase db) { this.db = db; }

    @Override public void exec(String sql, Object... args) {
        if (args.length == 0) db.execSQL(sql); else db.execSQL(sql, args);
    }

    @Override public long insert(String sql, Object... args) {
        try (SQLiteStatement statement = db.compileStatement(sql)) {
            bind(statement, args);
            return statement.executeInsert();
        }
    }

    @Override public int update(String sql, Object... args) {
        try (SQLiteStatement statement = db.compileStatement(sql)) {
            bind(statement, args);
            return statement.executeUpdateDelete();
        }
    }

    @Override public List<Object[]> query(String sql, Object... args) {
        List<Object[]> rows = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(sql, args)) {
            int columns = cursor.getColumnCount();
            while (cursor.moveToNext()) {
                Object[] row = new Object[columns];
                for (int i = 0; i < columns; i++) {
                    switch (cursor.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER: row[i] = cursor.getLong(i); break;
                        case Cursor.FIELD_TYPE_FLOAT: row[i] = cursor.getDouble(i); break;
                        case Cursor.FIELD_TYPE_STRING: row[i] = cursor.getString(i); break;
                        case Cursor.FIELD_TYPE_BLOB: row[i] = cursor.getBlob(i); break;
                        default: row[i] = null;
                    }
                }
                rows.add(row);
            }
        }
        return rows;
    }

    @Override public <T> T transaction(Work<T> work) throws Exception {
        db.beginTransaction();
        try {
            T result = work.run();
            db.setTransactionSuccessful();
            return result;
        } finally {
            db.endTransaction();
        }
    }

    private static void bind(SQLiteStatement statement, Object[] args) {
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            int index = i + 1;
            if (value == null) statement.bindNull(index);
            else if (value instanceof Long || value instanceof Integer) statement.bindLong(index, ((Number) value).longValue());
            else if (value instanceof Double || value instanceof Float) statement.bindDouble(index, ((Number) value).doubleValue());
            else if (value instanceof byte[]) statement.bindBlob(index, (byte[]) value);
            else statement.bindString(index, value.toString());
        }
    }
}
