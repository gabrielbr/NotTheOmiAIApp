package app.nottheomi.ai;

import java.util.List;

/** Host checks for the offline mixed PT/EN task finder. No Android, model or network. */
public final class TaskExtractorHostTest {
    public static void main(String[] args) {
        expect("Amanhã eu preciso ligar para o João sobre o contrato.", "Ligar para o João sobre o contrato");
        expect("Não esquecer de pagar o boleto da luz até sexta", "Pagar o boleto da luz até sexta");
        expect("Nao posso esquecer de comprar pão", "Comprar pão");
        expect("Tenho que mandar o relatório na segunda!", "Mandar o relatório na segunda");
        expect("Ficou combinado de enviar a proposta dia 15.", "Enviar a proposta dia 15");
        expect("Me lembra de renovar o passaporte", "Renovar o passaporte");
        expect("Temos que revisar o orçamento", "Revisar o orçamento");
        expect("I need to email Sarah the slides tomorrow.", "Email Sarah the slides tomorrow");
        expect("Don't forget to book the flight", "Book the flight");
        expect("Remind me to call the bank at 3pm", "Call the bank at 3pm");
        expect("We should follow up with the vendor", "Follow up with the vendor");
        expect("Follow up with Ana on the invoice", "Follow up with Ana on the invoice");

        // Mixed transcript: several tasks, order kept, duplicates removed.
        List<String> mixed = TaskExtractor.extract(
                "Bom dia pessoal. Preciso comprar café. Then the meeting started. "
                + "I have to fix the login bug; preciso comprar café\n"
                + "vou ligar pro Pedro e depois tenho que responder o email do cliente");
        check(mixed.size() == 3, "mixed count " + mixed);
        check(mixed.get(0).equals("Comprar café"), "mixed 0 " + mixed);
        check(mixed.get(1).equals("Fix the login bug"), "mixed 1 " + mixed);
        check(mixed.get(2).equals("Responder o email do cliente"), "mixed 2 " + mixed);

        // No cue, no task. Bare modal verbs without a speaker are not tasks.
        none("O tempo hoje está ótimo.");
        none("It should work fine now.");
        none("Preciso.");
        none("");
        none(null);

        // Length cap and accent folding stay index-aligned with the original text.
        String longTask = TaskExtractor.extract("Preciso " + "a".repeat(500)).get(0);
        check(longTask.length() == TaskExtractor.MAX_TASK_CHARS, "cap " + longTask.length());
        check(TaskExtractor.fold("NÃO Ação").equals("nao acao"), "fold");
        System.out.println("TaskExtractor host checks passed");
    }

    private static void expect(String transcript, String task) {
        List<String> tasks = TaskExtractor.extract(transcript);
        check(tasks.size() == 1 && tasks.get(0).equals(task), transcript + " -> " + tasks);
    }

    private static void none(String transcript) {
        List<String> tasks = TaskExtractor.extract(transcript);
        check(tasks.isEmpty(), transcript + " -> " + tasks);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
