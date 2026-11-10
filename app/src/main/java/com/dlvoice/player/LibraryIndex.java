package com.dlvoice.player;

import android.net.Uri;
import org.json.*;
import java.util.*;

final class LibraryIndex {
    static String encode(List<Library.Work> works) throws JSONException {
        JSONArray array = new JSONArray();
        for (Library.Work work : works) {
            JSONArray tracks = new JSONArray();
            for (Library.Track track : work.tracks) tracks.put(new JSONArray(Arrays.asList(track.name, track.uri, track.lrc)));
            array.put(new JSONObject().put("id", work.id).put("source", work.source).put("tracks", tracks)
                    .put("identity", work.identity).put("addedAt", work.addedAt).put("modifiedAt", work.modifiedAt));
        }
        return array.toString();
    }

    static List<Library.Work> decode(String json) throws JSONException {
        JSONArray array = new JSONArray(json);
        List<Library.Work> works = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject entry = array.getJSONObject(i);
            Library.Work work = new Library.Work(entry.getString("id"), entry.getString("source"));
            work.identity = entry.optString("identity");
            work.addedAt = entry.optLong("addedAt");
            work.modifiedAt = entry.optLong("modifiedAt");
            JSONArray tracks = entry.getJSONArray("tracks");
            for (int j = 0; j < tracks.length(); j++) {
                JSONArray track = tracks.getJSONArray(j);
                work.tracks.add(new Library.Track(track.getString(0), track.getString(1), track.getString(2)));
            }
            if (!work.tracks.isEmpty()) works.add(work);
        }
        return works;
    }

    static boolean removeDocuments(List<Library.Work> works, Set<Uri> deleted) {
        boolean changed = false;
        for (Library.Work work : works) {
            changed |= work.tracks.removeIf(track -> Library.containsDocument(deleted, track.uri));
            for (int i = 0; i < work.tracks.size(); i++) {
                Library.Track track = work.tracks.get(i);
                if (Library.containsDocument(deleted, track.lrc)) {
                    work.tracks.set(i, new Library.Track(track.name, track.uri, ""));
                    changed = true;
                }
            }
        }
        works.removeIf(work -> work.tracks.isEmpty());
        return changed;
    }

    static List<Library.Work> merge(List<Library.Work> old, List<Library.Work> fresh, Library scanner) {
        long now = System.currentTimeMillis();
        Set<Library.Work> matched = new HashSet<>();
        for (Library.Work work : fresh) {
            Library.Work previous = null;
            for (Library.Work candidate : old) {
                if ((!work.identity.isEmpty() && work.identity.equals(candidate.identity))
                        || work.source.equals(candidate.source)) { previous = candidate; break; }
            }
            if (previous == null && work.numbered()
                    && fresh.stream().filter(item -> item.id.equals(work.id)).count() == 1
                    && old.stream().filter(item -> item.id.equals(work.id)).count() == 1) {
                previous = old.stream().filter(item -> item.id.equals(work.id)).findFirst().get();
            }
            work.addedAt = previous != null && previous.addedAt > 0 ? previous.addedAt : now;
            if (previous == null) continue;
            matched.add(previous);
            if (scanner.affectedWorks.contains(work.identity)) {
                Set<String> uris = new HashSet<>();
                for (Library.Track track : work.tracks) uris.add(track.uri);
                for (Library.Track track : previous.tracks) if (uris.add(track.uri)) work.tracks.add(track);
            }
        }
        for (Library.Work work : old) {
            if (matched.contains(work)) continue;
            if ((!work.numbered() && !scanner.failedPaths.isEmpty())
                    || scanner.affectedWorks.contains(work.identity) || scanner.failedPaths.stream()
                    .anyMatch(path -> work.source.equals(path) || work.source.startsWith(path + "/"))) fresh.add(work);
        }
        return fresh;
    }

    private LibraryIndex() {}
}
