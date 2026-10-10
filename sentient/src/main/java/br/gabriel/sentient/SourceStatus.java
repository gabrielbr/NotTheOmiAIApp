package br.gabriel.sentient;

import android.text.format.DateUtils;

import java.util.Locale;

/** What a source's state means to the user: whether it needs attention, why, and a short status. */
final class SourceStatus {
    private static final String OK = "OK";

    private SourceStatus() {}

    static boolean live(Sources.State s) { return ChatMessages.App.forId(s.pluginId) != null; }

    /** A chat app's state is live: access can be granted or revoked between syncs. */
    static boolean needsAccess(Sources.State s, boolean accessGranted) { return live(s) && !accessGranted; }

    static boolean problem(Sources.State s, boolean accessGranted) {
        if (live(s)) return !accessGranted || s.notice != null;
        return s.lastStatus != null && !s.lastStatus.startsWith(OK);
    }

    /** The problem in one sentence, or null when there's none. */
    static String reason(Sources.State s, boolean accessGranted) {
        if (!problem(s, accessGranted)) return null;
        ChatMessages.App app = ChatMessages.App.forId(s.pluginId);
        if (app != null) return !accessGranted ? ChatPlugin.needsAccess(app) + "." : s.notice + ".";
        return problemText(s.lastStatus);
    }

    /** "Unavailable · <reason>" carries a user-facing reason; other failures only a class name. */
    static String problemText(String status) {
        String unavailable = "Unavailable · ";
        if (status.startsWith(unavailable)) return status.substring(unavailable.length()) + ".";
        return "The last sync failed. Sync now to try again.";
    }

    /** A few words for a list row: "318 messages", "Needs access". */
    static String summary(Sources.State s, boolean accessGranted) {
        if (needsAccess(s, accessGranted)) return "Needs access";
        if (problem(s, accessGranted)) return "Needs attention";
        if (live(s)) return s.itemCount == 0 ? "No messages yet" : count(s.itemCount, "message");
        return s.lastSyncAt == null ? "Not synced yet" : count(s.itemCount, noun(s));
    }

    /** "318 messages · last one 5 minutes ago"; "142 recordings · Synced 2 hours ago". */
    static String meta(Sources.State s) {
        if (live(s)) return s.lastItemAt == null ? "No messages yet"
                : count(s.itemCount, "message") + " · last one " + ago(s.lastItemAt);
        return count(s.itemCount, noun(s)) + " · " + (s.lastSyncAt == null ? "Not synced yet" : "Synced " + ago(s.lastSyncAt));
    }

    /** What the source keeps, and what it can't see. */
    static String saves(String pluginId) {
        ChatMessages.App app = ChatMessages.App.forId(pluginId);
        if (MatrixPlugin.ID.equals(pluginId))
            return "Messages in your unencrypted rooms, read from your homeserver once a day (the last 30 days at first).";
        ComposioToolkit toolkit = ComposioToolkit.forSource(pluginId);
        if (toolkit != null)
            return "Your " + noun(pluginId) + "s, read through Composio once a day (the last 30 days at first). Read-only.";
        if (app == null) return "Your finished GVoice recordings, copied once a day. GVoice must be installed on this phone.";
        String text = "Messages you receive from now on, and the replies you send from a notification. "
                + "Older history and muted chats aren't included.";
        if (app == ChatMessages.App.SIGNAL_APP) text += " Signal's notifications must show the name and message.";
        return text;
    }

    private static String noun(Sources.State s) { return noun(s.pluginId); }

    /** What a source's items are called. */
    static String noun(String pluginId) {
        if (OmiTranscripts.ID.equals(pluginId)) return "recording";
        if (MatrixPlugin.ID.equals(pluginId)) return "message";
        ComposioToolkit toolkit = ComposioToolkit.forSource(pluginId);
        if (toolkit instanceof ComposioGmail) return "email";
        if (toolkit instanceof ComposioCalendar) return "event";
        if (toolkit instanceof ComposioDrive) return "file";
        if (toolkit instanceof ComposioSlack) return "message";
        if (toolkit instanceof ComposioTodoist || toolkit instanceof ComposioTickTick) return "task";
        return "item";
    }

    /** A synced source's standing limit (e.g. Matrix encrypted rooms); not a failure. Null for chat apps. */
    static String info(Sources.State s) { return live(s) ? null : s.notice; }

    static String count(long n, String noun) { return n + " " + noun + (n == 1 ? "" : "s"); }

    /** "2 hours ago"; passing now explicitly keeps it relative instead of a calendar date. */
    static String ago(long time) {
        String value = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS).toString();
        // Android capitalises "Yesterday"; it reads mid-sentence here ("Synced yesterday").
        boolean english = "en".equals(Locale.getDefault().getLanguage());
        return english && !value.isEmpty() ? Character.toLowerCase(value.charAt(0)) + value.substring(1) : value;
    }
}
