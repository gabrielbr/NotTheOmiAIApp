package br.gabriel.sentient;

import br.gabriel.sentient.plugin.RawItem;

import java.util.List;

/**
 * Maps Omi Tarefas transcript rows to items and computes the next cursor. Plain Java; the
 * Android plugin (OmiTranscriptsPlugin) only reads the provider.
 */
public final class OmiTranscripts {
    public static final String ID = "omi.transcripts";
    static final String PENDING = "pending";

    private OmiTranscripts() {}

    public static final class Row {
        public final String id, title, status, transcriptState, text;
        public final long createdAt, durationMs;
        public Row(String id, String title, long createdAt, long durationMs, String status,
                   String transcriptState, String text) {
            this.id = id; this.title = title; this.createdAt = createdAt; this.durationMs = durationMs;
            this.status = status; this.transcriptState = transcriptState; this.text = text;
        }
    }

    /** Null for a recording with no words (audio only); nothing to search there. */
    public static RawItem toItem(Row row) {
        if (row.text == null || row.text.trim().isEmpty()) return null;
        return RawItem.builder(ID, row.id)
                .kind(RawItem.TRANSCRIPT)
                .timestamp(row.createdAt)
                .text(row.text)
                .conversation(row.id, row.title, "meeting")
                .build();
    }

    /**
     * The provider returns sessions created at or after the cursor. Rewind to the oldest
     * session still waiting for Whisper so its refined text replaces the draft next time;
     * otherwise continue from the newest. The newest is fetched again, harmlessly.
     */
    public static String nextCursor(List<Row> rows, String previous) {
        if (rows.isEmpty()) return previous;
        long oldestPending = Long.MAX_VALUE, newest = 0;
        for (Row row : rows) {
            if (PENDING.equals(row.transcriptState)) oldestPending = Math.min(oldestPending, row.createdAt);
            newest = Math.max(newest, row.createdAt);
        }
        return Long.toString(oldestPending != Long.MAX_VALUE ? oldestPending : newest);
    }
}
