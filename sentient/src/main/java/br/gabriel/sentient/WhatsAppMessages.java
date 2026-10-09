package br.gabriel.sentient;

import br.gabriel.sentient.plugin.RawItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns one WhatsApp notification (as a {@link Snapshot}) into message items. Plain Java so host
 * tests run it. WhatsApp re-posts the recent history of a chat with every new message, so each
 * message gets a content-derived id and re-posts are no-ops at ingest.
 */
public final class WhatsAppMessages {
    public static final String ID = "whatsapp";
    static final String ME = "me";
    private static final Pattern COUNT_SUFFIX = Pattern.compile("\\s*\\(\\d+ [^)]*\\)\\s*$");
    /** WhatsApp's own notices and roll-ups, in English and Portuguese. Never stored. */
    private static final Pattern NOISE = Pattern.compile(
            "^\\d+ (new messages|novas mensagens|mensagens novas)$"
            + "|^\\d+ messages from \\d+ chats$|^\\d+ mensagens de \\d+ conversas$"
            + "|^whatsapp web is currently active$|^o whatsapp web está ativo.*$"
            + "|^checking for new messages$|^verificando novas mensagens$|^buscando novas mensagens$"
            + "|^backup in progress.*$|^backup em andamento.*$",
            Pattern.CASE_INSENSITIVE);

    private WhatsAppMessages() {}

    /** One message inside a notification. {@code sender} is null for your own replies. */
    public static final class Message {
        public final String sender, text;
        public final long time;
        public Message(String sender, String text, long time) { this.sender = sender; this.text = text; this.time = time; }
    }

    /** What GMind reads from one notification; built by WhatsAppListenerService. */
    public static final class Snapshot {
        public final String packageName, conversationTitle, shortcutId, selfName, category;
        public final boolean group, groupSummary;
        public final long postTime;
        public final List<Message> messages;
        public Snapshot(String packageName, long postTime, String conversationTitle, String shortcutId, boolean group,
                        boolean groupSummary, String selfName, String category, List<Message> messages) {
            this.packageName = packageName; this.postTime = postTime; this.conversationTitle = conversationTitle;
            this.shortcutId = shortcutId; this.group = group; this.groupSummary = groupSummary;
            this.selfName = selfName; this.category = category; this.messages = messages;
        }
    }

    public static List<RawItem> parse(Snapshot s) {
        List<RawItem> items = new ArrayList<>();
        if (s.groupSummary || "call".equals(s.category) || s.messages == null) return items;
        String title = cleanTitle(s.conversationTitle);
        if (title.isEmpty() || NOISE.matcher(title).matches()) return items;
        String chat = s.shortcutId != null && !s.shortcutId.isEmpty() ? s.shortcutId : "title:" + title;
        for (Message m : s.messages) {
            String text = m.text == null ? "" : m.text.trim();
            if (text.isEmpty() || NOISE.matcher(text).matches()) continue;
            boolean me = m.sender == null || m.sender.trim().isEmpty()
                    || (s.selfName != null && s.selfName.equals(m.sender));
            String sender = me ? null : m.sender.trim();
            String handle = me ? ME : "name:" + sender;
            long time = m.time > 0 ? m.time : s.postTime;
            items.add(RawItem.builder(ID, id(chat, me ? ME : sender, time, text))
                    .kind(RawItem.MESSAGE)
                    .timestamp(time)
                    .text(text)
                    .conversation(chat, title, s.group ? "group" : "dm")
                    .author(handle, me ? null : sender, me)
                    .build());
        }
        return items;
    }

    /** "Family (3 messages)" → "Family". */
    static String cleanTitle(String title) {
        if (title == null) return "";
        return COUNT_SUFFIX.matcher(title.trim()).replaceFirst("").trim();
    }

    static String id(String chat, String sender, long time, String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[]{chat, sender, Long.toString(time), text}) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            StringBuilder hex = new StringBuilder();
            byte[] hash = digest.digest();
            for (int i = 0; i < 16; i++) hex.append(String.format(Locale.ROOT, "%02x", hash[i] & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
