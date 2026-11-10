package com.dlvoice.player;

import android.content.*;
import android.text.Html;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

final class Metadata {
    enum Result {
        COMPLETE(R.string.text_work_information_complete), PARTIAL(R.string.text_available_information_saved),
        COVER_FAILED(R.string.text_text_saved_cover_download_failed), UNAVAILABLE(R.string.text_dlsite_did_not_return_recognizable_work_informat),
        FAILED(R.string.text_lookup_failed_check_the_network_and_retry);
        final int label;
        Result(int label) { this.label = label; }
    }
    private static final Pattern META = Pattern.compile("(?is)<meta\\s+([^>]+)>");
    private static final Pattern ATTR = Pattern.compile("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");
    private static final Pattern ROW = Pattern.compile("(?is)<th\\b[^>]*>(.*?)</th>\\s*<td\\b[^>]*>(.*?)</td>");
    private static final Pattern TITLE = Pattern.compile("(?is)<h1\\b[^>]*>(.*?)</h1>");
    private static final Pattern CIRCLE = Pattern.compile("\\s*\\[([^\\[\\]]+)]\\s*\\|\\s*DLsite.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CV = Pattern.compile("(?i)(?:^|[♪\\s【（(])CV[：:.\\s]*([\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー々]{2,16})(?=[「」】）)♪。\\s]|$)");

    static String onlineId(String id) { return id.length() == 9 ? "RJ0" + id.substring(2) : id; }
    static String key(String id) { return "metadata:" + onlineId(id); }
    static File cover(Context context, String id) { return new File(context.getFilesDir(), onlineId(id) + ".cover"); }

    static JSONObject saved(SharedPreferences prefs, String id) {
        try { return new JSONObject(prefs.getString(key(id), "{}")); }
        catch (JSONException ignored) { return new JSONObject(); }
    }

    static boolean complete(Context context, SharedPreferences prefs, String id) {
        JSONObject info = saved(prefs, id);
        return !info.optString("title").trim().isEmpty() && !info.optString("circle").trim().isEmpty()
                && !info.optString("actors").trim().isEmpty() && cover(context, id).isFile();
    }

    static Result update(Context context, SharedPreferences prefs, String id) throws Exception {
        String work = onlineId(id);
        Map<String, String> fields = null;
        String coverUrl = null;
        for (String section : new String[]{"maniax", "home", "girls"}) {
            HttpURLConnection request = connect(new URL("https://www.dlsite.com/" + section + "/work/=/product_id/" + work + ".html"));
            try {
                int status = request.getResponseCode();
                if (status == 404) continue;
                if (status != 200) throw new IOException("HTTP " + status);
                URL destination = request.getURL();
                String query = destination.getQuery();
                boolean sameWork = destination.getPath().matches(".*/product_id/" + work + "\\.html/?");
                boolean translation = query != null && Arrays.asList(query.split("&")).contains("translation=" + work);
                if (!destination.getHost().equalsIgnoreCase("www.dlsite.com") || !(sameWork || translation)) continue;
                String html;
                try (InputStream input = request.getInputStream()) {
                    html = new String(read(input, 5_000_000), StandardCharsets.UTF_8);
                }
                Map<String, String> meta = metadata(html);
                String ogTitle = meta.getOrDefault("og:title", "");
                String image = meta.getOrDefault("og:image", "");
                if (ogTitle.isEmpty() || image.isEmpty()) continue;
                fields = fields(html, meta);
                coverUrl = new URL(destination, image).toString();
                break;
            } finally { request.disconnect(); }
        }
        if(fields==null)return Result.UNAVAILABLE;
        synchronized (Metadata.class) {
            JSONObject saved = saved(prefs, id);
            for (Map.Entry<String, String> field : fields.entrySet()) {
                if (!saved.optBoolean("manual_" + field.getKey())) saved.put(field.getKey(), field.getValue());
            }
            prefs.edit().putString(key(id), saved.toString()).apply();
        }
        try {
            download(coverUrl, cover(context, id));
        } catch (IOException e) {return Result.COVER_FAILED; }
        return complete(context, prefs, id) ? Result.COMPLETE : Result.PARTIAL;
    }

    private static Map<String, String> metadata(String html) {
        Map<String, String> result = new HashMap<>();
        Matcher tags = META.matcher(html);
        while (tags.find()) {
            Matcher attributes = ATTR.matcher(tags.group(1));
            Map<String, String> values = new HashMap<>();
            while (attributes.find()) {
                values.put(attributes.group(1).toLowerCase(Locale.ROOT),
                        attributes.group(2) == null ? attributes.group(3) : attributes.group(2));
            }
            String key = values.getOrDefault("property", values.get("name"));
            if (key != null && values.containsKey("content")) result.put(key, plain(values.get("content")));
        }
        return result;
    }

    private static Map<String, String> fields(String html, Map<String, String> meta) {
        Map<String, String> result = new LinkedHashMap<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            String label = plain(rows.group(1));
            String value = plain(rows.group(2)).replaceAll("\\s*/\\s*", "、");
            if ("サークル名".equals(label) && !value.isEmpty()) result.put("circle", value);
            if (("声優".equals(label) || "CV".equals(label)) && !value.isEmpty()) result.put("actors", value);
        }
        String ogTitle = meta.getOrDefault("og:title", "");
        Matcher circle = CIRCLE.matcher(ogTitle);
        String title = ogTitle.replaceFirst("\\s*\\|\\s*DLsite.*$", "");
        if (circle.find()) {
            result.putIfAbsent("circle", circle.group(1).trim());
            title = ogTitle.substring(0, circle.start()).trim();
        }
        Matcher heading = TITLE.matcher(html);
        if (heading.find()) title = plain(heading.group(1));
        if (!title.isEmpty()) result.put("title", title);
        Matcher cv = CV.matcher(meta.getOrDefault("og:description", meta.getOrDefault("description", "")));
        if (cv.find()) result.putIfAbsent("actors", cv.group(1));
        return result;
    }

    private static String plain(String html) {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().replaceAll("\\s+", " ").trim();
    }

    private static HttpURLConnection connect(URL url) throws IOException {
        HttpURLConnection request = (HttpURLConnection) url.openConnection();
        request.setConnectTimeout(8000);
        request.setReadTimeout(8000);
        request.setRequestProperty("User-Agent", "DLVoiceAndroid/1.0");
        return request;
    }

    private static void download(String source, File target) throws IOException {
        URL url = new URL(source);
        if (!"https".equals(url.getProtocol())) throw new IOException("invalid cover URL");
        HttpURLConnection request = connect(url);
        try {
            String type = request.getContentType();
            if (request.getResponseCode() != 200 || type == null || !type.startsWith("image/")) {
                throw new IOException("invalid cover response");
            }
            byte[] data;
            try (InputStream input = request.getInputStream()) { data = read(input, 10_000_000); }
            if (data.length == 0) throw new IOException("empty cover");
            AtomicFile file = new AtomicFile(target);
            FileOutputStream output = file.startWrite();
            try { output.write(data); file.finishWrite(output); }
            catch (IOException e) { file.failWrite(output); throw e; }
        } finally { request.disconnect(); }
    }

    static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] block = new byte[8192];
        int count;
        while ((count = input.read(block)) != -1) {
            if (count > limit - bytes.size()) throw new IOException("文件过大");
            bytes.write(block, 0, count);
        }
        return bytes.toByteArray();
    }
}
