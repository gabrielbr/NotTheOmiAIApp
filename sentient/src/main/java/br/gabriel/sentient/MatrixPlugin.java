package br.gabriel.sentient;

import br.gabriel.sentient.MatrixClient.MatrixException;
import br.gabriel.sentient.MatrixClient.Session;
import br.gabriel.sentient.plugin.Json;
import br.gabriel.sentient.plugin.Mode;
import br.gabriel.sentient.plugin.PluginContext;
import br.gabriel.sentient.plugin.PullResult;
import br.gabriel.sentient.plugin.RawItem;
import br.gabriel.sentient.plugin.SourcePlugin;
import br.gabriel.sentient.plugin.SourceUnavailableException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Matrix rooms you joined, read through /sync. The first sync back-fills each room to 30 days
 * ago; later syncs take what's new, back-filling a room whose timeline was cut short. End-to-end
 * encrypted rooms can't be read yet: their messages are skipped and counted in a notice.
 * Plain Java: host tests drive it with a fake Http.
 */
final class MatrixPlugin implements SourcePlugin {
    static final String ID = "matrix", SECRET = "matrix";
    static final long FIRST_SYNC_MS = ComposioToolkit.FIRST_SYNC_MS, OVERLAP_MS = 60 * 60 * 1000;
    static final int BACKFILL_PAGES = 10, BACKFILL_LIMIT = 100;
    private static final String[] MESSAGE_TYPES = {"m.room.message", "m.room.encrypted"};
    static final String SYNC_FILTER = Json.write(Json.object(
            "presence", Json.object("not_types", list("*")),
            "account_data", Json.object("types", list("m.direct")),
            "room", Json.object(
                    "timeline", Json.object("limit", 50L, "types", list(MESSAGE_TYPES)),
                    "state", Json.object("lazy_load_members", Boolean.TRUE, "types",
                            list("m.room.name", "m.room.canonical_alias", "m.room.member", "m.room.encryption")),
                    "ephemeral", Json.object("not_types", list("*")),
                    "account_data", Json.object("not_types", list("*")))));
    static final String HISTORY_FILTER = Json.write(Json.object(
            "types", list(MESSAGE_TYPES), "lazy_load_members", Boolean.TRUE));

    @Override public String id() { return ID; }
    @Override public String displayName() { return "Matrix"; }
    @Override public Set<Mode> modes() { return EnumSet.of(Mode.PULL); }

    @Override public PullResult pull(PluginContext ctx, String cursor) throws Exception {
        Session session = Session.fromJson(ctx.secret(SECRET));
        if (session == null) throw new SourceUnavailableException("Sign in to Matrix in Connect sources");
        MatrixClient client = new MatrixClient(ctx.http());
        Map<String, Object> state = ComposioToolkit.state(cursor);
        String since = state == null ? null : Json.str(state.get("since"));
        Long lastAt = state == null ? null : Json.num(state.get("at"));
        long floor = since == null || lastAt == null ? ctx.now() - FIRST_SYNC_MS : lastAt - OVERLAP_MS;
        try {
            Object sync = client.sync(session, since, SYNC_FILTER);
            String next = Json.str(Json.at(sync, "next_batch"));
            if (next == null) throw new SourceUnavailableException("The homeserver sent an incomplete sync");
            Set<String> direct = directRooms(sync);
            List<RawItem> items = new ArrayList<>();
            int encryptedRooms = 0;
            for (Map.Entry<String, Object> room : Json.obj(Json.at(sync, "rooms", "join")).entrySet()) {
                if (ctx.cancelled()) break;
                Room r = new Room(room.getKey(), room.getValue(), session.userId, direct);
                r.read(Json.list(Json.at(room.getValue(), "timeline", "events")), floor);
                String prev = Json.str(Json.at(room.getValue(), "timeline", "prev_batch"));
                boolean cut = since == null || Json.bool(Json.at(room.getValue(), "timeline", "limited"));
                for (int page = 0; cut && prev != null && r.oldest > floor && page < BACKFILL_PAGES; page++) {
                    if (ctx.cancelled()) break;
                    Object history = client.messages(session, r.id, prev, HISTORY_FILTER, BACKFILL_LIMIT);
                    r.members(Json.list(Json.at(history, "state")));
                    List<Object> chunk = Json.list(Json.at(history, "chunk"));
                    if (chunk.isEmpty()) break;
                    r.read(chunk, floor);
                    prev = Json.str(Json.at(history, "end"));
                }
                items.addAll(r.items());
                if (r.encrypted) encryptedRooms++;
            }
            String notice = encryptedRooms == 0 ? null : encryptedRooms + (encryptedRooms == 1 ? " room is" : " rooms are")
                    + " end-to-end encrypted. GMind can't read those yet; unencrypted rooms are saved.";
            return new PullResult(items, Json.write(Json.object("since", next, "at", ctx.now())), false, notice);
        } catch (MatrixException refused) {
            throw new SourceUnavailableException(refused.getMessage());
        } catch (IOException offline) {
            throw new SourceUnavailableException("Couldn't reach the homeserver · will retry on the next sync");
        }
    }

    /** Rooms marked as direct chats in the m.direct account data (sent on the first sync and on change). */
    static Set<String> directRooms(Object sync) {
        Set<String> rooms = new HashSet<>();
        for (Object event : Json.list(Json.at(sync, "account_data", "events"))) {
            if (!"m.direct".equals(Json.str(Json.at(event, "type")))) continue;
            for (Object ids : Json.obj(Json.at(event, "content")).values())
                for (Object id : Json.list(ids)) if (id instanceof String) rooms.add((String) id);
        }
        return rooms;
    }

    /** One joined room in one sync: its name, members and the messages read so far. */
    static final class Room {
        final String id, me;
        final Map<String, String> names = new HashMap<>();
        private final Map<String, RawItem> items = new java.util.LinkedHashMap<>();
        private final Object sync;
        private final Set<String> direct;
        String title;
        boolean encrypted;
        long oldest = Long.MAX_VALUE;

        Room(String id, Object sync, String me, Set<String> direct) {
            this.id = id; this.sync = sync; this.me = me; this.direct = direct;
            List<Object> state = Json.list(Json.at(sync, "state", "events"));
            members(state);
            String name = null, alias = null;
            for (Object e : state) {
                String type = Json.str(Json.at(e, "type"));
                if ("m.room.name".equals(type)) name = Json.str(Json.at(e, "content", "name"));
                else if ("m.room.canonical_alias".equals(type)) alias = Json.str(Json.at(e, "content", "alias"));
                else if ("m.room.encryption".equals(type)) encrypted = true;
            }
            title = name != null ? name : alias;
        }

        void members(List<Object> state) {
            for (Object e : state) {
                if (!"m.room.member".equals(Json.str(Json.at(e, "type")))) continue;
                String user = Json.str(Json.at(e, "state_key")), name = Json.str(Json.at(e, "content", "displayname"));
                if (user != null && name != null) names.put(user, name);
            }
        }

        void read(List<Object> events, long floor) {
            for (Object e : events) {
                long ts = Json.num(Json.at(e, "origin_server_ts")) == null ? 0 : Json.num(Json.at(e, "origin_server_ts"));
                if (ts > 0) oldest = Math.min(oldest, ts);
                if ("m.room.encrypted".equals(Json.str(Json.at(e, "type")))) { encrypted = true; continue; }
                if (ts < floor) continue;
                RawItem item = message(e);
                if (item != null) items.put(item.externalId, item);
            }
        }

        /** Room title: its name, its alias, else the other members' names (from the sync summary). */
        String title() {
            if (title != null) return title;
            List<String> others = new ArrayList<>();
            for (Object hero : Json.list(Json.at(sync, "summary", "m.heroes"))) {
                String user = Json.str(hero);
                if (user != null && !user.equals(me)) others.add(names.containsKey(user) ? names.get(user) : user);
            }
            return others.isEmpty() ? null : String.join(", ", others);
        }

        String kind() {
            if (direct.contains(id)) return "dm";
            Long members = Json.num(Json.at(sync, "summary", "m.joined_member_count"));
            return members != null && members <= 2 ? "dm" : "group";
        }

        List<RawItem> items() {
            String t = title(), k = kind();
            List<RawItem> result = new ArrayList<>();
            for (RawItem item : items.values()) {
                RawItem.Builder b = RawItem.builder(ID, item.externalId).kind(RawItem.MESSAGE)
                        .timestamp(item.timestamp).text(item.text).rawJson(item.rawJson)
                        .conversation(id, t, k)
                        .author(item.authorHandle, names.get(item.authorHandle), item.fromMe);
                result.add(b.build());
            }
            return result;
        }

        private RawItem message(Object e) {
            if (!"m.room.message".equals(Json.str(Json.at(e, "type")))) return null;
            String eventId = Json.str(Json.at(e, "event_id")), sender = Json.str(Json.at(e, "sender"));
            Long ts = Json.num(Json.at(e, "origin_server_ts"));
            if (eventId == null || sender == null || ts == null || ts <= 0) return null;
            // Edits arrive as new events pointing at the original; the original is kept as sent.
            if ("m.replace".equals(Json.str(Json.at(e, "content", "m.relates_to", "rel_type")))) return null;
            String text = MatrixPlugin.text(Json.at(e, "content"));
            if (text == null) return null;
            return RawItem.builder(ID, eventId).kind(RawItem.MESSAGE).timestamp(ts).text(text)
                    .rawJson(Json.write(Json.object("event_id", eventId, "room_id", id, "sender", sender,
                            "msgtype", Json.str(Json.at(e, "content", "msgtype")))))
                    .author(sender, null, sender.equals(me))
                    .build();
        }
    }

    /** The readable text of a message event's content; media becomes a short placeholder. */
    static String text(Object content) {
        String type = Json.str(Json.at(content, "msgtype")), body = Json.str(Json.at(content, "body"));
        if (type == null || body == null) return null; // redacted or malformed
        switch (type) {
            case "m.text": case "m.notice": return stripReplyFallback(body);
            case "m.emote": return "* " + stripReplyFallback(body);
            case "m.image": return "[image] " + body;
            case "m.video": return "[video] " + body;
            case "m.audio": return "[audio] " + body;
            case "m.file": return "[file] " + body;
            case "m.location": return "[location] " + body;
            default: return body;
        }
    }

    /** Replies quote the original as leading "> " lines, then a blank line; keep only the reply. */
    static String stripReplyFallback(String body) {
        if (!body.startsWith("> ")) return body.trim();
        String[] lines = body.split("\n", -1);
        int i = 0;
        while (i < lines.length && lines[i].startsWith(">")) i++;
        if (i < lines.length && lines[i].isEmpty()) i++;
        String rest = String.join("\n", java.util.Arrays.copyOfRange(lines, i, lines.length)).trim();
        return rest.isEmpty() ? body.trim() : rest;
    }

    private static List<Object> list(String... values) {
        return new ArrayList<>(java.util.Arrays.asList((Object[]) values));
    }
}
