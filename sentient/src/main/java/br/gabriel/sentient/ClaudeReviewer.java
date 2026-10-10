package br.gabriel.sentient;

import br.gabriel.sentient.plugin.Json;

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
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;

/** The overnight review with Claude Haiku 5.5: a batch of items in, {"noise": [ids]} out. */
final class ClaudeReviewer implements Review.Reviewer {
    static final String MODEL = AskSettings.HAIKU;
    static final Map<String, Object> SCHEMA = Json.object(
            "type", "object", "additionalProperties", Boolean.FALSE, "required", new ArrayList<Object>(java.util.List.of("noise")),
            "properties", Json.object("noise", Json.object("type", "array", "items", Json.object("type", "integer"),
                    "description", "Ids of items not worth remembering, like 123 for [#123]")));
    private final AnthropicClient client;
    private final JsonOutputFormat format;

    ClaudeReviewer(String apiKey, String baseUrl) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey).maxRetries(2).timeout(Duration.ofSeconds(120));
        if (baseUrl != null) builder.baseUrl(baseUrl);
        client = builder.build();
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        for (Map.Entry<String, Object> e : SCHEMA.entrySet()) schema.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
        format = JsonOutputFormat.builder().schema(schema.build()).build();
    }

    @Override public String name() { return "Claude"; }
    @Override public int batchChars() { return 14_000; }
    @Override public int itemChars() { return 500; }

    @Override public String review(String items) throws Exception {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(4_000L)
                .system(Review.SYSTEM)
                .outputConfig(OutputConfig.builder().format(format).effort(OutputConfig.Effort.LOW).build())
                .cacheControl(CacheControlEphemeral.builder().build())
                .addUserMessage("Items:\n" + items)
                .build();
        Message reply;
        try {
            reply = client.messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException denied) {
            throw new ClaudeBackend.AskException("Claude refused the API key. Check it in Ask settings.");
        } catch (RateLimitException busy) {
            throw new ClaudeBackend.AskException("Claude is busy (rate limit); the review continues next time the phone charges.");
        } catch (AnthropicServiceException failed) {
            throw new ClaudeBackend.AskException("Claude couldn't take the review (" + failed.statusCode() + ").");
        } catch (AnthropicIoException offline) {
            throw new ClaudeBackend.AskException("Couldn't reach Claude; the review continues next time the phone charges.");
        }
        if (StopReason.REFUSAL.equals(reply.stopReason().orElse(null))) return null;
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : reply.content()) block.text().ifPresent(t -> text.append(t.text()));
        return text.toString();
    }
}
