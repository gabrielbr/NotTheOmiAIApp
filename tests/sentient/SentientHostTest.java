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

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        checks++;
    }
}
