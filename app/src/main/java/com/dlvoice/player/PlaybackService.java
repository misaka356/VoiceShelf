package com.dlvoice.player;

import android.app.*;
import android.content.*;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.*;
import android.media.session.*;
import android.net.Uri;
import android.os.*;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import java.util.*;

public final class PlaybackService extends Service {
    private static PlaybackService live;
    enum PlayOrder {
        SEQUENTIAL(R.string.text_sequential), LOOP(R.string.text_repeat_queue), SHUFFLE(R.string.text_shuffle);

        final int label;
        PlayOrder(int label) { this.label = label; }
    }

    private static final String CHANNEL = "playback";
    private static final String PRIVATE_CHANNEL = "privatePlayback";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService subtitles = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final IBinder binder = new LocalBinder();
    private MediaPlayer player;
    private ExoPlayer usbPlayer;
    private int usbDeviceId = -1, retryPosition, trackGeneration;
    private boolean pendingPlay, systemSeeking, pendingHistory;
    private SessionStore sessions;
    private final java.util.concurrent.ExecutorService sessionWrites = java.util.concurrent.Executors.newSingleThreadExecutor();
    private int knownDuration;
    private long sleepDeadline;
    private VolumeProvider usbVolumeControl;
    private MediaSession session;
    private boolean ready, foreground, resumeOnFocus;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private List<Lrc.Cue> cues = Collections.emptyList();
    private final List<QueueItem> queue = new ArrayList<>();
    private final List<String> shuffledTracks = new ArrayList<>();
    private String nextShuffledTrack = "";
    private PlayOrder playOrder = PlayOrder.SEQUENTIAL;
    private boolean customQueue;
    private long queueGeneration;
    private android.graphics.Bitmap artwork;
    private final java.util.concurrent.ExecutorService artworkWorker = java.util.concurrent.Executors.newSingleThreadExecutor();

    private static final class QueueItem {
        final String workId;
        final Library.Track track;
        QueueItem(String workId, Library.Track track) { this.workId = workId; this.track = track; }
    }
    private String workId = "";
    private int index = -1;
    private float speed = 1f;
    private long sleepUntil;
    String error = "";
    int subtitleStatus;
    private final BroadcastReceiver unplug = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbOutput.PERMISSION.equals(action)) { UsbOutput.handleEvent(intent); return; }
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (usbMode()) {
                    UsbDevice device = UsbOutput.device(context);
                    if (device != null) UsbOutput.request(context, device);
                }
                return;
            }
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbOutput.handleEvent(intent);
                UsbDevice detached = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (usbPlayer != null && detached != null && detached.getDeviceId() == usbDeviceId) {
                    fail(UiLanguage.text(PlaybackService.this, R.string.text_usb_dac_disconnected_playback_paused));
                }
            } else if (usbPlayer == null) pause();
        }
    };

    public final class LocalBinder extends Binder {
        PlaybackService service() { return PlaybackService.this; }
    }

    @Override public void onCreate() {
        super.onCreate();
        live = this;
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, UiLanguage.text(PlaybackService.this, R.string.text_audio_playback), NotificationManager.IMPORTANCE_LOW));
        NotificationChannel privateChannel = new NotificationChannel(PRIVATE_CHANNEL, UiLanguage.text(PlaybackService.this, R.string.text_private_background_playback),
                NotificationManager.IMPORTANCE_LOW);
        privateChannel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
        privateChannel.setShowBadge(false);
        privateChannel.setSound(null,null);privateChannel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(privateChannel);
        if(!privacyMode())createMediaSession();
        audio = getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes()).setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        if (resumeOnFocus) { resumeOnFocus = false; toggle(true); }
                    } else {
                        boolean resume = change != AudioManager.AUDIOFOCUS_LOSS && playing();
                        pause();
                        resumeOnFocus = resume;
                    }
                }, handler).build();
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbOutput.PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unplug, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(unplug, filter);
        sessions = new SessionStore(this);
        try {
            playOrder = PlayOrder.valueOf(getSharedPreferences("library", MODE_PRIVATE)
                    .getString("playOrder", PlayOrder.SEQUENTIAL.name()));
        } catch (IllegalArgumentException invalid) { playOrder = PlayOrder.SEQUENTIAL; }
        restoreSession();
        handler.postDelayed(tick, 1000);
    }

    private void createMediaSession(){
        if(session!=null)return;
        session = new MediaSession(this, "DL Voice");
        session.setSessionActivity(openApp());
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { toggle(true); }
            @Override public void onPause() { pause(); }
            @Override public void onSeekTo(long time) { seek((int) time); }
            @Override public void onSkipToNext() { step(1); }
            @Override public void onSkipToPrevious() { step(-1); }
            @Override public void onStop() { pause(); }
        });
        if(usbVolumeControl!=null)session.setPlaybackToRemote(usbVolumeControl);
    }
    private void releaseMediaSession(){
        if(session==null)return;
        session.setPlaybackState(new PlaybackState.Builder().setState(PlaybackState.STATE_NONE,0,0).setActions(0).build());
        session.setMetadata(null);session.setActive(false);session.setCallback(null);session.release();session=null;
    }
    private int notificationId(){return privacyMode()?2:1;}
    private void clearPlaybackNotifications(){NotificationManager manager=getSystemService(NotificationManager.class);manager.cancel(1);manager.cancel(2);}

    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("toggle".equals(action)) toggle(!playing());
            if ("next".equals(action)) step(1);
            if ("previous".equals(action)) step(-1);
        }
        return START_NOT_STICKY;
    }

    void play(Library.Work work, int track, int position) {
        save();
        queue.clear();
        shuffledTracks.clear();
        nextShuffledTrack = "";
        queueGeneration++;
        customQueue = false;
        addWorkTracks(work);
        pendingHistory = true;
        load(track, position);
    }

    private void addWorkTracks(Library.Work work) {
        for (Library.Track track : work.tracks) queue.add(new QueueItem(work.id, track));
    }

    boolean addToQueue(String id, Library.Track track, boolean next) {
        int existing = -1;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).track.uri.equals(track.uri)) { existing = i; break; }
        }
        if (existing == index && existing >= 0) return false;
        if (existing >= 0 && !next) return false;
        if (existing >= 0) {
            queue.remove(existing);
            if (existing < index) index--;
        }
        queue.add(next ? index + 1 : queue.size(), new QueueItem(id, track));
        shuffledTracks.clear();
        if (next && playOrder == PlayOrder.SHUFFLE) nextShuffledTrack = track.uri;
        customQueue = true;
        checkpoint();
        return true;
    }

    boolean hasCustomQueue() { return customQueue && !queue.isEmpty(); }
    int queuedIndex(String uri) {
        for (int i = 0; i < queue.size(); i++) if (queue.get(i).track.uri.equals(uri)) return i;
        return -1;
    }

    void moveQueueItem(int from, int to) {
        if (from < 0 || from >= queue.size() || to < 0 || to >= queue.size() || from == to) return;
        QueueItem playing = index < 0 ? null : queue.get(index);
        queue.add(to, queue.remove(from));
        shuffledTracks.clear();
        index = queue.indexOf(playing);
        customQueue = true;
        checkpoint();
    }

    boolean removeQueueItem(int item) {
        if (item < 0 || item >= queue.size() || item == index) return false;
        queue.remove(item);
        shuffledTracks.clear();
        if (item < index) index--;
        customQueue = true;
        checkpoint();
        return true;
    }

    static void removeDeletedDocuments(Set<Uri> deleted) {
        if (live != null) live.pruneQueue(deleted);
    }

    private void pruneQueue(Set<Uri> deleted) {
        boolean changed = false;
        for (int i = queue.size() - 1; i >= 0; i--) {
            QueueItem item = queue.get(i);
            if (Library.containsDocument(deleted, item.track.uri)) {
                queue.remove(i);
                changed = true;
                if (i == index) {
                    index = -1;
                    trackGeneration++;
                    releasePlayer();
                    pendingPlay = resumeOnFocus = false;
                    audio.abandonAudioFocusRequest(focus);
                    stopForeground(STOP_FOREGROUND_REMOVE);
                    foreground = false;
                    clearPlaybackNotifications();
                    if (session != null) session.setActive(false);
                    workId = error = "";
                    subtitleStatus = 0;
                    retryPosition = knownDuration = 0;
                    cues = Collections.emptyList();
                    artwork = null;
                    stopSelf();
                } else if (i < index) index--;
            } else if (Library.containsDocument(deleted, item.track.lrc)) {
                queue.set(i, new QueueItem(item.workId, new Library.Track(item.track.name, item.track.uri, "")));
                changed = true;
                if (i == index) {
                    cues = Collections.emptyList();
                    subtitleStatus = R.string.text_no_matching_lrc_file;
                }
            }
        }
        if (changed) {
            shuffledTracks.clear();
            queueGeneration++;
            checkpoint();
        }
    }

    long queueGeneration() { return queueGeneration; }

    boolean restoreQueueItem(String id, Library.Track track, int position, String nextUri, long generation) {
        if (generation != queueGeneration || queuedIndex(track.uri) >= 0) return false;
        int next = queuedIndex(nextUri);
        int target = next >= 0 ? next : Math.min(position, queue.size());
        queue.add(target, new QueueItem(id, track));
        shuffledTracks.clear();
        if (index >= target) index++;
        customQueue = true;
        checkpoint();
        return true;
    }

    private void load(int track, int position) {
        releasePlayer();
        int generation = ++trackGeneration;
        retryPosition = position;
        knownDuration = 0;
        pendingPlay = true;
        index = track;
        workId = queue.get(index).workId;
        cues = Collections.emptyList();
        artwork = null;
        error = "";
        Library.Track item = current();
        checkpoint();
        subtitleStatus = item.lrc.isEmpty() ? R.string.text_no_matching_lrc_file : R.string.text_loading_lyrics;
        if (usbMode() && UsbOutput.device(this) != null) loadUsb(item, position);
        else loadSystem(item, position);
        if (!item.lrc.isEmpty()) subtitles.execute(() -> {
            try {
                List<Lrc.Cue> result = Lrc.read(getContentResolver(), item.lrc);
                handler.post(() -> {
                    if (trackGeneration != generation || !current().lrc.equals(item.lrc)) return;
                    cues = result;
                    subtitleStatus = result.isEmpty() ? R.string.text_no_valid_timestamps_in_the_lrc_file : R.string.text_lrc_lyrics;
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (trackGeneration == generation && current().lrc.equals(item.lrc)) subtitleStatus = R.string.text_could_not_read_lrc;
                });
            }
        });
        artworkWorker.execute(() -> {
            android.graphics.Bitmap result = Artwork.embedded(this, item.uri, 1024);
            handler.post(() -> { if (trackGeneration == generation) artwork = result; });
        });
    }

    private void loadSystem(Library.Track item, int position) {
        MediaPlayer next = new MediaPlayer();
        player = next;
        try {
            enterForeground();
            next.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            next.setAudioAttributes(attributes());
            next.setDataSource(this, Uri.parse(item.uri));
            next.setOnPreparedListener(p -> {
                if (p != player) return;
                ready = true;
                knownDuration = p.getDuration();
                if (position > 0) seek(Math.min(position, Math.max(0, duration() - 1)));
                else if (pendingPlay) toggle(true);
            });
            next.setOnSeekCompleteListener(p -> {
                if (p != player) return;
                systemSeeking = false;
                if (pendingPlay) toggle(true);
                save();
            });
            next.setOnCompletionListener(p -> { if (p == player) finished(); });
            next.setOnErrorListener((p, what, extra) -> {
                if (p == player) { ready = false; fail(UiLanguage.text(PlaybackService.this, R.string.text_cannot_play_this_audio) + what + " / " + extra + "）"); }
                return true;
            });
            next.prepareAsync();
            updateSession();
        } catch (Exception e) { fail(UiLanguage.text(PlaybackService.this, R.string.text_could_not_open_audio_check_folder_access)); }
    }

    private void loadUsb(Library.Track item, int position) {
        if (!UsbOutput.supports(item.name)) {
            fail(UiLanguage.text(PlaybackService.this, R.string.text_exclusive_usb_supports_wav_mp3_and_flac));
            return;
        }
        try {
            UsbDevice dac = UsbOutput.device(this);
            if (dac == null) { fail(UiLanguage.text(PlaybackService.this, R.string.text_no_usb_dac_detected)); return; }
            if (!UsbOutput.authorized(this, dac)) {
                UsbOutput.request(this, dac);
                fail(UiLanguage.text(PlaybackService.this, R.string.text_allow_access_to_the_usb_dac_then_tap_play));
                return;
            }
            enterForeground();
            usbDeviceId = dac.getDeviceId();
            ExoPlayer next = UsbOutput.create(this);
            usbPlayer = next;
            next.setVolume(usbVolume() / 100f);
            usbVolumeControl = new VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, 100, usbVolume()) {
                @Override public void onSetVolumeTo(int value) { handler.post(() -> setUsbVolume(value)); }
                @Override public void onAdjustVolume(int direction) {
                    if (direction == AudioManager.ADJUST_RAISE || direction == AudioManager.ADJUST_LOWER) {
                        handler.post(() -> setUsbVolume(usbVolume() + direction * 5));
                    } else if (direction == AudioManager.ADJUST_MUTE) handler.post(() -> setUsbVolume(0));
                }
            };
            if(session!=null)session.setPlaybackToRemote(usbVolumeControl);
            next.addListener(new Player.Listener() {
                @Override public void onPlaybackStateChanged(int state) {
                    if (usbPlayer != next) return;
                    if (state == Player.STATE_READY && !ready) {
                        ready = true;
                        if (pendingPlay) toggle(true);
                    } else if (state == Player.STATE_ENDED) finished();
                    updateSession();
                }
                @Override public void onPlayerError(PlaybackException issue) {
                    if (usbPlayer != next) return;
                    android.util.Log.w("YinqiUsb", "USB exclusive playback failed", issue);
                    Throwable cause = issue;
                    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
                    String reason = cause.getMessage();
                    fail(UiLanguage.text(PlaybackService.this, R.string.text_exclusive_usb_output_failed) + (reason == null ? issue.getErrorCodeName() : reason));
                }
            });
            UsbOutput.load(next, Uri.parse(item.uri), position);
            updateSession();
        } catch (RuntimeException | LinkageError issue) {
            android.util.Log.w("YinqiUsb", "Could not initialize USB output", issue);
            fail(UiLanguage.text(PlaybackService.this, R.string.text_could_not_start_exclusive_usb_output_check_your_));
        }
    }

    private void finished() {
        int next = orderedTrack(1);
        if (next >= 0) {
            savePosition(0);
            pendingHistory = true;
            load(next, 0);
        } else {
            shuffledTracks.clear();
            pause();
            seek(0);
            retryPosition = 0;
            savePosition(0);
        }
    }

    boolean usbMode() {
        return getSharedPreferences("library", MODE_PRIVATE).getBoolean(UsbOutput.MODE, false);
    }

    void outputChanged() {
        if (current() == null) return;
        if (ready) { retryPosition = position(); save(); }
        fail("");
    }
    void toggle(boolean start) {
        if (!start) { pause(); return; }
        pendingPlay = true;
        if (current() != null && player == null && usbPlayer == null) { load(index, retryPosition); return; }
        if (!ready || systemSeeking || playing()) return;
        try {
            enterForeground();
            if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                fail(UiLanguage.text(PlaybackService.this, R.string.text_could_not_obtain_audio_focus));
                return;
            }
            if (usbPlayer != null) usbPlayer.play();
            else {
                player.setPlaybackParams(new PlaybackParams().allowDefaults().setSpeed(speed));
                player.start();
            }
            if (pendingHistory) {
                PlaybackHistory.record(this, current().uri);
                pendingHistory = false;
            }
            save();
            updateSession();
        } catch (RuntimeException e) { fail(UiLanguage.text(PlaybackService.this, R.string.text_could_not_start_playback)); }
    }

    private void enterForeground() {
        if (foreground) return;
        startService(new Intent(this, PlaybackService.class));
        if(privacyMode())releaseMediaSession();else{createMediaSession();session.setActive(true);}
        startForeground(notificationId(), notification(true));
        foreground = true;
    }

    void pause() {
        resumeOnFocus = false;
        pendingPlay = false;
        if (usbPlayer != null) {
            position();
            save();
            releasePlayer();
        }
        else if (playing()) player.pause();
        save();
        if (foreground) {
            stopForeground(privacyMode() ? STOP_FOREGROUND_REMOVE : STOP_FOREGROUND_DETACH);
            foreground = false;
        }
        updateSession();
    }

    void step(int direction) {
        int next = orderedTrack(direction);
        if (next >= 0 && next != index) startTrack(next);
    }

    PlayOrder playOrder() { return playOrder; }

    void cyclePlayOrder() {
        PlayOrder[] orders = PlayOrder.values();
        playOrder = orders[(playOrder.ordinal() + 1) % orders.length];
        shuffledTracks.clear();
        nextShuffledTrack = "";
        getSharedPreferences("library", MODE_PRIVATE).edit().putString("playOrder", playOrder.name()).apply();
    }

    private int orderedTrack(int direction) {
        if (queue.isEmpty() || (index < 0 && direction < 0)) return -1;
        if (playOrder == PlayOrder.SHUFFLE) {
            String uri = current() == null ? "" : current().uri;
            if (shuffledTracks.isEmpty()) {
                for (QueueItem item : queue) if (!uri.equals(item.track.uri)) shuffledTracks.add(item.track.uri);
                Collections.shuffle(shuffledTracks);
                if (shuffledTracks.remove(nextShuffledTrack)) shuffledTracks.add(0, nextShuffledTrack);
                nextShuffledTrack = "";
                if (!uri.isEmpty()) shuffledTracks.add(0, uri);
            }
            int next = shuffledTracks.indexOf(uri) + direction;
            return next >= 0 && next < shuffledTracks.size() ? queuedIndex(shuffledTracks.get(next)) : -1;
        }
        int next = index + direction;
        if (next >= 0 && next < queue.size()) return next;
        return playOrder == PlayOrder.LOOP ? (direction > 0 ? 0 : queue.size() - 1) : -1;
    }

    int queueIndex() { return index; }
    int queueSize() { return queue.size(); }
    Library.Track queueTrack(int position) { return queue.get(position).track; }
    String queueWork(int position) { return queue.get(position).workId; }
    void selectTrack(int track) {
        if (track >= 0 && track < queue.size() && track != index) {
            shuffledTracks.clear();
            nextShuffledTrack = "";
            startTrack(track);
        }
    }

    private void startTrack(int track) {
        save();
        pendingHistory = true;
        load(track, 0);
    }

    void seek(int position) {
        if (ready) {
            int target = Math.max(0, Math.min(position, duration()));
            retryPosition = target;
            if (usbPlayer != null) usbPlayer.seekTo(target);
            else {
                systemSeeking = true;
                player.seekTo(target, MediaPlayer.SEEK_CLOSEST);
            }
            save();
            updateSession();
        }
    }

    void speed(float value) {
        if (usbPlayer != null) return;
        speed = value;
        if (playing()) player.setPlaybackParams(new PlaybackParams().allowDefaults().setSpeed(value));
        updateSession();
    }

    float speed() { return usbPlayer != null ? 1f : speed; }
    boolean usingUsb() { return usbPlayer != null; }
    int usbVolume() { return getSharedPreferences("library", MODE_PRIVATE).getInt("usbVolume", 50); }
    void setUsbVolume(int value) {
        int level = Math.max(0, Math.min(100, value));
        getSharedPreferences("library", MODE_PRIVATE).edit().putInt("usbVolume", level).apply();
        if (usbPlayer != null) usbPlayer.setVolume(level / 100f);
        if (usbVolumeControl != null) usbVolumeControl.setCurrentVolume(level);
    }
    void sleep(int minutes) {
        sleepUntil = minutes == 0 ? 0 : SystemClock.elapsedRealtime() + minutes * 60_000L;
        sleepDeadline = minutes == 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L;
        checkpoint();
    }
    long sleepRemaining() { return Math.max(0, sleepUntil - SystemClock.elapsedRealtime()); }
    int position() {
        if (ready && !systemSeeking) {
            try { retryPosition = usbPlayer != null ? timeInt(usbPlayer.getCurrentPosition()) : player.getCurrentPosition(); }
            catch (IllegalStateException ignored) {}
        }
        return retryPosition;
    }
    int duration() {
        if (ready) {
            try { knownDuration = usbPlayer != null ? timeInt(usbPlayer.getDuration()) : Math.max(0, player.getDuration()); }
            catch (IllegalStateException ignored) {}
        }
        return knownDuration;
    }
    private static int timeInt(long value) { return (int) Math.min(Integer.MAX_VALUE, Math.max(0, value)); }
    boolean playing() { return ready && (usbPlayer != null ? usbPlayer.getPlayWhenReady() : player.isPlaying()); }
    Library.Track current() { return index < 0 ? null : queue.get(index).track; }
    String workId() { return workId; }
    private String albumLabel() { return Library.UNNAMED.equals(workId) ? UiLanguage.text(PlaybackService.this, R.string.text_untitled_work) : workId; }
    List<Lrc.Cue> lyrics() { return cues; }
    android.graphics.Bitmap artwork() { return artwork; }

    private void save() {
        if (current() == null) { checkpoint(); return; }
        savePosition(position());
    }

    private void savePosition(int savedPosition) {
        getSharedPreferences("library", MODE_PRIVATE).edit()
                .putString("last", current().uri).putInt("position:" + current().uri, savedPosition)
                .putString("work:" + workId, current().uri).apply();
        checkpoint();
    }

    private void checkpoint() {
        if (sessions == null) return;
        org.json.JSONObject state = new org.json.JSONObject();
        org.json.JSONArray items = new org.json.JSONArray();
        try {
            for (QueueItem item : queue) {
                items.put(new org.json.JSONObject().put("work", item.workId).put("name", item.track.name)
                        .put("uri", item.track.uri).put("lrc", item.track.lrc));
            }
            state.put("queue", items).put("index", index).put("custom", customQueue)
                    .put("position", retryPosition).put("duration", knownDuration)
                    .put("speed", speed).put("sleepDeadline", sleepDeadline).put("projectQueue", true);
        } catch (org.json.JSONException invalid) { return; }
        sessionWrites.execute(() -> {
            try { sessions.write(state); }
            catch (java.io.IOException failure) { android.util.Log.w("YinqiSession", "Session save failed", failure); }
        });
    }

    private void restoreSession() {
        org.json.JSONObject state = sessions.read();
        try {
            org.json.JSONArray items = state.optJSONArray("queue");
            if (items == null) return;
            List<QueueItem> restored = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                org.json.JSONObject item = items.getJSONObject(i);
                restored.add(new QueueItem(item.getString("work"),
                        new Library.Track(item.getString("name"), item.getString("uri"), item.optString("lrc"))));
            }
            int restoredIndex = state.optInt("index", -1);
            if (restoredIndex < -1 || restoredIndex >= restored.size()) return;
            queue.addAll(restored);
            index = restoredIndex;
            customQueue = state.optBoolean("custom");
            retryPosition = Math.max(0, state.optInt("position"));
            knownDuration = Math.max(0, state.optInt("duration"));
            speed = (float) Math.max(.5, Math.min(2, state.optDouble("speed", 1)));
            if (current() != null) {
                if (!customQueue && !state.optBoolean("projectQueue")) expandRestoredQueue();
                workId = queue.get(index).workId;
                pendingHistory = true;
            }
            long remaining = state.optLong("sleepDeadline") - System.currentTimeMillis();
            if (remaining > 0 && remaining <= 86_400_000) {
                sleepDeadline = state.optLong("sleepDeadline");
                sleepUntil = SystemClock.elapsedRealtime() + remaining;
            }
        } catch (org.json.JSONException invalid) { queue.clear(); index = -1; }
    }

    private void expandRestoredQueue() {
        String uri = current().uri;
        try {
            Uri tree = Library.treeForDocument(Uri.parse(uri));
            SharedPreferences prefs = getSharedPreferences("library", MODE_PRIVATE);
            List<Library.Work> works = LibraryIndex.decode(prefs.getString("directoryIndex:" + tree, "[]"));
            for (Library.Work work : works) {
                if (work.tracks.stream().noneMatch(track -> track.uri.equals(uri))) continue;
                queue.clear();
                addWorkTracks(work);
                index = queuedIndex(uri);
                return;
            }
        } catch (org.json.JSONException | IllegalArgumentException invalid) {
            // The saved queue is usable even if its source no longer has an index.
        }
    }

    private final Runnable tick = new Runnable() {
        private int ticks;
        @Override public void run() {
            if (sleepUntil != 0 && sleepRemaining() == 0) { sleepUntil = sleepDeadline = 0; pause(); }
            if (ready) position();
            if (++ticks % 5 == 0 && playing()) save();
            handler.postDelayed(this, 1000);
        }
    };

    private void fail(String message) {
        if (ready) position();
        save();
        error = message;
        pendingPlay = resumeOnFocus = false;
        releasePlayer();
        audio.abandonAudioFocusRequest(focus);
        stopForeground(STOP_FOREGROUND_REMOVE);
        foreground = false;
        clearPlaybackNotifications();
        if(session!=null)session.setActive(false);
        stopSelf();
    }

    private void updateSession() {
        Library.Track item = current();
        if (item == null) return;
        if(privacyMode()) {releaseMediaSession();getSystemService(NotificationManager.class).cancel(1);return;}
        createMediaSession();
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, item.name)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, albumLabel())
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration()).build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(playing() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        position(), playing() ? speed() : 0).build());
        if (session.isActive()) getSystemService(NotificationManager.class).notify(1, notification(playing()));
    }

    private Notification notification(boolean playing) {
        if (privacyMode()) {
            return new Notification.Builder(this, PRIVATE_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle(getString(R.string.app_name)).setContentText(UiLanguage.text(PlaybackService.this, R.string.text_playing_in_background))
                    .setVisibility(Notification.VISIBILITY_SECRET)
                    .setContentIntent(openApp()).setOnlyAlertOnce(true)
                    .setOngoing(true).setShowWhen(false).setLocalOnly(true).setCategory(Notification.CATEGORY_SERVICE).setSound(null).setVibrate(null).setDefaults(0).build();
        }
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(current().name).setContentText(albumLabel()).setContentIntent(openApp())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0, 1, 2))
                .addAction(android.R.drawable.ic_media_previous, UiLanguage.text(PlaybackService.this, R.string.text_previous), action("previous"))
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? UiLanguage.text(PlaybackService.this, R.string.text_pause) : UiLanguage.text(PlaybackService.this, R.string.text_play), action("toggle"))
                .addAction(android.R.drawable.ic_media_next, UiLanguage.text(PlaybackService.this, R.string.text_next), action("next"))
                .setOnlyAlertOnce(true).setOngoing(playing).build();
    }

    private boolean privacyMode() {
        return getSharedPreferences("library", MODE_PRIVATE).getBoolean("privatePlayback", false);
    }

    void privacyChanged() {
        boolean active=foreground;
        if(active)stopForeground(STOP_FOREGROUND_REMOVE);
        clearPlaybackNotifications();
        if(privacyMode())releaseMediaSession();
        else {createMediaSession();session.setActive(active||ready);}
        if(active)startForeground(notificationId(),notification(playing()));
        updateSession();
    }

    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent action(String action) {
        return PendingIntent.getService(this, 0, new Intent(this, PlaybackService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE);
    }

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    }

    private void releasePlayer() {
        ready = systemSeeking = false;
        if (player != null) { player.release(); player = null; }
        if (usbPlayer != null) {
            ExoPlayer old = usbPlayer;
            usbPlayer = null;
            usbDeviceId = -1;
            old.release();
            UsbOutput.closeDevice(this);
        }
        usbVolumeControl = null;
        if (session != null) session.setPlaybackToLocal(attributes());
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        save();
        if (!foreground) stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        if (live == this) live = null;
        save();
        handler.removeCallbacksAndMessages(null);
        subtitles.shutdownNow();
        artworkWorker.shutdownNow();
        unregisterReceiver(unplug);
        audio.abandonAudioFocusRequest(focus);
        releasePlayer();
        if(session!=null)session.release();
        sessionWrites.shutdown();
        super.onDestroy();
    }
}


