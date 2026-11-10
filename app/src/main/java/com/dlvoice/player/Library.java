package com.dlvoice.player;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

final class Library {
    static final String UNNAMED = "unidentified";
    private static final Pattern ID = Pattern.compile("(?i)RJ[0-9]+");
    private static final Pattern AUDIO = Pattern.compile("(?i).+\\.(mp3|wav|flac|m4a|aac|ogg|opus|mp4)$");
    private static final Pattern PARTS = Pattern.compile("[0-9]+|[^0-9]+");

    static final class Track {
        final String name, uri, lrc;
        Track(String name, String uri, String lrc) {
            this.name = name; this.uri = uri; this.lrc = lrc;
        }
    }

    static final class Work {
        final String id, source;
        final ArrayList<Track> tracks = new ArrayList<>();
        long addedAt, modifiedAt;
        String identity = "";
        Work(String id, String source) { this.id = id; this.source = source; }
        boolean numbered() { return !UNNAMED.equals(id); }
    }

    static final class Entry {
        final String id, name;
        final boolean directory;
        final long modifiedAt;
        Entry(String id, String name, boolean directory, long modifiedAt) {
            this.id = id; this.name = name; this.directory = directory;
            this.modifiedAt = modifiedAt;
        }
    }

    private final ContentResolver resolver;
    private final Context context;
    private final Uri tree;
    private final ArrayList<Work> works = new ArrayList<>();
    private final Set<String> visited = new HashSet<>();
    private Work unnamed;
    final List<String> failedPaths = new ArrayList<>();
    final Set<String> affectedWorks = new HashSet<>();

    Library(Context context, Uri tree) { this.context = context; this.resolver = context.getContentResolver(); this.tree = tree; }

    List<Work> scan() throws IOException {
        String id = DocumentsContract.getTreeDocumentId(tree);
        Uri root = document(id);
        try (Cursor cursor = resolver.query(root,
                new String[]{Document.COLUMN_DISPLAY_NAME, Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException(UiLanguage.text(context, R.string.text_could_not_read_the_folder));
            String name = cursor.getString(0);
            unnamed = new Work(UNNAMED, name);
            unnamed.identity = tree + "#unnamed";
            visit(new Entry(id, name, true, cursor.getLong(1)), "", null);
        }
        works.sort(Comparator.comparing(work -> work.id));
        if (!unnamed.tracks.isEmpty()) works.add(unnamed);
        return works;
    }

    String rootId() { return DocumentsContract.getTreeDocumentId(tree); }

    Uri document(String id) { return DocumentsContract.buildDocumentUriUsingTree(tree, id); }

    static Uri treeForDocument(Uri document) {
        return DocumentsContract.buildTreeDocumentUri(document.getAuthority(), DocumentsContract.getTreeDocumentId(document));
    }

    static Uri documentKey(Uri uri, String id) {
        // The same document can be indexed through more than one authorized tree.
        return DocumentsContract.buildDocumentUri(uri.getAuthority(), id);
    }

    static boolean containsDocument(Set<Uri> documents, String value) {
        if (value.isEmpty()) return false;
        Uri uri = Uri.parse(value);
        try { return documents.contains(documentKey(uri, DocumentsContract.getDocumentId(uri))); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    List<Entry> children(String id) throws IOException {
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        String[] columns = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
                Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED};
        ArrayList<Entry> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(uri, columns, null, null, null)) {
            if (cursor == null) throw new IOException(UiLanguage.text(context, R.string.text_could_not_read_subfolders));
            while (cursor.moveToNext()) {
                result.add(new Entry(cursor.getString(0), cursor.getString(1),
                        Document.MIME_TYPE_DIR.equals(cursor.getString(2)), cursor.getLong(3)));
            }
        }
        result.sort((a, b) -> naturalCompare(a.name, b.name));
        return result;
    }

    Entry entry(String id) throws IOException {
        String[] columns = {Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED};
        try (Cursor cursor = resolver.query(document(id), columns, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException(UiLanguage.text(context, R.string.text_file_missing_or_unreadable));
            return new Entry(id, cursor.getString(0), Document.MIME_TYPE_DIR.equals(cursor.getString(1)), cursor.getLong(2));
        }
    }

    List<Entry> folderPath(String path) throws IOException {
        Entry folder = entry(rootId());
        List<Entry> result = new ArrayList<>();
        result.add(folder);
        String[] names = path.split("/");
        if (!folder.name.equals(names[0])) throw new IOException(UiLanguage.text(context, R.string.text_folder_name_changed_rescan_the_folder));
        for (int i = 1; i < names.length; i++) {
            Entry match = null;
            for (Entry child : children(folder.id)) {
                if (!child.directory || !child.name.equals(names[i])) continue;
                if (match != null) throw new IOException(UiLanguage.text(context, R.string.text_duplicate_folder_names_choose_in_the_file_manage));
                match = child;
            }
            if (match == null) throw new IOException(UiLanguage.text(context, R.string.text_folder_not_found_rescan_the_folder));
            folder = match;
            result.add(folder);
        }
        return result;
    }

    Set<Uri> documentsUnder(Entry entry) throws IOException {
        Set<Uri> documents = new HashSet<>();
        Deque<Entry> pending = new ArrayDeque<>();
        pending.push(entry);
        while (!pending.isEmpty()) {
            Entry child = pending.pop();
            if (documents.add(documentKey(tree, child.id)) && child.directory) pending.addAll(children(child.id));
        }
        return documents;
    }

    private void visit(Entry entry, String prefix, Work current) throws IOException {
        if (!visited.add(entry.id)) return;
        String path = prefix + entry.name;
        var match = ID.matcher(entry.name);
        boolean startsWork = current == null && match.find();
        if (startsWork) {
            current = new Work(match.group().toUpperCase(Locale.ROOT), path);
            current.identity = document(entry.id).toString();
            path = "";
        }
        List<Entry> children;
        try { children = children(entry.id); }
        catch (IOException | SecurityException | IllegalArgumentException failure) {
            if (prefix.isEmpty() && current == null && entry.name.equals(unnamed.source)) throw failure;
            failedPaths.add(prefix + entry.name);
            if (current != null) affectedWorks.add(current.identity);
            else affectedWorks.add(unnamed.identity);
            return;
        }
        Map<String, String> subtitles = new HashMap<>();
        Work destination = current == null ? unnamed : current;
        destination.modifiedAt = Math.max(destination.modifiedAt, entry.modifiedAt);
        for (Entry child : children) {
            String key = child.name.toLowerCase(Locale.ROOT);
            if (!child.directory && key.endsWith(".lrc")) {
                subtitles.putIfAbsent(key, document(child.id).toString());
            }
        }
        for (Entry child : children) {
            if (!child.directory && AUDIO.matcher(child.name).matches()) {
                String key = child.name.substring(0, child.name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
                destination.tracks.add(new Track((path.isEmpty() ? "" : path + "/") + child.name,
                        document(child.id).toString(), subtitles.getOrDefault(key + ".lrc", "")));
                destination.modifiedAt = Math.max(destination.modifiedAt, child.modifiedAt);
            }
        }
        for (Entry child : children) {
            if (child.directory) visit(child, path.isEmpty() ? "" : path + "/", current);
        }
        if (startsWork && !current.tracks.isEmpty()) works.add(current);
    }

    private static int naturalCompare(String left, String right) {
        var a = PARTS.matcher(left);
        var b = PARTS.matcher(right);
        while (a.find() && b.find()) {
            String x = a.group(), y = b.group();
            int order;
            if (x.charAt(0) >= '0' && x.charAt(0) <= '9' && y.charAt(0) >= '0' && y.charAt(0) <= '9') {
                order = new java.math.BigInteger(x).compareTo(new java.math.BigInteger(y));
            } else {
                order = x.compareToIgnoreCase(y);
            }
            if (order != 0) return order;
        }
        return left.compareToIgnoreCase(right);
    }
}

