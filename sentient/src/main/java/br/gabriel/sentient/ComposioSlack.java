package br.gabriel.sentient;

import br.gabriel.sentient.ComposioClient.ComposioException;
import br.gabriel.sentient.plugin.Json;
import br.gabriel.sentient.plugin.PullResult;
import br.gabriel.sentient.plugin.RawItem;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slack: messages in the channels, DMs and group DMs you're in. A sync round first lists your
 * conversations and the workspace's people, then reads each conversation's new messages a few
 * conversations per page, so one round's progress is committed as it goes.
 */
final class ComposioSlack extends ComposioToolkit {
    static final String CHANNELS = "SLACK_LIST_ALL_CHANNELS", USERS = "SLACK_LIST_ALL_USERS",
            HISTORY = "SLACK_FETCH_CONVERSATION_HISTORY", IDENTITY = "SLACK_RETRIEVE_A_USER_S_IDENTITY_DETAILS";
    static final int CHANNELS_PER_PAGE = 10, LIST_PAGES = 10, HISTORY_PAGES = 5, MAX_USERS = 5000, MAX_TEXT = 6000;
    /** Message subtypes that are what someone said; joins, topic changes and the like are skipped. */
    private static final Set<String> SAID = new HashSet<>(Arrays.asList("bot_message", "thread_broadcast", "file_share", "me_message"));
    private static final Pattern MENTION = Pattern.compile("<@([A-Z0-9]+)(?:\\|([^>]*))?>"),
            CHANNEL = Pattern.compile("<#[A-Z0-9]+\\|([^>]*)>"),
            LINK = Pattern.compile("<(https?://[^|>]+)(?:\\|([^>]*))?>"),
            SPECIAL = Pattern.compile("<!(here|channel|everyone)[^>]*>");

    @Override String slug() { return "slack"; }
    @Override String displayName() { return "Slack"; }
    @Override String reads() { return "Messages in the channels, DMs and group DMs you're in, from the last 30 days on."; }
    @Override Set<String> readTools() {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(CHANNELS, USERS, HISTORY, IDENTITY)));
    }

    @Override PullResult pull(Tools tools, String cursor, long now, ZoneId zone) throws Exception {
        Map<String, Object> state = state(cursor);
        Map<String, Object> latest = new LinkedHashMap<>(state == null ? Collections.emptyMap() : Json.obj(state.get("latest")));
        List<Object> pending = new ArrayList<>(state == null ? Collections.emptyList() : Json.list(state.get("pending")));
        Map<String, Object> round = state == null ? null : Json.obj(state.get("round"));

        if (pending.isEmpty() || round == null || round.isEmpty()) {
            // Start of a round: who's who, and which conversations to read.
            Map<String, Object> names = users(tools);
            Map<String, Object> channels = channels(tools, names);
            pending = new ArrayList<>(channels.keySet());
            String me = Json.str(state == null ? null : state.get("me"));
            if (me == null) me = me(tools);
            Map<String, Object> next = Json.object("latest", latest, "pending", pending,
                    "round", Json.object("names", names, "channels", channels), "me", me);
            return new PullResult(Collections.emptyList(), Json.write(next), !pending.isEmpty());
        }

        Map<String, Object> names = Json.obj(round.get("names")), channels = Json.obj(round.get("channels"));
        String me = Json.str(state.get("me"));
        List<RawItem> items = new ArrayList<>();
        int done = 0;
        while (!pending.isEmpty() && done < CHANNELS_PER_PAGE) {
            String channel = Json.str(pending.remove(0));
            done++;
            if (channel == null) continue;
            Object info = channels.get(channel);
            String since = Json.str(latest.get(channel));
            String oldest = since != null ? since : Long.toString((now - FIRST_SYNC_MS) / 1000);
            String newest = since;
            String page = null;
            for (int p = 0; p < HISTORY_PAGES; p++) {
                Map<String, Object> args = Json.object("channel", channel, "oldest", oldest, "limit", 200L, "cursor", page);
                Object data;
                try {
                    data = tools.run(HISTORY, args);
                } catch (ComposioException notReadable) {
                    break; // e.g. a channel you were removed from: skip it, keep the rest
                }
                for (Object m : array(data, "messages")) {
                    RawItem item = item(channel, info, m, names, me);
                    if (item == null) continue;
                    items.add(item);
                    String ts = Json.str(Json.at(m, "ts"));
                    if (ts != null && (newest == null || Double.parseDouble(ts) > Double.parseDouble(newest))) newest = ts;
                }
                page = nextCursor(data);
                if (page == null) break;
            }
            if (newest != null) latest.put(channel, newest);
        }
        boolean more = !pending.isEmpty();
        Map<String, Object> next = more
                ? Json.object("latest", latest, "pending", pending, "round", round, "me", me)
                : Json.object("latest", latest, "me", me);
        return new PullResult(items, Json.write(next), more);
    }

    /** User id → display name, for authors and mentions. */
    private static Map<String, Object> users(Tools tools) throws Exception {
        Map<String, Object> names = new LinkedHashMap<>();
        String page = null;
        for (int p = 0; p < LIST_PAGES && names.size() < MAX_USERS; p++) {
            Object data = tools.run(USERS, Json.object("limit", 200L, "cursor", page));
            for (Object u : array(data, "members", "users")) {
                String id = Json.firstStr(u, "id");
                String name = Json.str(Json.at(u, "profile", "display_name"));
                if (name == null) name = Json.str(Json.at(u, "profile", "real_name"));
                if (name == null) name = Json.firstStr(u, "real_name", "name");
                if (id != null && name != null) names.put(id, name);
            }
            page = nextCursor(data);
            if (page == null) break;
        }
        return names;
    }

    /** Conversations you're in: id → {title, kind}. */
    private static Map<String, Object> channels(Tools tools, Map<String, Object> names) throws Exception {
        Map<String, Object> channels = new LinkedHashMap<>();
        String page = null;
        for (int p = 0; p < LIST_PAGES; p++) {
            Object data = tools.run(CHANNELS, Json.object("types", "public_channel,private_channel,mpim,im",
                    "exclude_archived", Boolean.TRUE, "limit", 200L, "cursor", page));
            for (Object c : array(data, "channels")) {
                String id = Json.firstStr(c, "id");
                if (id == null) continue;
                boolean im = Json.bool(Json.at(c, "is_im"));
                boolean member = im || Json.bool(Json.at(c, "is_member")) || Json.bool(Json.at(c, "is_mpim"));
                if (!member || Json.bool(Json.at(c, "is_user_deleted"))) continue;
                String title;
                if (im) {
                    String other = Json.firstStr(c, "user");
                    title = other == null ? "Direct message" : Json.str(names.get(other)) != null ? Json.str(names.get(other)) : other;
                } else if (Json.bool(Json.at(c, "is_mpim"))) {
                    String purpose = Json.str(Json.at(c, "purpose", "value"));
                    title = purpose != null ? purpose.replaceFirst("^Group messaging with: ", "") : Json.firstStr(c, "name");
                } else {
                    title = "#" + Json.firstStr(c, "name");
                }
                channels.put(id, Json.object("title", title, "kind", im ? "dm" : "group"));
            }
            page = nextCursor(data);
            if (page == null) break;
        }
        return channels;
    }

    /** Your own Slack user id, when the connection's scopes allow; null otherwise. */
    private static String me(Tools tools) throws Exception {
        try {
            Object data = tools.run(IDENTITY, Collections.emptyMap());
            for (Object scope : new Object[]{data, Json.at(data, "response_data"), Json.at(data, "data")}) {
                String id = Json.str(Json.at(scope, "user", "id"));
                if (id == null) id = Json.firstStr(scope, "user_id");
                if (id != null) return id;
            }
        } catch (ComposioException notAllowed) {
            // missing identity scope: your messages just aren't marked as yours
        }
        return null;
    }

    static RawItem item(String channel, Object info, Object m, Map<String, Object> names, String me) {
        String ts = Json.firstStr(m, "ts");
        String subtype = Json.firstStr(m, "subtype");
        if (ts == null || (subtype != null && !SAID.contains(subtype))) return null;
        long time = time(ts, ZoneId.of("UTC"));
        if (time == 0) return null;
        String text = text(Json.firstStr(m, "text"), names);
        for (Object f : Json.list(Json.at(m, "files"))) {
            String name = Json.firstStr(f, "name", "title");
            if (name != null) text = (text.isEmpty() ? "" : text + "\n") + "[file] " + name;
        }
        if (text.isEmpty()) return null;
        String user = Json.firstStr(m, "user");
        String author = user != null ? Json.str(names.get(user)) : Json.firstStr(m, "username");
        String handle = user != null ? "slack:" + user : Json.firstStr(m, "bot_id") != null ? "slack-bot:" + Json.firstStr(m, "bot_id") : null;
        RawItem.Builder b = RawItem.builder("composio.slack", channel + ":" + ts)
                .kind(RawItem.MESSAGE)
                .timestamp(time)
                .text(cap(text, MAX_TEXT))
                .rawJson(Json.write(Json.object("channel", channel, "ts", ts, "thread_ts", Json.firstStr(m, "thread_ts"),
                        "user", user, "subtype", subtype)))
                .conversation(channel, Json.str(Json.at(info, "title")), Json.str(Json.at(info, "kind")));
        if (handle != null) b.author(handle, author, me != null && me.equals(user));
        return b.build();
    }

    /** Slack markup to plain text: mentions by name, channel names, link labels, basic entities. */
    static String text(String raw, Map<String, Object> names) {
        if (raw == null) return "";
        Matcher m = MENTION.matcher(raw);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String name = Json.str(names.get(m.group(1)));
            if (name == null) name = m.group(2) != null ? m.group(2) : m.group(1);
            m.appendReplacement(out, Matcher.quoteReplacement("@" + name));
        }
        m.appendTail(out);
        String s = CHANNEL.matcher(out.toString()).replaceAll("#$1");
        Matcher link = LINK.matcher(s);
        StringBuffer linked = new StringBuffer();
        while (link.find())
            link.appendReplacement(linked, Matcher.quoteReplacement(link.group(2) != null ? link.group(2) : link.group(1)));
        link.appendTail(linked);
        s = SPECIAL.matcher(linked.toString()).replaceAll("@$1");
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").trim();
    }
}
