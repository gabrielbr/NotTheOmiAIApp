package br.gabriel.sentient;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Answers with Claude through the official SDK: a manual tool loop over KnowledgeTools. Only the
 * question and the tool results Claude asks for leave the phone. Read-only: every tool reads.
 */
final class ClaudeBackend implements LlmBackend {
    static final int MAX_ROUNDS = 8;
    private static final long MAX_TOKENS = 16_000L;
    private static final List<Tool> TOOLS = Collections.unmodifiableList(Arrays.asList(
            tool(KnowledgeTools.SEARCH, AskPrompts.SEARCH_DESCRIPTION, Collections.emptyList(),
                    prop("query", "string", "Words to find"),
                    prop("source", "string", "A source id, e.g. whatsapp, matrix or composio.gmail"),
                    prop("person", "string", "A person's or chat's name"),
                    prop("from", "string", "First day, YYYY-MM-DD"),
                    prop("to", "string", "Last day, YYYY-MM-DD"),
                    prop("limit", "integer", "How many lines, up to 20")),
            tool(KnowledgeTools.CONVERSATION, AskPrompts.CONVERSATION_DESCRIPTION, Collections.singletonList("item_id"),
                    prop("item_id", "integer", "The id from [#id]"),
                    prop("around", "integer", "Messages before and after, up to 15")),
            tool(KnowledgeTools.PEOPLE, AskPrompts.PEOPLE_DESCRIPTION, Collections.singletonList("name"),
                    prop("name", "string", "Part of a name")),
            tool(KnowledgeTools.TIMELINE, AskPrompts.TIMELINE_DESCRIPTION, Arrays.asList("from", "to"),
                    prop("from", "string", "First day, YYYY-MM-DD"),
                    prop("to", "string", "Last day, YYYY-MM-DD"),
                    prop("source", "string", "A source id, e.g. whatsapp, matrix or composio.gmail"),
                    prop("limit", "integer", "How many lines, up to 50")),
            tool(KnowledgeTools.ABOUT, AskPrompts.ABOUT_DESCRIPTION, Collections.singletonList("name"),
                    prop("name", "string", "A name or part of it"))));

    private final AnthropicClient client;
    private final String model;
    private final KnowledgeTools tools;
    private final ZoneId zone;
    private final String portrait;
    /** Room for the portrait in each request; it's a summary, the tools hold the details. */
    static final int PORTRAIT_CHARS = 6000;

    ClaudeBackend(String apiKey, String model, KnowledgeTools tools, ZoneId zone, String baseUrl) {
        this(apiKey, model, tools, zone, baseUrl, null);
    }

    ClaudeBackend(String apiKey, String model, KnowledgeTools tools, ZoneId zone, String baseUrl, String portrait) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey).maxRetries(2).timeout(Duration.ofSeconds(120));
        if (baseUrl != null) builder.baseUrl(baseUrl);
        this.client = builder.build();
        this.model = model;
        this.tools = tools;
        this.zone = zone;
        this.portrait = portrait;
    }

    @Override public String name() { return AskSettings.modelName(model); }

    @Override public Answer answer(List<Turn> history, String question, Listener listener, BooleanSupplier cancelled)
            throws Exception {
        List<MessageParam> messages = new ArrayList<>();
        for (Turn turn : history) {
            messages.add(text(MessageParam.Role.USER, turn.question));
            messages.add(text(MessageParam.Role.ASSISTANT, turn.answer));
        }
        messages.add(text(MessageParam.Role.USER, question));
        // The date changes daily, so it goes in a mid-conversation system message, after the
        // cached system prompt and tools, instead of in them.
        messages.add(text(MessageParam.Role.SYSTEM, "Today is " + LocalDate.now(zone) + "." + portraitNote(portrait)));

        StringBuilder answer = new StringBuilder();
        for (int round = 0; round < MAX_ROUNDS; round++) {
            if (cancelled.getAsBoolean()) throw new AskException("Stopped.");
            listener.status(round == 0 ? "Thinking…" : "Reading what it found…");
            Message response = create(messages);
            messages.add(response.toParam());
            answer.setLength(0);
            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                block.text().ifPresent(t -> answer.append(t.text()));
                if (block.toolUse().isPresent()) results.add(runTool(block.toolUse().get(), listener));
            }
            StopReason stop = response.stopReason().orElse(StopReason.END_TURN);
            if (StopReason.REFUSAL.equals(stop))
                return new Answer(answer.toString(), "Claude declined to answer this one.");
            if (StopReason.MAX_TOKENS.equals(stop))
                return new Answer(answer.toString(), "The answer was cut off because it got too long.");
            if (results.isEmpty()) return new Answer(answer.toString().trim(), null);
            messages.add(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(results).build());
        }
        return new Answer(answer.toString().trim(), "Stopped after " + MAX_ROUNDS + " lookups without a final answer.");
    }

    private Message create(List<MessageParam> messages) throws AskException {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .system(AskPrompts.SYSTEM)
                .tools(toolUnions())
                // Caches the fixed system prompt and tools, and the conversation so far.
                .cacheControl(CacheControlEphemeral.builder().build())
                .messages(messages)
                .build();
        try {
            return client.messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException denied) {
            throw new AskException("Claude refused the API key. Check it in Ask settings.");
        } catch (RateLimitException busy) {
            throw new AskException("Claude is busy (rate limit). Try again in a minute.");
        } catch (AnthropicServiceException failed) {
            throw new AskException(failed.statusCode() >= 500 ? "Claude is unavailable right now. Try again later."
                    : "Claude couldn't take this request (" + failed.statusCode() + ").");
        } catch (AnthropicIoException offline) {
            throw new AskException("Couldn't reach Claude. Check your internet connection.");
        }
    }

    private ContentBlockParam runTool(ToolUseBlock use, Listener listener) {
        listener.status(statusFor(use.name()));
        String result;
        boolean error = false;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> input = use._input().convert(Map.class);
            result = tools.run(use.name(), input == null ? Collections.emptyMap() : input);
        } catch (IllegalArgumentException bad) {
            result = bad.getMessage();
            error = true;
        } catch (Exception failed) {
            result = "The knowledge base couldn't run that (" + failed.getClass().getSimpleName() + ").";
            error = true;
        }
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(use.id()).content(result).isError(error).build());
    }

    /** The user's portrait as background, after the cached prefix (it changes daily). */
    static String portraitNote(String portrait) {
        if (portrait == null || portrait.trim().isEmpty()) return "";
        String p = portrait.length() > PORTRAIT_CHARS ? portrait.substring(0, PORTRAIT_CHARS) + "…" : portrait;
        return "\n\nGMind's portrait of the user, rebuilt after each sync. Use it as background to know who they are "
                + "and who matters to them; its [#id] citations are real items you can cite or open with the tools:\n\n" + p;
    }

    static String statusFor(String tool) {
        switch (tool) {
            case KnowledgeTools.CONVERSATION: return "Reading a conversation…";
            case KnowledgeTools.PEOPLE: return "Looking up people…";
            case KnowledgeTools.TIMELINE: return "Going through a timeline…";
            case KnowledgeTools.ABOUT: return "Checking what GMind knows about it…";
            default: return "Searching messages and recordings…";
        }
    }

    private static MessageParam text(MessageParam.Role role, String text) {
        return MessageParam.builder().role(role).content(text).build();
    }

    private static List<com.anthropic.models.messages.ToolUnion> toolUnions() {
        List<com.anthropic.models.messages.ToolUnion> unions = new ArrayList<>();
        for (Tool t : TOOLS) unions.add(com.anthropic.models.messages.ToolUnion.ofTool(t));
        return unions;
    }

    @SafeVarargs
    private static Tool tool(String name, String description, List<String> required, Map.Entry<String, Object>... props) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        for (Map.Entry<String, Object> p : props) properties.putAdditionalProperty(p.getKey(), JsonValue.from(p.getValue()));
        return Tool.builder().name(name).description(description)
                .inputSchema(Tool.InputSchema.builder().properties(properties.build()).required(required).build())
                .build();
    }

    private static Map.Entry<String, Object> prop(String name, String type, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        schema.put("description", description);
        return new java.util.AbstractMap.SimpleImmutableEntry<>(name, schema);
    }

    /** A failure with a message meant for the person asking. */
    static final class AskException extends Exception {
        private static final long serialVersionUID = 1L;
        AskException(String message) { super(message); }
    }
}
