package br.gabriel.sentient;

import br.gabriel.sentient.ComposioClient.ComposioException;
import br.gabriel.sentient.plugin.Json;
import br.gabriel.sentient.plugin.PullResult;
import br.gabriel.sentient.plugin.RawItem;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Google Drive: files changed from 30 days ago on, oldest change first. Google Docs come in with
 * their text (exported as plain text); other files by name and type only.
 */
final class ComposioDrive extends ComposioToolkit {
    static final String FIND = "GOOGLEDRIVE_FIND_FILE", EXPORT = "GOOGLEDRIVE_EXPORT_GOOGLE_WORKSPACE_FILE";
    static final String GOOGLE_DOC = "application/vnd.google-apps.document";
    static final int PAGE = 20, MAX_TEXT = 20_000;
    static final String FIELDS = "nextPageToken,files(id,name,mimeType,modifiedTime,"
            + "lastModifyingUser(displayName,emailAddress,me),owners(displayName,emailAddress,me))";

    @Override String slug() { return "googledrive"; }
    @Override String displayName() { return "Google Drive"; }
    @Override String reads() { return "Files changed in the last 30 days on: the text of Google Docs, the name of everything else."; }
    @Override Set<String> readTools() { return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(FIND, EXPORT))); }

    @Override PullResult pull(Tools tools, String cursor, long now, ZoneId zone) throws Exception {
        Map<String, Object> state = state(cursor);
        String after = state == null ? null : Json.str(state.get("after"));
        if (after == null) after = iso(now - FIRST_SYNC_MS);
        String page = state == null ? null : Json.str(state.get("page"));
        String max = state == null ? null : Json.str(state.get("max"));

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("q", "modifiedTime > '" + after + "' and trashed = false");
        args.put("orderBy", "modifiedTime");
        args.put("pageSize", (long) PAGE);
        args.put("fields", FIELDS);
        if (page != null) args.put("pageToken", page);
        Object data = tools.run(FIND, args);

        List<RawItem> items = new ArrayList<>();
        for (Object file : array(data, "files")) {
            String content = null;
            if (GOOGLE_DOC.equals(Json.firstStr(file, "mimeType")) && Json.firstStr(file, "id") != null) {
                try {
                    content = exported(tools, tools.run(EXPORT, Json.object(
                            "fileId", Json.firstStr(file, "id"), "mimeType", "text/plain")));
                } catch (ComposioException oneDoc) {
                    content = null; // keep the file by name; the next change retries the text
                }
            }
            RawItem item = item(file, content, zone);
            if (item == null) continue;
            items.add(item);
            String modified = Json.firstStr(file, "modifiedTime");
            if (modified != null && (max == null || time(modified, zone) > time(max, zone))) max = modified;
        }
        String next = field(data, "nextPageToken", "next_page_token");
        if (next != null)
            return new PullResult(items, Json.write(Json.object("after", after, "page", next, "max", max)), true);
        return new PullResult(items, Json.write(Json.object("after", max != null ? max : after)), false);
    }

    /** The export's text, wherever the tool put it: inline, or behind a download URL. */
    static String exported(Tools tools, Object data) throws Exception {
        if (data instanceof String) return (String) data;
        String inline = field(data, "content", "text", "file_content", "exported_content");
        if (inline != null) return inline;
        for (Object scope : new Object[]{data, Json.at(data, "file"), Json.at(data, "downloaded_file_content"),
                Json.at(data, "response_data")}) {
            String url = Json.firstStr(scope, "s3url", "s3_url", "url", "download_url");
            if (url != null && url.startsWith("https://")) return tools.download(url);
        }
        return null;
    }

    static RawItem item(Object f, String content, ZoneId zone) {
        String id = Json.firstStr(f, "id");
        long ts = time(Json.at(f, "modifiedTime"), zone);
        if (id == null || ts == 0) return null;
        if ("application/vnd.google-apps.folder".equals(Json.firstStr(f, "mimeType"))) return null;
        String name = Json.firstStr(f, "name");
        if (name == null) name = "(untitled)";
        String mime = Json.firstStr(f, "mimeType");
        StringBuilder text = new StringBuilder(name);
        if (content != null && !content.trim().isEmpty()) text.append("\n\n").append(content.trim());
        else if (mime != null) text.append("\n(").append(kind(mime)).append(')');
        Object editor = Json.at(f, "lastModifyingUser");
        String email = Json.firstStr(editor, "emailAddress");
        RawItem.Builder b = RawItem.builder("composio.googledrive", id)
                .kind(RawItem.DOC)
                .timestamp(ts)
                .text(cap(text.toString(), MAX_TEXT))
                .rawJson(Json.write(f));
        if (email != null)
            b.author("email:" + email.toLowerCase(Locale.ROOT), Json.firstStr(editor, "displayName"),
                    Json.bool(Json.at(editor, "me")));
        return b.build();
    }

    static String kind(String mime) {
        switch (mime) {
            case GOOGLE_DOC: return "Google Doc";
            case "application/vnd.google-apps.spreadsheet": return "Google Sheet";
            case "application/vnd.google-apps.presentation": return "Google Slides";
            case "application/vnd.google-apps.folder": return "Folder";
            case "application/pdf": return "PDF";
            default: return mime;
        }
    }
}
