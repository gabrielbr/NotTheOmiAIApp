package br.gabriel.sentient;

import br.gabriel.sentient.plugin.RawItem;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Phase 4A: people across apps, found to-dos, digests, the portrait and the Markdown vault. */
public final class EnrichmentHostTest {
    private static int checks;
    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    static final long NOW = 1_791_547_200_000L; // 2026-10-09T12:00:00Z = 09:00 in São Paulo
    static final long HOUR = 3_600_000L, DAY = 24 * HOUR;

    public static void main(String[] args) throws Exception {
        TaskExtractorChecks.run();
        Db db = fixture();
        check(Schema.latest() == 2, "schema has the enrichment step");
        enrichment(db);
        people(db);
        foundTasks(db);
        digest(db);
        portrait(db);
        vault(db);
        forget(db);
        System.out.println("PASS_ENRICHMENT_HOST_CHECKS " + checks);
    }

    static RawItem.Builder msg(String source, String id, long ts, String text) {
        return RawItem.builder(source, id).kind(RawItem.MESSAGE).timestamp(ts).text(text);
    }

    static Db fixture() throws Exception {
        Db db = new JdbcDb(DriverManager.getConnection("jdbc:sqlite::memory:"));
        Schema.migrate(db);
        List<RawItem> items = Arrays.asList(
                msg("whatsapp", "w1", NOW - 20 * HOUR, "Não esquece de comprar pão amanhã").conversation("c:ana", "Ana", "dm")
                        .author("name:Ana", "Ana", false).build(),
                msg("whatsapp", "w2", NOW - 19 * HOUR, "Preciso ligar pro banco.").conversation("c:ana", "Ana", "dm")
                        .author("me", null, true).build(),
                msg("whatsapp", "w3", NOW - 18 * HOUR, "Jantar domingo?").conversation("c:fam", "Família", "group")
                        .author("name:Mãe", "Mãe", false).build(),
                msg("whatsapp", "w4", NOW - 17 * HOUR, "Tenho que levar o vinho").conversation("c:fam", "Família", "group")
                        .author("name:Pai", "Pai", false).build(),
                msg("whatsapp", "w5", NOW - 16 * HOUR, "Levo a sobremesa").conversation("c:fam", "Família", "group")
                        .author("me", null, true).build(),
                msg("matrix", "$m1", NOW - 15 * HOUR, "Oi, tudo bem?").conversation("!r", "Ana", "dm")
                        .author("@ana:x.org", "Ana", false).build(),
                msg("signal", "s1", NOW - 14 * HOUR, "Ana do trabalho aqui").conversation("c:ana2", "Ana", "dm")
                        .author("name:Ana", "Ana", false).build(),
                RawItem.builder("composio.gmail", "g1").kind(RawItem.EMAIL).timestamp(NOW - 13 * HOUR).text("Contrato\n\nSegue o contrato")
                        .conversation("t1", "Contrato", "thread").author("email:ana@x.com", "Ana Souza", false).build(),
                RawItem.builder("composio.gmail", "g2").kind(RawItem.EMAIL).timestamp(NOW - 12 * HOUR).text("Contrato\n\nRecebido")
                        .conversation("t1", "Contrato", "thread").author("email:me@x.com", "Gabriel", true).build(),
                RawItem.builder("composio.googlecalendar", "e1").kind(RawItem.EVENT).timestamp(NOW + DAY).text("Dentista\nWhen: …")
                        .conversation("primary", "Calendar", "calendar").author("email:ana@x.com", "Ana S.", false).build(),
                RawItem.builder("composio.googlecalendar", "e2").kind(RawItem.EVENT).timestamp(NOW + 2 * DAY).text("Reunião")
                        .conversation("primary", "Calendar", "calendar").author("email:me@x.com", null, true).build(),
                RawItem.builder("composio.todoist", "t1").kind(RawItem.TASK).timestamp(NOW - 30 * HOUR).text("Pagar luz\nDue: amanhã")
                        .author("me", null, true).build(),
                RawItem.builder("omi.transcripts", "r1").kind(RawItem.TRANSCRIPT).timestamp(NOW - 11 * HOUR)
                        .text("Bom dia. Tenho que mandar o relatório na segunda.").conversation("r1", "Reunião", "meeting").build());
        Ingest.upsert(db, items, NOW - 10 * HOUR);
        return db;
    }

    static long person(Db db, String source, String handle) throws Exception {
        return ((Number) db.query("SELECT person_id FROM identities WHERE source = ? AND handle = ?", source, handle).get(0)[0]).longValue();
    }

    static long count(Db db, String sql, Object... args) throws Exception {
        return ((Number) db.query(sql, args).get(0)[0]).longValue();
    }

    private static void enrichment(Db db) throws Exception {
        Enrichment.Result r = Enrichment.run(db, NOW, ZONE);
        check(r.merged == 1, "same email merges Ana Souza's Gmail + Calendar (yours were already you): " + r.merged);
        check(person(db, "composio.gmail", "email:ana@x.com") == person(db, "composio.googlecalendar", "email:ana@x.com"),
                "Ana's address is one person");
        long me = ((Number) db.query("SELECT id FROM people WHERE is_me = 1").get(0)[0]).longValue();
        check(person(db, "composio.googlecalendar", "email:me@x.com") == me && person(db, "whatsapp", "me") == me, "you stay one person");
        check(r.tasks == 3, "to-dos found in a DM, your own message and a recording: " + r.tasks);
        check(r.days >= 2, "digests for the days with new items: " + r.days);
        check(Portrait.read(db) != null, "portrait stored");
        Enrichment.Result again = Enrichment.run(db, NOW + 60_000, ZONE);
        check(again.merged == 0 && again.tasks == 0, "re-running changes nothing");
    }

    private static void people(Db db) throws Exception {
        List<People.Suggestion> s = People.suggestions(db, 20);
        long waAna = person(db, "whatsapp", "name:Ana"), mxAna = person(db, "matrix", "@ana:x.org"), sigAna = person(db, "signal", "name:Ana");
        boolean waMx = false, waSig = false, souza = false;
        for (People.Suggestion x : s) {
            long a = x.a.id, b = x.b.id;
            waMx |= (a == waAna && b == mxAna) || (a == mxAna && b == waAna);
            waSig |= (a == waAna && b == sigAna) || (a == sigAna && b == waAna);
            souza |= x.a.name.contains("Souza") || x.b.name.contains("Souza");
        }
        check(waMx && waSig, "same name across apps is suggested: " + s.size());
        check(!souza, "different names aren't suggested");
        check(s.get(0).reason.startsWith("Same name in "), "suggestion says why: " + s.get(0).reason);

        People.keepApart(db, waAna, sigAna);
        boolean stillSig = false;
        for (People.Suggestion x : People.suggestions(db, 20)) stillSig |= (x.a.id == sigAna || x.b.id == sigAna) && (x.a.id == waAna || x.b.id == waAna);
        check(!stillSig, "kept apart: never suggested again");

        db.transaction(() -> { People.merge(db, waAna, mxAna); return null; });
        check(person(db, "matrix", "@ana:x.org") == waAna && People.get(db, mxAna) == null, "merge moves identities");
        check(People.identities(db, waAna).size() == 2 && People.get(db, waAna).items == 2, "merged person has both");
        long mxIdentity = ((Number) db.query("SELECT id FROM identities WHERE handle = '@ana:x.org'").get(0)[0]).longValue();
        long split = db.transaction(() -> People.separate(db, mxIdentity, NOW));
        check(person(db, "matrix", "@ana:x.org") == split && "Ana".equals(People.get(db, split).name), "separate undoes it");
        boolean again = false;
        for (People.Suggestion x : People.suggestions(db, 20))
            again |= (x.a.id == split && x.b.id == waAna) || (x.a.id == waAna && x.b.id == split);
        check(!again, "a separated identity isn't suggested back with the person it left");
        try { People.separate(db, mxIdentity, NOW); check(false, "can't separate a last identity"); }
        catch (IllegalArgumentException expected) { check(true, "can't separate a last identity"); }

        long me = ((Number) db.query("SELECT id FROM people WHERE is_me = 1").get(0)[0]).longValue();
        db.transaction(() -> { People.merge(db, waAna, me); return null; });
        check(People.get(db, me) != null && People.get(db, me).me, "merging into you keeps you");
        // put Ana back for the rest of the checks
        long anaIdentity = ((Number) db.query("SELECT id FROM identities WHERE source = 'whatsapp' AND handle = 'name:Ana'").get(0)[0]).longValue();
        long ana = db.transaction(() -> People.separate(db, anaIdentity, NOW));
        People.rename(db, ana, "  Ana Lima ");
        check("Ana Lima".equals(People.get(db, ana).name), "rename trims");
        check("ana souza".equals(People.nameKey("  Ána  SOUZA 🙂")), "names compare without accents, case or emoji");
        check(People.list(db, 100).get(0).me, "you're listed first");
    }

    private static void foundTasks(Db db) throws Exception {
        List<FoundTasks.Task> open = FoundTasks.list(db, FoundTasks.OPEN, 0, 50);
        List<String> texts = new ArrayList<>();
        for (FoundTasks.Task t : open) texts.add(t.text);
        check(texts.containsAll(Arrays.asList("Comprar pão amanhã", "Ligar pro banco", "Mandar o relatório na segunda")), "found: " + texts);
        check(!texts.contains("Levar o vinho"), "others' group messages aren't your to-dos");
        // A refined transcript is re-read.
        Ingest.upsert(db, Arrays.asList(RawItem.builder("omi.transcripts", "r1").kind(RawItem.TRANSCRIPT).timestamp(NOW - 11 * HOUR)
                .text("Bom dia. Tenho que mandar o relatório na segunda. Preciso renovar o passaporte.")
                .conversation("r1", "Reunião", "meeting").build()), NOW + HOUR);
        check(FoundTasks.scan(db, NOW + HOUR) == 1, "refined transcript: only the new to-do added");
        long id = open.get(0).id;
        FoundTasks.setStatus(db, id, FoundTasks.DISMISSED);
        boolean gone = true;
        for (FoundTasks.Task t : FoundTasks.list(db, FoundTasks.OPEN, 0, 50)) gone &= t.id != id;
        check(gone, "dismissed to-dos leave the open list");
        FoundTasks.setStatus(db, id, FoundTasks.OPEN);
    }

    private static void digest(Db db) throws Exception {
        LocalDate day = LocalDate.of(2026, 10, 8);
        String md = Digest.write(db, day, ZONE, NOW);
        check(md.startsWith("# Thursday, 8 October 2026\n"), "digest title: " + md.split("\n")[0]);
        check(md.contains("## What came in") && md.contains("- WhatsApp: 5 messages"), "counts per app");
        check(md.contains("## People") && md.contains("## Conversations\n\n- Família (WhatsApp) · 3 messages [#"), "people and chats");
        check(md.contains("## Recordings") && md.contains("Reunião [#"), "recordings");
        check(md.contains("## To-dos mentioned") && md.contains("Comprar pão amanhã [#"), "to-dos");
        check(Digest.read(db, day).equals(md), "stored");
        check(Digest.write(db, LocalDate.of(2026, 1, 1), ZONE, NOW) == null && Digest.read(db, LocalDate.of(2026, 1, 1)) == null,
                "empty days have no digest");
        String tomorrow = Digest.write(db, LocalDate.of(2026, 10, 10), ZONE, NOW);
        check(tomorrow != null && tomorrow.contains("## Calendar") && tomorrow.contains("Dentista [#"), "events in the day's digest");
    }

    private static void portrait(Db db) throws Exception {
        String md = Portrait.write(db, NOW, ZONE);
        check(md.startsWith("# About me\n"), "portrait title");
        check(md.contains("Known as ") && md.contains("Gabriel · me@x.com (Gmail)") && md.contains("me@x.com (Calendar)"), "who you are: " + md);
        check(md.contains("## People I talk to most (last 30 days)\n\n1. "), "people ranked");
        check(md.contains("## Groups I'm active in") && md.contains("Família (WhatsApp) · 3 messages, 1 mine"), "groups");
        check(md.contains("## Coming up (next 7 days)") && md.contains("Dentista [#") && md.contains("Reunião [#"), "upcoming events");
        check(md.contains("Pagar luz (Todoist) [#") && md.contains("Comprar pão amanhã (said in WhatsApp"), "open to-dos from both places");
        check(md.contains("## What GMind knows from"), "sources");
        String brief = Portrait.brief(md);
        check(brief.length() <= Portrait.BRIEF_CHARS && !brief.contains("[#") && brief.startsWith("# About me"), "brief for small models");
    }

    static final class MapWriter implements Vault.Writer {
        final Map<String, String> files = new LinkedHashMap<>();
        final List<String> deleted = new ArrayList<>();
        @Override public void write(String path, String content) { files.put(path, content); }
        @Override public void delete(String path) { files.remove(path); deleted.add(path); }
        @Override public String read(String path) { return files.get(path); }
    }

    private static void vault(Db db) throws Exception {
        MapWriter w = new MapWriter();
        w.files.put("My own notes.md", "mine");
        int n = Vault.export(db, w, NOW, ZONE);
        check(n > 5 && w.files.containsKey("README.md") && w.files.containsKey("Tasks.md") && w.files.containsKey(Vault.MANIFEST), "vault written: " + w.files.keySet());
        check(w.files.containsKey("Chats/Família (WhatsApp).md") && w.files.containsKey("People/Mãe.md"), "chat and person notes");
        check(w.files.containsKey("Days/2026-10-08.md"), "day notes");
        boolean noIds = true;
        for (Map.Entry<String, String> f : w.files.entrySet()) if (f.getKey().endsWith(".md")) noIds &= !f.getValue().contains("[#");
        check(noIds, "item ids replaced by app and date");
        String readme = w.files.get("README.md");
        check(readme.contains("(WhatsApp · 8 Oct)") || readme.contains("(Gmail · 8 Oct)"), "citations readable");
        check(readme.contains("[[Mãe]]") || readme.contains("[[Ana Lima]]") || readme.contains("[[Ana Souza]]"), "people linked from README");
        String fam = w.files.get("Chats/Família (WhatsApp).md");
        check(fam.contains("**[[Mãe]]**: Jantar domingo?") && fam.contains("**Me**: Levo a sobremesa"), "chat note links people: " + fam);
        String mae = w.files.get("People/Mãe.md");
        check(mae.startsWith("---\naliases: []\napps: [\"WhatsApp\"]\n---\n\n# Mãe") && mae.contains("[[Família (WhatsApp)]]"), "person note: " + mae);
        check(w.files.get("Tasks.md").contains("- [ ] Pagar luz (Todoist)") && w.files.get("Tasks.md").contains("- [ ] Comprar pão amanhã (WhatsApp"),
                "tasks note");
        check("mine".equals(w.files.get("My own notes.md")), "your own files are left alone");

        // A note GMind wrote before that no longer exists is removed; nothing else is.
        w.files.put("People/Gone.md", "old");
        w.files.put(Vault.MANIFEST, "{\"files\":[\"People/Gone.md\",\"../escape.md\",\"My own notes.md\"]}");
        Vault.export(db, w, NOW, ZONE);
        check(!w.files.containsKey("People/Gone.md") && w.deleted.contains("People/Gone.md"), "stale note removed");
        check(!w.deleted.contains("../escape.md"), "never deletes outside the folder");
        check("mine".equals(w.files.get("My own notes.md")), "only GMind's kinds of notes are ever deleted");
        w.files.put("People/Gone.md", "old");
        w.files.put(Vault.MANIFEST, "not json");
        Vault.export(db, w, NOW, ZONE);
        check(w.files.containsKey("People/Gone.md"), "a corrupt manifest deletes nothing");
        check(Vault.safe("People/A.md") && Vault.safe("README.md") && !Vault.safe("../A.md") && !Vault.safe("People/../A.md")
                && !Vault.safe("/etc/x.md") && !Vault.safe("A.md") && !Vault.safe("People/a/b.md"), "path guard");
        MapWriter used = new MapWriter();
        used.files.put("README.md", "someone else's vault");
        try { Vault.export(db, used, NOW, ZONE); check(false, "refuses a folder with notes"); }
        catch (IllegalStateException expected) { check("someone else's vault".equals(used.files.get("README.md")), "refuses a folder with notes"); }
    }

    private static void forget(Db db) throws Exception {
        long before = count(db, "SELECT COUNT(*) FROM found_tasks");
        db.transaction(() -> Sources.forget(db, "whatsapp"));
        check(count(db, "SELECT COUNT(*) FROM found_tasks") == before - 2, "forgetting a source removes its to-dos");
        check(count(db, "SELECT COUNT(*) FROM not_same_person WHERE a NOT IN (SELECT id FROM people) OR b NOT IN (SELECT id FROM people)") == 0,
                "no dangling kept-apart pairs");
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        checks++;
    }
}
