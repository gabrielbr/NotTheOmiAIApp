package br.gabriel.sentient;

import br.gabriel.sentient.ComposioClient.ComposioException;
import br.gabriel.sentient.plugin.Http;
import br.gabriel.sentient.plugin.Mode;
import br.gabriel.sentient.plugin.PluginContext;
import br.gabriel.sentient.plugin.PullResult;
import br.gabriel.sentient.plugin.SourcePlugin;
import br.gabriel.sentient.plugin.SourceUnavailableException;

import java.io.IOException;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * One connected Composio toolkit (e.g. "composio.gmail"). Composio's servers fetch the data with
 * the person's key and hand it to GMind; GMind only ever runs the toolkit's read tools.
 * Plain Java: host tests drive it with a fake Http.
 */
final class ComposioPlugin implements SourcePlugin {
    /** PluginContext.secret name of the Composio API key. */
    static final String SECRET = "composio";
    /** Characters kept from a downloaded export; mappers cap the stored text further. */
    static final int MAX_DOWNLOAD = 1024 * 1024;

    private final ComposioToolkit toolkit;
    private final String userId, connectedAccountId, base;
    private final ZoneId zone;

    ComposioPlugin(ComposioToolkit toolkit, String userId, String connectedAccountId, String base, ZoneId zone) {
        this.toolkit = toolkit;
        this.userId = userId;
        this.connectedAccountId = connectedAccountId;
        this.base = base;
        this.zone = zone;
    }

    @Override public String id() { return toolkit.sourceId(); }
    @Override public String displayName() { return toolkit.displayName(); }
    @Override public Set<Mode> modes() { return EnumSet.of(Mode.PULL); }

    @Override public PullResult pull(PluginContext ctx, String cursor) throws Exception {
        String key = ctx.secret(SECRET);
        if (key == null) throw new SourceUnavailableException("Add your Composio API key in Connect sources");
        Http http = ctx.http();
        ComposioClient client = new ComposioClient(http, key, base);
        ComposioToolkit.Tools tools = new ComposioToolkit.Tools() {
            @Override public Object run(String slug, java.util.Map<String, Object> arguments) throws Exception {
                if (!toolkit.readTools().contains(slug)) throw new IllegalArgumentException("Not this toolkit's read tool");
                return client.execute(slug, userId, connectedAccountId, arguments);
            }
            @Override public String download(String url) throws Exception {
                if (!url.startsWith("https://")) throw new IOException("Only HTTPS downloads");
                Http.Response r = http.get(url, Collections.emptyMap());
                if (!r.ok()) throw new ComposioException(r.status, "Couldn't download an exported file");
                return r.body.length() > MAX_DOWNLOAD ? r.body.substring(0, MAX_DOWNLOAD) : r.body;
            }
        };
        try {
            return toolkit.pull(tools, cursor, ctx.now(), zone);
        } catch (ComposioException refused) {
            throw new SourceUnavailableException(refused.getMessage());
        } catch (IOException offline) {
            throw new SourceUnavailableException("Couldn't reach Composio · will retry on the next sync");
        }
    }
}
