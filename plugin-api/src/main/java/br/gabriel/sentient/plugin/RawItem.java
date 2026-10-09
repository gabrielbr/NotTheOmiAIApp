package br.gabriel.sentient.plugin;

/**
 * The normalized unit every plugin produces: a message, email, transcript, event, doc or task.
 * (source, externalId) is unique, so pulling the same item again updates it in place.
 */
public final class RawItem {
    public static final String MESSAGE = "message", EMAIL = "email", TRANSCRIPT = "transcript",
            EVENT = "event", DOC = "doc", TASK = "task";

    public final String source, externalId, kind, text, rawJson;
    public final long timestamp;
    public final String conversationExternalId, conversationTitle, conversationKind;
    public final String authorHandle, authorDisplayName;
    public final boolean fromMe;

    private RawItem(Builder b) {
        source = require(b.source, "source");
        externalId = require(b.externalId, "externalId");
        kind = require(b.kind, "kind");
        if (b.timestamp <= 0) throw new IllegalArgumentException("timestamp is required");
        timestamp = b.timestamp;
        text = b.text == null ? "" : b.text;
        rawJson = b.rawJson;
        conversationExternalId = b.conversationExternalId;
        conversationTitle = b.conversationTitle;
        conversationKind = b.conversationKind;
        authorHandle = b.authorHandle;
        authorDisplayName = b.authorDisplayName;
        fromMe = b.fromMe;
    }

    private static String require(String value, String name) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    public static Builder builder(String source, String externalId) {
        Builder b = new Builder();
        b.source = source;
        b.externalId = externalId;
        return b;
    }

    public static final class Builder {
        private String source, externalId, kind, text, rawJson;
        private long timestamp;
        private String conversationExternalId, conversationTitle, conversationKind;
        private String authorHandle, authorDisplayName;
        private boolean fromMe;

        private Builder() {}

        public Builder kind(String value) { kind = value; return this; }
        public Builder timestamp(long millis) { timestamp = millis; return this; }
        public Builder text(String value) { text = value; return this; }
        public Builder rawJson(String value) { rawJson = value; return this; }
        /** kind: "dm", "group", "meeting", "thread" or "mailbox". */
        public Builder conversation(String externalId, String title, String kind) {
            conversationExternalId = externalId;
            conversationTitle = title;
            conversationKind = kind;
            return this;
        }
        public Builder author(String handle, String displayName, boolean me) {
            authorHandle = handle;
            authorDisplayName = displayName;
            fromMe = me;
            return this;
        }
        public RawItem build() { return new RawItem(this); }
    }
}
