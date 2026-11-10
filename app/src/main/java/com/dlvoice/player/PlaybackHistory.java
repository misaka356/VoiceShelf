package com.dlvoice.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.*;

final class PlaybackHistory {
    private static long revision;

    static long revision() { return revision; }

    static final class Entry {
        final long playedAt;
        final int count;

        Entry(long playedAt, int count) {
            this.playedAt = playedAt;
            this.count = count;
        }
    }

    private static SharedPreferences store(Context context) {
        return context.getSharedPreferences("playbackHistory", Context.MODE_PRIVATE);
    }

    private static Entry decode(String value) {
        try {
            JSONArray data = new JSONArray(value);
            return new Entry(data.getLong(0), data.getInt(1));
        } catch (JSONException invalid) {
            return new Entry(0, 0);
        }
    }

    static void record(Context context, String uri) {
        SharedPreferences history = store(context);
        Entry previous = decode(history.getString(uri, "[]"));
        JSONArray next = new JSONArray().put(System.currentTimeMillis()).put(previous.count + 1);
        history.edit().putString(uri, next.toString()).apply();
        revision++;
    }

    static Map<String, Entry> read(Context context) {
        Map<String, Entry> result = new HashMap<>();
        for (Map.Entry<String, ?> item : store(context).getAll().entrySet()) {
            if (!(item.getValue() instanceof String)) continue;
            Entry entry = decode((String) item.getValue());
            if (entry.count > 0) result.put(item.getKey(), entry);
        }
        return result;
    }

    static void remove(Context context, Set<Uri> deleted) {
        SharedPreferences history = store(context);
        SharedPreferences.Editor edit = history.edit();
        for (String uri : history.getAll().keySet()) {
            if (Library.containsDocument(deleted, uri)) edit.remove(uri);
        }
        edit.apply();
        revision++;
    }

    private PlaybackHistory() {}
}
