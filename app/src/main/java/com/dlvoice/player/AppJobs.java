package com.dlvoice.player;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import java.io.IOException;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

final class AppJobs {
    private static AppJobs instance;
    static synchronized AppJobs get(Context context) {
        if (instance == null) instance = new AppJobs(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences prefs, journal;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService scanWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService metadataWorker = Executors.newSingleThreadExecutor();
    int revision = 1, indexRevision;
    boolean scanning, batchActive, batchTranslating;
    volatile boolean cancelBatch;
    String scanKey = "", batchCurrent = "";
    private String scanMessage = "";
    private UiLanguage scanMessageLanguage = UiLanguage.CHINESE;
    boolean scanAdded;
    int batchTotal;
    UiLanguage batchLanguage;
    final Map<String, UpdateResult> batchResults = new LinkedHashMap<>();
    final List<String> failedPaths = new ArrayList<>();
    private final List<String> pendingBatch = new ArrayList<>();
    private String pendingScan = "";

    enum TitleResult { SAVED, CACHED, NO_KEY, NO_TITLE, NOT_RUN, FAILED }

    static final class UpdateResult {
        final Metadata.Result metadata;
        final TitleResult title;
        final String error;

        UpdateResult(Metadata.Result metadata, TitleResult title, String error) {
            this.metadata = metadata;
            this.title = title;
            this.error = error;
        }

        boolean complete() { return metadata == Metadata.Result.COMPLETE && title != TitleResult.FAILED; }

        JSONObject encode() throws JSONException {
            return new JSONObject().put("metadata", metadata.name()).put("title", title.name()).put("error", error);
        }

        static UpdateResult decode(Object saved) throws JSONException {
            if (saved instanceof JSONObject) {
                JSONObject value = (JSONObject) saved;
                return new UpdateResult(Metadata.Result.valueOf(value.getString("metadata")),
                        TitleResult.valueOf(value.getString("title")), value.optString("error"));
            }
            return new UpdateResult(Metadata.Result.valueOf((String) saved), TitleResult.NOT_RUN, "");
        }
    }

    private AppJobs(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE);
        journal = context.getSharedPreferences("taskJournal", Context.MODE_PRIVATE);
        try {
            JSONObject state = new JSONObject(journal.getString("state", "{}"));
            JSONArray batch = state.optJSONArray("pendingBatch");
            if (batch != null) for (int i = 0; i < batch.length(); i++) pendingBatch.add(batch.getString(i));
            JSONObject titles = state.optJSONObject("pendingTranslation");
            if (titles != null) for (Iterator<String> keys = titles.keys(); keys.hasNext();) pendingBatch.add(keys.next());
            pendingScan = state.optString("pendingScan");
            batchTotal = state.optInt("batchTotal");
            JSONObject results = state.optJSONObject("results");
            if (results != null) for (Iterator<String> keys = results.keys(); keys.hasNext();) {
                String id = keys.next();
                batchResults.put(id, UpdateResult.decode(results.get(id)));
            }
            batchLanguage = UiLanguage.valueOf(state.optString("batchLanguage", UiLanguage.CHINESE.name()));
            scanMessage = state.optString("scanMessage");
            scanMessageLanguage = UiLanguage.valueOf(state.optString("scanMessageLanguage", UiLanguage.CHINESE.name()));
            JSONArray failures = state.optJSONArray("failedPaths");
            if (failures != null) for (int i = 0; i < failures.length(); i++) failedPaths.add(failures.getString(i));
        } catch (JSONException | IllegalArgumentException invalid) {
            pendingBatch.clear();
        }
    }

    private void changed() {
        revision++;
        try {
            JSONObject state = new JSONObject().put("pendingBatch", new JSONArray(pendingBatch))
                    .put("pendingScan", pendingScan).put("batchTotal", batchTotal)
                    .put("batchLanguage", batchLanguage == null ? UiLanguage.CHINESE.name() : batchLanguage.name())
                    .put("scanMessage", scanMessage).put("scanMessageLanguage", scanMessageLanguage.name())
                    .put("failedPaths", new JSONArray(failedPaths));
            JSONObject results = new JSONObject();
            for (Map.Entry<String, UpdateResult> entry : batchResults.entrySet()) results.put(entry.getKey(), entry.getValue().encode());
            state.put("results", results);
            journal.edit().putString("state", state.toString()).apply();
        } catch (JSONException ignored) {}
    }

    boolean canResume() {
        return !scanning && !batchActive && (!pendingScan.isEmpty() || !pendingBatch.isEmpty());
    }

    String scanMessage(UiLanguage language) { return scanMessageLanguage == language ? scanMessage : ""; }

    private void setScanMessage(String message) {
        scanMessage = message;
        scanMessageLanguage = UiLanguage.current(context);
    }

    void resume() {
        if (!canResume()) return;
        String scan = pendingScan;
        List<String> batch = new ArrayList<>(pendingBatch);
        if (!scan.isEmpty()) scan(Uri.parse(scan));
        if (!batch.isEmpty()) batch(batch, false);
    }

    boolean scan(Uri tree) {
        if (scanning) return false;
        String key = tree.toString();
        scanning = true;
        scanKey = pendingScan = key;
        setScanMessage(UiLanguage.text(context, R.string.text_scanning_folders));
        scanAdded = !prefs.contains("directoryIndex:" + key);
        changed();
        scanWorker.execute(() -> {
            try {
                List<Library.Work> previous = LibraryIndex.decode(prefs.getString("directoryIndex:" + key, "[]"));
                Library scanner = new Library(context, tree);
                List<Library.Work> found = LibraryIndex.merge(previous, scanner.scan(), scanner);
                String json = LibraryIndex.encode(found);
                main.post(() -> {
                    try {
                        JSONArray keys = new JSONArray(prefs.getString("directories", "[]"));
                        boolean exists = false;
                        for (int i = 0; i < keys.length(); i++) if (key.equals(keys.getString(i))) exists = true;
                        if (!exists) keys.put(key);
                        prefs.edit().putString("directories", keys.toString()).putString("directoryIndex:" + key, json).apply();
                        failedPaths.clear();
                        failedPaths.addAll(scanner.failedPaths);
                        setScanMessage(UiLanguage.text(context, R.string.text_scan_complete) + found.size() + UiLanguage.text(context, R.string.text_work_folders)
                                + (failedPaths.isEmpty() ? "" : " · " + failedPaths.size() + UiLanguage.text(context, R.string.text_folders_could_not_be_read_previous_entries_were_)));
                        pendingScan = failedPaths.isEmpty() ? "" : key;
                        indexRevision++;
                    } catch (JSONException invalid) { setScanMessage(UiLanguage.text(context, R.string.text_could_not_save_the_index)); }
                    scanning = false;
                    changed();
                });
            } catch (Exception failure) {
                main.post(() -> { scanning = false; setScanMessage(UiLanguage.text(context, R.string.text_scan_failed_check_folder_access_and_retry)); changed(); });
            }
        });
        return true;
    }

    boolean delete(Uri tree, Library.Entry requested, Consumer<Boolean> completed) {
        if (scanning) return false;
        scanning = true;
        scanAdded = false;
        setScanMessage(UiLanguage.text(context, R.string.text_deleting));
        changed();
        scanWorker.execute(() -> {
            boolean deleteStarted = false;
            try {
                ContentResolver resolver = context.getContentResolver();
                Library library = new Library(context, tree);
                JSONArray roots = new JSONArray(prefs.getString("directories", "[]"));
                if (!prefs.contains("directoryIndex:" + tree) || requested.id.equals(library.rootId())) {
                    throw new IOException(UiLanguage.text(context, R.string.text_select_an_item_inside_an_audio_folder_in_the_fil));
                }
                Library.Entry current = library.entry(requested.id);
                if (!current.name.equals(requested.name) || current.directory != requested.directory) {
                    throw new IOException(UiLanguage.text(context, R.string.text_the_file_changed_select_it_again));
                }
                Uri target = library.document(current.id);
                String[] columns = {DocumentsContract.Document.COLUMN_FLAGS};
                try (android.database.Cursor cursor = resolver.query(target, columns, null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst()
                            || (cursor.getInt(0) & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) == 0) {
                        throw new IOException(UiLanguage.text(context, R.string.text_this_location_does_not_support_deletion));
                    }
                }
                Set<Uri> deleted = library.documentsUnder(current);
                Map<String, String> updates = new HashMap<>();
                for (int i = 0; i < roots.length(); i++) {
                    Uri root = Uri.parse(roots.getString(i));
                    if (deleted.contains(Library.documentKey(root, DocumentsContract.getTreeDocumentId(root)))) {
                        throw new IOException(UiLanguage.text(context, R.string.text_remove_the_added_audio_folders_inside_this_folde));
                    }
                    String key = "directoryIndex:" + root;
                    List<Library.Work> indexed = LibraryIndex.decode(prefs.getString(key, "[]"));
                    if (LibraryIndex.removeDocuments(indexed, deleted)) updates.put(key, LibraryIndex.encode(indexed));
                }
                deleteStarted = true;
                if (!DocumentsContract.deleteDocument(resolver, target)) throw new IOException();
                main.post(() -> {
                    PlaybackService.removeDeletedDocuments(deleted);
                    PlaybackHistory.remove(context, deleted);
                    SharedPreferences.Editor edit = prefs.edit();
                    updates.forEach(edit::putString);
                    for (Map.Entry<String, ?> saved : prefs.getAll().entrySet()) {
                        String key = saved.getKey();
                        if (key.startsWith("position:") && Library.containsDocument(deleted, key.substring(9))) edit.remove(key);
                        if ((key.equals("last") || key.startsWith("work:")) && saved.getValue() instanceof String
                                && Library.containsDocument(deleted, (String) saved.getValue())) edit.remove(key);
                    }
                    edit.apply();
                    indexRevision++;
                    finishDelete(UiLanguage.text(context, R.string.text_deleted) + current.name, true, completed);
                });
            } catch (Exception failure) {
                String message = deleteStarted ? UiLanguage.text(context, R.string.text_deletion_did_not_finish_rescan_to_check)
                        : failure instanceof SecurityException ? UiLanguage.text(context, R.string.text_no_delete_permission_grant_folder_access_again)
                        : failure instanceof IOException && failure.getMessage() != null ? failure.getMessage()
                        : UiLanguage.text(context, R.string.text_could_not_delete_check_folder_and_storage_permis);
                main.post(() -> finishDelete(message, false, completed));
            }
        });
        return true;
    }

    private void finishDelete(String message, boolean success, Consumer<Boolean> completed) {
        scanning = false;
        setScanMessage(message);
        changed();
        completed.accept(success);
    }

    boolean refreshTimes(List<String> roots) {
        if (scanning) return false;
        scanning = true;
        scanAdded = false;
        setScanMessage(UiLanguage.text(context, R.string.text_refreshing_modified_dates));
        changed();
        scanWorker.execute(() -> {
            int errors = 0;
            for (String root : roots) {
                try {
                    Library scanner = new Library(context, Uri.parse(root));
                    List<Library.Work> found = scanner.scan();
                    Map<String, Long> times = new HashMap<>();
                    for (Library.Work work : found) times.put(work.source, work.modifiedAt);
                    main.post(() -> {
                        try {
                            if (!prefs.contains("directoryIndex:" + root)) return;
                            List<Library.Work> existing = LibraryIndex.decode(prefs.getString("directoryIndex:" + root, "[]"));
                            for (Library.Work work : existing) work.modifiedAt = times.getOrDefault(work.source, work.modifiedAt);
                            prefs.edit().putString("directoryIndex:" + root, LibraryIndex.encode(existing)).apply();
                            indexRevision++;
                            changed();
                        } catch (JSONException ignored) {}
                    });
                    if (!scanner.failedPaths.isEmpty()) errors++;
                } catch (Exception failure) { errors++; }
            }
            int failures = errors;
            main.post(() -> {
                scanning = false;
                setScanMessage(UiLanguage.text(context, failures == 0 ? R.string.text_modified_dates_refreshed
                        : R.string.text_some_folders_could_not_be_refreshed_check_access));
                changed();
            });
        });
        return true;
    }

    boolean batch(List<String> ids, boolean refreshMetadata) {
        if (batchActive || ids.isEmpty()) return false;
        batchActive = true;
        batchTranslating = false;
        UiLanguage language = batchLanguage = UiLanguage.current(context);
        cancelBatch = false;
        batchResults.clear();
        pendingBatch.clear();
        pendingBatch.addAll(new LinkedHashSet<>(ids));
        List<String> queue = new ArrayList<>(pendingBatch);
        batchTotal = queue.size();
        changed();
        metadataWorker.execute(() -> {
            String apiKey = "";
            boolean keyError = false;
            try { apiKey = Translation.key(context); }
            catch (Exception failure) { keyError = true; }
            for (String id : queue) {
                if (cancelBatch) break;
                main.post(() -> { batchCurrent = id; batchTranslating = false; changed(); });
                boolean fetch = refreshMetadata || !Metadata.complete(context, prefs, id);
                Metadata.Result metadata = Metadata.Result.COMPLETE;
                if (fetch) {
                    try { metadata = Metadata.update(context, prefs, id); }
                    catch (Exception failure) { metadata = Metadata.Result.FAILED; }
                }
                UpdateResult outcome = enrichTitle(id, metadata, apiKey, keyError, language);
                main.post(() -> {
                    batchResults.put(id, outcome);
                    if (outcome.complete()) pendingBatch.remove(id);
                    changed();
                });
                if (fetch && !id.equals(queue.get(queue.size() - 1))) {
                    try { Thread.sleep(1000); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
            main.post(() -> { batchActive = batchTranslating = false; batchCurrent = ""; changed(); });
        });
        return true;
    }

    private UpdateResult enrichTitle(String id, Metadata.Result metadata, String apiKey, boolean keyError,
                                     UiLanguage language) {
        JSONObject info = Metadata.saved(prefs, id);
        String source = info.optString("title");
        if (source.trim().isEmpty()) return new UpdateResult(metadata, TitleResult.NO_TITLE, "");
        if (!Translation.cached(info, language).isEmpty()) return new UpdateResult(metadata, TitleResult.CACHED, "");
        if (keyError) return new UpdateResult(metadata, TitleResult.FAILED, "key");
        if (apiKey.isEmpty()) return new UpdateResult(metadata, TitleResult.NO_KEY, "");
        main.post(() -> { batchTranslating = true; changed(); });
        try {
            String translated = Translation.request(apiKey, source, language);
            if (Translation.save(prefs, id, source, translated, language)) {
                return new UpdateResult(metadata, TitleResult.SAVED, "");
            }
            return new UpdateResult(metadata, TitleResult.FAILED, "source");
        } catch (Exception failure) {
            String message = failure.getMessage();
            return new UpdateResult(metadata, TitleResult.FAILED,
                    message != null && message.matches("DeepSeek HTTP [0-9]{3}") ? message : "");
        }
    }
}
