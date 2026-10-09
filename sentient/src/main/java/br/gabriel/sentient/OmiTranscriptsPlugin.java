package br.gabriel.sentient;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import br.gabriel.sentient.plugin.Mode;
import br.gabriel.sentient.plugin.PluginContext;
import br.gabriel.sentient.plugin.PullResult;
import br.gabriel.sentient.plugin.RawItem;
import br.gabriel.sentient.plugin.SourcePlugin;
import br.gabriel.sentient.plugin.SourceUnavailableException;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Pulls saved recordings' transcripts from Omi Tarefas' signature-protected provider. */
final class OmiTranscriptsPlugin implements SourcePlugin {
    static final Uri SESSIONS = Uri.parse("content://br.gabriel.omitarefas.transcripts/sessions");
    private final Context context;

    OmiTranscriptsPlugin(Context context) { this.context = context.getApplicationContext(); }

    @Override public String id() { return OmiTranscripts.ID; }
    @Override public String displayName() { return "GVoice recordings"; }
    @Override public Set<Mode> modes() { return EnumSet.of(Mode.PULL); }

    @Override public PullResult pull(PluginContext ctx, String cursor) throws Exception {
        Uri uri = cursor == null ? SESSIONS : SESSIONS.buildUpon().appendQueryParameter("since", cursor).build();
        List<OmiTranscripts.Row> rows = new ArrayList<>();
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c == null) throw new SourceUnavailableException("Install GVoice to sync recordings");
            while (c.moveToNext()) {
                rows.add(new OmiTranscripts.Row(
                        c.getString(c.getColumnIndexOrThrow("id")),
                        c.getString(c.getColumnIndexOrThrow("title")),
                        c.getLong(c.getColumnIndexOrThrow("created_at")),
                        c.getLong(c.getColumnIndexOrThrow("duration_ms")),
                        c.getString(c.getColumnIndexOrThrow("status")),
                        c.getString(c.getColumnIndexOrThrow("transcript_state")),
                        c.getString(c.getColumnIndexOrThrow("text"))));
            }
        } catch (SecurityException denied) {
            throw new SourceUnavailableException("No access: reinstall GMind after GVoice, from the same release");
        }
        List<RawItem> items = new ArrayList<>();
        for (OmiTranscripts.Row row : rows) {
            RawItem item = OmiTranscripts.toItem(row);
            if (item != null) items.add(item);
        }
        return new PullResult(items, OmiTranscripts.nextCursor(rows, cursor), false);
    }
}
