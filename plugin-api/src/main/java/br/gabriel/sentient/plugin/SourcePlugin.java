package br.gabriel.sentient.plugin;

import java.util.Set;

/**
 * One content source (Omi transcripts, WhatsApp notifications, a Composio toolkit, Matrix…).
 * Read-only by design: a plugin only returns items. There is no send or act method.
 * Register a new plugin with one line in Sentient's PluginRegistry.
 */
public interface SourcePlugin {
    /** Stable id, also used as RawItem.source, e.g. "omi.transcripts" or "composio.gmail". */
    String id();

    String displayName();

    Set<Mode> modes();

    /**
     * Returns items after {@code cursor} (null on the first sync) and the next cursor.
     * The caller stores the next cursor only after the items are committed, so a plugin
     * may return items it already returned before: ingest is idempotent per externalId.
     */
    PullResult pull(PluginContext context, String cursor) throws Exception;
}
