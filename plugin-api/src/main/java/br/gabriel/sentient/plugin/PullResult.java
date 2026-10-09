package br.gabriel.sentient.plugin;

import java.util.Collections;
import java.util.List;

/**
 * One page of items. {@code hasMore} asks the host to pull again with {@code nextCursor}.
 * {@code notice}, when not null, replaces the source's standing notice ("" clears it); it is
 * shown to the user, so it must never contain content from the source.
 */
public final class PullResult {
    public final List<RawItem> items;
    public final String nextCursor;
    public final boolean hasMore;
    public final String notice;

    public PullResult(List<RawItem> items, String nextCursor, boolean hasMore) {
        this(items, nextCursor, hasMore, null);
    }

    public PullResult(List<RawItem> items, String nextCursor, boolean hasMore, String notice) {
        this.items = Collections.unmodifiableList(items);
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
        this.notice = notice;
    }
}
