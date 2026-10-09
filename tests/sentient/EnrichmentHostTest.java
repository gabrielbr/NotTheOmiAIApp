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
        check(Schema.latest() == 3, "schema has the enrichment and watched-chats steps");
        enrichment(db);
        people(db);
        foundTasks(db);
        digest(db);
        portrait(db);
        vault(db);
        forget(db);
        extraction();
        dueDates();
        requests();
        watchedChats();
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
        check(FoundTasks.scan(db, NOW + HOUR, ZONE) == 1, "refined transcript: only the new to-do added");
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

    static long item(Db db, String externalId) throws Exception {
        return ((Number) db.query("SELECT id FROM items WHERE external_id = ?", externalId).get(0)[0]).longValue();
    }

    private static void extraction() throws Exception {
        Db db = fixture();
        Enrichment.run(db, NOW, ZONE);
        long g1 = item(db, "g1"), g2 = item(db, "g2"), r1 = item(db, "r1");
        String reply = "{\"entities\":["
                + "{\"key\":\"ana\",\"type\":\"person\",\"name\":\"Ana Souza\",\"aliases\":[\"Ana S.\"],\"evidence\":[" + g1 + "]},"
                + "{\"key\":\"acme\",\"type\":\"org\",\"name\":\"Acme\",\"aliases\":[\"ACME Ltda\"],\"evidence\":[" + g1 + "," + r1 + "]},"
                + "{\"key\":\"rel\",\"type\":\"project\",\"name\":\"Relatório trimestral\",\"aliases\":[],\"evidence\":[" + r1 + ",999999]},"
                + "{\"key\":\"ghost\",\"type\":\"person\",\"name\":\"Ghost\",\"aliases\":[],\"evidence\":[999999]},"
                + "{\"key\":\"me\",\"type\":\"person\",\"name\":\"Gabriel\",\"aliases\":[],\"evidence\":[" + g2 + "]}],"
                + "\"relations\":[{\"from\":\"ana\",\"type\":\"Works At\",\"to\":\"acme\",\"evidence\":[" + g1 + "]},"
                + "{\"from\":\"me\",\"type\":\"works_on\",\"to\":\"rel\",\"evidence\":[" + r1 + "]},"
                + "{\"from\":\"ana\",\"type\":\"knows\",\"to\":\"ghost\",\"evidence\":[" + g1 + "]}],"
                + "\"facts\":[{\"about\":\"me\",\"key\":\"role\",\"value\":\"Designer\",\"evidence\":[" + r1 + "]},"
                + "{\"about\":\"ana\",\"key\":\"lives in\",\"value\":\"Recife\",\"evidence\":[999999]}]}";
        List<String> sent = new ArrayList<>();
        Extraction.Run run = Extraction.run(db, batch -> { sent.add(batch); return reply; }, NOW, ZONE, 10, () -> false);
        check(run.batches == 1 && sent.size() == 1, "one batch for a small day: " + run.batches);
        check(sent.get(0).contains("[#" + g1 + "]") && sent.get(0).contains("· me: Contrato") && sent.get(0).contains("## Gmail · Contrato"),
                "batch lists items with ids, authors and conversation headers");
        check(run.counts.entities == 3 && run.counts.relations == 2 && run.counts.facts == 1 && run.counts.dropped == 3,
                "uncited or unknown things are dropped: " + run.counts.entities + "/" + run.counts.relations + "/" + run.counts.facts + "/" + run.counts.dropped);
        long ana = person(db, "composio.gmail", "email:ana@x.com");
        check(count(db, "SELECT COUNT(*) FROM entities WHERE canonical_key = ?", "person:" + ana) == 1, "a named person links to the GMind person");
        check(count(db, "SELECT COUNT(*) FROM entities WHERE name = 'Ghost'") == 0, "nothing without evidence");
        check(count(db, "SELECT COUNT(*) FROM relations WHERE type = 'works_at'") == 1, "relation types normalized");
        check(count(db, "SELECT COUNT(*) FROM mentions WHERE item_id = 999999") == 0, "foreign ids never stored");
        check(count(db, "SELECT COUNT(*) FROM entity_aliases WHERE alias = 'ACME Ltda'") == 1, "aliases kept");

        check(Extraction.run(db, b -> { throw new AssertionError("nothing new to send"); }, NOW, ZONE, 10, () -> false).batches == 0,
                "nothing is sent twice");
        Ingest.upsert(db, Arrays.asList(msg("whatsapp", "w9", NOW, "Acme fechou o contrato!").conversation("c:ana", "Ana", "dm")
                .author("name:Ana", "Ana", false).build()), NOW + HOUR);
        long w9 = item(db, "w9");
        List<String> second = new ArrayList<>();
        Extraction.Run refused = Extraction.run(db, b -> { second.add(b); return null; }, NOW + HOUR, ZONE, 10, () -> false);
        check(refused.refused == 1 && second.get(0).contains("[#" + w9 + "]") && !second.get(0).contains("[#" + g1 + "]"),
                "only new items are sent; a declined batch is skipped");
        Ingest.upsert(db, Arrays.asList(msg("whatsapp", "w10", NOW, "ok").conversation("c:ana", "Ana", "dm")
                .author("name:Ana", "Ana", false).build()), NOW + 2 * HOUR);
        Extraction.Run broken = Extraction.run(db, b -> "{not json", NOW + 2 * HOUR, ZONE, 10, () -> false);
        check(broken.batches == 1 && broken.counts.dropped == 1, "a malformed reply is dropped, not retried forever");
        check(Extraction.batches(db, NOW + 3 * HOUR, ZONE, 10).isEmpty(), "cursor moved past it");

        String portrait = Portrait.write(db, NOW + HOUR, ZONE);
        check(portrait.contains("## Facts about me\n\n- role: Designer [#" + r1 + "]"), "facts about you in the portrait");
        check(portrait.contains("## What I'm working on (last 30 days)") && portrait.contains("- Acme · 2 mentions [#"), "work from the graph: " + portrait);

        String about = new KnowledgeTools(db, ZONE).about("acme");
        check(about.startsWith("Acme (org)") && about.contains("Ana Souza works_at Acme [#" + g1 + "]"), "about tool: " + about);
        check(new KnowledgeTools(db, ZONE).run(KnowledgeTools.ABOUT, java.util.Collections.singletonMap("name", (Object) "Ltda")).startsWith("Acme"),
                "about finds aliases");

        MapWriter w = new MapWriter();
        Vault.export(db, w, NOW + HOUR, ZONE);
        check(w.files.containsKey("Projects/Acme.md") && !w.files.containsKey("Projects/Relatório trimestral.md"), "notes for things mentioned twice or more");
        String acme = w.files.get("Projects/Acme.md");
        check(acme.contains("aliases: [\"ACME Ltda\"]") && acme.contains("type: org") && acme.contains("[[Ana Souza]] (works at)")
                && acme.contains("## Mentioned in"), "entity note: " + acme);
        check(w.files.get("People/Ana Souza.md").contains("- works at [[Acme]]"), "person note links what they relate to");
        check(w.files.get("People/Me.md").contains("- role: Designer") && w.files.get("People/Me.md").contains("works on Relatório trimestral"),
                "your note has your facts");
        Object schema = Extraction.SCHEMA;
        check(Boolean.FALSE.equals(br.gabriel.sentient.plugin.Json.at(schema, "additionalProperties"))
                && br.gabriel.sentient.plugin.Json.list(br.gabriel.sentient.plugin.Json.at(schema, "required")).size() == 3
                && Boolean.FALSE.equals(br.gabriel.sentient.plugin.Json.at(schema, "properties", "facts", "items", "additionalProperties")),
                "strict schema");
    }

    static void due(String text, String expected) {
        java.time.LocalDate d = DueDates.parse(text, NOW, ZONE); // NOW is Friday 9 Oct 2026, 09:00 in São Paulo
        check(expected == null ? d == null : d != null && d.toString().equals(expected), "due: " + text + " -> " + d);
    }

    private static void dueDates() {
        due("até amanhã", "2026-10-10");
        due("Can you send it tomorrow?", "2026-10-10");
        due("hoje ainda", "2026-10-09");
        due("depois de amanhã", "2026-10-11");
        due("preenche a planilha até sexta", "2026-10-16");
        due("by Monday please", "2026-10-12");
        due("na terça-feira", "2026-10-13");
        due("até o fim do mês", "2026-10-31");
        due("end of the month", "2026-10-31");
        due("semana que vem", "2026-10-12");
        due("dia 15", "2026-10-15");
        due("before the 5th", "2026-11-05");
        due("entregar 20/10", "2026-10-20");
        due("deadline 10/25", "2026-10-25");
        due("pagar 05/01", "2027-01-05");
        due("15/10/2026", "2026-10-15");
        due("31/02", null);
        due("bom dia pessoal", null);
        due("the monster", null);
    }

    private static void requests() {
        List<String> me = Arrays.asList("Gabriel", "Gabi");
        List<String> r = Requests.find("Gabriel, você precisa preencher a planilha de horas até sexta.", me, true);
        check(r.equals(Arrays.asList("Preencher a planilha de horas até sexta")), "named request in a group: " + r);
        check(Requests.find("Gabi pode revisar o PR hoje?", me, true).equals(Arrays.asList("Revisar o PR hoje?")) ||
                Requests.find("Gabi pode revisar o PR hoje?", me, true).equals(Arrays.asList("Revisar o PR hoje")),
                "nickname works: " + Requests.find("Gabi pode revisar o PR hoje?", me, true));
        check(Requests.find("Ana, manda o relatório até sexta", me, true).isEmpty(), "someone else's request in a group skipped");
        check(Requests.find("Precisa preencher a planilha", me, true).isEmpty(), "group message without your name skipped");
        check(Requests.find("Bom dia Gabriel!", me, true).isEmpty(), "greeting isn't a task");
        check(Requests.find("Gabrielle, pode ver isso?", me, true).isEmpty(), "whole names only");
        check(Requests.find("Pode mandar o contrato amanhã?", me, false).size() == 1, "direct chat: addressed to you by default");
        check(Requests.find("Ana, manda o contrato amanhã", me, false).isEmpty(), "direct chat opening with another name skipped");
        check(Requests.find("Gabriel precisa preencher X", java.util.Collections.emptyList(), true).isEmpty(), "no names, no group tasks");
        check(Requests.splitNames(" Gabriel , Gabi;Gabriel ").equals(Arrays.asList("Gabriel", "Gabi")), "names split");
    }

    private static void watchedChats() throws Exception {
        Db db = fixture();
        FoundTasks.setNames(db, Arrays.asList("Gabriel", "Gabi"));
        check("Gabriel".equals(db.query("SELECT display_name FROM people WHERE is_me = 1").get(0)[0]), "your name renames you");
        check(FoundTasks.names(db).equals(Arrays.asList("Gabriel", "Gabi")), "names saved");
        Ingest.upsert(db, Arrays.asList(
                msg("whatsapp", "g10", NOW, "Gabriel, você precisa preencher a planilha de horas até sexta.")
                        .conversation("c:work", "Empresa", "group").author("name:Chefe", "Chefe", false).build(),
                msg("whatsapp", "g11", NOW, "Ana, manda o relatório até sexta").conversation("c:work", "Empresa", "group")
                        .author("name:Chefe", "Chefe", false).build()), NOW);
        long work = ((Number) db.query("SELECT id FROM conversations WHERE external_id = 'c:work'").get(0)[0]).longValue();
        check(!FoundTasks.watched(db, work), "groups are off by default");
        FoundTasks.scan(db, NOW, ZONE);
        check(count(db, "SELECT COUNT(*) FROM found_tasks WHERE item_id = ?", item(db, "g10")) == 0, "unwatched group ignored");
        boolean listed = false;
        for (Object[] c : FoundTasks.chats(db, 0, 100)) listed |= ((Number) c[0]).longValue() == work && ((Number) c[4]).intValue() == 0;
        check(listed, "group listed as off");

        FoundTasks.setWatched(db, work, true);
        check(FoundTasks.scanChat(db, work, NOW + 1, ZONE, 30) == 1, "switching a group on reads it again");
        List<Object[]> t = db.query("SELECT text, due FROM found_tasks WHERE item_id = ?", item(db, "g10"));
        check(t.size() == 1 && "Preencher a planilha de horas até sexta".equals(t.get(0)[0]) && "2026-10-16".equals(t.get(0)[1]),
                "watched group: your task with its due date");
        check(count(db, "SELECT COUNT(*) FROM found_tasks WHERE item_id = ?", item(db, "g11")) == 0, "someone else's task not added");
        check(count(db, "SELECT COUNT(*) FROM found_tasks WHERE text = 'Comprar pão amanhã' AND due = '2026-10-09'") == 1,
                "direct chat task dated from its message (sent the day before)");
        boolean carried = false;
        for (FoundTasks.Task x : FoundTasks.list(db, FoundTasks.OPEN, 0, 50)) carried |= "2026-10-16".equals(x.due);
        check(carried, "listed tasks carry their due date");
        db.transaction(() -> Sources.forget(db, "whatsapp"));
        check(count(db, "SELECT COUNT(*) FROM watched_chats") == 0, "forgetting a source forgets its watched chats");
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError("FAILED: " + what);
        checks++;
    }
}
