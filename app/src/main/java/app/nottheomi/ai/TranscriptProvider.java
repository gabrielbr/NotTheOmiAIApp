package app.nottheomi.ai;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.List;

/**
 * Read-only transcript feed for the Sentient companion app. Guarded by a signature
 * permission, so only apps signed with this app's key can query it. This app still has
 * no INTERNET permission: the companion pulls, and nothing here sends anything anywhere.
 *
 * content://br.gabriel.omitarefas.transcripts/sessions?since=<createdAt millis>
 * Rows: saved (not recording) sessions created at or after {@code since}, oldest first.
 * {@code transcript_state} is "pending" while Whisper refinement is still queued, so the
 * caller can fetch the session again later. Text is decrypted on demand and never cached.
 */
public final class TranscriptProvider extends ContentProvider {
    public static final String AUTHORITY = "br.gabriel.omitarefas.transcripts";
    static final String[] COLUMNS = {"id", "title", "created_at", "duration_ms", "status",
            "transcript_state", "text"};
    private static final String ACTIVE = "recording";

    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        if (!AUTHORITY.equals(uri.getAuthority()) || !"/sessions".equals(uri.getPath()))
            throw new IllegalArgumentException("Unknown transcript URI");
        long since = 0;
        String raw = uri.getQueryParameter("since");
        if (raw != null) {
            try { since = Long.parseLong(raw); }
            catch (NumberFormatException bad) { throw new IllegalArgumentException("Bad since"); }
        }
        MatrixCursor rows = new MatrixCursor(COLUMNS);
        List<Recordings.Session> sessions;
        try { sessions = Recordings.get(getContext()).list(""); }
        catch (Exception failure) { throw new IllegalStateException("Transcripts unavailable", failure); }
        // list() is newest first; the companion advances its cursor oldest first.
        for (int i = sessions.size() - 1; i >= 0; i--) {
            Recordings.Session s = sessions.get(i);
            if (s.createdAt < since || ACTIVE.equals(s.status)) continue;
            rows.addRow(new Object[]{s.id, s.title, s.createdAt, s.durationMs, s.status,
                    s.transcriptState, s.text});
        }
        return rows;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
