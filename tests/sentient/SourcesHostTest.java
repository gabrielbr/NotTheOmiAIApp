package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Http;
import br.gabriel.sentient.plugin.Json;
import br.gabriel.sentient.plugin.PullResult;
import br.gabriel.sentient.plugin.RawItem;
import br.gabriel.sentient.plugin.SourcePlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.DriverManager;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/** Phase 3 sources (Composio toolkits, Matrix, Telegram) against recorded-shape fixtures, with a fake network. */
public final class SourcesHostTest {
    private static int checks;
    static final long NOW = 1_791_547_200_000L; // 2026-10-09T12:00:00Z
    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    static Path fixtures;

    public static void main(String[] args) throws Exception {
        fixtures = Paths.get(args[0]);
        json();
        composioRefusesWriteTools();
        composioClient();
        gmail();
        calendar();
        drive();
        composioFailures();
        slack();
        todoist();
        tickTick();
        matrix();
        matrixFailuresAndNames();
        telegram();
        forget();
        tasksAndNotices();
        System.out.println("PASS_SOURCES_HOST_CHECKS " + checks);
    }

    // ---- fake network -----------------------------------------------------------------------

    static final class Request {
        final String method, url, body;
        final Map<String, String> headers;
        Request(String method, String url, Map<String, String> headers, String body) {
            this.method = method; this.url = url; this.headers = headers; this.body = body;
        }
    }

    /** Answers requests in order from a script of (method, URL fragment, status, body or IOException). */
    static final class FakeHttp implements Http {
        final List<Request> requests = new ArrayList<>();
        private final Deque<Object[]> script = new ArrayDeque<>();
        FakeHttp on(String method, String urlPart, int status, String body) {
            script.add(new Object[]{method, urlPart, status, body});
            return this;
        }
        FakeHttp offline(String method, String urlPart) {
            script.add(new Object[]{method, urlPart, -1, null});
            return this;
        }
        @Override public Response send(String method, String url, Map<String, String> headers, String body) throws IOException {
            requests.add(new Request(method, url, headers, body));
            Object[] next = script.poll();
            if (next == null) throw new AssertionError("Unexpected request " + method + " " + url);
            if (!next[0].equals(method) || !url.contains((String) next[1]))
                throw new AssertionError("Expected " + next[0] + " " + next[1] + " but got " + method + " " + url);
            if ((int) next[2] < 0) throw new IOException("offline");
            return new Response((int) next[2], (String) next[3]);
        }
        boolean done() { return script.isEmpty(); }
    }

    static String fixture(String name) throws IOException {
        return new String(Files.readAllBytes(fixtures.resolve(name)), StandardCharsets.UTF_8);
    }

    static Db fresh() throws Exception {
        Db db = new JdbcDb(DriverManager.getConnection("jdbc:sqlite::memory:"));
        Schema.migrate(db);
        return db;
    }

    static List<SyncRunner.Outcome> sync(Db db, SourcePlugin plugin, FakeHttp http, String composioKey, String matrix, long now)
            throws Exception {
        return SyncRunner.run(db, Collections.singletonList(plugin), () -> false, () -> now,
                name -> "composio".equals(name) ? composioKey : "matrix".equals(name) ? matrix : null, http);
    }

    static Map<String, Object> args(Request execute) {
        return Json.obj(Json.at(Json.parse(execute.body), "arguments"));
    }

    static Object[] row(Db db, String sql, Object... args) throws Exception {
        List<Object[]> rows = db.query(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    static long count(Db db, String sql, Object... args) throws Exception {
        return ((Number) db.query(sql, args).get(0)[0]).longValue();
    }

    // ---- JSON ---------------------------------------------------------------------------------

    private static void json() {
        Object parsed = Json.parse("{\"a\":[1,2.5,\"x\\u00e7\\n\",true,null],\"b\":{\"c\":-3e2}}");
        check(Json.num(Json.at(parsed, "a", "0")) == 1L, "json integer");
        check(Json.at(parsed, "a", "1").equals(2.5), "json double");
        check("xç\n".equals(Json.str(Json.at(parsed, "a", "2"))), "json escapes");
        check(Json.bool(Json.at(parsed, "a", "3")) && Json.at(parsed, "a", "4") == null, "json literals");
        check(Json.num(Json.at(parsed, "b", "c")) == -300L, "json exponent");
        check(Json.at(parsed, "missing", "x") == null && Json.list(Json.at(parsed, "b")).isEmpty(), "json tolerant navigation");
        String written = Json.write(Json.object("q", "a\"b\\c\u0001", "n", 3L, "skip", null, "l", Arrays.asList(1L, "z")));
        check(written.equals("{\"q\":\"a\\\"b\\\\c\\u0001\",\"n\":3,\"l\":[1,\"z\"]}"), "json writer escapes and drops nulls");
        check(Json.parse(written) instanceof Map, "json round-trip");
        for (String bad : new String[]{"", "{", "[1,]", "{\"a\" 1}", "tru", "\"unterminated", "{\"a\":1} x", "01x"}) {
            try { Json.parse(bad); check(false, "malformed json rejected: " + bad); }
            catch (IllegalArgumentException expected) { check(!expected.getMessage().contains(bad) || bad.isEmpty(), "json error hides input"); }
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 500; i++) deep.append('[');
        try { Json.parse(deep.toString()); check(false, "deep json rejected"); }
        catch (IllegalArgumentException expected) { check(true, "deep json rejected"); }
    }

    // ---- Composio -----------------------------------------------------------------------------

    private static void composioRefusesWriteTools() throws Exception {
        FakeHttp http = new FakeHttp();
        ComposioClient client = new ComposioClient(http, "key", "https://composio.test");
        for (String write : new String[]{"GMAIL_SEND_EMAIL", "GMAIL_DELETE_MESSAGE", "GOOGLECALENDAR_CREATE_EVENT",
                "GOOGLEDRIVE_DELETE_FILE", "SLACK_SENDS_A_MESSAGE_TO_A_SLACK_CHANNEL", "TODOIST_CREATE_TASK", "ANYTHING"}) {
            try { client.execute(write, "u", "ca", Collections.emptyMap()); check(false, "write tool refused: " + write); }
            catch (IllegalArgumentException expected) { check(true, "write tool refused: " + write); }
        }
        check(http.requests.isEmpty(), "refused tools never reach the network");
        for (String tool : ComposioClient.READ_TOOLS)
            check(!tool.matches(".*(SEND|CREATE|DELETE|UPDATE|MODIFY|MOVE|TRASH|REPLY|PATCH|INSERT|ADD|REMOVE|UPLOAD|SHARE).*"),
                    "allow-list holds reads only: " + tool);
        check(ComposioClient.READ_TOOLS.containsAll(Arrays.asList(ComposioGmail.FETCH, ComposioCalendar.LIST,
                ComposioDrive.FIND, ComposioDrive.EXPORT)), "allow-list covers the mappers' tools");
    }

    private static void composioClient() throws Exception {
        FakeHttp http = new FakeHttp()
                .on("GET", "/auth_configs?toolkit_slug=gmail", 200, fixture("composio/auth-configs.json"))
                .on("GET", "/auth_configs?toolkit_slug=gmail", 200, fixture("composio/auth-configs-empty.json"))
                .on("POST", "/auth_configs", 201, fixture("composio/auth-config-created.json"))
                .on("POST", "/connected_accounts/link", 201, fixture("composio/link.json"))
                .on("GET", "/connected_accounts/ca_1", 200, "{\"id\":\"ca_1\",\"status\":\"ACTIVE\"}")
                .on("GET", "/connected_accounts/ca_gone", 404, "{\"error\":{\"message\":\"x\"}}")
                .on("DELETE", "/connected_accounts/ca_1", 200, "{}");
        ComposioClient client = new ComposioClient(http, "ck_secret", "https://composio.test/api/v3.1");
        check("ac_managed".equals(client.authConfig("gmail")), "reuses a Composio-managed auth config");
        check("ac_new".equals(client.authConfig("gmail")), "creates one when none exists");
        Object created = Json.parse(http.requests.get(2).body);
        check("use_composio_managed_auth".equals(Json.str(Json.at(created, "auth_config", "type")))
                && "gmail".equals(Json.str(Json.at(created, "toolkit", "slug"))), "auth config request body");
        ComposioClient.Link link = client.link("ac_new", "gmind-123");
        check(link.redirectUrl.startsWith("https://connect.composio.dev/") && "ca_1".equals(link.connectedAccountId), "connect link");
        check("gmind-123".equals(Json.str(Json.at(Json.parse(http.requests.get(3).body), "user_id"))), "link carries the install's user id");
        check("ACTIVE".equals(client.status("ca_1")), "account status");
        check(client.status("ca_gone") == null, "deleted account reads as gone");
        client.disconnect("ca_1");
        check(http.done(), "every scripted call made");
        check(ComposioClient.looksLikeKey("ak_AbC123-xyz.7890QW") && !ComposioClient.looksLikeKey("my key with spaces in it")
                && !ComposioClient.looksLikeKey("short") && !ComposioClient.looksLikeKey(null), "pasted key shape");
        SourcesHostTest.FakeHttp verify = new SourcesHostTest.FakeHttp()
                .on("GET", "/connected_accounts?limit=1", 200, "{\"items\":[]}")
                .on("GET", "/connected_accounts?limit=1", 401, "{\"error\":{\"message\":\"PRIVATE\"}}");
        new ComposioClient(verify, "ak_good", "https://composio.test").verify();
        try { new ComposioClient(verify, "ak_bad", "https://composio.test").verify(); check(false, "bad key refused"); }
        catch (ComposioClient.ComposioException e) { check(e.status == 401 && !e.getMessage().contains("PRIVATE"), "bad key refused on check"); }
        boolean keyed = true;
        for (Request r : http.requests) keyed &= "ck_secret".equals(r.headers.get("x-api-key")) && r.url.startsWith("https://");
        check(keyed, "every call is HTTPS with the key header");
    }

    private static ComposioPlugin plugin(ComposioToolkit toolkit) {
        return new ComposioPlugin(toolkit, "gmind-u", "ca_" + toolkit.slug(), "https://composio.test", ZONE);
    }

    private static void gmail() throws Exception {
        Db db = fresh();
        FakeHttp http = new FakeHttp()
                .on("POST", "/tools/execute/GMAIL_FETCH_EMAILS", 200, fixture("composio/gmail-page1.json"))
                .on("POST", "/tools/execute/GMAIL_FETCH_EMAILS", 200, fixture("composio/gmail-page2.json"));
        List<SyncRunner.Outcome> out = sync(db, plugin(new ComposioGmail()), http, "ck", null, NOW);
        check(!out.get(0).failed && out.get(0).added == 4, "gmail: four emails (draft skipped): " + out.get(0).status);
        Map<String, Object> first = args(http.requests.get(0));
        check(("after:" + (NOW - ComposioToolkit.FIRST_SYNC_MS) / 1000).equals(first.get("query")), "gmail: first sync is the last 30 days");
        check(!first.containsKey("page_token") && "p2".equals(args(http.requests.get(1)).get("page_token")), "gmail: pages with the token");
        Object body = Json.parse(http.requests.get(0).body);
        check("gmind-u".equals(Json.str(Json.at(body, "user_id"))) && "ca_gmail".equals(Json.str(Json.at(body, "connected_account_id"))),
                "gmail: runs as the connected account");
        Object[] m1 = row(db, "SELECT items.text, identities.display_name, identities.handle, items.from_me, conversations.title, items.kind"
                + " FROM items JOIN identities ON identities.id = items.author_identity_id"
                + " JOIN conversations ON conversations.id = items.conversation_id WHERE items.external_id = 'm1'");
        check(m1 != null && ((String) m1[0]).startsWith("Almoço domingo\n\nVamos almoçar"), "gmail: subject then body");
        check("Ana Souza".equals(m1[1]) && "email:ana@example.com".equals(m1[2]) && ((Number) m1[3]).intValue() == 0, "gmail: sender name and address");
        check("Almoço domingo".equals(m1[4]) && RawItem.EMAIL.equals(m1[5]), "gmail: thread title, email kind");
        Object[] m2 = row(db, "SELECT text, ts FROM items WHERE items.external_id = 'm2'");
        check("Report\n\nRelatório anexo".equals(m2[0]), "gmail: plain-text part decoded from the payload");
        check(((Number) m2[1]).longValue() == 1_791_460_800_000L, "gmail: internalDate time");
        Object[] m3 = row(db, "SELECT items.from_me, people.is_me, items.text FROM items JOIN identities ON identities.id = items.author_identity_id"
                + " JOIN people ON people.id = identities.person_id WHERE items.external_id = 'm3'");
        check(((Number) m3[0]).intValue() == 1 && ((Number) m3[1]).intValue() == 1, "gmail: SENT mail is the user's own");
        check(((String) m3[2]).endsWith("Sim! 13h?"), "gmail: preview body when there's no full text");
        check(count(db, "SELECT COUNT(*) FROM items JOIN conversations ON conversations.id = items.conversation_id"
                + " WHERE conversations.external_id = 't1'") == 2, "gmail: a reply joins its thread");
        check(!Search.find(db, "almoco", 10).isEmpty(), "gmail: emails are searchable");
        check("Lunch".equals(ComposioGmail.threadTitle("RES: Fwd: Lunch")) && "Re:".equals(ComposioGmail.threadTitle("Re:"))
                && "Rebuild".equals(ComposioGmail.threadTitle("Rebuild")), "gmail: reply prefixes off thread titles");
        Object cursor = Json.parse((String) row(db, "SELECT cursor FROM sources WHERE plugin_id = 'composio.gmail'")[0]);
        long newest = 1_791_469_800_000L; // m3, 2026-10-08T14:30Z
        check(Json.num(Json.at(cursor, "after")) == newest / 1000 - 3600 && Json.at(cursor, "page") == null,
                "gmail: next sync starts an hour before the newest email");
        String raw = (String) row(db, "SELECT raw_json FROM items WHERE items.external_id = 'm1'")[0];
        check(!raw.contains("payload") && !raw.contains("messageText"), "gmail: raw json drops the bulky body");
        Object[] promo = row(db, "SELECT raw_json, noise, noise_reason FROM items WHERE external_id = 'm5'");
        check(((String) promo[0]).contains("\"bulk\":true") && !((String) promo[0]).contains("payload"),
                "gmail: the mailing-list header is kept as a bulk flag");
        check(((Number) promo[1]).intValue() == Relevance.RULE && "Gmail: Promotions".equals(promo[2]),
                "gmail: promotions are hidden from memory on arrival");
        check(((Number) row(db, "SELECT noise FROM items WHERE external_id = 'm1'")[0]).intValue() == 0,
                "gmail: personal mail is memory");

        FakeHttp again = new FakeHttp().on("POST", "/tools/execute/GMAIL_FETCH_EMAILS", 200, fixture("composio/gmail-page2.json"));
        List<SyncRunner.Outcome> second = sync(db, plugin(new ComposioGmail()), again, "ck", null, NOW + 1000);
        check(second.get(0).added == 0 && ("after:" + (newest / 1000 - 3600)).equals(args(again.requests.get(0)).get("query")),
                "gmail: re-reading the overlap adds nothing");
    }

    private static void calendar() throws Exception {
        Db db = fresh();
        FakeHttp http = new FakeHttp()
                .on("POST", "/tools/execute/GOOGLECALENDAR_EVENTS_LIST", 200, fixture("composio/calendar.json"))
                .on("POST", "/tools/execute/GOOGLECALENDAR_EVENTS_LIST", 200, fixture("composio/calendar-next.json"));
        List<SyncRunner.Outcome> out = sync(db, plugin(new ComposioCalendar()), http, "ck", null, NOW);
        check(out.get(0).added == 2, "calendar: two events, the bare cancellation stub skipped: " + out.get(0).status);
        Map<String, Object> first = args(http.requests.get(0));
        check(ComposioToolkit.iso(NOW - ComposioToolkit.FIRST_SYNC_MS).equals(first.get("timeMin"))
                && Boolean.TRUE.equals(first.get("singleEvents")) && "primary".equals(first.get("calendarId")), "calendar: first sync window");
        Object[] e1 = row(db, "SELECT text, ts, from_me, kind FROM items WHERE items.external_id = 'e1'");
        String text = (String) e1[0];
        check(text.startsWith("Dentista\nWhen: 2026-10-12 10:00 – 11:00\nWhere: Rua A, 10\nWith: Bob"), "calendar: title, local time, place, guests: " + text);
        check(text.endsWith("Levar exames"), "calendar: description as plain text");
        check(((Number) e1[1]).longValue() == 1_791_810_000_000L, "calendar: timestamp is the start");
        check(((Number) e1[2]).intValue() == 1 && RawItem.EVENT.equals(e1[3]), "calendar: events I organize are mine");
        Object[] e2 = row(db, "SELECT text, ts FROM items WHERE items.external_id = 'e2'");
        check(((String) e2[0]).contains("2026-10-12 (all day)"), "calendar: all-day event");
        check(((Number) e2[1]).longValue() == java.time.LocalDate.of(2026, 10, 12).atStartOfDay(ZONE).toInstant().toEpochMilli(),
                "calendar: all-day event starts at local midnight");

        sync(db, plugin(new ComposioCalendar()), http, "ck", null, NOW + 60_000);
        Map<String, Object> next = args(http.requests.get(1));
        check(!next.containsKey("timeMin") && ComposioToolkit.iso(NOW - 10 * 60 * 1000).equals(next.get("updatedMin"))
                && Boolean.TRUE.equals(next.get("showDeleted")), "calendar: later syncs ask for changes since the last one");
        check(((String) row(db, "SELECT text FROM items WHERE items.external_id = 'e1'")[0]).startsWith("Cancelled: Dentista"),
                "calendar: a cancellation updates the same event");
    }

    private static void drive() throws Exception {
        Db db = fresh();
        FakeHttp http = new FakeHttp()
                .on("POST", "/tools/execute/GOOGLEDRIVE_FIND_FILE", 200, fixture("composio/drive-find.json"))
                .on("POST", "/tools/execute/GOOGLEDRIVE_EXPORT_GOOGLE_WORKSPACE_FILE", 200, fixture("composio/drive-export-inline.json"))
                .on("POST", "/tools/execute/GOOGLEDRIVE_EXPORT_GOOGLE_WORKSPACE_FILE", 200, fixture("composio/drive-export-url.json"))
                .on("GET", "https://files.example.com/notas.txt", 200, "Comprar presente");
        List<SyncRunner.Outcome> out = sync(db, plugin(new ComposioDrive()), http, "ck", null, NOW);
        check(out.get(0).added == 3 && http.done(), "drive: two docs and a pdf, folder skipped: " + out.get(0).status);
        Map<String, Object> find = args(http.requests.get(0));
        check(("modifiedTime > '" + ComposioToolkit.iso(NOW - ComposioToolkit.FIRST_SYNC_MS) + "' and trashed = false").equals(find.get("q"))
                && "modifiedTime".equals(find.get("orderBy")), "drive: changed files, oldest first");
        Map<String, Object> export = args(http.requests.get(1));
        check("d1".equals(export.get("fileId")) && "text/plain".equals(export.get("mimeType")), "drive: docs exported as text");
        check("Plano do projeto\n\nObjetivo: lançar em novembro.".equals(row(db, "SELECT text FROM items WHERE items.external_id = 'd1'")[0]),
                "drive: doc text inline");
        check("Notas\n\nComprar presente".equals(row(db, "SELECT text FROM items WHERE items.external_id = 'd4'")[0]), "drive: doc text by download");
        check("Contrato.pdf\n(PDF)".equals(row(db, "SELECT text FROM items WHERE items.external_id = 'd2'")[0]), "drive: other files by name");
        check(((Number) row(db, "SELECT from_me FROM items WHERE items.external_id = 'd2'")[0]).intValue() == 1, "drive: my edits are mine");
        Object cursor = Json.parse((String) row(db, "SELECT cursor FROM sources WHERE plugin_id = 'composio.googledrive'")[0]);
        check("2026-10-04T09:00:00.000Z".equals(Json.str(Json.at(cursor, "after"))), "drive: next sync after the newest change");
    }

    private static void composioFailures() throws Exception {
        Db db = fresh();
        List<SyncRunner.Outcome> noKey = sync(db, plugin(new ComposioGmail()), new FakeHttp(), null, null, NOW);
        check(noKey.get(0).status.contains("Composio API key"), "composio: missing key explained");
        FakeHttp refused = new FakeHttp().on("POST", "/tools/execute/", 401, "{\"error\":{\"message\":\"PRIVATE-BODY\"}}");
        String status = sync(db, plugin(new ComposioGmail()), refused, "ck", null, NOW).get(0).status;
        check(status.startsWith("Unavailable · Composio refused the API key") && !status.contains("PRIVATE"), "composio: 401 explained, body never shown");
        String offline = sync(db, plugin(new ComposioGmail()), new FakeHttp().offline("POST", "/tools/"), "ck", null, NOW).get(0).status;
        check(offline.contains("Couldn't reach Composio"), "composio: offline explained");
        FakeHttp failed = new FakeHttp().on("POST", "/tools/execute/", 200, "{\"successful\":false,\"error\":\"PRIVATE\",\"data\":{}}");
        String unsuccessful = sync(db, plugin(new ComposioGmail()), failed, "ck", null, NOW).get(0).status;
        check(unsuccessful.contains("failed at Composio") && !unsuccessful.contains("PRIVATE"), "composio: tool failure without its text");
        check(row(db, "SELECT cursor FROM sources WHERE plugin_id = 'composio.gmail'")[0] == null, "composio: failures never advance the cursor");
        check(ComposioToolkit.state("not json") == null, "composio: a corrupt cursor starts over");
    }

    private static void slack() throws Exception {
        Db db = fresh();
        FakeHttp http = new FakeHttp()
                .on("POST", "/tools/execute/SLACK_LIST_ALL_USERS", 200, fixture("composio/slack-users.json"))
                .on("POST", "/tools/execute/SLACK_LIST_ALL_CHANNELS", 200, fixture("composio/slack-channels.json"))
                .on("POST", "/tools/execute/SLACK_RETRIEVE_A_USER_S_IDENTITY_DETAILS", 200, fixture("composio/slack-identity.json"))
                .on("POST", "/tools/execute/SLACK_FETCH_CONVERSATION_HISTORY", 200, fixture("composio/slack-history-c1.json"))
                .on("POST", "/tools/execute/SLACK_FETCH_CONVERSATION_HISTORY", 200, fixture("composio/slack-history-c1-page2.json"))
                .on("POST", "/tools/execute/SLACK_FETCH_CONVERSATION_HISTORY", 200, fixture("composio/slack-history-d1.json"))
                .on("POST", "/tools/execute/SLACK_FETCH_CONVERSATION_HISTORY", 403, "{\"error\":{\"message\":\"not_in_channel\"}}");
        List<SyncRunner.Outcome> out = sync(db, plugin(new ComposioSlack()), http, "ck", null, NOW);
        check(!out.get(0).failed && out.get(0).added == 4 && http.done(), "slack: four messages, join skipped, unreadable channel skipped: " + out.get(0).status);
        Map<String, Object> channels = args(http.requests.get(1));
        check("public_channel,private_channel,mpim,im".equals(channels.get("types")) && Boolean.TRUE.equals(channels.get("exclude_archived")),
                "slack: lists channels, DMs and group DMs");
        Map<String, Object> history = args(http.requests.get(3));
        check("C1".equals(history.get("channel")) && Long.toString((NOW - ComposioToolkit.FIRST_SYNC_MS) / 1000).equals(history.get("oldest")),
                "slack: first sync reads 30 days");
        check("h2".equals(args(http.requests.get(4)).get("cursor")) && "D1".equals(args(http.requests.get(5)).get("channel"))
                && "G1".equals(args(http.requests.get(6)).get("channel")), "slack: pages history; skips channels you're not in");
        String first = (String) row(db, "SELECT text FROM items WHERE items.external_id = 'C1:" + (NOW / 1000 - 3600) + ".000200'")[0];
        check("Oi @Bob Lima, viu o #deploy? o doc & tal".equals(first), "slack: mentions, channels, links unescaped: " + first);
        Object[] mine = row(db, "SELECT items.text, items.from_me, conversations.title, conversations.kind FROM items"
                + " JOIN conversations ON conversations.id = items.conversation_id WHERE items.external_id LIKE 'C1:%.000300'");
        check("Vi sim\n[file] plano.pdf".equals(mine[0]) && ((Number) mine[1]).intValue() == 1, "slack: your own message, files listed");
        check("#geral".equals(mine[2]) && "group".equals(mine[3]), "slack: channel title");
        Object[] dm = row(db, "SELECT conversations.title, conversations.kind, identities.display_name FROM items"
                + " JOIN conversations ON conversations.id = items.conversation_id"
                + " JOIN identities ON identities.id = items.author_identity_id WHERE items.external_id LIKE 'D1:%'");
        check("Ana".equals(dm[0]) && "dm".equals(dm[1]) && "Ana".equals(dm[2]), "slack: DM titled after the other person");
        check("@here build ok".equals(row(db, "SELECT text FROM items WHERE items.external_id LIKE 'C1:%.000100' AND text LIKE '%build%'")[0]),
                "slack: bot messages kept");
        Object cursor = Json.parse((String) row(db, "SELECT cursor FROM sources WHERE plugin_id = 'composio.slack'")[0]);
        check(Json.at(cursor, "round") == null && Json.at(cursor, "pending") == null && "UME".equals(Json.str(Json.at(cursor, "me"))),
                "slack: round state dropped once every channel is read");
        check((NOW / 1000 - 1800 + ".000300").equals(Json.str(Json.at(cursor, "latest", "C1"))), "slack: next sync starts after the newest message");
    }

    private static void todoist() throws Exception {
        Db db = fresh();
        FakeHttp http = new FakeHttp()
                .on("POST", "/tools/execute/TODOIST_GET_ALL_PROJECTS", 200, fixture("composio/todoist-projects.json"))
                .on("POST", "/tools/execute/TODOIST_GET_ALL_TASKS", 200, fixture("composio/todoist-tasks.json"))
                .on("POST", "/tools/execute/TODOIST_GET_ALL_TASKS", 200, fixture("composio/todoist-tasks-page2.json"))
                .on("POST", "/tools/execute/TODOIST_GET_COMPLETED_TASKS_BY_COMPLETION_DATE", 200, fixture("composio/todoist-completed.json"));
        List<SyncRunner.Outcome> out = sync(db, plugin(new ComposioTodoist()), http, "ck", null, NOW);
        check(out.get(0).added == 3 && http.done(), "todoist: two open tasks over two pages, one completed: " + out.get(0).status);
        check("c2".equals(args(http.requests.get(2)).get("cursor")), "todoist: pages with the cursor");
        Map<String, Object> completed = args(http.requests.get(3));
        check(ComposioToolkit.iso(NOW - ComposioToolkit.FIRST_SYNC_MS).equals(completed.get("since"))
                && ComposioToolkit.iso(NOW).equals(completed.get("until")), "todoist: completed in the last 30 days");
        Object[] t1 = row(db, "SELECT items.text, items.kind, items.from_me, conversations.title FROM items"
                + " JOIN conversations ON conversations.id = items.conversation_id WHERE items.external_id = 'T1'");
        check("Pagar luz\nDue: amanhã\nProject: Casa\nLabels: contas\n\nBoleto no email".equals(t1[0]), "todoist: task text: " + t1[0]);
        check(RawItem.TASK.equals(t1[1]) && ((Number) t1[2]).intValue() == 1 && "Casa".equals(t1[3]), "todoist: your task in its project");
        check("Done: Marcar dentista\nProject: Casa".equals(row(db, "SELECT text FROM items WHERE items.external_id = 'T3'")[0]), "todoist: completed task");

        FakeHttp next = new FakeHttp()
                .on("POST", "/tools/execute/TODOIST_GET_ALL_PROJECTS", 200, fixture("composio/todoist-projects.json"))
                .on("POST", "/tools/execute/TODOIST_GET_ALL_TASKS", 200, fixture("composio/todoist-tasks-page2.json"))
                .on("POST", "/tools/execute/TODOIST_GET_COMPLETED_TASKS_BY_COMPLETION_DATE", 200, fixture("composio/todoist-completed-t1.json"));
        List<SyncRunner.Outcome> second = sync(db, plugin(new ComposioTodoist()), next, "ck", null, NOW + 3_600_000);
        check(ComposioToolkit.iso(NOW - 3_600_000).equals(args(next.requests.get(2)).get("since")), "todoist: later syncs from the last one");
        check(second.get(0).updated == 1 && ((String) row(db, "SELECT text FROM items WHERE items.external_id = 'T1'")[0]).startsWith("Done: Pagar luz"),
                "todoist: completing a task updates it in place");
        check(count(db, "SELECT COUNT(*) FROM items") == 3, "todoist: no duplicates");
    }

    private static void tickTick() throws Exception {
        Db db = fresh();
        FakeHttp http = new FakeHttp()
                .on("POST", "/tools/execute/TICKTICK_GET_USER_PROJECT", 200, fixture("composio/ticktick-projects.json"))
                .on("POST", "/tools/execute/TICKTICK_LIST_ALL_TASKS", 200, fixture("composio/ticktick-tasks.json"));
        List<SyncRunner.Outcome> out = sync(db, plugin(new ComposioTickTick()), http, "ck", null, NOW);
        check(out.get(0).added == 2, "ticktick: two open tasks: " + out.get(0).status);
        Object[] k1 = row(db, "SELECT text, ts FROM items WHERE items.external_id = 'k1'");
        check("Reservar hotel\nDue: 2026-10-20\nProject: Viagem\n\nPerto da praia\n- [x] Comparar preços\n- [ ] Pagar sinal".equals(k1[0]),
                "ticktick: task with due date, project, checklist: " + k1[0]);
        check(((Number) k1[1]).longValue() == 1_791_374_400_000L, "ticktick: +0000 offsets parsed");
        check(((String) row(db, "SELECT text FROM items WHERE items.external_id = 'k2'")[0]).contains("Project: Inbox"), "ticktick: inbox named");
    }

    // ---- Matrix -------------------------------------------------------------------------------

    static final String SESSION = new MatrixClient.Session("https://matrix-client.example.org", "@me:example.org", "syt_token").toJson();

    private static void matrix() throws Exception {
        Db db = fresh();
        FakeHttp http = new FakeHttp()
                .on("GET", "/_matrix/client/v3/sync?timeout=0&filter=", 200, fixture("matrix/sync-initial.json"))
                .on("GET", "/rooms/%21fam%3Aexample.org/messages?dir=b", 200, fixture("matrix/messages-fam.json"))
                .on("GET", "/rooms/%21dm%3Aexample.org/messages?dir=b", 200, fixture("matrix/messages-empty.json"));
        List<SyncRunner.Outcome> out = sync(db, new MatrixPlugin(), http, null, SESSION, NOW);
        check(!out.get(0).failed && out.get(0).added == 4 && http.done(), "matrix: four readable messages: " + out.get(0).status);
        check(!http.requests.get(0).url.contains("since="), "matrix: first sync has no since token");
        check(http.requests.get(1).url.contains("from=p1"), "matrix: back-fill starts at the timeline's prev_batch");
        boolean auth = true;
        for (Request r : http.requests) auth &= "Bearer syt_token".equals(r.headers.get("Authorization"));
        check(auth, "matrix: token sent as a bearer header, never in the URL");
        check(!http.requests.get(0).url.contains("syt_token"), "matrix: token not in URLs");
        check("Ótimo, obrigada!".equals(row(db, "SELECT text FROM items WHERE items.external_id = '$f1'")[0]), "matrix: reply quote stripped");
        check("[image] foto.jpg".equals(row(db, "SELECT text FROM items WHERE items.external_id = '$f2'")[0]), "matrix: media as placeholder");
        check(row(db, "SELECT id FROM items WHERE items.external_id = '$f3'") == null, "matrix: edits don't duplicate");
        check(row(db, "SELECT id FROM items WHERE items.external_id = '$old'") == null, "matrix: back-fill stops at 30 days");
        Object[] f0 = row(db, "SELECT items.from_me, conversations.title, conversations.kind FROM items"
                + " JOIN conversations ON conversations.id = items.conversation_id WHERE items.external_id = '$f0'");
        check(((Number) f0[0]).intValue() == 1 && "Família".equals(f0[1]) && "group".equals(f0[2]), "matrix: back-filled own message in a named group");
        Object[] f1 = row(db, "SELECT identities.display_name FROM items JOIN identities ON identities.id = items.author_identity_id WHERE items.external_id = '$f1'");
        check("Mãe".equals(f1[0]), "matrix: sender display name");
        Object[] d1 = row(db, "SELECT conversations.title, conversations.kind, items.from_me FROM items"
                + " JOIN conversations ON conversations.id = items.conversation_id WHERE items.external_id = '$d1'");
        check("Ana".equals(d1[0]) && "dm".equals(d1[1]) && ((Number) d1[2]).intValue() == 1, "matrix: direct chat titled after the other person");
        String notice = Sources.ensure(db, MatrixPlugin.ID).notice;
        check(notice != null && notice.startsWith("1 room is end-to-end encrypted"), "matrix: encrypted rooms noted: " + notice);
        check(count(db, "SELECT COUNT(*) FROM items WHERE text LIKE '%AAAA%'") == 0, "matrix: ciphertext never stored");

        FakeHttp next = new FakeHttp().on("GET", "since=s1", 200, fixture("matrix/sync-next.json"));
        List<SyncRunner.Outcome> second = sync(db, new MatrixPlugin(), next, null, SESSION, NOW + 60_000);
        check(second.get(0).added == 1 && next.done(), "matrix: incremental sync, no back-fill when not cut short");
        Object[] f4 = row(db, "SELECT items.text, conversations.title FROM items JOIN conversations ON conversations.id = items.conversation_id"
                + " WHERE items.external_id = '$f4'");
        check("* acena".equals(f4[0]) && "Família".equals(f4[1]), "matrix: room keeps its name when the sync omits it");
        check(notice.equals(Sources.ensure(db, MatrixPlugin.ID).notice), "matrix: notice stays until a sync says otherwise");
    }

    private static void matrixFailuresAndNames() throws Exception {
        Db db = fresh();
        check(sync(db, new MatrixPlugin(), new FakeHttp(), null, null, NOW).get(0).status.contains("Sign in to Matrix"), "matrix: signed out explained");
        FakeHttp gone = new FakeHttp().on("GET", "/sync", 401, "{\"errcode\":\"M_UNKNOWN_TOKEN\",\"error\":\"PRIVATE\"}");
        String status = sync(db, new MatrixPlugin(), gone, null, SESSION, NOW).get(0).status;
        check(status.contains("Matrix signed GMind out") && !status.contains("PRIVATE"), "matrix: revoked token explained");
        check(sync(db, new MatrixPlugin(), new FakeHttp().offline("GET", "/sync"), null, SESSION, NOW).get(0).status
                .contains("Couldn't reach the homeserver"), "matrix: offline explained");
        String plain = new MatrixClient.Session("http://insecure.example.org", "@me:x", "t").toJson();
        check(sync(db, new MatrixPlugin(), new FakeHttp(), null, plain, NOW).get(0).status.contains("Couldn't reach"),
                "matrix: plain-http homeserver never contacted");

        check("example.org".equals(MatrixClient.serverName("@ana:example.org")), "matrix: server from user id");
        check("matrix.org".equals(MatrixClient.serverName("https://matrix.org/")), "matrix: server from URL");
        try { MatrixClient.serverName("http://matrix.org"); check(false, "matrix: http refused"); }
        catch (IllegalArgumentException expected) { check(true, "matrix: http refused"); }

        FakeHttp login = new FakeHttp()
                .on("GET", "https://example.org/.well-known/matrix/client", 200, fixture("matrix/well-known.json"))
                .on("POST", "https://matrix-client.example.org/_matrix/client/v3/login", 200, fixture("matrix/login.json"))
                .on("GET", "https://other.org/.well-known/matrix/client", 404, "")
                .on("GET", "https://other.org/_matrix/client/v3/account/whoami", 200, "{\"user_id\":\"@me:other.org\"}");
        MatrixClient client = new MatrixClient(login);
        String base = client.discover("example.org");
        check("https://matrix-client.example.org".equals(base), "matrix: well-known discovery");
        MatrixClient.Session session = client.login(base, "@me:example.org", "hunter2");
        Object body = Json.parse(login.requests.get(1).body);
        check("m.login.password".equals(Json.str(Json.at(body, "type"))) && "@me:example.org".equals(Json.str(Json.at(body, "identifier", "user")))
                && MatrixClient.DEVICE_NAME.equals(Json.str(Json.at(body, "initial_device_display_name"))), "matrix: password login body");
        check("syt_token".equals(session.accessToken) && !session.toJson().contains("hunter2"), "matrix: only the token is kept");
        check(MatrixClient.Session.fromJson(session.toJson()).userId.equals("@me:example.org"), "matrix: session round-trip");
        check("https://other.org".equals(client.discover("other.org")), "matrix: server is its own homeserver without well-known");
        check("@me:other.org".equals(client.withToken("https://other.org", " tok ").userId), "matrix: pasted token checked");
        check("Bearer tok".equals(login.requests.get(3).headers.get("Authorization")), "matrix: pasted token trimmed");
        check(MatrixClient.explain(403, "M_FORBIDDEN").equals("Wrong user or password."), "matrix: wrong password explained");
    }

    // ---- Telegram, deletion, tasks ------------------------------------------------------------

    private static void telegram() {
        for (String pkg : new String[]{"org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram"}) {
            ChatMessages.Snapshot s = new ChatMessages.Snapshot(pkg, NOW, "Ana", "chat:42", false, false, "Me", "msg",
                    Collections.singletonList(new ChatMessages.Message("Ana", "Oi!", NOW)));
            List<RawItem> items = ChatMessages.parse(s);
            check(items.size() == 1 && ChatMessages.TELEGRAM.equals(items.get(0).source), "telegram: " + pkg);
        }
        check(ChatMessages.App.forId("telegram").displayName.equals("Telegram"), "telegram: named");
    }

    private static void forget() throws Exception {
        Db db = fresh();
        FakeHttp http = new FakeHttp()
                .on("POST", "/tools/execute/GMAIL_FETCH_EMAILS", 200, fixture("composio/gmail-page1.json"))
                .on("POST", "/tools/execute/GMAIL_FETCH_EMAILS", 200, fixture("composio/gmail-page2.json"));
        sync(db, plugin(new ComposioGmail()), http, "ck", null, NOW);
        Ingest.upsert(db, Collections.singletonList(RawItem.builder("whatsapp", "w1").kind(RawItem.MESSAGE).timestamp(NOW)
                .text("almoço amanhã").conversation("c", "Ana", "dm").author("name:Ana", "Ana", false).build()), NOW);
        long people = count(db, "SELECT COUNT(*) FROM people");
        int deleted = db.transaction(() -> Sources.forget(db, "composio.gmail"));
        check(deleted == 4, "forget: counts deleted items");
        check(count(db, "SELECT COUNT(*) FROM items WHERE source = 'composio.gmail'") == 0
                && count(db, "SELECT COUNT(*) FROM conversations WHERE source = 'composio.gmail'") == 0
                && count(db, "SELECT COUNT(*) FROM identities WHERE source = 'composio.gmail'") == 0, "forget: items, threads and senders gone");
        List<Search.Hit> hits = Search.find(db, "almoco", 10);
        check(hits.size() == 1 && "whatsapp".equals(hits.get(0).source), "forget: search index updated, other sources kept");
        check(count(db, "SELECT COUNT(*) FROM people") == people - 3 && count(db, "SELECT COUNT(*) FROM people WHERE is_me = 1") == 1,
                "forget: senders' people removed, Me kept");
        check(row(db, "SELECT plugin_id FROM sources WHERE plugin_id = 'composio.gmail'") == null, "forget: source state reset");
    }

    private static void tasksAndNotices() throws Exception {
        Db db = fresh();
        RawItem open = RawItem.builder("composio.todoist", "t1").kind(RawItem.TASK).timestamp(NOW).text("Pagar luz").build();
        RawItem done = RawItem.builder("composio.todoist", "t1").kind(RawItem.TASK).timestamp(NOW).text("Done: Pagar luz").build();
        Ingest.upsert(db, Collections.singletonList(open), NOW);
        Ingest.Stats stats = Ingest.upsert(db, Collections.singletonList(done), NOW);
        check(stats.updated == 1 && count(db, "SELECT COUNT(*) FROM items") == 1, "tasks: completion updates in place");

        String[] notices = {"Something to know", null, ""};
        List<String> seen = new ArrayList<>();
        for (String notice : notices) {
            SourcePlugin p = new SourcePlugin() {
                @Override public String id() { return "noticer"; }
                @Override public String displayName() { return "N"; }
                @Override public java.util.Set<br.gabriel.sentient.plugin.Mode> modes() {
                    return java.util.EnumSet.of(br.gabriel.sentient.plugin.Mode.PULL);
                }
                @Override public PullResult pull(br.gabriel.sentient.plugin.PluginContext c, String cursor) {
                    return new PullResult(Collections.emptyList(), "x", false, notice);
                }
            };
            SyncRunner.run(db, Collections.singletonList(p), () -> false, () -> NOW);
            seen.add(Sources.ensure(db, "noticer").notice);
        }
        check("Something to know".equals(seen.get(0)) && "Something to know".equals(seen.get(1)) && seen.get(2) == null,
                "notices: set, kept when null, cleared by empty");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        checks++;
    }
}
