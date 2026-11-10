package com.dlvoice.player;

import android.content.ContentResolver;
import android.net.Uri;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

final class Lrc {
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,6}):([0-5]\\d)(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\s*\\[offset:([+-]?\\d{1,9})\\]");

    static final class Cue {
        final long time;
        final String text;
        Cue(long time, String text) { this.time = time; this.text = text; }
    }

    static List<Cue> read(ContentResolver resolver, String uri) throws Exception {
        if (uri.isEmpty()) return new ArrayList<>();
        try (InputStream stream = resolver.openInputStream(Uri.parse(uri))) {
            if (stream == null) throw new java.io.IOException("无法读取字幕");
            byte[] bytes = Metadata.read(stream, 1_048_576);

            String content;
            if (bytes.length >= 2 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)
                    || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))) {
                content = new String(bytes, StandardCharsets.UTF_16);
            } else {
                try {
                    content = StandardCharsets.UTF_8.newDecoder()
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                } catch (java.nio.charset.CharacterCodingException malformed) {
                    content = new String(bytes, java.nio.charset.Charset.forName("GB18030"));
                }
            }
            return parse(content);
        }
    }

    static List<Cue> parse(String content) {
        content = content.replace("\uFEFF", "");
        var offsetTag = OFFSET.matcher(content);
        long offset = offsetTag.find() ? Long.parseLong(offsetTag.group(1)) : 0;
        List<Cue> cues = new ArrayList<>();
        for (String line : content.split("\\R")) {
            var matcher = TIME.matcher(line);
            List<Long> times = new ArrayList<>();
            int end = 0;
            while (matcher.find() && line.substring(end, matcher.start()).trim().isEmpty()) {
                String fraction = matcher.group(3);
                long ms = fraction == null ? 0 : Integer.parseInt((fraction + "000").substring(0, 3));
                times.add(Math.max(0, Long.parseLong(matcher.group(1)) * 60_000
                        + Integer.parseInt(matcher.group(2)) * 1000 + ms - offset));
                end = matcher.end();
            }
            String text = line.substring(end).trim();
            for (long time : times) cues.add(new Cue(time, text));
        }
        cues.sort(Comparator.comparingLong(cue -> cue.time));
        return cues;
    }

    static String at(List<Cue> cues, long position) {
        int low = 0;
        int high = cues.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (cues.get(mid).time <= position) low = mid + 1;
            else high = mid;
        }
        if (low == 0) return "";
        int start = low - 1;
        while (start > 0 && cues.get(start - 1).time == cues.get(low - 1).time) start--;
        StringBuilder text = new StringBuilder();
        for (int i = start; i < low; i++) {
            if (i > start) text.append('\n');
            text.append(cues.get(i).text);
        }
        return text.toString();
    }
}
