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
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;

import java.time.Duration;
import java.util.Map;

/**
 * Enrichment with Claude Haiku 5.5: one batch of items in, the Extraction schema out (structured
 * outputs, so the reply is always valid JSON). Low effort: this is extraction, not reasoning.
 * The fixed instructions and schema are cached; only the batch changes between requests.
 */
final class ClaudeExtractor implements Extraction.Extractor {
    static final String MODEL = AskSettings.HAIKU;
    private static final long MAX_TOKENS = 16_000L;
    private final AnthropicClient client;
    private final JsonOutputFormat format;

    ClaudeExtractor(String apiKey, String baseUrl) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
                .apiKey(apiKey).maxRetries(2).timeout(Duration.ofSeconds(180));
        if (baseUrl != null) builder.baseUrl(baseUrl);
        client = builder.build();
        JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
        for (Map.Entry<String, Object> e : Extraction.SCHEMA.entrySet()) schema.putAdditionalProperty(e.getKey(), JsonValue.from(e.getValue()));
        format = JsonOutputFormat.builder().schema(schema.build()).build();
    }

    /** The reply's JSON, or null when Claude declined this batch. */
    @Override public String extract(String batch) throws Exception {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .system(Extraction.SYSTEM)
                .outputConfig(OutputConfig.builder().format(format).effort(OutputConfig.Effort.LOW).build())
                .cacheControl(CacheControlEphemeral.builder().build())
                .addUserMessage("Items:\n" + batch)
                .build();
        Message reply;
        try {
            reply = client.messages().create(params);
        } catch (UnauthorizedException | PermissionDeniedException denied) {
            throw new ClaudeBackend.AskException("Claude refused the API key. Check it in Ask settings.");
        } catch (RateLimitException busy) {
            throw new ClaudeBackend.AskException("Claude is busy (rate limit); enrichment continues next sync.");
        } catch (AnthropicServiceException failed) {
            throw new ClaudeBackend.AskException("Claude couldn't take the enrichment request (" + failed.statusCode() + ").");
        } catch (AnthropicIoException offline) {
            throw new ClaudeBackend.AskException("Couldn't reach Claude; enrichment continues next sync.");
        }
        if (StopReason.REFUSAL.equals(reply.stopReason().orElse(null))) return null;
        StringBuilder text = new StringBuilder();
        for (ContentBlock block : reply.content()) block.text().ifPresent(t -> text.append(t.text()));
        // A reply cut off at max_tokens isn't valid JSON; Extraction.run counts it as dropped.
        return text.toString();
    }
}
