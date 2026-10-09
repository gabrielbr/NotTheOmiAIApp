package br.gabriel.sentient.plugin;

import java.util.Collections;
import java.util.List;

/** One page of items. {@code hasMore} asks the host to pull again with {@code nextCursor}. */
public final class PullResult {
    public final List<RawItem> items;
    public final String nextCursor;
    public final boolean hasMore;

    public PullResult(List<RawItem> items, String nextCursor, boolean hasMore) {
        this.items = Collections.unmodifiableList(items);
        this.nextCursor = nextCursor;
        this.hasMore = hasMore;
    }
}
