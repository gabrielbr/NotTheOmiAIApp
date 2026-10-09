package br.gabriel.sentient;

import br.gabriel.sentient.plugin.RawItem;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turns one chat-app notification (WhatsApp, Signal or Telegram, as a {@link Snapshot}) into message items.
 * Plain Java so host tests run it. The apps re-post the recent history of a chat with every new
 * message, so each message gets a content-derived id and re-posts are no-ops at ingest.
 */
public final class ChatMessages {
    public static final String WHATSAPP = "whatsapp", SIGNAL = "signal", TELEGRAM = "telegram";

    /** Chat apps GMind reads, by notification package. Each is its own source. */
    public enum App {
        WHATSAPP_APP(WHATSAPP, "WhatsApp", "com.whatsapp", "com.whatsapp.w4b"),
        SIGNAL_APP(SIGNAL, "Signal", "org.thoughtcrime.securesms"),
        TELEGRAM_APP(TELEGRAM, "Telegram", "org.telegram.messenger", "org.telegram.messenger.web",
                "org.thunderdog.challegram");

        public final String id, displayName;
        final String[] packages;
        App(String id, String displayName, String... packages) {
            this.id = id; this.displayName = displayName; this.packages = packages;
        }

        public static App forPackage(String packageName) {
            for (App app : values()) for (String p : app.packages) if (p.equals(packageName)) return app;
            return null;
        }

        public static App forId(String id) {
            for (App app : values()) if (app.id.equals(id)) return app;
            return null;
        }
    }
    static final String ME = "me";
    private static final Pattern COUNT_SUFFIX = Pattern.compile("\\s*\\(\\d+ [^)]*\\)\\s*$");
    /** The apps' own notices and roll-ups, in English and Portuguese. Never stored. */
    private static final Pattern NOISE = Pattern.compile(
            "^\\d+ (new messages|novas mensagens|mensagens novas)$"
            + "|^\\d+ new messages? in \\d+ chats?$|^\\d+ novas? mensage(m|ns) em \\d+ conversas?$"
            + "|^background connection enabled$|^conexão em segundo plano ativada$"
            + "|^\\d+ messages from \\d+ chats$|^\\d+ mensagens de \\d+ conversas$"
            + "|^whatsapp web is currently active$|^o whatsapp web está ativo.*$"
            + "|^checking for new messages$|^verificando novas mensagens$|^buscando novas mensagens$"
            + "|^backup in progress.*$|^backup em andamento.*$",
            Pattern.CASE_INSENSITIVE);

    private ChatMessages() {}

    /** One message inside a notification. {@code sender} is null for your own replies. */
    public static final class Message {
        public final String sender, text;
        public final long time;
        public Message(String sender, String text, long time) { this.sender = sender; this.text = text; this.time = time; }
    }

    /** What GMind reads from one notification; built by MessagesListenerService. */
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

    /** Signal's placeholder when its notifications are set to hide the sender or the message. */
    private static final Pattern HIDDEN = Pattern.compile("^(new message|nova mensagem)$", Pattern.CASE_INSENSITIVE);

    public static List<RawItem> parse(Snapshot s) {
        List<RawItem> items = new ArrayList<>();
        App app = App.forPackage(s.packageName);
        if (app == null || s.groupSummary || "call".equals(s.category) || s.messages == null) return items;
        String title = cleanTitle(s.conversationTitle);
        if (title.isEmpty() || NOISE.matcher(title).matches()) return items;
        String chat = s.shortcutId != null && !s.shortcutId.isEmpty() ? s.shortcutId : "title:" + title;
        for (Message m : s.messages) {
            String text = m.text == null ? "" : m.text.trim();
            if (text.isEmpty() || NOISE.matcher(text).matches() || HIDDEN.matcher(text).matches()) continue;
            boolean me = m.sender == null || m.sender.trim().isEmpty()
                    || (s.selfName != null && s.selfName.equals(m.sender));
            String sender = me ? null : m.sender.trim();
            String handle = me ? ME : "name:" + sender;
            long time = m.time > 0 ? m.time : s.postTime;
            items.add(RawItem.builder(app.id, id(chat, me ? ME : sender, time, text))
                    .kind(RawItem.MESSAGE)
                    .timestamp(time)
                    .text(text)
                    .conversation(chat, title, s.group ? "group" : "dm")
                    .author(handle, me ? null : sender, me)
                    .build());
        }
        return items;
    }

    /**
     * True when the app shows a notification but hides what was said (Signal's "Name only" or "No
     * name or message" setting), so GMind can tell you which setting to change.
     */
    public static boolean contentHidden(Snapshot s) {
        if (App.forPackage(s.packageName) != App.SIGNAL_APP || s.groupSummary || s.messages == null) return false;
        boolean any = false;
        for (Message m : s.messages) {
            String text = m.text == null ? "" : m.text.trim();
            if (text.isEmpty()) continue;
            if (!HIDDEN.matcher(text).matches()) return false;
            any = true;
        }
        return any;
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
