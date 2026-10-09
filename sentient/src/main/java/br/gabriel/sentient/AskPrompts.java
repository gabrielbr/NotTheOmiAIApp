package br.gabriel.sentient;

/** The instructions both backends follow. Fixed text, so Claude's prompt cache stays valid. */
public final class AskPrompts {
    private AskPrompts() {}

    public static final String SYSTEM =
            "You answer questions about the user's own life using GMind, a private knowledge base on their phone. "
            + "It holds transcripts of their recordings (source omi.transcripts); messages from WhatsApp "
            + "(whatsapp), Signal (signal), Telegram (telegram) and Matrix (matrix); and, when connected, Gmail "
            + "emails (composio.gmail), Google Calendar events (composio.googlecalendar) and Google Drive files "
            + "(composio.googledrive), Slack messages (composio.slack), and Todoist and TickTick tasks "
            + "(composio.todoist, composio.ticktick).\n\n"
            + "Look things up with the tools before answering, and answer only from what they return. Each line "
            + "a tool returns starts with an id like [#123]. Cite every fact with the ids it came from, right after "
            + "the sentence, for example: \"Ana suggested lunch on Sunday [#41].\" If the tools don't show the answer, "
            + "say you couldn't find it instead of guessing.\n\n"
            + "Items marked \"me\" were written, sent or organized by the user. Answer in the language of the question, briefly, "
            + "in plain sentences.";

    /** For the on-device model, which gets the sources in the prompt instead of tools. */
    public static final String LOCAL_SYSTEM =
            "You answer questions about the user's own life from the sources below, which come from their "
            + "recordings, messages, emails, calendar events, files and tasks. Each source starts with an id like [#123]. Use only these "
            + "sources and cite the ids you used right after each sentence, like [#123]. If the sources don't "
            + "contain the answer, say you couldn't find it. Messages marked \"me\" are the user's own. Answer in the "
            + "language of the question, in two or three short sentences.";

    public static final String SEARCH_DESCRIPTION =
            "Full-text search over everything: recordings, messages, emails, events, files and tasks (accent-insensitive, "
            + "word prefixes). Returns up to "
            + "`limit` lines like \"[#id] YYYY-MM-DD HH:MM · source · chat · author: snippet\". Narrow with source "
            + "(omi.transcripts, whatsapp, signal, telegram, matrix, composio.gmail, composio.googlecalendar, "
            + "composio.googledrive, composio.slack, composio.todoist, composio.ticktick), person (a name), and from/to dates (YYYY-MM-DD, inclusive). "
            + "With only a person, lists their latest items.";
    public static final String CONVERSATION_DESCRIPTION =
            "Shows an item in context: the items before and after it in the same chat, email thread or calendar, "
            + "oldest first. Use it to read a whole exchange around a search hit.";
    public static final String PEOPLE_DESCRIPTION =
            "Finds people by name: which apps they appear in, how many items, when last, and their chats.";
    public static final String ABOUT_DESCRIPTION =
            "Looks up what GMind learned about a person, organization, project, place or topic: lasting facts, how it "
            + "relates to others, and the latest items mentioning it. Use it for questions about who someone is or what "
            + "a project is.";
    public static final String TIMELINE_DESCRIPTION =
            "Lists everything between two dates (YYYY-MM-DD, inclusive), oldest first, optionally from one source. "
            + "Use it for questions like \"what happened on Tuesday\".";
}
