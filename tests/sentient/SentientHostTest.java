package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Mode;
import br.gabriel.sentient.plugin.PluginContext;
import br.gabriel.sentient.plugin.PullResult;
import br.gabriel.sentient.plugin.RawItem;
import br.gabriel.sentient.plugin.SourcePlugin;

import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class SentientHostTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        migrations();
        ingestAndSearch();
        reingestUpdatesInPlace();
        identitiesAndConversations();
        syncCommitsPageByPage();
        syncIsolatesFailures();
        syncRejectsForeignItems();
        disabledSourceSkipped();
        omiTranscriptMapping();
        searchExpressions();
        rawItemValidation();
        whatsAppParsing();
        whatsAppIngest();
        signal();
        recentItems();
        System.out.println("PASS_SENTIENT_HOST_CHECKS " + checks);
    }

    private static Db fresh() throws Exception {
        Db db = new JdbcDb(DriverManager.getConnection("jdbc:sqlite::memory:"));
        Schema.migrate(db);
        return db;
    }

    private static void migrations() throws Exception {
        Db db = fresh();
        check(Schema.version(db) == Schema.latest(), "migrated to latest");
        Schema.migrate(db);
        check(Schema.version(db) == Schema.latest(), "re-migrate is a no-op");
        check(db.query("SELECT COUNT(*) FROM people WHERE is_me = 1").get(0)[0].equals(1L), "one Me person");
        db.exec("PRAGMA user_version = 99");
        try { Schema.migrate(db); check(false, "newer store must be refused"); }
        catch (IllegalStateException expected) { check(true, "newer store refused"); }
    }

    private static RawItem transcript(String id, long ts, String text) {
        return RawItem.builder("omi.transcripts", id).kind(RawItem.TRANSCRIPT).timestamp(ts).text(text)
                .conversation(id, "Recording " + id, "meeting").build();
    }

    private static void ingestAndSearch() throws Exception {
        Db db = fresh();
        Ingest.Stats stats = db.transaction(() -> Ingest.upsert(db, Arrays.asList(
                transcript("a", 1000, "Amanhã temos reunião com a Ana sobre o orçamento."),
                transcript("b", 2000, "Lunch with Bruno, talk about the Lisbon trip.")), 5000));
        check(stats.added == 2 && stats.updated == 0, "two items added");
        List<Search.Hit> hits = Search.find(db, "reuniao orcamento", 10);
        check(hits.size() == 1 && hits.get(0).conversation.equals("Recording a"), "accent-insensitive PT search");
        check(hits.get(0).snippet.contains(Search.MATCH_START + "reunião" + Search.MATCH_END), "snippet marks the match");
        Items.Item item = Items.get(db, hits.get(0).itemId);
        check(item != null && item.text.startsWith("Amanhã") && "Recording a".equals(item.conversation)
                && item.ts == 1000 && RawItem.TRANSCRIPT.equals(item.kind), "item loads with its conversation");
        check(Items.get(db, 9999) == null, "missing item is null");
        String marked = Items.highlighted(db, item.id, "orcamento");
        check(marked != null && marked.contains(Search.MATCH_START + "orçamento" + Search.MATCH_END)
                && marked.replace(Search.START, "").replace(Search.END, "").equals(item.text), "full text highlighted");
        check(Items.highlighted(db, item.id, "lisbon") == null, "no highlight when the item doesn't match");
        check(Items.highlighted(db, item.id, " ") == null, "no highlight for a blank query");
        check(Search.find(db, "lisb", 10).size() == 1, "prefix search");
        check(Search.find(db, "ana ( \" * -", 10).size() == 1, "FTS syntax characters are ignored");
        check(Search.find(db, "ana NOT", 10).isEmpty(), "operator words are plain words that must match");
        check(Search.find(db, "  ", 10).isEmpty(), "blank query finds nothing");
    }

    private static void reingestUpdatesInPlace() throws Exception {
        Db db = fresh();
        db.transaction(() -> Ingest.upsert(db, List.of(transcript("a", 1000, "draft words here")), 1));
        Ingest.Stats same = db.transaction(() -> Ingest.upsert(db, List.of(transcript("a", 1000, "draft words here")), 2));
        check(same.unchanged == 1 && same.added == 0 && same.updated == 0, "identical re-pull is unchanged");
        Ingest.Stats refined = db.transaction(() -> Ingest.upsert(db, List.of(transcript("a", 1000, "refined whisper text")), 3));
        check(refined.updated == 1, "changed text updates in place");
        check(db.query("SELECT COUNT(*) FROM items").get(0)[0].equals(1L), "still one row");
        check(Search.find(db, "draft", 10).isEmpty(), "old text left the index");
        check(Search.find(db, "whisper", 10).size() == 1, "new text indexed");
    }

    private static RawItem message(String id, String chat, String handle, String name, boolean me, String text) {
        return RawItem.builder("whatsapp", id).kind(RawItem.MESSAGE).timestamp(10_000 + id.hashCode() % 100)
                .conversation(chat, "Family", "group").author(handle, name, me).text(text).build();
    }

    private static void identitiesAndConversations() throws Exception {
        Db db = fresh();
        db.transaction(() -> Ingest.upsert(db, Arrays.asList(
                message("1", "fam", "+55 11 9999", "Mãe", false, "Oi filho"),
                message("2", "fam", "+55 11 9999", "Mae", false, "Vem jantar?"),
                message("3", "fam", "me", "Gabriel", true, "Vou sim")), 1));
        check(db.query("SELECT COUNT(*) FROM identities").get(0)[0].equals(2L), "one identity per handle");
        check(db.query("SELECT display_name FROM identities WHERE handle = '+55 11 9999'").get(0)[0].equals("Mae"),
                "display name follows the latest message");
        check(db.query("SELECT COUNT(*) FROM people").get(0)[0].equals(2L), "Me plus one new person");
        check(db.query("SELECT p.is_me FROM identities i JOIN people p ON p.id = i.person_id WHERE i.handle = 'me'")
                .get(0)[0].equals(1L), "own messages map to Me");
        check(db.query("SELECT COUNT(*) FROM conversations").get(0)[0].equals(1L), "one conversation");
        check(db.query("SELECT COUNT(*) FROM conversation_members").get(0)[0].equals(2L), "both members recorded");
        check(db.query("SELECT COUNT(*) FROM items WHERE from_me = 1").get(0)[0].equals(1L), "from_me stored");
    }

    /** Fake source: pages of items keyed by cursor; optionally fails on a given cursor. */
    private static final class Pages implements SourcePlugin {
        final String id;
        final List<List<RawItem>> pages;
        final int failAt;
        final List<String> seenCursors = new ArrayList<>();
        Pages(String id, List<List<RawItem>> pages, int failAt) { this.id = id; this.pages = pages; this.failAt = failAt; }
        @Override public String id() { return id; }
        @Override public String displayName() { return id; }
        @Override public Set<Mode> modes() { return EnumSet.of(Mode.PULL); }
        @Override public PullResult pull(PluginContext context, String cursor) throws Exception {
            seenCursors.add(cursor);
            int page = cursor == null ? 0 : Integer.parseInt(cursor);
            if (page == failAt) throw new java.io.IOException("secret message text must not be stored");
            if (failAt == -2) throw new br.gabriel.sentient.plugin.SourceUnavailableException("Install Omi Tarefas");
            if (page >= pages.size()) return new PullResult(Collections.emptyList(), cursor, false);
            return new PullResult(pages.get(page), Integer.toString(page + 1), page + 1 < pages.size());
        }
    }

    private static RawItem item(String source, String id) {
        return RawItem.builder(source, id).kind(RawItem.DOC).timestamp(1).text("doc " + id).build();
    }

    private static void syncCommitsPageByPage() throws Exception {
        Db db = fresh();
        Pages source = new Pages("docs", Arrays.asList(List.of(item("docs", "1")), List.of(item("docs", "2"))), -1);
        List<SyncRunner.Outcome> outcomes = SyncRunner.run(db, List.of(source), () -> false, () -> 42);
        check(outcomes.size() == 1 && !outcomes.get(0).failed && outcomes.get(0).added == 2, "both pages ingested");
        Sources.State state = Sources.ensure(db, "docs");
        check("2".equals(state.cursor) && state.itemCount == 2 && state.lastSyncAt == 42, "cursor and counts stored");
        SyncRunner.run(db, List.of(source), () -> false, () -> 43);
        check("2".equals(source.seenCursors.get(source.seenCursors.size() - 1)), "next run resumes from cursor");
    }

    private static void syncIsolatesFailures() throws Exception {
        Db db = fresh();
        Pages broken = new Pages("broken", Arrays.asList(List.of(item("broken", "1")), List.of(item("broken", "2"))), 1);
        Pages fine = new Pages("fine", List.of(List.of(item("fine", "1"))), -1);
        List<SyncRunner.Outcome> outcomes = SyncRunner.run(db, Arrays.asList(broken, fine), () -> false, () -> 7);
        check(outcomes.get(0).failed && outcomes.get(0).added == 1, "first page kept before failure");
        check("1".equals(Sources.ensure(db, "broken").cursor), "cursor stops at last committed page");
        check(Sources.ensure(db, "broken").lastStatus.equals("Failed · IOException"), "status has no message text");
        check(!outcomes.get(1).failed && outcomes.get(1).added == 1, "other plugin still synced");
        Pages missing = new Pages("missing", List.of(), -2);
        SyncRunner.run(db, List.of(missing), () -> false, () -> 8);
        check(Sources.ensure(db, "missing").lastStatus.equals("Unavailable · Install Omi Tarefas"),
                "unavailable reason shown to the user");
    }

    private static void syncRejectsForeignItems() throws Exception {
        Db db = fresh();
        Pages liar = new Pages("liar", List.of(List.of(item("someone-else", "1"))), -1);
        List<SyncRunner.Outcome> outcomes = SyncRunner.run(db, List.of(liar), () -> false, () -> 1);
        check(outcomes.get(0).failed, "foreign source rejected");
        check(db.query("SELECT COUNT(*) FROM items").get(0)[0].equals(0L), "nothing committed");
        check(Sources.ensure(db, "liar").cursor == null, "cursor not advanced");
    }

    private static void disabledSourceSkipped() throws Exception {
        Db db = fresh();
        Sources.ensure(db, "off");
        db.exec("UPDATE sources SET enabled = 0 WHERE plugin_id = 'off'");
        Pages off = new Pages("off", List.of(List.of(item("off", "1"))), -1);
        check(SyncRunner.run(db, List.of(off), () -> false, () -> 1).isEmpty() && off.seenCursors.isEmpty(),
                "disabled source never pulled");
        Pages any = new Pages("any", List.of(List.of(item("any", "1"))), -1);
        check(SyncRunner.run(db, List.of(any), () -> true, () -> 1).isEmpty(), "cancelled sync stops");
    }

    private static void omiTranscriptMapping() {
        OmiTranscripts.Row done1 = new OmiTranscripts.Row("s1", "Morning", 100, 60_000, "saved", "complete", "texto");
        OmiTranscripts.Row pending = new OmiTranscripts.Row("s2", "Lunch", 200, 60_000, "saved", "pending", "draft");
        OmiTranscripts.Row done3 = new OmiTranscripts.Row("s3", "Evening", 300, 60_000, "saved", "none", "words");
        OmiTranscripts.Row silent = new OmiTranscripts.Row("s4", "Pocket", 400, 60_000, "audio_only", "none", "  ");
        RawItem mapped = OmiTranscripts.toItem(done1);
        check(mapped.source.equals(OmiTranscripts.ID) && mapped.externalId.equals("s1")
                && mapped.timestamp == 100 && "Morning".equals(mapped.conversationTitle), "transcript mapped");
        check(OmiTranscripts.toItem(silent) == null, "audio-only recording skipped");
        check("200".equals(OmiTranscripts.nextCursor(Arrays.asList(done1, pending, done3), null)),
                "cursor rewinds to oldest pending refinement");
        check("300".equals(OmiTranscripts.nextCursor(Arrays.asList(done1, done3), "50")), "cursor advances to newest");
        check("50".equals(OmiTranscripts.nextCursor(Collections.emptyList(), "50")), "empty page keeps cursor");
    }

    private static void searchExpressions() {
        check(Search.matchExpression("reunião  amanhã!").equals("\"reunião\"* \"amanhã\"*"), "words quoted as prefixes");
        check(Search.matchExpression("\"; DROP").equals("\"DROP\"*"), "punctuation dropped");
        check(Search.matchExpression(null).isEmpty(), "null query");
    }

    private static void rawItemValidation() {
        try { RawItem.builder("s", "1").kind(RawItem.DOC).build(); check(false, "timestamp required"); }
        catch (IllegalArgumentException expected) { check(true, "timestamp required"); }
        try { RawItem.builder("s", "").kind(RawItem.DOC).timestamp(1).build(); check(false, "externalId required"); }
        catch (IllegalArgumentException expected) { check(true, "externalId required"); }
    }

    private static ChatMessages.Snapshot snap(String title, String shortcut, boolean group, boolean summary,
                                                  String category, ChatMessages.Message... messages) {
        return new ChatMessages.Snapshot("com.whatsapp", 50_000, title, shortcut, group, summary, "Gabriel",
                category, Arrays.asList(messages));
    }

    private static ChatMessages.Snapshot signalSnap(String title, boolean group, ChatMessages.Message... messages) {
        return new ChatMessages.Snapshot("org.thoughtcrime.securesms", 60_000, title, "recipient-7", group, false,
                "Gabriel", "msg", Arrays.asList(messages));
    }

    private static void signal() throws Exception {
        List<RawItem> items = ChatMessages.parse(signalSnap("Rui", false,
                msg("Rui", "Chego às 9", 1000), msg(null, "Combinado", 2000)));
        check(items.size() == 2 && items.get(0).source.equals(ChatMessages.SIGNAL), "Signal messages get their own source");
        check(items.get(1).fromMe && "recipient-7".equals(items.get(0).conversationExternalId), "Signal reply and chat key");
        check(ChatMessages.parse(signalSnap("Signal", false, msg(null, "3 new messages in 2 chats", 1))).isEmpty()
                && ChatMessages.parse(signalSnap("Signal", false, msg(null, "Background connection enabled", 1))).isEmpty(),
                "Signal notices skipped");
        ChatMessages.Snapshot hidden = signalSnap("Signal", false, msg(null, "New message", 1), msg(null, "New message", 2));
        check(ChatMessages.parse(hidden).isEmpty() && ChatMessages.contentHidden(hidden), "hidden Signal content detected");
        check(!ChatMessages.contentHidden(signalSnap("Rui", false, msg("Rui", "Chego às 9", 1))), "real content isn't hidden");
        check(!ChatMessages.contentHidden(snap("Ana", null, false, false, "msg", msg("Ana", "New message", 1))),
                "only Signal reports hidden content");
        check(ChatMessages.parse(new ChatMessages.Snapshot("org.telegram.messenger", 1, "X", null, false, false, null, "msg",
                Arrays.asList(msg("X", "hi", 1)))).isEmpty(), "other apps ignored");
        check(ChatMessages.App.forId("signal") == ChatMessages.App.SIGNAL_APP
                && ChatMessages.App.forPackage("com.whatsapp.w4b") == ChatMessages.App.WHATSAPP_APP, "app table lookups");

        Db db = fresh();
        Sources.ensure(db, ChatMessages.SIGNAL);
        Sources.setNotice(db, ChatMessages.SIGNAL, "Signal is hiding message content");
        Sources.finish(db, ChatMessages.SIGNAL, 5, "OK · 0 new, 0 updated");
        check("Signal is hiding message content".equals(Sources.ensure(db, ChatMessages.SIGNAL).notice),
                "the daily sync doesn't wipe a standing notice");
        Sources.setNotice(db, ChatMessages.SIGNAL, null);
        check(Sources.ensure(db, ChatMessages.SIGNAL).notice == null, "notice cleared");
    }

    private static ChatMessages.Message msg(String sender, String text, long time) {
        return new ChatMessages.Message(sender, text, time);
    }

    private static void whatsAppParsing() {
        List<RawItem> dm = ChatMessages.parse(snap("Ana", "5511999@s.whatsapp.net", false, false, "msg",
                msg("Ana", "Oi! Vamos almoçar amanhã?", 1000), msg(null, "Bora, 12h?", 2000)));
        check(dm.size() == 2, "DM: both messages");
        check(dm.get(0).conversationExternalId.equals("5511999@s.whatsapp.net") && "dm".equals(dm.get(0).conversationKind),
                "DM keyed by shortcut id");
        check(dm.get(0).authorHandle.equals("name:Ana") && !dm.get(0).fromMe, "incoming author");
        check(dm.get(1).fromMe && "me".equals(dm.get(1).authorHandle), "reply from the notification is mine");
        check(ChatMessages.parse(snap("Ana", null, false, false, "msg", msg("Gabriel", "eu", 3000))).get(0).fromMe,
                "self display name is mine");

        List<RawItem> group = ChatMessages.parse(snap("Família (3 mensagens)", null, true, false, "msg",
                msg("Mãe", "Jantar domingo?", 1000), msg("Pai", "📷 Foto", 1100)));
        check(group.size() == 2 && "Família".equals(group.get(0).conversationTitle), "group title cleaned");
        check("title:Família".equals(group.get(0).conversationExternalId) && "group".equals(group.get(0).conversationKind),
                "group keyed by title without shortcut");
        check("📷 Foto".equals(group.get(1).text), "media placeholder kept as text");

        check(ChatMessages.parse(snap("WhatsApp", null, false, true, "msg", msg("Ana", "hi", 1))).isEmpty(),
                "group summary skipped");
        check(ChatMessages.parse(snap("Ana", null, false, false, "call", msg("Ana", "Incoming voice call", 1))).isEmpty(),
                "calls skipped");
        check(ChatMessages.parse(snap("WhatsApp", null, false, false, "msg",
                msg(null, "5 messages from 3 chats", 1), msg("x", "3 novas mensagens", 2),
                msg("x", "Checking for new messages", 3), msg("x", "  ", 4))).isEmpty(), "WhatsApp notices skipped");
        check(ChatMessages.parse(snap("Ana", null, false, false, "msg", msg("Ana", "hi", 0))).get(0).timestamp == 50_000,
                "missing time falls back to post time");

        String a = ChatMessages.parse(snap("Ana", "j", false, false, "msg", msg("Ana", "hi", 7))).get(0).externalId;
        String b = ChatMessages.parse(snap("Ana (2 messages)", "j", false, false, "msg", msg("Ana", "hi", 7))).get(0).externalId;
        String c = ChatMessages.parse(snap("Ana", "j", false, false, "msg", msg("Ana", "hi", 8))).get(0).externalId;
        check(a.equals(b) && !a.equals(c), "id stable across re-posts, distinct per message");
    }

    private static void whatsAppIngest() throws Exception {
        Db db = fresh();
        ChatMessages.Snapshot first = snap("Família", "fam", true, false, "msg",
                msg("Mãe", "Jantar domingo?", 1000), msg("Pai", "Pode ser", 2000));
        ChatMessages.Snapshot repost = snap("Família (3 messages)", "fam", true, false, "msg",
                msg("Mãe", "Jantar domingo?", 1000), msg("Pai", "Pode ser", 2000), msg(null, "Levo a sobremesa", 3000));
        Ingest.Stats one = db.transaction(() -> Ingest.upsert(db, ChatMessages.parse(first), 1));
        Ingest.Stats two = db.transaction(() -> Ingest.upsert(db, ChatMessages.parse(repost), 2));
        check(one.added == 2 && two.added == 1 && two.updated == 0 && two.unchanged == 2, "re-posted history adds only the new message");
        check(db.query("SELECT COUNT(*) FROM people").get(0)[0].equals(3L), "Me plus Mãe and Pai");

        db.transaction(() -> Ingest.upsert(db, java.util.Arrays.asList(
                RawItem.builder(ChatMessages.WHATSAPP, "x4").kind(RawItem.MESSAGE).timestamp(4000).text("Ótimo")
                        .conversation("fam", "Família", "group").author("name:Mãe", "Mãe", false).build(),
                RawItem.builder(ChatMessages.WHATSAPP, "y1").kind(RawItem.MESSAGE).timestamp(2500).text("outro chat")
                        .conversation("other", "Trabalho", "group").author("name:Rui", "Rui", false).build()), 3));
        Search.Hit hit = Search.find(db, "sobremesa", 5).get(0);
        check(hit.fromMe && hit.author == null && "Família".equals(hit.conversation), "search hit knows it's mine");
        check("Mãe".equals(Search.find(db, "domingo", 5).get(0).author), "search hit has the author");
        Items.Item item = Items.get(db, hit.itemId);
        List<Items.Item> around = Items.around(db, item, 1);
        check(around.size() == 3 && around.get(0).text.equals("Pode ser") && around.get(1).id == item.id
                && around.get(2).text.equals("Ótimo"), "around: neighbours in order, same chat only");
        check(Items.around(db, item, 10).size() == 4, "around: bounded by the conversation");
        Sources.ensure(db, ChatMessages.WHATSAPP);
        Sources.State state = Sources.ensure(db, ChatMessages.WHATSAPP);
        check(state.lastItemAt == 4000, "source knows its newest message time");
    }

    private static void recentItems() throws Exception {
        Db db = fresh();
        check(Items.recent(db, 20).isEmpty(), "recent: empty store");
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 100; i++) longText.append("palavra ");
        db.transaction(() -> Ingest.upsert(db, Arrays.asList(
                RawItem.builder(ChatMessages.WHATSAPP, "r1").kind(RawItem.MESSAGE).timestamp(1000).text("primeira")
                        .conversation("fam", "Família", "group").author("name:Mãe", "Mãe", false).build(),
                RawItem.builder(ChatMessages.WHATSAPP, "r2").kind(RawItem.MESSAGE).timestamp(3000).text("terceira")
                        .conversation("fam", "Família", "group").author("name:Pai", "Pai", false).build(),
                RawItem.builder(OmiTranscripts.ID, "r3").kind(RawItem.TRANSCRIPT).timestamp(2000).text(longText.toString())
                        .conversation("r3", "Reunião", "meeting").build()), 1));
        List<Items.Item> recent = Items.recent(db, 20);
        check(recent.size() == 3 && recent.get(0).text.equals("terceira") && recent.get(1).source.equals(OmiTranscripts.ID)
                && recent.get(2).text.equals("primeira"), "recent: newest first, across sources");
        check(recent.get(0).author.equals("Pai") && recent.get(0).conversation.equals("Família"), "recent: author and chat");
        check(recent.get(1).text.length() == Items.PREVIEW_CHARS, "recent: long text cut to a preview");
        check(Items.recent(db, 2).size() == 2, "recent: limit");
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
