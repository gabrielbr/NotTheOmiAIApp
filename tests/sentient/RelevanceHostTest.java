package br.gabriel.sentient;

import br.gabriel.sentient.plugin.RawItem;

import java.sql.DriverManager;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** What's worth remembering: the rules, your overrides, Claude's verdict, and every place hidden items stay out. */
public final class RelevanceHostTest {
    static final long NOW = 1_791_547_200_000L, HOUR = 3_600_000L;
    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");
    static int checks;

    public static void main(String[] args) throws Exception {
        rules();
        surfaces();
        overrides();
        backfill();
        claude();
        System.out.println("PASS_RELEVANCE_HOST_CHECKS " + checks);
    }

    static Db db() throws Exception {
        Db db = new JdbcDb(DriverManager.getConnection("jdbc:sqlite::memory:"));
        Schema.migrate(db);
        Relevance.enabled = true;
        return db;
    }

    static RawItem mail(String id, String thread, String from, String labels, boolean bulk, String text, long ts) {
        String raw = "{\"labelIds\":[" + labels + "]" + (bulk ? ",\"bulk\":true" : "") + "}";
        boolean me = labels.contains("\"SENT\"");
        RawItem.Builder b = RawItem.builder("composio.gmail", id).kind(RawItem.EMAIL).timestamp(ts).text(text).rawJson(raw)
                .conversation(thread, text, "thread");
        return (me ? b.author("email:gabriel@example.com", "Gabriel", true) : b.author("email:" + from, from, false)).build();
    }

    static void store(Db db, RawItem... items) throws Exception {
        db.transaction(() -> Ingest.upsert(db, Arrays.asList(items), NOW));
    }

    static int noise(Db db, String external) throws Exception {
        return ((Number) db.query("SELECT noise FROM items WHERE external_id = ?", external).get(0)[0]).intValue();
    }

    static String reason(Db db, String external) throws Exception {
        return (String) db.query("SELECT noise_reason FROM items WHERE external_id = ?", external).get(0)[0];
    }

    static void rules() throws Exception {
        Db db = db();
        FoundTasks.setNames(db, List.of("Gabriel"));
        store(db,
                mail("promo", "t1", "ofertas@loja.com", "\"INBOX\",\"CATEGORY_PROMOTIONS\"", true, "50% off sapatos", NOW - 9 * HOUR),
                mail("social", "t2", "notify@social.com", "\"INBOX\",\"CATEGORY_SOCIAL\"", false, "Ana curtiu sua foto", NOW - 8 * HOUR),
                mail("newsletter", "t3", "editor@jornal.com", "\"INBOX\",\"CATEGORY_UPDATES\"", true, "Edição da semana", NOW - 7 * HOUR),
                mail("receipt", "t4", "pedidos@loja.com", "\"INBOX\",\"CATEGORY_UPDATES\"", false, "Seu pedido 123 foi enviado", NOW - 6 * HOUR),
                mail("noreply", "t5", "no-reply@banco.com", "\"INBOX\"", false, "Seu extrato", NOW - 5 * HOUR),
                mail("list", "t6", "grupo@lista.org", "\"INBOX\"", true, "Lista de discussão", NOW - 5 * HOUR),
                mail("personal", "t7", "ana@example.com", "\"INBOX\"", false, "Jantar sábado?", NOW - 4 * HOUR),
                mail("spam", "t8", "x@spam.biz", "\"SPAM\"", false, "Ganhe dinheiro", NOW - 4 * HOUR),
                mail("sent", "t7", "gabriel@example.com", "\"SENT\"", false, "Pode ser!", NOW - 3 * HOUR),
                RawItem.builder("composio.slack", "bot1").kind(RawItem.MESSAGE).timestamp(NOW - 3 * HOUR).text("Deploy finished")
                        .conversation("C1", "eng", "group").author("slack-bot:B1", "CI", false).build(),
                RawItem.builder("composio.slack", "bot2").kind(RawItem.MESSAGE).timestamp(NOW - 3 * HOUR).text("Gabriel, approve the release")
                        .conversation("C1", "eng", "group").author("slack-bot:B1", "CI", false).build(),
                RawItem.builder("whatsapp", "w1").kind(RawItem.MESSAGE).timestamp(NOW - 2 * HOUR).text("Oferta imperdível!")
                        .conversation("c:loja", "Loja", "dm").author("name:Loja", "Loja", false).build());
        check(noise(db, "promo") == 1 && "Gmail: Promotions".equals(reason(db, "promo")), "Promotions hidden: " + reason(db, "promo"));
        check(noise(db, "social") == 1 && "Gmail: Social".equals(reason(db, "social")), "Social hidden");
        check(noise(db, "newsletter") == 1 && reason(db, "newsletter").contains("mailing list"), "a bulk Updates newsletter is hidden");
        check(noise(db, "receipt") == 0, "a one-off update (an order) stays memory");
        check(noise(db, "noreply") == 1 && "Automated sender".equals(reason(db, "noreply")), "no-reply sender hidden");
        check(noise(db, "list") == 1 && "Mailing list".equals(reason(db, "list")), "bulk mail without a category hidden");
        check(noise(db, "spam") == 1 && "Gmail: Spam".equals(reason(db, "spam")), "spam hidden");
        check(noise(db, "personal") == 0 && noise(db, "sent") == 0, "personal mail and your own mail stay");
        check(noise(db, "bot1") == 1 && "Slack bot".equals(reason(db, "bot1")), "Slack bot noise hidden");
        check(noise(db, "bot2") == 0, "a Slack bot mentioning your name stays");
        check(noise(db, "w1") == 0, "chats are never hidden, whatever they say");

        // Writing in a thread makes it memory again; a sender you've written to is someone you talk to.
        store(db, mail("promo-reply", "t1", "gabriel@example.com", "\"SENT\"", false, "Ainda tem o 42?", NOW - HOUR));
        check(noise(db, "promo") == 0, "you replied in the promotion's thread: it's memory again");
        store(db, mail("promo2", "t9", "ofertas@loja.com", "\"INBOX\",\"CATEGORY_PROMOTIONS\"", true, "Nova coleção", NOW));
        check(noise(db, "promo2") == 0, "the next mail from a sender you've written to stays");
    }

    static void surfaces() throws Exception {
        Db db = db();
        store(db,
                mail("promo", "t1", "ofertas@loja.com", "\"CATEGORY_PROMOTIONS\"", true, "Contrato de fidelidade com desconto", NOW - 5 * HOUR),
                mail("personal", "t2", "ana@example.com", "\"INBOX\"", false, "O contrato está assinado", NOW - 4 * HOUR),
                RawItem.builder("whatsapp", "w1").kind(RawItem.MESSAGE).timestamp(NOW - 3 * HOUR).text("Contrato ok?")
                        .conversation("c:ana", "Ana", "dm").author("name:Ana", "Ana", false).build());
        List<Long> feed = new ArrayList<>();
        for (Items.Item i : Items.recent(db, 20)) feed.add(i.id);
        long promo = id(db, "promo");
        check(!feed.contains(promo) && feed.size() == 2, "home feed leaves hidden mail out");
        check(Search.find(db, "contrato", 30).size() == 2, "search shows memory only");
        check(Search.hiddenMatches(db, "contrato") == 1, "and counts the hidden match");
        check(Search.find(db, "contrato", 30, true).size() == 3, "Show includes it");
        KnowledgeTools tools = new KnowledgeTools(db, ZONE);
        check(!tools.searchAny(List.of("contrato"), 12).contains("#" + promo + "]"), "on-phone Ask sources leave it out");
        String claude = tools.search("fidelidade", null, null, null, null, 10);
        check(claude.startsWith("No matches.") && claude.contains("1 match") && claude.contains("include_hidden"),
                "Claude's search says a hidden match exists: " + claude);
        check(tools.run(KnowledgeTools.SEARCH, new java.util.HashMap<>(java.util.Map.of("query", "fidelidade", "include_hidden", true)))
                .contains("#" + promo + "]"), "include_hidden finds it");
        check(!tools.timeline(NOW - 10 * HOUR, NOW, null, 50).contains("#" + promo + "]"), "timeline leaves it out");
        String digest = Digest.build(db, java.time.Instant.ofEpochMilli(NOW - 4 * HOUR).atZone(ZONE).toLocalDate(), ZONE);
        check(digest.contains("Hidden from memory: 1 item") && !digest.contains("ofertas@loja.com"), "digest: counted, not listed:\n" + digest);
        String portrait = Portrait.build(db, NOW, ZONE);
        check(!portrait.contains("ofertas@loja.com"), "portrait leaves the sender out");
        boolean listed = false;
        for (People.Person p : People.list(db, 50)) listed |= p.name.contains("ofertas");
        check(!listed, "someone who only sends marketing isn't in People");
        Relevance.setEnabled(db, false);
        check(Search.find(db, "contrato", 30).size() == 3 && Items.recent(db, 20).size() == 3, "switch off: everything shows");
        Relevance.setEnabled(db, true);
    }

    static void overrides() throws Exception {
        Db db = db();
        store(db,
                mail("promo", "t1", "ofertas@loja.com", "\"CATEGORY_PROMOTIONS\"", true, "Promo", NOW - 5 * HOUR),
                mail("personal", "t2", "ana@example.com", "\"INBOX\"", false, "Oi", NOW - 4 * HOUR));
        Relevance.keep(db, id(db, "promo"));
        store(db, mail("promo", "t1", "ofertas@loja.com", "\"CATEGORY_PROMOTIONS\"", true, "Promo (edited)", NOW - 5 * HOUR));
        check(noise(db, "promo") == Relevance.KEPT_BY_YOU, "a re-sync never undoes your Keep");
        Relevance.hide(db, id(db, "personal"));
        store(db, mail("personal", "t2", "ana@example.com", "\"INBOX\"", false, "Oi (edited)", NOW - 4 * HOUR));
        check(noise(db, "personal") == Relevance.HIDDEN_BY_YOU, "a re-sync never undoes your Hide");
        Relevance.hideSender(db, "email:ana@example.com");
        store(db, mail("personal2", "t3", "ana@example.com", "\"INBOX\"", false, "De novo", NOW));
        check(noise(db, "personal2") == Relevance.HIDDEN_BY_YOU, "a hidden sender's new mail is hidden too");
        Relevance.keepSender(db, "email:ana@example.com");
        check(noise(db, "personal") == Relevance.KEPT_BY_YOU && noise(db, "personal2") == Relevance.KEPT_BY_YOU
                && Relevance.hiddenSenders(db).isEmpty(), "keeping the sender brings everything back");
        Relevance.setEnabled(db, false);
        Relevance.hide(db, id(db, "promo"));
        check(Items.recent(db, 20).size() == 2, "your own hides apply even with the switch off");
        Relevance.setEnabled(db, true);
    }

    static void backfill() throws Exception {
        Db db = db();
        // Stored before this existed: noise 0 everywhere.
        db.exec("INSERT INTO items(source, external_id, ts, kind, text, raw_json, ingested_at) VALUES"
                + "('composio.gmail', 'old-promo', 1, 'email', 'Sale', '{\"labelIds\":[\"CATEGORY_PROMOTIONS\"]}', 1),"
                + "('composio.gmail', 'old-mail', 2, 'email', 'Hi', '{\"labelIds\":[\"INBOX\"]}', 1),"
                + "('whatsapp', 'old-chat', 3, 'message', 'Oi', null, 1)");
        check(Relevance.backfill(db, 2) == 2 && Relevance.backfill(db, 2) == 1 && Relevance.backfill(db, 2) == 0,
                "backfill runs in batches, resumes, then stops");
        check(noise(db, "old-promo") == 1 && noise(db, "old-mail") == 0 && noise(db, "old-chat") == 0, "old mail judged from raw_json");
        check(Relevance.backfill(db, 2) == 0, "and never again");
        Enrichment.run(db, NOW, ZONE); // runs the backfill too (already done here)
        check(Relevance.hiddenCount(db) == 1, "hidden count");
    }

    static void claude() throws Exception {
        Db db = db();
        store(db,
                mail("promo", "t1", "ofertas@loja.com", "\"CATEGORY_PROMOTIONS\"", true, "Promo", NOW - 5 * HOUR),
                mail("digest", "t2", "team@saas.com", "\"INBOX\"", false, "Your weekly usage report", NOW - 4 * HOUR),
                mail("personal", "t3", "ana@example.com", "\"INBOX\"", false, "Oi", NOW - 3 * HOUR));
        Relevance.keep(db, id(db, "personal"));
        List<Extraction.Batch> batches = Extraction.batches(db, NOW, ZONE, 5);
        check(batches.size() == 1 && !batches.get(0).ids.contains(id(db, "promo")), "Claude doesn't read what rules hid");
        Extraction.Batch b = batches.get(0);
        String reply = "{\"entities\":[],\"relations\":[],\"facts\":[],\"noise\":[" + id(db, "digest") + "," + id(db, "personal")
                + "," + id(db, "promo") + ",999]}";
        Extraction.Counts c = Extraction.apply(db, b, reply, NOW);
        check(noise(db, "digest") == Relevance.CLAUDE && "Claude: not worth remembering".equals(reason(db, "digest")),
                "Claude hides what it judges not worth remembering");
        check(noise(db, "personal") == Relevance.KEPT_BY_YOU, "but never what you kept");
        check(noise(db, "promo") == Relevance.RULE, "ids outside the batch are ignored");
        check(c.noise == 2, "counts the batch's ids only: " + c.noise);
        store(db, mail("digest", "t2", "team@saas.com", "\"INBOX\"", false, "Your weekly usage report (v2)", NOW - 4 * HOUR));
        check(noise(db, "digest") == Relevance.CLAUDE, "a re-sync keeps Claude's verdict");
    }

    static long id(Db db, String external) throws Exception {
        return ((Number) db.query("SELECT id FROM items WHERE external_id = ?", external).get(0)[0]).longValue();
    }

    static void check(boolean ok, String label) {
        checks++;
        if (!ok) throw new AssertionError("FAILED: " + label);
    }
}
