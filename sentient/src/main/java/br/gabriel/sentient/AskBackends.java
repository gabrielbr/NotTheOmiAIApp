package br.gabriel.sentient;

import android.content.Context;

import java.time.ZoneId;

/** Builds the backend the settings choose, or says what setup is missing. */
final class AskBackends {
    private AskBackends() {}

    static boolean local(Context c) { return AskSettings.LOCAL.equals(AskSettings.backend(c)); }

    /** What the Ask screen should ask the user to do first, or null when ready. */
    static String missingSetup(Context c) {
        if (local(c)) {
            if (!LocalModel.ready(c)) return "Download the on-device model (" + LocalModel.gb(LocalModel.BYTES)
                    + ") to ask without the internet. Nothing you ask leaves the phone.";
            return null;
        }
        if (AskSettings.apiKey(c) == null)
            return "Add your Claude API key to ask questions. Only your question and the messages Claude looks up are sent.";
        return null;
    }

    static LlmBackend create(Context c) throws Exception {
        Db db = KnowledgeStore.get(c);
        if (local(c)) return new LocalBackend(c, db);
        ZoneId zone = ZoneId.systemDefault();
        String key = AskSettings.apiKey(c);
        if (key == null) throw new ClaudeBackend.AskException("Add your Claude API key in Ask settings.");
        return new ClaudeBackend(key, AskSettings.model(c), new KnowledgeTools(db, zone), zone, null);
    }

    /** The one-line footer: who answers and where the data goes. */
    static String footer(Context c) {
        if (local(c)) return "Answered on this phone by " + LocalModel.NAME + ". Nothing leaves the phone.";
        return "Answered by " + AskSettings.modelName(AskSettings.model(c))
                + ". Your question and the messages it looks up are sent to Anthropic.";
    }
}
