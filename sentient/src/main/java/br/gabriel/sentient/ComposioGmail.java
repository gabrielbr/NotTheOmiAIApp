package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Json;
import br.gabriel.sentient.plugin.PullResult;
import br.gabriel.sentient.plugin.RawItem;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Gmail through GMAIL_FETCH_EMAILS: one email item per message, threads as conversations. */
final class ComposioGmail extends ComposioToolkit {
    static final String FETCH = "GMAIL_FETCH_EMAILS";
    static final int PAGE = 25, MAX_TEXT = 8000;
    /** Re-read the last hour on every sync: late-arriving mail is cheap to re-check, ingest is idempotent. */
    private static final long OVERLAP_S = 3600;

    @Override String slug() { return "gmail"; }
    @Override String displayName() { return "Gmail"; }
    @Override String reads() { return "Emails you received and sent, from the last 30 days on."; }
    @Override Set<String> readTools() { return Collections.singleton(FETCH); }

    @Override PullResult pull(Tools tools, String cursor, long now, ZoneId zone) throws Exception {
        Map<String, Object> state = state(cursor);
        long after = state != null && Json.num(state.get("after")) != null ? Json.num(state.get("after"))
                : (now - FIRST_SYNC_MS) / 1000;
        String page = state == null ? null : Json.str(state.get("page"));
        Long maxSeen = state == null ? null : Json.num(state.get("max"));
        long max = maxSeen == null ? 0 : maxSeen;

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", "after:" + after);
        args.put("max_results", (long) PAGE);
        args.put("verbose", Boolean.TRUE);
        args.put("include_payload", Boolean.TRUE);
        if (page != null) args.put("page_token", page);
        Object data = tools.run(FETCH, args);

        List<RawItem> items = new ArrayList<>();
        for (Object message : array(data, "messages")) {
            RawItem item = item(message, zone);
            if (item == null) continue;
            items.add(item);
            max = Math.max(max, item.timestamp);
        }
        String next = field(data, "nextPageToken", "next_page_token");
        if (next != null)
            return new PullResult(items, Json.write(Json.object("after", after, "page", next, "max", max)), true);
        long newAfter = max > 0 ? Math.max(after, max / 1000 - OVERLAP_S) : after;
        return new PullResult(items, Json.write(Json.object("after", newAfter)), false);
    }

    static RawItem item(Object m, ZoneId zone) {
        String id = Json.firstStr(m, "messageId", "id");
        if (id == null) return null;
        List<Object> labels = Json.list(Json.at(m, "labelIds"));
        if (labels.contains("DRAFT")) return null;
        long ts = time(Json.at(m, "messageTimestamp"), zone);
        if (ts == 0) ts = time(Json.at(m, "internalDate"), zone);
        if (ts == 0) return null;
        Object payload = Json.at(m, "payload");
        String subject = Json.firstStr(m, "subject");
        if (subject == null) subject = header(payload, "Subject");
        String from = Json.firstStr(m, "sender", "from");
        if (from == null) from = header(payload, "From");
        String body = Json.firstStr(m, "messageText");
        if (body == null) body = payloadText(payload);
        if (body == null) body = Json.str(Json.at(m, "preview", "body"));
        if (body == null) body = Json.firstStr(m, "snippet");
        boolean me = labels.contains("SENT");
        String[] who = nameAndAddress(from);
        String thread = Json.firstStr(m, "threadId");
        String title = subject == null ? "(no subject)" : subject;
        StringBuilder text = new StringBuilder(title);
        if (body != null && !body.trim().isEmpty()) text.append("\n\n").append(body.trim());

        Map<String, Object> raw = new LinkedHashMap<>(Json.obj(m));
        // Mailing-list headers decide what's worth remembering (Relevance); the rest of the payload goes.
        String precedence = header(payload, "Precedence");
        boolean listUnsubscribe = header(payload, "List-Unsubscribe") != null || header(payload, "List-Id") != null;
        if (listUnsubscribe || (precedence != null && precedence.trim().toLowerCase(java.util.Locale.ROOT).matches("bulk|list|junk")))
            raw.put("bulk", Boolean.TRUE);
        raw.remove("payload");
        raw.remove("messageText");
        raw.remove("attachmentList");
        RawItem.Builder b = RawItem.builder("composio.gmail", id)
                .kind(RawItem.EMAIL)
                .timestamp(ts)
                .text(cap(text.toString(), MAX_TEXT))
                .rawJson(Json.write(raw));
        if (thread != null) b.conversation(thread, threadTitle(title), "thread");
        if (who[1] != null) b.author("email:" + who[1], who[0], me);
        else if (me) b.author("me", null, true);
        return b.build();
    }

    private static final java.util.regex.Pattern REPLY_PREFIX =
            java.util.regex.Pattern.compile("^(?:(?:re|res|fw|fwd|enc|tr|aw|wg)\\s*(?:\\[\\d+\\])?\\s*:\\s*)+",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    /** "Re: Fwd: Lunch" → "Lunch", so replies don't rename their thread (English and Portuguese prefixes). */
    static String threadTitle(String subject) {
        String t = REPLY_PREFIX.matcher(subject.trim()).replaceFirst("").trim();
        return t.isEmpty() ? subject : t;
    }

    static String header(Object payload, String name) {
        for (Object h : Json.list(Json.at(payload, "headers")))
            if (name.equalsIgnoreCase(Json.str(Json.at(h, "name")))) return Json.str(Json.at(h, "value"));
        return null;
    }

    /** The message's text/plain part (or text/html, flattened), decoded from base64url. */
    static String payloadText(Object payload) {
        String plain = part(payload, "text/plain", 0);
        if (plain != null) return plain;
        String html = part(payload, "text/html", 0);
        return html == null ? null : plain(html);
    }

    private static String part(Object payload, String mime, int depth) {
        if (payload == null || depth > 10) return null;
        if (mime.equalsIgnoreCase(Json.str(Json.at(payload, "mimeType")))) {
            String data = Json.str(Json.at(payload, "body", "data"));
            if (data != null) {
                try {
                    return new String(Base64.getUrlDecoder().decode(data.replace('+', '-').replace('/', '_').trim()),
                            StandardCharsets.UTF_8);
                } catch (IllegalArgumentException notBase64) {
                    return null;
                }
            }
        }
        for (Object child : Json.list(Json.at(payload, "parts"))) {
            String found = part(child, mime, depth + 1);
            if (found != null) return found;
        }
        return null;
    }
}
