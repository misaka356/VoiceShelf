package com.dlvoice.player;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** One atomic checkpoint for the playback queue and sleep deadline. */
final class SessionStore {
    private final AtomicFile file;
    SessionStore(Context context) { file = new AtomicFile(new File(context.getFilesDir(), "playback-session.json")); }
    JSONObject read() {
        try (InputStream input = file.openRead()) {
            return new JSONObject(new String(Metadata.read(input, 8_000_000), StandardCharsets.UTF_8));
        } catch (IOException | JSONException invalid) { return new JSONObject(); }
    }
    void write(JSONObject state) throws IOException {
        FileOutputStream output = file.startWrite();
        try {
            output.write(state.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException error) { file.failWrite(output); throw error; }
    }
}
