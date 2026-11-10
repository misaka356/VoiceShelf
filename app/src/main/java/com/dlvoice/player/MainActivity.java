package com.dlvoice.player;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.content.res.ColorStateList;
import android.util.LruCache;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private AppJobs jobs;
    private UiLanguage uiLanguage;
    private int jobsRevision = -1, indexRevision;
    private List<Library.Work> works = new ArrayList<>();
    private final Map<String, List<Library.Work>> directories = new LinkedHashMap<>();
    private String activeTree = "";
    private Uri writeGrantTree;
    private final ExecutorService fileBrowserWorker = Executors.newSingleThreadExecutor();
    private int fileBrowserRequest;
    private Uri fileTree;
    private List<Library.Entry> filePath = Collections.emptyList();
    private String filesBack = "library";
    private boolean frequentHistory;
    private HomePager homePager, gesturePager;
    private long homeHistoryRevision;
    private View librarySwipeArea;
    private TextView homeTitle;
    private int gestureHomeSection;
    private int renderedFileDepth;
    private boolean homeSwipeAllowed, homeSwipeCaptured;
    private float homeSwipeX, homeSwipeY, homeSwipeVertical;
    private VelocityTracker homeVelocity;
    private String settingsBack = "library";
    private SettingsSection settingsSection = SettingsSection.HOME;
    private boolean returnAfterImport;
    private LinearLayout settingsTasks;
    private Button directoryTask, informationTask, resumeTask;
    private Button locateWorkButton;
    private PlaybackService service;
    private boolean bound, scanning, dragging;
    private LinearLayout root, libraryEmpty;
    private ListView libraryList;
    private LibraryAdapter libraryAdapter;
    private FrameLayout screenHost;
    private LinearLayout messageBar;
    private final Runnable dismissMessage = () -> {
        if (messageBar != null) {
            screenHost.removeView(messageBar);
            messageBar = null;
        }
    };
    private Runnable pendingSearch;
    private TextView searchResults, taskStatus;
    private EditText librarySearch;
    private String renderedKey = "", renderedPage = "";
    private final Map<String, Integer> scrollPositions = new HashMap<>();
    private final Map<String, Parcelable> listPositions = new HashMap<>();
    private ListView queueList, recentList, frequentList;
    private TextView queueSummary;
    private final Map<TextView, android.animation.Animator> lyricColors = new HashMap<>();
    private String lyricViewUri = "", savedLyricUri = "";
    private Parcelable savedLyrics;
    private boolean savedFollow = true, restoringLyrics;
    private Bitmap renderedCover;
    private String displayedPlayerUri = "";
    private String libraryAnchor = "";
    private int libraryAnchorOffset, libraryAnchorIndex;
    private TextView playerTitle, playerWork, clock, lyricStatus;
    private ImageView playerCover;
    private View coverShade;
    private ListView lyricList;
    private LyricsAdapter lyricAdapter;
    private List<Lrc.Cue> displayedCues;
    private android.graphics.Bitmap displayedArt;
    private String artworkTrack = "";
    private boolean followLyrics = true, detailsExpanded;
    private SeekBar seek;
    private Button toggle, playOrderButton;
    private Library.Work selected;
    private String page = "library", query = "", scanMessage = "";
    private String circleFilter = "", actorFilter = "";
    private String[] SORT_OPTIONS, HOME_SECTIONS;
    private Button shareToggle, shareCopy, shareCode, usbAuthorize, batterySettings, appPermission;
    private TextView shareStatus;
    private long shareRevision;
    private TextView privacyStatus;
    private TextView usbStatus;
    private TextView usbVolumeLabel;
    private SeekBar usbVolumeBar;
    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            UsbOutput.handleEvent(intent);
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) requestConnectedUsb();
            refreshUsbStatus();
            if (UsbOutput.PERMISSION.equals(intent.getAction())
                    && !intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                toast(getString(R.string.text_usb_permission_denied));
            }
        }
    };
    private static final int BACKGROUND = 0xff10131c, SURFACE = 0xff1b2030, ELEVATED = 0xff272e42;
    private static final int INK = 0xfff3f4fc, MUTED = 0xffaeb7ce, ACCENT = 0xffbec7ff, ON_ACCENT = 0xff232b53;
    private final LruCache<String, Bitmap> covers = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap bitmap) { return bitmap.getByteCount(); }
    };
    private boolean favoritesOnly, destroyed, batchActive;
    private volatile boolean cancelBatch;
    private final Map<String, AppJobs.UpdateResult> batchResults = new LinkedHashMap<>();
    private int batchTotal;
    private String batchCurrent = "";
    private TextView batchStatus, miniTitle, miniInfo;
    private ProgressBar batchProgress, miniProgress;
    private Button miniToggle, batchCancel;
    private String playerBack = "library", queueBack = "player";
    private boolean editingQueue;
    private BaseAdapter queueAdapter;
    private int displayedQueueIndex = -1;
    private final ExecutorService queueArtWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService workCoverWorker = Executors.newFixedThreadPool(2);
    private final Map<String, List<Consumer<Bitmap>>> pendingArt = new HashMap<>();
    private final Set<String> unavailableArt = new HashSet<>();

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).service();
            render();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; }
    };

    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(UiLanguage.attach(base));
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        uiLanguage = UiLanguage.current(this);
        SORT_OPTIONS = new String[]{getString(R.string.text_catalog_id_ascending), getString(R.string.text_date_added_newest), getString(R.string.text_modified_newest),
                getString(R.string.text_catalog_id_descending), getString(R.string.text_date_added_oldest), getString(R.string.text_modified_oldest)};
        HOME_SECTIONS = new String[]{getString(R.string.text_library), getString(R.string.text_recently_played), getString(R.string.text_most_played)};
        prefs = getSharedPreferences("library", MODE_PRIVATE);
        jobs = AppJobs.get(this);
        loadDirectories();
        synchronizeJobs(false);
        if (state != null) {
            writeGrantTree = state.getParcelable("writeGrantTree");
            page = state.getString("page", "library");
            filesBack = state.getString("filesBack", "library");
            fileTree = state.getParcelable("fileTree");
            ArrayList<String> fileIds = state.getStringArrayList("fileIds");
            ArrayList<String> fileNames = state.getStringArrayList("fileNames");
            if (fileTree != null && fileIds != null && fileNames != null && fileIds.size() == fileNames.size()) {
                filePath = new ArrayList<>();
                for (int i = 0; i < fileIds.size(); i++) {
                    filePath.add(new Library.Entry(fileIds.get(i), fileNames.get(i), true, 0));
                }
            }
            if ("files".equals(page) && filePath.isEmpty()) page = filesBack;
            frequentHistory = state.getBoolean("frequentHistory");
            settingsBack = state.getString("settingsBack", "library");
            settingsSection = SettingsSection.valueOf(state.getString("settingsSection", SettingsSection.HOME.name()));
            returnAfterImport = state.getBoolean("returnAfterImport") && jobs.scanning && jobs.scanAdded;
            playerBack = state.getString("playerBack", "library");
            queueBack = state.getString("queueBack", "player");
            query = state.getString("query", "");
            circleFilter = state.getString("circleFilter", "");
            actorFilter = state.getString("actorFilter", "");
            favoritesOnly = state.getBoolean("favoritesOnly");
            savedLyrics = state.getParcelable("lyrics");
            savedLyricUri = state.getString("lyricUri", "");
            savedFollow = state.getBoolean("follow", true);
            libraryAnchor = state.getString("libraryAnchor", "");
            libraryAnchorOffset = state.getInt("libraryOffset");
            libraryAnchorIndex = state.getInt("libraryIndex");
            Bundle positions = state.getBundle("scrollPositions");
            if (positions != null) for (String key : positions.keySet()) scrollPositions.put(key, positions.getInt(key));
            String selectedUri = state.getString("selectedUri", "");
            for (List<Library.Work> group : directories.values()) for (Library.Work work : group) {
                if (work.tracks.stream().anyMatch(track -> track.uri.equals(selectedUri))) selected = work;
            }
            if ("detail".equals(page) && selected == null) page = "library";
        }
        IntentFilter usbFilter = new IntentFilter(UsbOutput.PERMISSION);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usbFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(usbEvents, usbFilter, RECEIVER_NOT_EXPORTED);
        else registerReceiver(usbEvents, usbFilter);
        requestConnectedUsb();
        render();
        bound = bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        requestConnectedUsb();
    }

    private void requestConnectedUsb() {
        if (!prefs.getBoolean(UsbOutput.MODE, false)) return;
        UsbDevice device = UsbOutput.device(this);
        if (device != null) UsbOutput.request(this, device);
    }

    private boolean privateNotificationsHidden(){
        NotificationManager manager=getSystemService(NotificationManager.class);
        android.app.NotificationChannel channel=manager.getNotificationChannel("privatePlayback");
        return !manager.areNotificationsEnabled() || (channel!=null&&channel.getImportance()==NotificationManager.IMPORTANCE_NONE);
    }
    private void refreshPrivacyStatus(){
        if(privacyStatus==null)return;
        boolean enabled=prefs.getBoolean("privatePlayback",false);
        privacyStatus.setText(!enabled?getString(R.string.text_off):privateNotificationsHidden()?getString(R.string.text_on_background_notification_hidden):getString(R.string.text_on_background_notification_still_visible));
    }
    private void openPrivacyNotifications(){
        Intent intent=new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE,getPackageName()).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID,"privatePlayback");
        openSystemSettings(intent);
    }
    @Override protected void onResume() {
        super.onResume();
        refreshPrivacyStatus();
        refreshBackgroundStatus();
    }

    @Override protected void onStart() { super.onStart(); handler.post(update); }
    @Override protected void onStop() { handler.removeCallbacks(update); super.onStop(); }

    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (!homeSwipeCaptured) synchronizeJobs(true);
            refreshConnectionSettings();
            long shared=LibraryShareService.libraryRevision();
            if(!homeSwipeCaptured&&shared!=shareRevision){shareRevision=shared;if(homePager!=null)refreshLibraryInPlace();else if("detail".equals(page))render();else if("player".equals(page)){updateCoverAppearance();if(lyricAdapter!=null)lyricAdapter.notifyDataSetChanged();}}
            refreshHomeHistory();
            refreshPlayer();
            refreshMiniPlayer();
            refreshTaskStatus();
            if (usbVolumeBar != null && service != null && !usbVolumeBar.isPressed()) {
                int volume = service.usbVolume();
                if (usbVolumeBar.getProgress() != volume) usbVolumeBar.setProgress(volume);
            }
            if (locateWorkButton != null) locateWorkButton.setEnabled(service != null && service.current() != null);
            if (queueAdapter != null && service != null && displayedQueueIndex != service.queueIndex()) {
                displayedQueueIndex = service.queueIndex();
                queueAdapter.notifyDataSetChanged();
            }
            handler.postDelayed(this, 180);
        }
    };

    private void synchronizeJobs(boolean notify) {
        if (jobs == null || jobsRevision == jobs.revision || destroyed) return;
        boolean wasScanning = scanning, wasBatch = batchActive;
        jobsRevision = jobs.revision;
        scanning = jobs.scanning;
        scanMessage = jobs.scanMessage(uiLanguage);
        if (scanning && scanMessage.isEmpty()) scanMessage = getString(R.string.text_folder_task);
        batchActive = jobs.batchActive;
        cancelBatch = jobs.cancelBatch;
        batchTotal = jobs.batchTotal;
        batchCurrent = jobs.batchCurrent;
        batchResults.clear();
        batchResults.putAll(jobs.batchResults);
        if (indexRevision != jobs.indexRevision) {
            indexRevision = jobs.indexRevision;
            Library.Work previous = selected;
            directories.clear();
            loadDirectories();
            if (previous != null) {
                Set<String> tracks = new HashSet<>();
                for (Library.Track track : previous.tracks) tracks.add(track.uri);
                selected = directories.values().stream().flatMap(List::stream).filter(work ->
                        (!previous.identity.isEmpty() && previous.identity.equals(work.identity))
                                || work.tracks.stream().anyMatch(track -> tracks.contains(track.uri)))
                        .findFirst().orElse(null);
                if (selected == null && "detail".equals(page)) page = "library";
            }
        }
        refreshBatch();
        refreshTaskStatus();
        if (!notify) return;
        if (wasScanning && !scanning) {
            boolean openImported = returnAfterImport && jobs.scanAdded && onSettingsSection(SettingsSection.DIRECTORIES)
                    && directories.containsKey(jobs.scanKey);
            returnAfterImport = false;
            if (openImported) selectDirectory(jobs.scanKey);
            else if (onSettingsSection(SettingsSection.DIRECTORIES) || onSettingsSection(SettingsSection.HOME)
                    || onSettingsSection(SettingsSection.INFORMATION) || "library".equals(page) || "detail".equals(page)
                    || "queue".equals(page) || "player".equals(page) || "files".equals(page)
                    || "history".equals(page)) render();
            showMessage(scanMessage, "", null);
        }
        if (wasBatch && !batchActive) {
            covers.evictAll();
            pendingArt.clear();
            unavailableArt.clear();
            if (onSettingsSection(SettingsSection.INFORMATION) || "detail".equals(page)) render();
            else refreshLibraryInPlace();
            showMessage(getString(R.string.text_work_information_update_finished), getString(R.string.text_view),
                    () -> openSettingsSection(SettingsSection.INFORMATION));
        }
    }

    private void render() {
        capturePagePosition();
        if ("detail".equals(page) && selected == null) page = "library";
        if (homePager != null) homePager.finish();
        homePager = null;
        librarySwipeArea = null;
        if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
        pendingSearch = null;
        librarySearch = null;
        libraryList = null;
        libraryAdapter = null;
        libraryEmpty = null;
        recentList = frequentList = null;
        searchResults = taskStatus = null;
        settingsTasks = null;
        directoryTask = informationTask = resumeTask = null;
        for (android.animation.Animator color : lyricColors.values()) color.cancel();
        lyricColors.clear();
        LinearLayout outgoing = root;
        String fromPage = renderedPage;
        String fromKey = renderedKey;
        if (screenHost == null) {
            screenHost = new FrameLayout(this);
            screenHost.setBackgroundColor(BACKGROUND);
            setContentView(screenHost);
        }
        for (int i = screenHost.getChildCount() - 1; i >= 0; i--) {
            View child = screenHost.getChildAt(i);
            if (child == messageBar) continue;
            child.animate().cancel();
            if (child != outgoing && child != messageBar) screenHost.removeView(child);
        }
        if (outgoing != null) {
            Motion.reset(outgoing);
            outgoing.setBackgroundColor(BACKGROUND);
        }
        root = new LinearLayout(this);
        root.setOnTouchListener((view, event) -> true);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(dp(16) + insets.getSystemWindowInsetLeft(),
                    dp(10) + insets.getSystemWindowInsetTop(),
                    dp(16) + insets.getSystemWindowInsetRight(),
                    dp(10) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        screenHost.addView(root, messageBar == null ? screenHost.getChildCount() : screenHost.indexOfChild(messageBar),
                new FrameLayout.LayoutParams(-1, -1));
        root.requestApplyInsets();
        playerTitle = playerWork = clock = miniTitle = miniInfo = batchStatus = null;
        lyricStatus = null;
        shareStatus = null;
        shareToggle = shareCopy = shareCode = usbAuthorize = null;
        batterySettings = appPermission = null;
        privacyStatus=null;
        usbStatus = null;
        usbVolumeLabel = null;
        usbVolumeBar = null;
        locateWorkButton = null;
        queueAdapter = null;
        queueList = null;
        queueSummary = null;
        lyricList = null;
        lyricAdapter = null;
        playerCover = null;
        coverShade = null;
        displayedCues = null;
        displayedArt = null;
        renderedCover = null;
        displayedPlayerUri = "";
        artworkTrack = "";
        seek = null;
        toggle = playOrderButton = miniToggle = batchCancel = null;
        miniProgress = batchProgress = null;
        if ("player".equals(page)) {
            renderPlayer();
        } else if ("queue".equals(page)) {
            renderQueue();
        } else {
            if ("detail".equals(page) && selected != null) renderDetail();
            else if ("manage".equals(page)) renderManagement();
            else if ("files".equals(page)) renderFiles();
            else renderHome();
            if (!"manage".equals(page)) renderMiniPlayer();
        }
        finishRender(outgoing, fromPage, fromKey);
    }

    private String pageKey() {
        if ("manage".equals(page)) return "settings:" + settingsSection.name();
        if ("history".equals(page)) return frequentHistory ? "history:frequent" : "history:recent";
        if ("files".equals(page) && !filePath.isEmpty()) return "files:" + fileTree + ":" + filePath.get(filePath.size() - 1).id;
        return "detail".equals(page) && selected != null ? "detail:" + selected.tracks.get(0).uri : page;
    }

    private ScrollView scrollIn(View view) {
        if (view instanceof ScrollView) return (ScrollView) view;
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) {
                ScrollView scroll = scrollIn(parent.getChildAt(i));
                if (scroll != null) return scroll;
            }
        }
        return null;
    }

    private void capturePagePosition() {
        if (root == null || renderedKey.isEmpty()) return;
        ScrollView scroll = scrollIn(root);
        if (scroll != null) scrollPositions.put(renderedKey, scroll.getScrollY());
        captureLibraryPosition();
        if (queueList != null) listPositions.put(renderedKey, queueList.onSaveInstanceState());
        if (recentList != null) listPositions.put("history:recent", recentList.onSaveInstanceState());
        if (frequentList != null) listPositions.put("history:frequent", frequentList.onSaveInstanceState());
        if (lyricList != null) {
            savedLyrics = lyricList.onSaveInstanceState();
            savedLyricUri = lyricViewUri;
            savedFollow = followLyrics;
        }
    }

    private void finishRender(LinearLayout outgoing, String fromPage, String fromKey) {
        int fromFileDepth = renderedFileDepth;
        renderedFileDepth = "files".equals(page) ? filePath.size() : 0;
        renderedKey = pageKey();
        renderedPage = page;
        LinearLayout incoming = root;
        ScrollView scroll = scrollIn(incoming);
        if (scroll != null) {
            int position = scrollPositions.getOrDefault(renderedKey, 0);
            scroll.post(() -> {
                if (root == incoming) scroll.scrollTo(0, position);
            });
        }
        restoreLibraryPosition();
        if (queueList != null && listPositions.containsKey(renderedKey)) {
            queueList.onRestoreInstanceState(listPositions.get(renderedKey));
        }
        if (recentList != null && listPositions.containsKey("history:recent")) recentList.onRestoreInstanceState(listPositions.get("history:recent"));
        if (frequentList != null && listPositions.containsKey("history:frequent")) frequentList.onRestoreInstanceState(listPositions.get("history:frequent"));
        if (outgoing == null || renderedKey.equals(fromKey) || !Motion.enabled(this)) {
            if (outgoing != null) screenHost.removeView(outgoing);
            return;
        }
        boolean openingPlayer = "player".equals(page) && !"player".equals(fromPage);
        boolean closingPlayer = "player".equals(fromPage) && !"manage".equals(page);
        boolean changingFolder = fromFileDepth > 0 && renderedFileDepth > 0;
        boolean lateral = !openingPlayer && !closingPlayer && (!page.equals(fromPage) || changingFolder);
        boolean back = changingFolder ? renderedFileDepth < fromFileDepth
                : "library".equals(page) || "manage".equals(fromPage) || "queue".equals(fromPage) || "files".equals(fromPage);
        float direction = back ? -1f : 1f;
        float x = lateral ? direction * dp(44) : 0;
        float y = openingPlayer ? dp(76) : closingPlayer ? -dp(18) : lateral ? 0 : dp(8);
        outgoing.animate().alpha(0).translationY(closingPlayer ? dp(82) : openingPlayer ? -dp(16) : 0)
                .translationX(lateral ? -direction * dp(22) : 0)
                .setDuration(closingPlayer ? 275 : 240).setInterpolator(Motion.EXIT).start();
        Motion.enter(incoming, x, y, .52f, openingPlayer ? 365 : 325)
                .withEndAction(() -> screenHost.removeView(outgoing)).start();
    }

    private int homeSection() {
        if ("library".equals(page)) return 0;
        if ("history".equals(page)) return frequentHistory ? 2 : 1;
        return -1;
    }

    private void showHomeSection(int section) {
        showHomeSection(section, 0);
    }

    private void showHomeSection(int section, float velocity) {
        if (section < 0 || section >= HOME_SECTIONS.length || section == homeSection()) return;
        frequentHistory = section == 2;
        String destination = section == 0 ? "library" : "history";
        if (homePager == null) { show(destination); return; }
        fileBrowserRequest++;
        if (pendingSearch != null) {
            handler.removeCallbacks(pendingSearch);
            pendingSearch.run();
        }
        if (librarySearch != null && librarySearch.hasFocus()) {
            getSystemService(android.view.inputmethod.InputMethodManager.class)
                    .hideSoftInputFromWindow(librarySearch.getWindowToken(), 0);
            librarySearch.clearFocus();
        }
        page = renderedPage = destination;
        renderedKey = pageKey();
        homeTitle.setText(HOME_SECTIONS[section]);
        homePager.settle(section, velocity);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (homePager != null) homePager.finish();
            releaseHomeVelocity();
            gestureHomeSection = homeSection();
            gesturePager = homePager;
            homeSwipeX = event.getRawX();
            homeSwipeY = event.getRawY();
            homeSwipeVertical = 0;
            homeSwipeCaptured = false;
            homeSwipeAllowed = gestureHomeSection >= 0 && startsHomeSwipe();
            if (homeSwipeAllowed) homeVelocity = VelocityTracker.obtain();
        } else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
            homeSwipeAllowed = false;
        }
        if (homeVelocity != null) homeVelocity.addMovement(event);
        if (gesturePager != homePager) homeSwipeAllowed = false;

        if (homeSwipeAllowed && action == MotionEvent.ACTION_MOVE) {
            float horizontal = Math.abs(event.getRawX() - homeSwipeX);
            homeSwipeVertical = Math.max(homeSwipeVertical, Math.abs(event.getRawY() - homeSwipeY));
            if (!homeSwipeCaptured) {
                int slop = ViewConfiguration.get(this).getScaledTouchSlop();
                if (homeSwipeVertical > slop && horizontal < homeSwipeVertical * 1.5f) {
                    homeSwipeAllowed = false;
                } else if (horizontal > Math.max(dp(18), slop * 2) && horizontal > homeSwipeVertical * 1.8f) {
                    homeSwipeCaptured = true;
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    super.dispatchTouchEvent(cancel);
                    cancel.recycle();
                }
            }
            if (homeSwipeCaptured) homePager.drag(event.getRawX() - homeSwipeX);
        }
        if (homeSwipeCaptured) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                homeSwipeCaptured = false;
                boolean changed = false;
                if (homeSwipeAllowed && gestureHomeSection == homeSection() && homePager != null) {
                    float delta = event.getRawX() - homeSwipeX;
                    float horizontal = Math.abs(delta);
                    float vertical = Math.max(homeSwipeVertical, Math.abs(event.getRawY() - homeSwipeY));
                    homeVelocity.computeCurrentVelocity(1000);
                    float velocity = homeVelocity.getXVelocity();
                    boolean flick = horizontal >= dp(44) && Math.abs(velocity) >= dp(750) && velocity * delta > 0;
                    int target = gestureHomeSection + (delta < 0 ? 1 : -1);
                    if ((horizontal >= Math.max(dp(76), homePager.getWidth() * .18f) || flick)
                            && vertical <= Math.min(dp(52), horizontal / 1.8f)
                            && target >= 0 && target < HOME_SECTIONS.length) {
                        homePager.drag(delta);
                        showHomeSection(target, velocity);
                        changed = true;
                    }
                }
                if (!changed && gesturePager != null && gesturePager == homePager) homePager.settle(gestureHomeSection, 0);
                homeSwipeAllowed = false;
                releaseHomeVelocity();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) releaseHomeVelocity();
        return super.dispatchTouchEvent(event);
    }

    private void releaseHomeVelocity() {
        if (homeVelocity != null) {
            homeVelocity.recycle();
            homeVelocity = null;
        }
    }

    private boolean startsHomeSwipe() {
        View homeSwipeArea = homeSection() == 0 ? librarySwipeArea : homePager;
        if (homeSwipeArea == null || !homeSwipeArea.isShown() || (librarySearch != null && librarySearch.hasFocus())) return false;
        int[] location = new int[2];
        homeSwipeArea.getLocationOnScreen(location);
        int edge = dp(10);
        return homeSwipeX >= location[0] + edge && homeSwipeX < location[0] + homeSwipeArea.getWidth() - edge
                && homeSwipeY >= location[1] && homeSwipeY < location[1] + homeSwipeArea.getHeight();
    }

    private void renderHomeHeader() {
        LinearLayout brand = row(root);
        ImageView logo = new ImageView(this);
        logo.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        logo.setPadding(dp(3), dp(3), dp(3), dp(3));
        logo.setBackground(clickable(ELEVATED, 12));
        logo.setContentDescription(getString(R.string.text_open_sidebar));
        logo.setTooltipText(getString(R.string.text_open_sidebar));
        logo.setFocusable(true);
        logo.setOnClickListener(view -> openLibraryDrawer());
        Motion.press(logo);
        LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(dp(46), dp(46));
        icon.rightMargin = dp(8);
        brand.addView(logo, icon);
        homeTitle = heading(column(brand), HOME_SECTIONS[Math.max(0, homeSection())]);
        Button random = button(brand, getString(R.string.text_shuffle), this::playRandomWork);
        random.setContentDescription(getString(R.string.text_play_a_random_work_and_track));
        random.setTooltipText(getString(R.string.text_play_a_random_work_and_track));
        LinearLayout.LayoutParams randomSize = new LinearLayout.LayoutParams(dp(46), dp(44));
        randomSize.rightMargin = dp(6);
        random.setLayoutParams(randomSize);
        Button settings = button(brand, "⚙", this::openSettings);
        settings.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(44)));
    }

    private void renderHome() {
        renderHomeHeader();
        LinearLayout library = new LinearLayout(this);
        library.setOrientation(LinearLayout.VERTICAL);
        renderLibrary(library);
        List<HistoryRow> history = historyRows();
        homeHistoryRevision = PlaybackHistory.revision();
        homePager = new HomePager(this, new View[]{library, renderHistory(history, false), renderHistory(history, true)},
                Math.max(0, homeSection()));
        root.addView(homePager, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void renderLibrary(LinearLayout body) {
        EditText search = new EditText(this);
        librarySearch = search;
        search.setSingleLine(true);
        search.setTextColor(INK);
        search.setHintTextColor(MUTED);
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(shape(SURFACE, 14));
        search.setHint(getString(R.string.text_search_id_title_circle_or_cast));
        search.setText(query);
        LinearLayout tools = row(body);
        tools.setPadding(0, dp(6), 0, dp(4));
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((view, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
                pendingSearch = null;
                fillLibrary();
                getSystemService(android.view.inputmethod.InputMethodManager.class)
                        .hideSoftInputFromWindow(search.getWindowToken(), 0);
                search.clearFocus();
                return true;
            }
            return false;
        });
        tools.addView(search, new LinearLayout.LayoutParams(0, dp(46), 1));
        Button clear = button(tools, getString(R.string.text_clear_search), () -> search.setText(""));
        clear.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(44)));
        clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
        Button filter = button(tools, getString(R.string.text_filter), this::showLibraryFilters);
        LinearLayout.LayoutParams filterSize = new LinearLayout.LayoutParams(dp(46), dp(46));
        filterSize.leftMargin = dp(8);
        filter.setLayoutParams(filterSize);
        if (favoritesOnly || !circleFilter.isEmpty() || !actorFilter.isEmpty() || sortOrder()!=0) highlight(filter);
        renderFilterChips(body);
        searchResults = caption(body, "");
        taskStatus = caption(body, "");
        taskStatus.setTextColor(ACCENT);
        taskStatus.setOnClickListener(view -> openTaskSettings());
        refreshTaskStatus();
        FrameLayout libraryArea = new FrameLayout(this);
        librarySwipeArea = libraryArea;
        body.addView(libraryArea, new LinearLayout.LayoutParams(-1, 0, 1));
        libraryList = new ListView(this);
        libraryList.setDivider(new android.graphics.drawable.ColorDrawable(BACKGROUND));
        libraryList.setDividerHeight(dp(10));
        libraryList.setClipToPadding(false);
        libraryList.setPadding(0, dp(5), 0, dp(64));
        libraryAdapter = new LibraryAdapter();
        libraryList.setAdapter(libraryAdapter);
        libraryArea.addView(libraryList, new FrameLayout.LayoutParams(-1, -1));
        libraryEmpty = new LinearLayout(this);
        libraryEmpty.setOrientation(LinearLayout.VERTICAL);
        libraryArea.addView(libraryEmpty, new FrameLayout.LayoutParams(-1, -2));
        locateWorkButton = new IconButton(this, getString(R.string.text_locate_current_work));
        styleButton(locateWorkButton, true);
        locateWorkButton.setOnClickListener(view -> locateCurrentWork());
        locateWorkButton.setEnabled(service != null && service.current() != null);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.BOTTOM | Gravity.END);
        position.setMargins(0, 0, dp(6), dp(10));
        libraryArea.addView(locateWorkButton, position);
        fillLibrary();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString();
                clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
                pendingSearch = () -> {
                    pendingSearch = null;
                    if (librarySearch == search && "library".equals(page)) {
                        fillLibrary();
                        libraryList.setSelection(0);
                    }
                };
                handler.postDelayed(pendingSearch, 110);
            }
            public void afterTextChanged(Editable e) {}
        });
        body.setFocusableInTouchMode(true);
        if (homeSection() == 0) body.requestFocus();
    }

    private boolean matchesFilter(Library.Work work,boolean favorites,String circle,String actor){
        if(favorites&&!favorite(work))return false;
        String key = query.trim().toLowerCase(Locale.ROOT);
        if (circle.isEmpty() && actor.isEmpty() && key.isEmpty()) return true;
        JSONObject info=Metadata.saved(prefs,work.id);
        if(!circle.isEmpty()&&!circle.equals(info.optString("circle").trim()))return false;
        if(!actor.isEmpty()&&!actorNames(info).contains(actor))return false;
        return key.isEmpty() || (work.id+" "+Metadata.onlineId(work.id)+" "+title(work.id, info)+" "+info.optString("title")+" "+info.optString("circle")+" "+info.optString("actors")).toLowerCase(Locale.ROOT).contains(key);
    }
    private int matchingCount(boolean favorites,String circle,String actor){
        Set<String> ids=new HashSet<>();for(Library.Work work:works)if(matchesFilter(work,favorites,circle,actor))ids.add(Metadata.onlineId(work.id));return ids.size();
    }
    private void showLibraryFilters(){
        if(librarySearch!=null){librarySearch.clearFocus();getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(librarySearch.getWindowToken(),0);}
        String scope=(activeTree.isEmpty()?getString(R.string.text_all_folders):directoryName(activeTree))+(query.trim().isEmpty()?"":getString(R.string.text_search)+query.trim());
        new LibraryFilters(this,favoritesOnly,circleFilter,actorFilter,sortOrder(),scope,new LibraryFilters.Source(){
            public int count(boolean fav,String circle,String actor){return matchingCount(fav,circle,actor);}
            public Map<String,Integer> credits(boolean circles,boolean fav,String circle,String actor){
                Map<String,Set<String>> ids=new HashMap<>();
                for(Library.Work work:works){
                    if(!matchesFilter(work,fav,circles?"":circle,circles?actor:""))continue;
                    JSONObject info=Metadata.saved(prefs,work.id);Set<String> names=circles?Collections.singleton(info.optString("circle").trim()):actorNames(info);
                    for(String name:names)if(!name.isEmpty())ids.computeIfAbsent(name,k->new HashSet<>()).add(Metadata.onlineId(work.id));
                }
                Map<String,Integer> counts=new HashMap<>();for(Map.Entry<String,Set<String>> entry:ids.entrySet())counts.put(entry.getKey(),entry.getValue().size());return counts;
            }
            public void apply(boolean fav,String circle,String actor,int sort){
                favoritesOnly=fav;circleFilter=circle;actorFilter=actor;prefs.edit().putInt("workSort",sort).apply();
                render();
                if (libraryList != null) libraryList.setSelection(0);
            }
        }).show();
    }

    private void renderFilterChips(LinearLayout body){
        if(!favoritesOnly&&circleFilter.isEmpty()&&actorFilter.isEmpty()&&sortOrder()==0)return;
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips=new LinearLayout(this);chips.setPadding(0,dp(4),0,dp(6));scroll.addView(chips);
        if(favoritesOnly)filterChip(chips,getString(R.string.text_favorites),()->{favoritesOnly=false;render();});
        if(!circleFilter.isEmpty())filterChip(chips,getString(R.string.text_circle_2)+circleFilter+" ×",()->{circleFilter="";render();});
        if(!actorFilter.isEmpty())filterChip(chips,getString(R.string.text_cast_2)+actorFilter+" ×",()->{actorFilter="";render();});
        if(sortOrder()!=0)filterChip(chips,SORT_OPTIONS[sortOrder()]+" ×",()->{prefs.edit().putInt("workSort",0).apply();render();});
        body.addView(scroll,new LinearLayout.LayoutParams(-1,-2));
    }
    private void filterChip(LinearLayout parent,String value,Runnable clear){
        Button chip=new Button(this);chip.setText(value);chip.setTextSize(12);chip.setTextColor(ACCENT);chip.setAllCaps(false);chip.setSingleLine(true);chip.setEllipsize(TextUtils.TruncateAt.END);chip.setMaxWidth(dp(220));chip.setPadding(dp(12),0,dp(12),0);chip.setMinWidth(0);chip.setMinimumWidth(0);chip.setBackground(clickable(ELEVATED,18));chip.setOnClickListener(v->clear.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(40));p.rightMargin=dp(6);parent.addView(chip,p);
    }

    private int sortOrder() {
        int order = prefs.getInt("workSort", 0);
        return order >= 0 && order < SORT_OPTIONS.length ? order : 0;
    }

    private void refreshSortTimes() {
        if (!jobs.refreshTimes(new ArrayList<>(directories.keySet()))) {
            toast(getString(R.string.text_a_folder_scan_is_already_running));
            return;
        }
        synchronizeJobs(false);
        if (onSettingsSection(SettingsSection.DIRECTORIES)) render();
    }

    private static int compareWorkGroups(List<Library.Work> left, List<Library.Work> right, int order) {
        Library.Work a = left.get(0), b = right.get(0);
        int field = order % 3;
        if (field != 0) {
            long aTime = left.stream().mapToLong(work -> field == 1 ? work.addedAt : work.modifiedAt).max().orElse(0);
            long bTime = right.stream().mapToLong(work -> field == 1 ? work.addedAt : work.modifiedAt).max().orElse(0);
            if (aTime == 0 && bTime != 0) return 1;
            if (bTime == 0 && aTime != 0) return -1;
            int time = order < 3 ? Long.compare(bTime, aTime) : Long.compare(aTime, bTime);
            if (time != 0) return time;
        }
        if (!a.numbered() || !b.numbered()) return Boolean.compare(b.numbered(), a.numbered());
        int number = new java.math.BigInteger(a.id.substring(2)).compareTo(new java.math.BigInteger(b.id.substring(2)));
        return order == 3 ? -number : number;
    }
    private static Set<String> actorNames(JSONObject info) {
        Set<String> actors = new LinkedHashSet<>();
        for (String name : info.optString("actors").split("[、，,／/\\r\\n]+")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) actors.add(trimmed);
        }
        return actors;
    }

    private Map<String, List<Library.Work>> visibleWorks(boolean filtered) {
        Map<String, List<Library.Work>> groups = new LinkedHashMap<>();
        for (Library.Work work : works) {
            if(filtered&&!matchesFilter(work,favoritesOnly,circleFilter,actorFilter))continue;
            groups.computeIfAbsent(Metadata.onlineId(work.id), key -> new ArrayList<>()).add(work);
        }
        return groups;
    }

    private void fillLibrary() {
        Map<String,List<Library.Work>> groups = visibleWorks(true);
        if(searchResults!=null){searchResults.setText(query.isEmpty()&&circleFilter.isEmpty()&&actorFilter.isEmpty()&&!favoritesOnly?"":getString(R.string.text_found)+groups.size()+getString(R.string.text_works));searchResults.setVisibility(searchResults.length()==0?View.GONE:View.VISIBLE);}
        List<List<Library.Work>> sorted = new ArrayList<>(groups.values());
        int order = sortOrder();
        sorted.sort((a, b) -> compareWorkGroups(a, b, order));
        libraryAdapter.rows = sorted;
        libraryAdapter.order = order;
        libraryAdapter.notifyDataSetChanged();
        libraryEmpty.removeAllViews();
        libraryEmpty.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
        if (groups.isEmpty()) {
            LinearLayout empty=panel(libraryEmpty);text(empty,works.isEmpty()?getString(R.string.text_no_works_yet):getString(R.string.text_no_matching_works),20);
            if(works.isEmpty()){caption(empty,getString(R.string.text_choose_a_folder_containing_rj_works_to_organize_));highlight(button(empty,getString(R.string.text_choose_folder),this::choose));}
            else button(empty,getString(R.string.text_clear_search_and_filters),()->{query=circleFilter=actorFilter="";favoritesOnly=false;render();});
        }
    }

    private final class LibraryAdapter extends BaseAdapter {
        List<List<Library.Work>> rows = Collections.emptyList();
        int order;
        private final java.text.DateFormat date = new java.text.SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ROOT);

        @Override public int getCount() { return rows.size(); }
        @Override public List<Library.Work> getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        int indexOf(String key) {
            for (int i = 0; i < rows.size(); i++) {
                if (Metadata.onlineId(rows.get(i).get(0).id).equals(key)) return i;
            }
            return -1;
        }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            WorkCard card = recycled instanceof WorkCard ? (WorkCard) recycled : new WorkCard();
            List<Library.Work> sources = getItem(position);
            Library.Work work = sources.get(0);
            int count = sources.stream().mapToInt(source -> source.tracks.size()).sum();
            JSONObject info = Metadata.saved(prefs, work.id);
            String circle = info.optString("circle");
            card.setTag(Metadata.onlineId(work.id));
            card.name.setText(title(work.id, info));
            card.info.setText((!work.numbered() ? getString(R.string.text_unsorted) : circle.isEmpty() ? work.id : circle) + " · " + count + getString(R.string.text_tracks)
                    + (sources.size() > 1 ? " · " + sources.size() + getString(R.string.text_sources) : "") + (favorite(work) ? " · ★" : ""));
            card.date.setVisibility(order % 3 == 0 ? View.GONE : View.VISIBLE);
            if (order % 3 != 0) {
                long value = sources.stream().mapToLong(source -> order % 3 == 1 ? source.addedAt : source.modifiedAt).max().orElse(0);
                card.date.setText(value == 0 ? getString(R.string.text_unknown_date) : date.format(new Date(value)));
            }
            card.setBackground(clickable(SURFACE, 16));
            card.setOnClickListener(view -> { selected = work; detailsExpanded = false; show("detail"); });
            card.setOnLongClickListener(view -> { workActions(sources); return true; });
            bindPreviewableCover(card.cover, work.id, card::performLongClick);
            return card;
        }
    }

    private final class WorkCard extends LinearLayout {
        final CoverTile cover;
        final TextView name, info, date;

        WorkCard() {
            super(MainActivity.this);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(12), dp(10), dp(12), dp(10));
            setBackground(clickable(SURFACE, 16));
            setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            setLayoutParams(new AbsListView.LayoutParams(-1, -2));
            Motion.press(this);
            cover = addCoverTile(this, null, 58);
            LinearLayout labels = column(this);
            labels.setPadding(dp(12), 0, 0, 0);
            name = text(labels, "", 17);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            info = caption(labels, "");
            info.setMaxLines(2);
            info.setEllipsize(TextUtils.TruncateAt.END);
            date = caption(labels, "");
        }
    }

    private void openLibraryDrawer() {
        Dialog drawer = new Dialog(this);
        drawer.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackground(shape(SURFACE, 18));
        shell.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout content = scroll(shell);
        LinearLayout header = row(content);
        heading(column(header), getString(R.string.app_name));
        Button close = button(header, getString(R.string.text_close), drawer::dismiss);
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(44)));
        caption(content, getString(R.string.text_listening)).setTextColor(ACCENT);
        for (int i = 0; i < HOME_SECTIONS.length; i++) {
            int section = i;
            Button link = drawerItem(content, drawer, HOME_SECTIONS[i], () -> showHomeSection(section));
            if (section == homeSection()) { highlight(link); link.setSelected(true); }
        }
        TextView management = caption(content, getString(R.string.text_audio_folders));
        management.setPadding(0, dp(14), 0, dp(3));
        management.setTextColor(ACCENT);
        String directory = activeTree.isEmpty() ? getString(R.string.text_all_folders) : directoryName(activeTree);
        Button switchDirectory = drawerItem(content, drawer, getString(R.string.text_switch_folder) + directory, this::chooseLibraryDirectory);
        switchDirectory.setSingleLine(true);
        switchDirectory.setEllipsize(TextUtils.TruncateAt.END);
        drawerItem(content, drawer, getString(R.string.text_file_manager), this::chooseFileDirectory);
        TextView application = caption(content, getString(R.string.text_app));
        application.setPadding(0, dp(14), 0, dp(3));
        application.setTextColor(ACCENT);
        drawerItem(content, drawer, getString(R.string.text_settings), this::openSettings);

        drawer.setContentView(shell);
        drawer.setCanceledOnTouchOutside(true);
        Window window = drawer.getWindow();
        if (window != null) {
            Motion.window(window, R.style.MotionDrawer);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(.48f);
            window.setGravity(Gravity.START | Gravity.TOP);
            window.setLayout(Math.min(dp(320), Math.round(getResources().getDisplayMetrics().widthPixels * .86f)), -1);
        }
        drawer.show();
    }

    private Button drawerItem(LinearLayout content, Dialog drawer, String label, Runnable action) {
        Button entry = button(content, label, () -> {
            drawer.dismiss();
            action.run();
        });
        entry.setTextSize(16);
        entry.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        entry.setPadding(dp(14), 0, dp(14), 0);
        return entry;
    }

    private static final class HistoryRow {
        final Library.Work work;
        final Library.Track track;
        final PlaybackHistory.Entry entry;

        HistoryRow(Library.Work work, Library.Track track, PlaybackHistory.Entry entry) {
            this.work = work;
            this.track = track;
            this.entry = entry;
        }
    }

    private List<HistoryRow> historyRows() {
        Map<String, PlaybackHistory.Entry> history = PlaybackHistory.read(this);
        String last = prefs.getString("last", "");
        if (!last.isEmpty()) history.putIfAbsent(last, new PlaybackHistory.Entry(0, 0));
        List<HistoryRow> rows = new ArrayList<>();
        for (List<Library.Work> group : directories.values()) for (Library.Work work : group) {
            for (Library.Track track : work.tracks) {
                if (history.isEmpty()) return rows;
                PlaybackHistory.Entry entry = history.remove(track.uri);
                if (entry != null) rows.add(new HistoryRow(work, track, entry));
            }
        }
        return rows;
    }

    private void refreshHomeHistory() {
        long revision = PlaybackHistory.revision();
        if (homePager == null || revision == homeHistoryRevision || homeSwipeCaptured || homePager.isMoving()) return;
        Parcelable recentPosition = recentList == null ? null : recentList.onSaveInstanceState();
        Parcelable frequentPosition = frequentList == null ? null : frequentList.onSaveInstanceState();
        recentList = frequentList = null;
        List<HistoryRow> history = historyRows();
        homePager.replacePage(1, renderHistory(history, false));
        homePager.replacePage(2, renderHistory(history, true));
        if (recentList != null && recentPosition != null) recentList.onRestoreInstanceState(recentPosition);
        if (frequentList != null && frequentPosition != null) frequentList.onRestoreInstanceState(frequentPosition);
        homeHistoryRevision = revision;
    }

    private View renderHistory(List<HistoryRow> history, boolean frequent) {
        FrameLayout area = new FrameLayout(this);
        List<HistoryRow> rows = new ArrayList<>(history);
        if (frequent) rows.removeIf(item -> item.entry.count <= 0);
        Comparator<HistoryRow> recentFirst = Comparator.comparingLong((HistoryRow item) -> item.entry.playedAt).reversed();
        rows.sort(frequent ? Comparator.comparingInt((HistoryRow item) -> item.entry.count).reversed().thenComparing(recentFirst)
                : recentFirst);
        if (rows.isEmpty()) {
            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            area.addView(body, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout empty = panel(body);
            text(empty, frequent ? getString(R.string.text_no_play_counts_yet) : getString(R.string.text_no_playback_history_yet), 17);
            caption(empty, getString(R.string.text_play_a_track_to_see_it_here));
            return area;
        }

        ListView records = new ListView(this);
        if (frequent) frequentList = records;
        else recentList = records;
        records.setDivider(new android.graphics.drawable.ColorDrawable(BACKGROUND));
        records.setDividerHeight(dp(10));
        records.setPadding(0, dp(5), 0, dp(5));
        java.text.DateFormat date = new java.text.SimpleDateFormat("MM/dd HH:mm", Locale.ROOT);
        records.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int position) { return rows.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                WorkCard card = recycled instanceof WorkCard ? (WorkCard) recycled : new WorkCard();
                HistoryRow item = rows.get(position);
                String when = frequent ? item.entry.count + getString(R.string.text_plays)
                        : item.entry.playedAt > 0 ? date.format(new Date(item.entry.playedAt)) : getString(R.string.text_played_before_the_update);
                card.name.setText(fileName(item.track.name));
                card.info.setText(title(item.work));
                card.date.setText(when);
                card.date.setVisibility(View.VISIBLE);
                card.setOnClickListener(view -> resume(item.work, item.track.uri));
                card.setOnLongClickListener(view -> { trackOptions(item.work, item.track); return true; });
                bindPreviewableCover(card.cover, item.work.id, card::performLongClick);
                return card;
            }
        });
        area.addView(records, new FrameLayout.LayoutParams(-1, -1));
        return area;
    }

    private void workActions(List<Library.Work> sources) {
        Library.Work work = sources.get(0);
        new AppDialog.Builder(this).setTitle(title(work))
                .setItems(new String[]{getString(R.string.text_resume), getString(R.string.text_play_from_start), favorite(work) ? getString(R.string.text_remove_from_favorites) : getString(R.string.text_add_to_favorites), getString(R.string.text_manage_folder)},
                        (dialog, action) -> {
                             if (action == 0) resume(work, workResumeUri(work));
                            else if (action == 1) play(work, 0, 0);
                             else if (action == 2) {
                                prefs.edit().putBoolean("favorite:" + Metadata.onlineId(work.id), !favorite(work)).apply();
                                refreshLibraryInPlace();
                             } else if (sources.size() == 1) manageWorkFolder(work, "", false);
                             else new AppDialog.Builder(this).setTitle(getString(R.string.text_choose_a_source_to_manage))
                                     .setItems(sources.stream().map(source -> source.source).toArray(String[]::new),
                                             (picker, index) -> manageWorkFolder(sources.get(index), "", false)).show();
                        }).setNegativeButton(getString(R.string.text_close), null).show();
    }

    private void refreshLibraryInPlace(){
        if (libraryList == null) return;
        captureLibraryPosition();
        fillLibrary();
        restoreLibraryPosition();
    }

    private void captureLibraryPosition() {
        if (libraryList == null) return;
        View first = libraryList.getChildAt(0);
        if (first == null) return;
        libraryAnchor = first.getTag() instanceof String ? (String) first.getTag() : "";
        libraryAnchorIndex = libraryList.getFirstVisiblePosition();
        libraryAnchorOffset = first.getTop() - libraryList.getPaddingTop();
    }

    private void restoreLibraryPosition() {
        if (libraryList == null || libraryAdapter.getCount() == 0) return;
        int position = libraryAdapter.indexOf(libraryAnchor);
        if (position < 0) position = Math.min(libraryAnchorIndex, libraryAdapter.getCount() - 1);
        libraryList.setSelectionFromTop(position, libraryAnchorOffset);
    }

    private void showMessage(String message, String action, Runnable operation) {
        handler.removeCallbacks(dismissMessage);
        dismissMessage.run();
        if (screenHost == null || destroyed) return;
        messageBar = new LinearLayout(this);
        messageBar.setGravity(Gravity.CENTER_VERTICAL);
        messageBar.setPadding(dp(14), dp(8), dp(8), dp(8));
        messageBar.setBackground(shape(ELEVATED, 14));
        TextView label = new TextView(this);
        label.setTextColor(INK);
        label.setTextSize(14);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setText(message);
        messageBar.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (operation != null) {
            button(messageBar, action, () -> {
                handler.removeCallbacks(dismissMessage);
                dismissMessage.run();
                operation.run();
            }).setLayoutParams(new LinearLayout.LayoutParams(dp(76), dp(44)));
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        int bottom = root == null ? 0 : root.getPaddingBottom();
        params.setMargins(dp(20), 0, dp(20), bottom + dp(88));
        screenHost.addView(messageBar, params);
        Motion.appear(messageBar);
        handler.postDelayed(dismissMessage, operation == null ? 3500 : 6000);
    }

    private void refreshTaskStatus() {
        if (taskStatus == null && settingsTasks == null) return;
        List<String> tasks = new ArrayList<>();
        if (batchActive) tasks.add(getString(R.string.text_updating_work_information) + batchResults.size() + "/" + batchTotal);
        if (settingsTasks != null) {
            boolean canResume = jobs.canResume();
            setText(directoryTask, scanMessage);
            directoryTask.setVisibility(scanning ? View.VISIBLE : View.GONE);
            setText(informationTask, String.join("\n", tasks));
            informationTask.setVisibility(tasks.isEmpty() ? View.GONE : View.VISIBLE);
            resumeTask.setVisibility(canResume ? View.VISIBLE : View.GONE);
            settingsTasks.setVisibility(scanning || !tasks.isEmpty() || canResume ? View.VISIBLE : View.GONE);
        }
        if (taskStatus != null) {
            if (scanning) tasks.add(0, scanMessage);
            setText(taskStatus, String.join("  ·  ", tasks));
            taskStatus.setVisibility(tasks.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void locateCurrentWork(){
        if (service == null || service.current() == null || libraryList == null) return;
        String key = Metadata.onlineId(service.workId());
        if (libraryAdapter.indexOf(key) < 0) {
            query = circleFilter = actorFilter = "";
            favoritesOnly = false;
            if (!visibleWorks(false).containsKey(key)) {
                String uri = service.current().uri;
                for (Map.Entry<String, List<Library.Work>> entry : directories.entrySet()) {
                    if (entry.getValue().stream().anyMatch(work -> work.tracks.stream().anyMatch(track -> track.uri.equals(uri)))) {
                        selectDirectory(entry.getKey());
                        break;
                    }
                }
            } else render();
        }
        int index = libraryAdapter.indexOf(key);
        if (index < 0) { toast(getString(R.string.text_current_work_is_outside_the_library)); return; }
        ListView target = libraryList;
        target.setSelectionFromTop(index, 0);
        target.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                target.getViewTreeObserver().removeOnPreDrawListener(this);
                View card = target.findViewWithTag(key);
                if (target == libraryList && card != null) {
                    card.setBackground(clickable(ELEVATED, 16));
                    handler.postDelayed(() -> {
                        if (key.equals(card.getTag())) card.setBackground(clickable(SURFACE, 16));
                    }, 1200);
                }
                return true;
            }
        });
    }

    private enum SettingsSection {
        HOME(R.string.text_settings), DIRECTORIES(R.string.text_audio_folders), INFORMATION(R.string.text_work_information), OUTPUT(R.string.text_playback_output),
        DISPLAY(R.string.text_lyrics_motion), PRIVACY(R.string.text_privacy_background), CONNECTION(R.string.text_pc_connection);

        final int title;
        SettingsSection(int title) { this.title = title; }
    }

    private boolean onSettingsSection(SettingsSection section) {
        return "manage".equals(page) && settingsSection == section;
    }

    private void openSettings() { openSettingsSection(SettingsSection.HOME); }

    private void openSettingsSection(SettingsSection section) {
        if (onSettingsSection(section)) return;
        if (librarySearch != null && librarySearch.hasFocus()) {
            getSystemService(android.view.inputmethod.InputMethodManager.class)
                    .hideSoftInputFromWindow(librarySearch.getWindowToken(), 0);
            librarySearch.clearFocus();
        }
        if (!"manage".equals(page)) settingsBack = page;
        if (section != SettingsSection.DIRECTORIES) returnAfterImport = false;
        settingsSection = section;
        show("manage");
    }

    private void backFromSettings() {
        returnAfterImport = false;
        if (settingsSection == SettingsSection.HOME) show(settingsBack);
        else openSettings();
    }

    private void openTaskSettings() {
        boolean information = batchActive;
        if (scanning && !information) openSettingsSection(SettingsSection.DIRECTORIES);
        else if (information && !scanning) openSettingsSection(SettingsSection.INFORMATION);
        else openSettings();
    }

    @Override public boolean onKeyDown(int code, KeyEvent event) {
        if (service != null && service.usingUsb()
                && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) {
            service.setUsbVolume(service.usbVolume() + (code == KeyEvent.KEYCODE_VOLUME_UP ? 5 : -5));
            return true;
        }
        return super.onKeyDown(code, event);
    }

    @Override public boolean onKeyUp(int code, KeyEvent event) {
        if (service != null && service.usingUsb()
                && (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN)) return true;
        return super.onKeyUp(code, event);
    }

    private void renderManagement() {
        LinearLayout navigation = row(root);
        Button back = button(navigation, getString(R.string.text_back), this::backFromSettings);
        back.setContentDescription(settingsSection == SettingsSection.HOME ? getString(R.string.text_back_2) : getString(R.string.text_back_to_settings));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(44)));
        heading(column(navigation), getString(settingsSection.title)).setTextSize(26);
        LinearLayout content = scroll(root);
        switch (settingsSection) {
            case DIRECTORIES: renderDirectorySettings(content); break;
            case INFORMATION: renderInformationSettings(content); break;
            case OUTPUT: renderPlaybackSettings(content); break;
            case DISPLAY: renderBackgroundSettings(content); break;
            case PRIVACY: renderPrivacySettings(content); break;
            case CONNECTION: renderConnectionSettings(content); break;
            default: renderSettingsHome(content); break;
        }
    }

    private void renderSettingsHome(LinearLayout content) {
        settingsTasks = panel(content);
        text(settingsTasks, getString(R.string.text_tasks), 18);
        directoryTask = button(settingsTasks, getString(R.string.text_folder_task), () -> openSettingsSection(SettingsSection.DIRECTORIES));
        informationTask = button(settingsTasks, getString(R.string.text_work_information_task), () -> openSettingsSection(SettingsSection.INFORMATION));
        resumeTask = button(settingsTasks, getString(R.string.text_resume_unfinished_tasks), this::resumeSettingsTasks);
        refreshTaskStatus();

        settingsGroup(content, getString(R.string.text_library));
        String directorySummary = directories.isEmpty() ? getString(R.string.text_add_an_audio_folder_on_this_device)
                : directories.size() + getString(R.string.text_folders_scan_permissions_and_files);
        settingsEntry(content, SettingsSection.DIRECTORIES, directorySummary);
        settingsEntry(content, SettingsSection.INFORMATION, getString(R.string.text_download_metadata_translate_titles_and_view_resu));
        settingsGroup(content, getString(R.string.text_playback_experience));
        settingsEntry(content, SettingsSection.OUTPUT, getString(R.string.text_exclusive_usb_dac_permissions_and_volume));
        settingsEntry(content, SettingsSection.DISPLAY, getString(R.string.text_cover_background_lyric_opacity_and_motion));
        settingsEntry(content, SettingsSection.PRIVACY, getString(R.string.text_private_playback_notifications_and_background_ac));
        settingsGroup(content, getString(R.string.text_device_connection));
        settingsEntry(content, SettingsSection.CONNECTION, getString(R.string.text_local_network_sharing_and_pc_pairing));
        settingsGroup(content, getString(R.string.text_app));
        settingsEntry(content, getString(R.string.text_app_language), uiLanguage.label, this::chooseLanguage);
    }

    private void settingsGroup(LinearLayout content, String title) {
        TextView label = caption(content, title);
        label.setTextColor(ACCENT);
        label.setPadding(dp(4), dp(14), 0, dp(4));
    }

    private void settingsEntry(LinearLayout content, SettingsSection section, String summary) {
        settingsEntry(content, getString(section.title), summary, () -> openSettingsSection(section));
    }

    private void settingsEntry(LinearLayout content, String title, String summary, Runnable open) {
        LinearLayout entry = cardRow(content);
        entry.setMinimumHeight(dp(64));
        entry.setFocusable(true);
        LinearLayout labels = column(entry);
        text(labels, title, 16);
        caption(labels, summary);
        TextView arrow = text(entry, "›", 24);
        arrow.setTextColor(MUTED);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        arrow.setLayoutParams(new LinearLayout.LayoutParams(dp(20), -2));
        entry.setOnClickListener(view -> open.run());
    }

    private void chooseLanguage() {
        UiLanguage[] languages = UiLanguage.values();
        String[] labels = Arrays.stream(languages).map(language -> language.label).toArray(String[]::new);
        new AppDialog.Builder(this).setTitle(getString(R.string.text_app_language)).setSingleChoiceItems(labels, uiLanguage.ordinal(), (dialog, choice) -> {
            dialog.dismiss();
            languages[choice].select(this);
        }).setNegativeButton(getString(R.string.text_cancel), null).show();
    }

    private void resumeSettingsTasks() {
        jobs.resume();
        synchronizeJobs(false);
        openTaskSettings();
    }

    private void renderConnectionSettings(LinearLayout content) {
        LinearLayout sharing = panel(content);
        text(sharing, getString(R.string.text_local_network_sharing), 20);
        caption(sharing, getString(R.string.text_enable_sharing_then_enter_the_address_and_pairin));
        shareStatus = caption(sharing, "");
        shareStatus.setTextIsSelectable(true);
        shareToggle = highlight(button(sharing, getString(R.string.text_start_sharing), () -> {
            Intent target = new Intent(this, LibraryShareService.class);
            if (LibraryShareService.live != null) stopService(target);
            else if (LibraryShareService.wifiAddress(this).isEmpty()) toast(getString(R.string.text_connect_to_wi_fi_first));
            else startForegroundService(target);
        }));
        LinearLayout actions = row(sharing);
        shareCopy = button(actions, getString(R.string.text_copy_connection_details), () -> {
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
                    ClipData.newPlainText(getString(R.string.app_name) + getString(R.string.text_connection), LibraryShareService.status(this)));
            toast(getString(R.string.text_copied));
        });
        shareCode = button(actions, getString(R.string.text_refresh_pairing_code), () -> {
            LibraryShareService.refreshCode();
            refreshConnectionSettings();
        });
        refreshConnectionSettings();
    }

    private void refreshConnectionSettings() {
        if (shareStatus == null) return;
        boolean active = LibraryShareService.live != null;
        setText(shareStatus, LibraryShareService.status(this));
        setText(shareToggle, active ? getString(R.string.text_stop_sharing) : getString(R.string.text_start_sharing));
        shareCopy.setEnabled(active);
        shareCode.setEnabled(active);
    }

    private void renderDirectorySettings(LinearLayout content) {
        LinearLayout directory = panel(content);
        text(directory, getString(R.string.text_added_folders), 20);
        caption(directory, directories.isEmpty() ? getString(R.string.text_select_the_folder_containing_your_audio_files_it)
                : getString(R.string.text_tap_a_folder_to_browse_its_files_use_its_menu_to));
        if (!scanMessage.isEmpty()) caption(directory, scanMessage);
        LinearLayout actions = row(directory);
        highlight(button(actions, getString(R.string.text_add_folder), this::choose)).setEnabled(!scanning);
        if (!directories.isEmpty()) button(actions,getString(R.string.text_refresh_modified_dates),this::refreshSortTimes).setEnabled(!scanning);
        if (!jobs.failedPaths.isEmpty()) {
            button(directory, getString(R.string.text_view_failed_scans) + jobs.failedPaths.size(), () -> new AppDialog.Builder(this)
                    .setTitle(getString(R.string.text_folders_that_could_not_be_read)).setItems(jobs.failedPaths.toArray(new String[0]), null)
                    .setPositiveButton(getString(R.string.text_close), null).show());
        }
        for (String key : directories.keySet()) {
            LinearLayout item = row(directory);
            Button label = button(item, (key.equals(activeTree) ? "✓  " : "") + directoryName(key), () -> openDirectoryFiles(key));
            label.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            label.setOnLongClickListener(view -> { directoryOptions(key); return true; });
            Button options = button(item, "⋯", () -> directoryOptions(key));
            options.setContentDescription(getString(R.string.text_manage) + directoryName(key));
            options.setLayoutParams(new LinearLayout.LayoutParams(dp(48), -2));
        }
    }

    private void renderInformationSettings(LinearLayout content) {
        LinearLayout metadata = panel(content);
        text(metadata, getString(R.string.text_update_work_information), 20);
        caption(metadata, getString(R.string.text_get_covers_and_work_details_by_rj_id_keeping_man));
        caption(metadata, getString(R.string.translation_target, uiLanguage.label));
        boolean hasKey = Translation.hasKey(this);
        caption(metadata, hasKey ? getString(R.string.text_deepseek_is_configured_titles_are_translated_as_) : getString(R.string.text_set_a_deepseek_api_key_to_translate_titles_durin));
        LinearLayout actions = row(metadata);
        highlight(button(actions, getString(R.string.text_choose_update_scope), this::chooseBatch)).setEnabled(!batchActive && !works.isEmpty());
        button(actions, hasKey ? getString(R.string.text_replace_api_key) : getString(R.string.text_set_api_key), this::editTranslationKey).setEnabled(!batchActive);
        if (batchTotal > 0) {
            batchStatus = caption(metadata, "");
            batchProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            batchProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
            metadata.addView(batchProgress, new LinearLayout.LayoutParams(-1, dp(12)));
            batchCancel = button(metadata, getString(R.string.text_stop_after_current_work), () -> {
                jobs.cancelBatch = cancelBatch = true;
                refreshBatch();
            });
            if (!batchActive) {
                button(metadata, getString(R.string.text_view_results), this::showBatchResults).setEnabled(!batchResults.isEmpty());
            }
            refreshBatch();
        }
    }

    private void renderPrivacySettings(LinearLayout content) {
        LinearLayout settings = panel(content);
        text(settings, getString(R.string.text_private_playback), 20);
        Switch privacy = new Switch(this);
        privacy.setText(getString(R.string.text_private_background_playback));
        privacy.setChecked(prefs.getBoolean("privatePlayback", false));
        settings.addView(privacy, new LinearLayout.LayoutParams(-1, dp(48)));
        privacy.setOnCheckedChangeListener((view, value) -> {
            prefs.edit().putBoolean("privatePlayback", value).apply();
            if (service != null) service.privacyChanged();
            refreshPrivacyStatus();
            if(value&&!privateNotificationsHidden())new AppDialog.Builder(this).setTitle(getString(R.string.text_hide_background_notifications)).setMessage(getString(R.string.text_turn_off_the_private_background_playback_notific))
                .setPositiveButton(getString(R.string.text_open_settings),(d,n)->openPrivacyNotifications()).setNegativeButton(getString(R.string.text_later),null).show();
        });
        privacyStatus=caption(settings,"");refreshPrivacyStatus();
        button(settings, getString(R.string.text_private_notification_settings), this::openPrivacyNotifications);

        LinearLayout permissions = panel(content);
        text(permissions, getString(R.string.text_system_background_access), 20);
        batterySettings = button(permissions, getString(R.string.text_battery_optimization), () -> openSystemSettings(
                new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)));
        appPermission = button(permissions, getString(R.string.text_app_details), () -> openSystemSettings(appSettings()));
        button(permissions, getString(R.string.text_media_notification_settings), () -> openSystemSettings(
                new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())));
        refreshBackgroundStatus();
    }

    private void renderPlaybackSettings(LinearLayout content) {
        LinearLayout settings = panel(content);
        text(settings, getString(R.string.text_audio_output), 20);
        Switch exclusive = new Switch(this);
        exclusive.setText(getString(R.string.text_exclusive_usb_output));
        exclusive.setEnabled(UsbOutput.available(this));
        exclusive.setChecked(prefs.getBoolean(UsbOutput.MODE, false));
        settings.addView(exclusive, new LinearLayout.LayoutParams(-1, dp(48)));
        caption(settings, getString(R.string.text_wav_mp3_flac_1_in_exclusive_mode));
        usbStatus = caption(settings, "");
        usbAuthorize = button(settings, getString(R.string.text_authorize_usb_dac), this::authorizeUsb);
        LinearLayout volumePanel = panel(content);
        text(volumePanel, getString(R.string.text_usb_volume), 20);
        int volume = service == null ? prefs.getInt("usbVolume", 50) : service.usbVolume();
        usbVolumeLabel = caption(volumePanel, volume + "%");
        usbVolumeBar = new SeekBar(this);
        usbVolumeBar.setMax(100);
        usbVolumeBar.setProgress(volume);
        usbVolumeBar.setEnabled(UsbOutput.available(this));
        volumePanel.addView(usbVolumeBar, new LinearLayout.LayoutParams(-1, -2));
        usbVolumeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                usbVolumeLabel.setText(value + "%");
                if (user) {
                    if (service != null) service.setUsbVolume(value);
                    else prefs.edit().putInt("usbVolume", value).apply();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        exclusive.setOnCheckedChangeListener((view, enabled) -> {
            prefs.edit().putBoolean(UsbOutput.MODE, enabled).apply();
            if (service != null) service.outputChanged();
            refreshUsbStatus();
            if (enabled && UsbOutput.device(this) != null) authorizeUsb();
        });
        refreshUsbStatus();
    }

    private void refreshUsbStatus() {
        if (usbStatus == null) return;
        usbAuthorize.setEnabled(false);
        if (!UsbOutput.available(this)) { usbStatus.setText(getString(R.string.text_requires_android_10_and_usb_host)); return; }
        UsbDevice device = UsbOutput.device(this);
        if (device == null) { usbStatus.setText(getString(R.string.text_no_usb_dac_connected)); return; }
        String name = device.getProductName();
        boolean authorized = UsbOutput.authorized(this, device);
        usbAuthorize.setEnabled(!authorized);
        usbStatus.setText((name == null ? getString(R.string.text_usb_dac) : name) + (authorized ? getString(R.string.text_authorized) : getString(R.string.text_permission_needed)));
    }

    private void authorizeUsb() {
        UsbDevice device = UsbOutput.device(this);
        if (device == null) { toast(getString(R.string.text_connect_a_usb_dac_first)); return; }
        if (UsbOutput.authorized(this, device)) { toast(getString(R.string.text_usb_dac_already_authorized)); return; }
        UsbOutput.request(this, device);
    }

    private void refreshBackgroundStatus() {
        if (batterySettings == null) return;
        PowerManager power = getSystemService(PowerManager.class);
        ActivityManager activity = getSystemService(ActivityManager.class);
        boolean exempt = power != null && power.isIgnoringBatteryOptimizations(getPackageName());
        boolean restricted = Build.VERSION.SDK_INT >= 28 && activity != null && activity.isBackgroundRestricted();
        setText(batterySettings, getString(R.string.text_battery_optimization_2) + (exempt ? getString(R.string.text_exempt) : getString(R.string.text_managed_by_system)));
        setText(appPermission, getString(R.string.text_app_details_2) + (restricted ? getString(R.string.text_background_use_restricted) : getString(R.string.text_background_and_startup_settings)));
    }

    private Intent appSettings() {
        return new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
    }

    private void openSystemSettings(Intent target) {
        try { startActivity(target); }
        catch (ActivityNotFoundException | SecurityException unsupported) {
            try { startActivity(appSettings()); }
            catch (ActivityNotFoundException | SecurityException ignored) {
                toast(getString(R.string.text_find_this_app_in_system_settings) + getString(R.string.app_name));
            }
        }
    }
    private boolean coverBackgroundEnabled() { return prefs.getBoolean("coverBackground", true); }

    private void renderBackgroundSettings(LinearLayout content) {
        LinearLayout form = panel(content);
        text(form, getString(R.string.text_lyrics_background), 20);
        Switch enabled = new Switch(this);
        enabled.setText(getString(R.string.text_use_cover_as_lyrics_background));
        enabled.setChecked(coverBackgroundEnabled());
        form.addView(enabled, new LinearLayout.LayoutParams(-1, dp(48)));
        int opacity = prefs.getInt("coverOpacity", 48);
        TextView label = caption(form, getString(R.string.text_cover_opacity) + opacity + "%");
        SeekBar amount = new SeekBar(this);
        amount.setMax(100);
        amount.setProgress(opacity);
        amount.setEnabled(enabled.isChecked());
        form.addView(amount, new LinearLayout.LayoutParams(-1, -2));
        amount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                label.setText(getString(R.string.text_cover_opacity) + value + "%");
                if (!user) return;
                prefs.edit().putInt("coverOpacity", value).apply();
                updateCoverAppearance();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        int brightness = prefs.getInt("coverBrightness", 40);
        TextView brightnessLabel = caption(form, getString(R.string.text_cover_brightness) + brightness + "%");
        SeekBar brightnessBar = new SeekBar(this);
        brightnessBar.setMax(100);
        brightnessBar.setProgress(brightness);
        brightnessBar.setEnabled(enabled.isChecked());
        form.addView(brightnessBar, new LinearLayout.LayoutParams(-1, -2));
        enabled.setOnCheckedChangeListener((view, checked) -> {
            prefs.edit().putBoolean("coverBackground", checked).apply();
            amount.setEnabled(checked);
            brightnessBar.setEnabled(checked);
        });
        brightnessBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                brightnessLabel.setText(getString(R.string.text_cover_brightness) + value + "%");
                if (user) {
                    prefs.edit().putInt("coverBrightness", value).apply();
                    updateCoverAppearance();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        int transparency = prefs.getInt("lyricTransparency", 0);
        TextView lyricLabel = caption(form, getString(R.string.text_lyric_transparency) + transparency + "%");
        SeekBar lyricAmount = new SeekBar(this);
        lyricAmount.setMax(100);
        lyricAmount.setProgress(transparency);
        form.addView(lyricAmount, new LinearLayout.LayoutParams(-1, -2));
        lyricAmount.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                lyricLabel.setText(getString(R.string.text_lyric_transparency) + value + "%");
                if (!user) return;
                prefs.edit().putInt("lyricTransparency", value).apply();
                if (lyricAdapter != null) lyricAdapter.notifyDataSetChanged();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout motion = panel(content);
        text(motion, getString(R.string.text_interface_motion), 20);
        Switch reduced = new Switch(this);
        reduced.setText(getString(R.string.text_reduce_motion));
        reduced.setChecked(prefs.getBoolean("reduceMotion", false));
        reduced.setOnCheckedChangeListener((view, value) -> prefs.edit().putBoolean("reduceMotion", value).apply());
        motion.addView(reduced, new LinearLayout.LayoutParams(-1, dp(48)));
        caption(motion, getString(R.string.text_reduce_animations_for_navigation_dialogs_and_but));
    }

    private void updateCoverAppearance() {
        if (playerCover == null) return;
        playerCover.setVisibility(coverBackgroundEnabled() ? View.VISIBLE : View.INVISIBLE);
        playerCover.setAlpha(prefs.getInt("coverOpacity", 48) / 100f);
        if (coverShade != null) coverShade.setAlpha(1 - prefs.getInt("coverBrightness", 40) / 100f);
    }
    private void editTranslationKey() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(getString(R.string.text_enter_deepseek_api_key));
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);
        form.addView(input);
        AppDialog.Builder dialog = new AppDialog.Builder(this).setTitle("DeepSeek API Key")
                .setView(form).setNegativeButton(getString(R.string.text_cancel), null)
                .setPositiveButton(getString(R.string.text_save), (view, which) -> {
                    String key = input.getText().toString().trim();
                    if (key.isEmpty()) { toast(getString(R.string.text_enter_an_api_key)); return; }
                    storeTranslationKey(key);
                });
        if (Translation.hasKey(this)) dialog.setNeutralButton(getString(R.string.text_remove_key), (view, which) -> storeTranslationKey(""));
        dialog.show();
    }

    private void storeTranslationKey(String key) {
        try {
            Translation.setKey(this, key);
            toast(key.isEmpty() ? getString(R.string.text_api_key_removed) : getString(R.string.text_api_key_saved));
            render();
        } catch (Exception e) { toast(getString(R.string.text_could_not_save_api_key)); }
    }

    private void chooseBatch() {
        if (batchActive || works.isEmpty()) return;
        Map<String, List<Library.Work>> all = visibleWorks(false);
        all.remove(Library.UNNAMED);
        List<String> missing = new ArrayList<>();
        boolean translate = Translation.hasKey(this);
        for (String id : all.keySet()) {
            if (!Metadata.complete(this, prefs, id)
                    || (translate && Translation.cached(Metadata.saved(prefs, id), uiLanguage).isEmpty())) missing.add(id);
        }
        List<String> current = new ArrayList<>(visibleWorks(true).keySet());
        current.remove(Library.UNNAMED);
        new AppDialog.Builder(this).setTitle(getString(R.string.text_choose_update_scope))
                .setItems(new String[]{getString(R.string.text_missing_details_or_translation) + missing.size(), getString(R.string.text_current_filters) + current.size(),
                        getString(R.string.text_fetch_again_for_current_folder) + all.size()}, (dialog, which) -> {
                    List<String> ids = which == 0 ? missing : which == 1 ? current : new ArrayList<>(all.keySet());
                    if (ids.isEmpty()) { toast(getString(R.string.text_no_works_to_update_in_this_scope)); return; }
                    startBatch(ids, which == 2);
                }).show();
    }

    private void startBatch(List<String> ids, boolean refreshMetadata) {
        if (!jobs.batch(ids, refreshMetadata)) return;
        synchronizeJobs(false);
        render();
    }

    private void refreshBatch() {
        if (batchStatus == null || destroyed) return;
        int full = 0, translated = 0;
        for (AppJobs.UpdateResult result : batchResults.values()) {
            if (result.complete()) full++;
            if (result.title == AppJobs.TitleResult.SAVED || result.title == AppJobs.TitleResult.CACHED) translated++;
        }
        String state = batchActive ? (cancelBatch ? getString(R.string.text_stopping) : (jobs.batchTranslating ? getString(R.string.text_translating) : getString(R.string.text_fetching)) + " " + batchCurrent)
                : (batchResults.size() == batchTotal ? getString(R.string.text_completed) : getString(R.string.text_stopped));
        UiLanguage target = jobs.batchLanguage == null ? uiLanguage : jobs.batchLanguage;
        batchStatus.setText(getString(R.string.update_progress, state, batchResults.size(), batchTotal,
                full, translated, batchResults.size() - full, target.label));
        batchProgress.setMax(batchTotal);
        batchProgress.setProgress(batchResults.size(), Motion.enabled(this));
        batchCancel.setVisibility(batchActive ? View.VISIBLE : View.GONE);
        batchCancel.setEnabled(!cancelBatch);
    }

    private void showBatchResults() {
        List<String> lines = new ArrayList<>();
        List<String> retry = new ArrayList<>();
        for (Map.Entry<String, AppJobs.UpdateResult> item : batchResults.entrySet()) {
            AppJobs.UpdateResult result = item.getValue();
            lines.add(item.getKey() + "  ·  " + getString(result.metadata.label) + "\n" + titleUpdateLabel(result));
            if (!result.complete()) retry.add(item.getKey());
        }
        AppDialog.Builder dialog = new AppDialog.Builder(this).setTitle(getString(R.string.text_update_results))
                .setItems(lines.toArray(new String[0]), null).setNegativeButton(getString(R.string.text_close), null);
        if (!retry.isEmpty()) dialog.setPositiveButton(getString(R.string.text_retry_incomplete_works), (view, index) -> startBatch(retry, false));
        dialog.show();
    }

    private String titleUpdateLabel(AppJobs.UpdateResult result) {
        switch (result.title) {
            case SAVED: return getString(R.string.text_title_translated);
            case CACHED: return getString(R.string.text_used_saved_translation);
            case NO_KEY: return getString(R.string.text_no_api_key_original_title_kept);
            case NO_TITLE: return getString(R.string.text_no_original_title_to_translate);
            case NOT_RUN: return getString(R.string.title_translation_not_run);
            default:
                if ("key".equals(result.error)) return getString(R.string.text_could_not_read_the_api_key_set_it_again);
                if ("source".equals(result.error)) return getString(R.string.text_the_original_title_changed_retry);
                return getString(R.string.text_title_translation_failed) + (result.error.isEmpty() ? "" : " · " + result.error);
        }
    }

    private void renderMiniPlayer() {
        if (service == null || service.current() == null) {
            return;
        }
        LinearLayout card = panel(root);
        LinearLayout controls = row(card);
        LinearLayout labels = column(controls);
        labels.setOnClickListener(view -> openPlayer());
        miniTitle = text(labels, "", 16);
        miniTitle.setSingleLine();
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniInfo = caption(labels, "");
        miniToggle = button(controls, getString(R.string.text_play), () -> service.toggle(!service.playing()));
        miniToggle.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(40)));
        highlight(miniToggle);
        boolean collapsed = prefs.getBoolean("miniCollapsed", false);
        Button collapse = button(controls, collapsed ? getString(R.string.text_expand_mini_player) : getString(R.string.text_collapse_mini_player), null);
        LinearLayout.LayoutParams collapseSize = new LinearLayout.LayoutParams(dp(36), dp(36));
        collapseSize.leftMargin = dp(4);
        collapse.setLayoutParams(collapseSize);
        miniProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        miniProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        card.addView(miniProgress, new LinearLayout.LayoutParams(-1, dp(5)));
        styleMiniPlayer(card, collapse, collapsed);
        collapse.setOnClickListener(view -> {
            boolean next = !prefs.getBoolean("miniCollapsed", false);
            prefs.edit().putBoolean("miniCollapsed", next).apply();
            Motion.layout(root);
            styleMiniPlayer(card, collapse, next);
        });
        refreshMiniPlayer();
    }

    private void styleMiniPlayer(LinearLayout card, Button collapse, boolean collapsed) {
        int visibility = collapsed ? View.GONE : View.VISIBLE;
        miniInfo.setVisibility(visibility);
        miniProgress.setVisibility(visibility);
        miniTitle.setTextSize(collapsed ? 14 : 16);
        card.setPadding(dp(12), dp(collapsed ? 2 : 8), dp(8), dp(collapsed ? 2 : 8));
        setText(collapse, collapsed ? getString(R.string.text_expand_mini_player) : getString(R.string.text_collapse_mini_player));
    }
    private void refreshMiniPlayer() {
        if (miniTitle == null || service == null || service.current() == null) return;
        setText(miniTitle, fileName(service.current().name));
        String work = service.workId();
        setText(miniInfo, title(work) + "  ·  " + time(service.position()) + " / " + time(service.duration()));
        setText(miniToggle, service.playing() ? getString(R.string.text_pause) : getString(R.string.text_play));
        miniProgress.setMax(service.duration());
        miniProgress.setProgress(service.position(), Motion.enabled(this));
    }

    private void openPlayer() {
        playerBack = page;
        show("player");
    }
    private void renderDetail() {
        LinearLayout nav = row(root);
        button(nav, getString(R.string.text_library_2), () -> show("library"));
        Button options = button(nav, "⋯", this::detailOptions);
        options.setContentDescription(getString(R.string.text_work_actions));
        options.setLayoutParams(new LinearLayout.LayoutParams(dp(48), -2));
        LinearLayout content = scroll(root);
        JSONObject info = Metadata.saved(prefs, selected.id);
        LinearLayout hero = panel(content);
        LinearLayout identity = row(hero);
        addPreviewableCover(identity, selected.id, 82);
        LinearLayout labels = column(identity);
        labels.setPadding(dp(12), 0, 0, 0);
        text(labels, title(selected), 20).setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        List<Library.Work> sources = visibleWorks(false).getOrDefault(Metadata.onlineId(selected.id), Collections.emptyList());
        if (sources.size() > 1) {
            Button source = button(labels, getString(R.string.text_source) + fileName(selected.source) + "  ▾", () -> chooseWorkSource(sources));
            source.setSingleLine(true);
            source.setEllipsize(TextUtils.TruncateAt.END);
        }
        String resumeUri = workResumeUri(selected);
        boolean canResume = !resumeUri.isEmpty();
        LinearLayout actions = row(content);
        highlight(button(actions, canResume ? getString(R.string.text_resume) : getString(R.string.text_play_work),
                () -> resume(selected, resumeUri)));
        if (canResume) button(actions, getString(R.string.text_play_from_start), () -> play(selected, 0, 0));
        button(actions, favorite(selected) ? getString(R.string.text_favorited) : getString(R.string.text_favorite), () -> {
            prefs.edit().putBoolean("favorite:" + Metadata.onlineId(selected.id), !favorite(selected)).apply();
            render();
        });

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        Button expand = button(content, detailsExpanded ? getString(R.string.text_hide_work_information) : getString(R.string.text_show_work_information), null);
        expand.setOnClickListener(view -> {
            detailsExpanded = !detailsExpanded;
            Motion.layout((ViewGroup) details.getParent());
            details.setVisibility(detailsExpanded ? View.VISIBLE : View.GONE);
            expand.setText(detailsExpanded ? getString(R.string.text_hide_work_information) : getString(R.string.text_show_work_information));
        });
        content.addView(details, new LinearLayout.LayoutParams(-1, -2));
        details.setVisibility(detailsExpanded ? View.VISIBLE : View.GONE);
        if (selected.numbered()) caption(details, getString(R.string.text_catalog_id) + selected.id);
        if (!Translation.cached(info, uiLanguage).isEmpty()) caption(details, getString(R.string.text_original_title) + info.optString("title"));
        if (!info.optString("circle").isEmpty()) caption(details, getString(R.string.text_circle_3) + info.optString("circle"));
        if (!info.optString("actors").isEmpty()) caption(details, getString(R.string.text_cast_3) + info.optString("actors"));
        caption(details, getString(R.string.text_source) + selected.source);
        String previousGroup = null;
        for (int i = 0; i < selected.tracks.size(); i++) {
            int track = i;
            Library.Track item = selected.tracks.get(i);
            int separator = item.name.lastIndexOf('/');
            String group = separator < 0 ? getString(R.string.text_main_folder) : item.name.substring(0, separator);
            if (!group.equals(previousGroup)) {
                Library.Work work = selected;
                caption(content, group).setOnLongClickListener(view -> {
                    manageWorkFolder(work, separator < 0 ? "" : group, false);
                    return true;
                });
            }
            previousGroup = group;
            LinearLayout entry = cardRow(content);
            boolean active = service != null && service.current() != null && service.current().uri.equals(item.uri);
            TextView number = caption(entry, active ? "▶" : String.format(Locale.ROOT, "%02d", i + 1));
            number.setLayoutParams(new LinearLayout.LayoutParams(dp(32), -2));
            LinearLayout trackLabel = column(entry);
            text(trackLabel, fileName(item.name), 15);
            if (!item.lrc.isEmpty()) caption(trackLabel, getString(R.string.text_lrc_lyrics));
            entry.setFocusable(true);
            entry.setOnClickListener(view -> {
                if (service != null && service.current() != null && service.current().uri.equals(item.uri)) {
                    openPlayer();
                } else play(selected, track, 0);
            });
            entry.setOnLongClickListener(view -> { trackOptions(selected, item); return true; });
        }
    }

    private void chooseWorkSource(List<Library.Work> sources) {
        String[] names = sources.stream().map(work -> work.source + "  ·  " + work.tracks.size() + getString(R.string.text_tracks))
                .toArray(String[]::new);
        new AppDialog.Builder(this).setTitle(getString(R.string.text_choose_work_source))
                .setItems(names, (dialog, choice) -> { selected = sources.get(choice); render(); })
                .setNegativeButton(getString(R.string.text_cancel), null).show();
    }

    private void detailOptions() {
        Library.Work work = selected;
        if (work == null) return;
        String[] actions = work.numbered()
                ? new String[]{getString(R.string.text_manage_files), getString(R.string.text_edit_work_information), getString(R.string.text_update_work_information)}
                : new String[]{getString(R.string.text_manage_files)};
        new AppDialog.Builder(this).setTitle(title(work)).setItems(actions, (dialog, choice) -> {
            if (choice == 0) manageWorkFolder(work, "", true);
            else if (choice == 1) editMetadata();
            else if (choice == 2) {
                if (batchActive) toast(getString(R.string.text_a_work_update_is_already_running));
                else metadata();
            }
        }).setNegativeButton(getString(R.string.text_cancel), null).show();
    }

    private void renderPlayer() {
        LinearLayout nav = row(root);
        button(nav, getString(R.string.text_minimize), () -> show(playerBack));
        button(nav, getString(R.string.text_play_queue), this::showQueue);
        Button background = button(nav, "⚙", this::openSettings);
        background.setLayoutParams(new LinearLayout.LayoutParams(dp(48), -2));
        playerTitle = heading(root, getString(R.string.text_now_playing));
        playerTitle.setMaxLines(2);
        playerTitle.setEllipsize(TextUtils.TruncateAt.END);
        playerTitle.setTextSize(22);
        playerWork = caption(root, "");
        renderLyrics();
        clock = caption(root, "");
        clock.setTextSize(14);
        seek = new SeekBar(this);
        stylePlaybackSeek(seek);
        root.addView(seek, new LinearLayout.LayoutParams(-1, dp(56)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user && service != null) setText(clock,time(value)+" / "+time(service.duration()));
            }
            public void onStartTrackingTouch(SeekBar bar) { dragging = true; }
            public void onStopTrackingTouch(SeekBar bar) {
                if (service != null) service.seek(bar.getProgress());
                dragging = false;
            }
        });
        LinearLayout controls = row(root);
        button(controls, getString(R.string.text_previous), () -> { if (service != null) service.step(-1); });
        button(controls, getString(R.string.text_15_sec), () -> jump(-15000));
        toggle = highlight(button(controls, getString(R.string.text_play), () -> {
            if (service != null) service.toggle(!service.playing());
        }));
        button(controls, getString(R.string.text_15_sec_2), () -> jump(15000));
        button(controls, getString(R.string.text_next), () -> { if (service != null) service.step(1); });
        LinearLayout options = row(root);
        button(options, getString(R.string.text_speed), () -> new AppDialog.Builder(this).setTitle(getString(R.string.text_playback_speed))
                .setItems(new String[]{"0.75×", "1×", "1.25×", "1.5×", "2×"}, (d, n) -> {
                    if (service != null) service.speed(new float[]{.75f, 1, 1.25f, 1.5f, 2}[n]);
                }).show());
        button(options, getString(R.string.text_sleep_timer), () -> new AppDialog.Builder(this).setTitle(getString(R.string.text_sleep_timer))
                .setItems(new String[]{getString(R.string.text_close), getString(R.string.text_15_minutes), getString(R.string.text_30_minutes), getString(R.string.text_60_minutes), getString(R.string.text_custom)}, (d, n) -> {
                    if (n == 4) customSleep();
                    else if (service != null) service.sleep(new int[]{0, 15, 30, 60}[n]);
                }).show());
        PlaybackService.PlayOrder order = service == null ? PlaybackService.PlayOrder.SEQUENTIAL : service.playOrder();
        playOrderButton = button(options, getString(order.label), () -> {
            if (service == null) return;
            service.cyclePlayOrder();
            refreshPlayOrder(service.playOrder());
            toast(getString(service.playOrder().label));
        });
        refreshPlayOrder(order);
        refreshPlayer();
    }

    private void refreshPlayOrder(PlaybackService.PlayOrder order) {
        String label = getString(order.label);
        setText(playOrderButton, label);
        playOrderButton.setContentDescription(getString(R.string.text_playback_mode) + label + getString(R.string.text_tap_to_change));
        playOrderButton.setTooltipText(label);
    }

    private void stylePlaybackSeek(SeekBar bar) {
        android.graphics.drawable.ClipDrawable progress = new android.graphics.drawable.ClipDrawable(
                shape(ACCENT, 4), Gravity.LEFT, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        android.graphics.drawable.LayerDrawable track = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{shape(0xff424b64, 4), progress});
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        for (int i = 0; i < 2; i++) {
            track.setLayerHeight(i, dp(8));
            track.setLayerGravity(i, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        }
        bar.setProgressDrawable(track);
        android.graphics.drawable.StateListDrawable thumb = new android.graphics.drawable.StateListDrawable();
        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.OVAL);
        pressed.setColor(INK);
        pressed.setStroke(dp(4), ACCENT);
        pressed.setSize(dp(26), dp(26));
        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.OVAL);
        normal.setColor(ACCENT);
        normal.setStroke(dp(2), INK);
        normal.setSize(dp(26), dp(26));
        thumb.addState(new int[]{android.R.attr.state_pressed}, pressed);
        thumb.addState(new int[]{}, normal);
        bar.setThumb(thumb);
        bar.setThumbOffset(dp(13));
        bar.setSplitTrack(false);
        bar.setPadding(dp(13), dp(8), dp(13), dp(8));
    }


    private void customSleep() {
        EditText minutes = new EditText(this);
        minutes.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        minutes.setHint(getString(R.string.text_minutes_1_1440));
        minutes.setText(String.valueOf(prefs.getInt("customSleep", 45)));
        LinearLayout form = new LinearLayout(this);
        form.setPadding(dp(20), 0, dp(20), 0);
        form.addView(minutes, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = new AppDialog.Builder(this).setTitle(getString(R.string.text_custom_sleep_timer))
                .setView(form).setNegativeButton(getString(R.string.text_cancel), null).setPositiveButton(getString(R.string.text_start), null).create();
        dialog.setOnShowListener(view -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            int value;
            try { value = Integer.parseInt(minutes.getText().toString()); }
            catch (NumberFormatException invalid) { minutes.setError(getString(R.string.text_enter_1_1440_minutes)); return; }
            if (value < 1 || value > 1440) { minutes.setError(getString(R.string.text_enter_1_1440_minutes)); return; }
            prefs.edit().putInt("customSleep", value).apply();
            if (service != null) service.sleep(value);
            dialog.dismiss();
        }));
        dialog.show();
    }

    private void renderLyrics() {
        lyricViewUri = service == null || service.current() == null ? "" : service.current().uri;
        restoringLyrics = !lyricViewUri.isEmpty() && lyricViewUri.equals(savedLyricUri) && savedLyrics != null;
        lyricList = new ListView(this);
        lyricList.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        lyricList.setDivider(null);
        lyricAdapter = new LyricsAdapter();
        lyricList.setAdapter(lyricAdapter);
        lyricList.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) followLyrics = false;
            }
            @Override public void onScroll(AbsListView view, int first, int visible, int total) {}
        });
        lyricList.setOnItemClickListener((parent, view, index, id) -> {
            if (service != null) service.seek((int) Math.min(Integer.MAX_VALUE, lyricAdapter.lines.get(index).time));
            followLyrics = false;
        });
        FrameLayout stage = new FrameLayout(this);
        stage.setBackground(shape(SURFACE, 16));
        stage.setClipToOutline(true);
        playerCover = new ImageView(this);
        playerCover.setScaleType(ImageView.ScaleType.FIT_CENTER);
        playerCover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        stage.addView(playerCover, new FrameLayout.LayoutParams(-1, -1));
        coverShade = new View(this);
        coverShade.setBackgroundColor(0xff000000);
        stage.addView(coverShade, new FrameLayout.LayoutParams(-1, -1));
        stage.addView(lyricList, new FrameLayout.LayoutParams(-1, -1));
        lyricStatus = new TextView(this);
        lyricStatus.setTextColor(MUTED);
        lyricStatus.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams statusSize = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        statusSize.setMargins(dp(16), 0, dp(16), 0);
        stage.addView(lyricStatus, statusSize);
        Button follow = new IconButton(this, getString(R.string.text_locate_current_lyric));
        styleButton(follow, false);
        follow.setOnClickListener(view -> {
            followLyrics = true;
            if (lyricAdapter.active >= 0) scrollToLyric(lyricAdapter.active, true);
        });
        FrameLayout.LayoutParams followSize = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM | Gravity.END);
        followSize.setMargins(0, 0, dp(10), dp(10));
        stage.addView(follow, followSize);
        lyricList.setPadding(0, 0, 0, dp(56));
        lyricList.setClipToPadding(false);
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        updateCoverAppearance();
    }

    private final class LyricsAdapter extends BaseAdapter {
        List<Lrc.Cue> lines = Collections.emptyList();
        int active = -1;
        @Override public int getCount() { return lines.size(); }
        @Override public Object getItem(int position) { return lines.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            TextView line = recycled instanceof TextView ? (TextView) recycled : new TextView(MainActivity.this);
            android.animation.Animator previous = lyricColors.remove(line);
            if (previous != null) previous.cancel();
            Lrc.Cue cue = lines.get(position);
            line.setText(time(cue.time)+"   "+cue.text);
            boolean current = active >= 0 && lines.get(active).time == cue.time;
            line.setTextSize(18);
            int alpha = Math.round(255 * (100 - prefs.getInt("lyricTransparency", 0)) / 100f) << 24;
            line.setTextColor(alpha | (current ? 0xe4e8ff : 0xe0e3ef));
            line.setShadowLayer(dp(2), 0, dp(1), alpha | 0x080a12);
            line.setBackgroundColor(current ? 0xc4384267 : 0x00000000);
            line.setPadding(dp(14), dp(11), dp(14), dp(11));
            return line;
        }
    }

    private void scrollToLyric(int index, boolean animate) {
        if (animate && Motion.enabled(this)) lyricList.smoothScrollToPositionFromTop(index, dp(68), 380);
        else lyricList.setSelectionFromTop(index, dp(68));
    }

    private void updateVisibleLyrics() {
        if (lyricList == null) return;
        int first = lyricList.getFirstVisiblePosition();
        for (int i = 0; i < lyricList.getChildCount(); i++) {
            int index = first + i;
            if (index >= lyricAdapter.lines.size()) break;
            TextView line = (TextView) lyricList.getChildAt(i);
            int oldColor = line.getCurrentTextColor();
            lyricAdapter.getView(index, line, lyricList);
            int target = line.getCurrentTextColor();
            if (oldColor != target && Motion.enabled(this)) {
                line.setTextColor(oldColor);
                android.animation.ObjectAnimator tint = android.animation.ObjectAnimator.ofArgb(line, "textColor", oldColor, target);
                tint.setDuration(265);
                tint.setInterpolator(Motion.ENTER);
                lyricColors.put(line, tint);
                tint.start();
            }
        }
    }

    private void updateLyrics(int position) {
        List<Lrc.Cue> cues = service.lyrics();
        boolean changed = displayedCues != cues;
        boolean restore = changed && restoringLyrics && !cues.isEmpty();
        if (changed) {
            displayedCues = cues;
            lyricAdapter.lines = cues;
            followLyrics = restore ? savedFollow : true;
        }
        int low = 0, high = cues.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (cues.get(middle).time <= position) low = middle + 1; else high = middle;
        }
        int active = low - 1;
        if (changed || lyricAdapter.active != active) {
            lyricAdapter.active = active;
            if (changed) lyricAdapter.notifyDataSetChanged();
            else updateVisibleLyrics();
            if (restore) {
                lyricList.onRestoreInstanceState(savedLyrics);
                restoringLyrics = false;
            } else if (followLyrics) {
                if (active >= 0) scrollToLyric(active, !changed);
                else if (changed) lyricList.setSelection(0);
            }
        }
        String status = !service.error.isEmpty() ? service.error
                : cues.isEmpty() && service.subtitleStatus != 0 ? getString(service.subtitleStatus) : "";
        if (!status.contentEquals(lyricStatus.getText())) {
            lyricStatus.setText(status);
            lyricStatus.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void updateArtwork() {
        if (!coverBackgroundEnabled() || playerCover == null || service == null || service.current() == null) return;
        Bitmap embedded = service.artwork();
        String uri = service.current().uri;
        if (uri.equals(artworkTrack) && embedded == displayedArt) return;
        artworkTrack = uri;
        displayedArt = embedded;
        if (embedded != null) {
            displayPlayerArtwork(embedded);
        } else {
            String workId = service.workId();
            ImageView target = playerCover;
            displayPlayerArtwork(covers.get("work:" + Metadata.onlineId(workId)));
            loadWorkCover(workId, true, image -> {
                if (playerCover == target && uri.equals(artworkTrack) && displayedArt == null) displayPlayerArtwork(image);
            });
        }
    }

    private void displayPlayerArtwork(Bitmap image) {
        if (renderedCover == image) return;
        if (renderedCover != null && image != null && renderedCover != image && Motion.enabled(this)) {
            android.graphics.drawable.TransitionDrawable fade = new android.graphics.drawable.TransitionDrawable(
                    new android.graphics.drawable.Drawable[]{
                            new android.graphics.drawable.BitmapDrawable(getResources(), renderedCover),
                            new android.graphics.drawable.BitmapDrawable(getResources(), image)});
            fade.setCrossFadeEnabled(true);
            playerCover.setImageDrawable(fade);
            fade.startTransition(365);
        } else playerCover.setImageBitmap(image);
        renderedCover = image;
    }
    private void trackOptions(Library.Work work, Library.Track item) {
        boolean queued = service != null && service.queuedIndex(item.uri) >= 0;
        String tail = queued ? getString(R.string.text_move_to_end_of_queue) : getString(R.string.text_add_to_end_of_queue);
        new AppDialog.Builder(this).setTitle(fileName(item.name))
                .setItems(new String[]{getString(R.string.text_play_next), tail, getString(R.string.text_edit_play_queue), getString(R.string.text_delete_file)}, (dialog, choice) -> {
                    if (choice == 3) {
                        Uri uri = Uri.parse(item.uri);
                        Uri tree = Library.treeForDocument(uri);
                        readFiles(tree, library -> library.entry(DocumentsContract.getDocumentId(uri)),
                                entry -> {
                                    if (entry.directory || !entry.name.equals(fileName(item.name))) {
                                        toast(getString(R.string.text_the_track_changed_rescan_the_folder));
                                    } else confirmDelete(tree, entry, work.numbered() ? work.source + "/" + item.name : item.name);
                                });
                        return;
                    }
                    if (service == null) { toast(getString(R.string.text_player_is_starting)); return; }
                    if (choice == 2) { showQueue(); return; }
                    int index = service.queuedIndex(item.uri);
                    if (choice == 1 && index >= 0) {
                        if (index == service.queueIndex()) toast(getString(R.string.text_this_track_is_playing));
                        else if (index == service.queueSize() - 1) toast(getString(R.string.text_track_is_already_last_in_the_queue));
                        else {
                            service.moveQueueItem(index, service.queueSize() - 1);
                            toast(getString(R.string.text_moved_to_end_of_queue));
                        }
                        return;
                    }
                    boolean added = service.addToQueue(work.id, item, choice == 0);
                    toast(added ? (choice == 0 ? getString(R.string.text_set_to_play_next) : getString(R.string.text_added_to_queue)) : getString(R.string.text_track_is_already_in_the_queue));
                }).show();
    }

    private void showQueue() {
        if (service == null) return;
        queueBack = page;
        editingQueue = false;
        show("queue");
    }

    private void renderQueue() {
        LinearLayout nav = row(root);
        button(nav, getString(R.string.text_back), () -> show(queueBack));
        button(nav, editingQueue ? getString(R.string.text_done) : getString(R.string.text_edit_queue), () -> {
            editingQueue = !editingQueue;
            render();
        });
        heading(root, getString(R.string.text_play_queue));
        queueSummary = caption(root, "");
        queueSummary.setMaxLines(2);
        queueSummary.setEllipsize(TextUtils.TruncateAt.END);
        refreshQueueSummary();
        if (service == null) return;
        if (service.queueSize() == 0) { caption(root, getString(R.string.text_long_press_a_track_in_the_work_details_to_add_it)); return; }
        ListView entries = new ListView(this);
        queueList = entries;
        entries.setDivider(null);
        displayedQueueIndex = service.queueIndex();
        queueAdapter = new BaseAdapter() {
            @Override public int getCount() { return service == null ? 0 : service.queueSize(); }
            @Override public Object getItem(int position) { return service.queueTrack(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View unused, ViewGroup parent) {
                Library.Track track = service.queueTrack(position);
                String uri = track.uri;
                LinearLayout outer = new LinearLayout(MainActivity.this);
                outer.setOrientation(LinearLayout.VERTICAL);
                outer.setTag(uri);
                LinearLayout card = panel(outer);
                LinearLayout identity = row(card);
                addQueueCover(identity, track, service.queueWork(position));
                LinearLayout labels = column(identity);
                labels.setPadding(dp(12), 0, 0, 0);
                TextView name = text(labels, fileName(track.name), 16);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                if (position == service.queueIndex()) {
                    name.setTextColor(ACCENT);
                    caption(labels, getString(R.string.text_now_playing));
                }
                identity.setFocusable(true);
                identity.setOnClickListener(view -> {
                    int current = service.queuedIndex(uri);
                    if (current < 0) return;
                    service.selectTrack(current);
                    if (!"player".equals(queueBack)) playerBack = queueBack;
                    show("player");
                });
                if (editingQueue) {
                    LinearLayout edits = row(card);
                    button(edits, getString(R.string.text_move_up), () -> changeQueue(uri, -1)).setEnabled(position > 0);
                    button(edits, getString(R.string.text_move_down), () -> changeQueue(uri, 1)).setEnabled(position + 1 < getCount());
                    button(edits, getString(R.string.text_remove), () -> changeQueue(uri, 0)).setEnabled(position != service.queueIndex());
                }
                return outer;
            }
        };
        entries.setAdapter(queueAdapter);
        root.addView(entries, new LinearLayout.LayoutParams(-1, 0, 1));
        renderMiniPlayer();
    }

    private void refreshQueueSummary() {
        if (queueSummary == null) return;
        if (service == null || service.queueSize() == 0) { setText(queueSummary, getString(R.string.text_queue_is_empty)); return; }
        String count = service.queueSize() + getString(R.string.text_tracks_2);
        if (service.hasCustomQueue()) { setText(queueSummary, getString(R.string.text_custom_queue) + count); return; }
        Library.Work work = workForTrack(service.queueTrack(0).uri);
        String source = work == null ? title(service.queueWork(0)) : work.source;
        setText(queueSummary, getString(R.string.text_work_queue) + count + "\n" + source);
    }

    private void changeQueue(String uri, int direction) {
        if (service == null) return;
        int index = service.queuedIndex(uri);
        if (index < 0) return;
        ListView beforeList = queueList;
        Map<String, Integer> positions = new HashMap<>();
        if (beforeList != null) {
            for (int i = 0; i < beforeList.getChildCount(); i++) {
                View child = beforeList.getChildAt(i);
                if (child.getTag() instanceof String) {
                    positions.put((String) child.getTag(), child.getTop());
                }
            }
        }
        commitQueueEdit(uri, direction, beforeList, positions);
    }

    private void commitQueueEdit(String uri, int direction, ListView previousList, Map<String, Integer> positions) {
        if (service == null) return;
        int index = service.queuedIndex(uri);
        if (index < 0) return;
        if (direction == 0) {
            Library.Track removed = service.queueTrack(index);
            String work = service.queueWork(index);
            String next = index + 1 < service.queueSize() ? service.queueTrack(index + 1).uri : "";
            long generation = service.queueGeneration();
            if (!service.removeQueueItem(index)) return;
            showMessage(getString(R.string.text_removed_from_queue) + fileName(removed.name), getString(R.string.text_undo), () -> {
                if (service != null && service.restoreQueueItem(work, removed, index, next, generation)) {
                    refreshQueueSummary();
                    if (queueAdapter != null) queueAdapter.notifyDataSetChanged();
                    else if ("queue".equals(page)) render();
                } else showMessage(getString(R.string.text_the_queue_changed_this_action_cannot_be_undone), "", null);
            });
        } else service.moveQueueItem(index, index + direction);
        refreshQueueSummary();
        if (queueList == null || queueAdapter == null) return;
        if (queueList != previousList) { queueAdapter.notifyDataSetChanged(); return; }
        if (service.queueSize() == 0) { render(); return; }
        ListView target = queueList;
        queueAdapter.notifyDataSetChanged();
        if (!Motion.enabled(this)) return;
        target.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                target.getViewTreeObserver().removeOnPreDrawListener(this);
                if (target != queueList) return true;
                for (int i = 0; i < target.getChildCount(); i++) {
                    View child = target.getChildAt(i);
                    Integer oldTop = positions.get(child.getTag());
                    if (oldTop == null) continue;
                    child.setAlpha(1);
                    child.setTranslationX(0);
                    child.setTranslationY(oldTop - child.getTop());
                    child.animate().translationY(0).setDuration(240).setInterpolator(Motion.SETTLE).start();
                }
                return true;
            }
        });
    }

    private void addQueueCover(LinearLayout parent, Library.Track track, String workId) {
        String key = "track:" + track.uri;
        Bitmap embedded = covers.get(key);
        CoverTile tile = addCoverTile(parent, embedded, 54);
        tile.setTag(embedded != null);
        Context context = getApplicationContext();
        if (embedded == null) loadWorkCover(workId, false, bitmap -> {
            if (!Boolean.TRUE.equals(tile.getTag())) tile.display(bitmap);
        });
        loadArt(key, queueArtWorker, () -> Artwork.embedded(context, track.uri, 192), bitmap -> {
            if (bitmap != null) { tile.setTag(true); tile.display(bitmap); }
        });
    }
    private void refreshPlayer() {
        if (playerTitle == null || service == null || service.current() == null) return;
        String currentUri = service.current().uri;
        if (!currentUri.equals(displayedPlayerUri)) {
            boolean switching = !displayedPlayerUri.isEmpty();
            displayedPlayerUri = currentUri;
            lyricViewUri = currentUri;
            if (switching) restoringLyrics = false;
            setText(playerTitle, fileName(service.current().name));
            if (switching) Motion.appear(playerTitle);
        }
        String id = service.workId();
        setText(playerWork,title(id));
        int position = service.position();
        int duration = service.duration();
        if (!dragging) {
            seek.setMax(duration);
            seek.setProgress(position);
            String timer = service.sleepRemaining() > 0 ? getString(R.string.text_timer) + time(service.sleepRemaining()) : "";
            String mode = service.usingUsb() ? getString(R.string.text_exclusive_usb) : "  ·  " + service.speed() + "×";
            setText(clock, time(position) + " / " + time(duration) + mode + timer);
        }
        setText(toggle, service.playing() ? getString(R.string.text_pause) : getString(R.string.text_play));
        if (lyricList != null) updateLyrics(position);
        if (playerCover != null) updateArtwork();
    }
    private void jump(int amount) { if (service != null) service.seek(service.position() + amount); }

    private void playRandomWork() {
        List<List<Library.Work>> projects = new ArrayList<>(visibleWorks(false).values());
        if (projects.isEmpty()) { toast(getString(R.string.text_no_playable_works_in_this_folder)); return; }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Library.Work> sources = projects.get(random.nextInt(projects.size()));
        Library.Work work = sources.get(random.nextInt(sources.size()));
        play(work, random.nextInt(work.tracks.size()), 0);
    }

    private void play(Library.Work work, int track, int position) {
        if (service == null) { toast(getString(R.string.text_player_is_starting)); return; }
        selected = work;
        service.play(work, track, position);
        openPlayer();
    }

    private Library.Work workForTrack(String uri) {
        if (uri.isEmpty()) return null;
        for (List<Library.Work> group : directories.values()) for (Library.Work work : group) {
            for (Library.Track track : work.tracks) if (track.uri.equals(uri)) return work;
        }
        return null;
    }

    private String workResumeUri(Library.Work work) {
        String saved = prefs.getString("work:" + work.id, "");
        for (Library.Track track : work.tracks) if (track.uri.equals(saved)) return saved;
        Map<String, PlaybackHistory.Entry> history = PlaybackHistory.read(this);
        long last = 0;
        for (Library.Track track : work.tracks) {
            PlaybackHistory.Entry entry = history.get(track.uri);
            if (entry != null && entry.playedAt > last) {
                last = entry.playedAt;
                saved = track.uri;
            }
        }
        return last > 0 ? saved : "";
    }

    private void resume(Library.Work work, String uri) {
        boolean current = service != null && service.current() != null && service.current().uri.equals(uri);
        if (current && !service.hasCustomQueue()) {
            service.toggle(true);
            openPlayer();
            return;
        }
        for (int i = 0; i < work.tracks.size(); i++) {
            if (work.tracks.get(i).uri.equals(uri)) {
                play(work, i, current ? service.position() : prefs.getInt("position:" + uri, 0));
                return;
            }
        }
        play(work, 0, 0);
    }

    private void loadDirectories() {
        try {
            JSONArray keys = new JSONArray(prefs.getString("directories", "[]"));
            for (int i = 0; i < keys.length(); i++) {
                String key = keys.getString(i);
                directories.put(key, decode(prefs.getString(indexKey(key), "[]")));
            }
            if (!prefs.contains("directories")) {
                String legacy = prefs.getString("tree", "");
                if (!legacy.isEmpty()) {
                    directories.put(legacy, decode(prefs.getString("index", "[]")));
                    prefs.edit().putString("directories", new JSONArray(directories.keySet()).toString())
                            .putString(indexKey(legacy), prefs.getString("index", "[]"))
                            .putString("activeTree", legacy).apply();
                }
            }
        } catch (JSONException e) { scanMessage = getString(R.string.text_could_not_read_the_index_rescan_the_folder); }
        activeTree = prefs.getString("activeTree", "");
        if (!directories.containsKey(activeTree)) activeTree = "";
        updateVisibleDirectory();
    }

    private static String indexKey(String key) { return "directoryIndex:" + key; }
    private static String directoryName(String key) {
        String segment = Uri.parse(key).getLastPathSegment();
        if (segment == null) return key;
        int split = Math.max(segment.lastIndexOf('/'), segment.lastIndexOf(':'));
        return split + 1 < segment.length() ? segment.substring(split + 1) : segment;
    }

    private void updateVisibleDirectory() {
        if (!activeTree.isEmpty()) {
            works = directories.getOrDefault(activeTree, Collections.emptyList());
            return;
        }
        List<Library.Work> combined = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<Library.Work> entries : directories.values()) {
            for (Library.Work work : entries) {
                if (seen.add(work.tracks.get(0).uri)) combined.add(work);
            }
        }
        combined.sort(Comparator.comparing(work -> work.id));
        works = combined;
    }

    private void selectDirectory(String key) {
        boolean switchingFromHome = homeSection() >= 0;
        activeTree = key;
        prefs.edit().putString("activeTree", key).apply();
        updateVisibleDirectory();
        selected = null;
        if (!switchingFromHome) {
            query = "";
            circleFilter = actorFilter = "";
            favoritesOnly = false;
        }
        renderedKey = "";
        scrollPositions.remove("library");
        libraryAnchor = "";
        libraryAnchorOffset = libraryAnchorIndex = 0;
        show("library");
    }

    private void chooseLibraryDirectory() {
        if (directories.isEmpty()) { choose(); return; }
        List<String> keys = new ArrayList<>();
        keys.add("");
        keys.addAll(directories.keySet());
        String[] names = keys.stream().map(key -> {
            String name = key.isEmpty() ? getString(R.string.text_all_folders) : directoryName(key);
            return (key.equals(activeTree) ? "✓  " : "") + name;
        }).toArray(String[]::new);
        new AppDialog.Builder(this).setTitle(getString(R.string.text_switch_audio_folder))
                .setItems(names, (dialog, choice) -> { if (!keys.get(choice).equals(activeTree)) selectDirectory(keys.get(choice)); })
                .setNegativeButton(getString(R.string.text_cancel), null).show();
    }

    private void openDirectoryFiles(String key) {
        Uri tree = Uri.parse(key);
        readFiles(tree, library -> library.entry(library.rootId()),
                entry -> openFileBrowser(tree, Collections.singletonList(entry)));
    }

    private void chooseFileDirectory() {
        if (directories.isEmpty()) { choose(); return; }
        if (directories.size() == 1) { openDirectoryFiles(directories.keySet().iterator().next()); return; }
        List<String> keys = new ArrayList<>(directories.keySet());
        String[] names = keys.stream().map(key -> directoryName(key)).toArray(String[]::new);
        new AppDialog.Builder(this).setTitle(getString(R.string.text_choose_a_folder_to_manage))
                .setItems(names, (dialog, choice) -> openDirectoryFiles(keys.get(choice)))
                .setNegativeButton(getString(R.string.text_cancel), null).show();
    }

    private void directoryOptions(String key) {
        new AppDialog.Builder(this).setTitle(directoryName(key))
                .setItems(new String[]{getString(R.string.text_file_manager), getString(R.string.text_rescan), getString(R.string.text_remove_from_library)}, (dialog, choice) -> {
                    if (jobs.scanning) { toast(getString(R.string.text_wait_for_the_current_folder_operation_to_finish)); return; }
                    if (choice == 0) openDirectoryFiles(key);
                    else if (choice == 1) scan(Uri.parse(key));
                    else new AppDialog.Builder(this).setTitle(getString(R.string.text_remove_from_library_2))
                            .setMessage(getString(R.string.text_the_original_files_will_stay_on_this_device))
                            .setNegativeButton(getString(R.string.text_cancel), null).setPositiveButton(getString(R.string.text_remove_2), (d, w) -> {
                                if (jobs.scanning) return;
                                directories.remove(key);
                                if (activeTree.equals(key)) activeTree = "";
                                prefs.edit().putString("directories", new JSONArray(directories.keySet()).toString())
                                        .putString("activeTree", activeTree).remove(indexKey(key)).apply();
                                selected = null;
                                updateVisibleDirectory();
                                render();
                            }).show();
                }).show();
    }

    private interface FileRead<T> { T run(Library library) throws IOException; }

    private <T> void readFiles(Uri tree, FileRead<T> read, Consumer<T> display) {
        readFiles(tree, read, display, this::toast);
    }

    private <T> void readFiles(Uri tree, FileRead<T> read, Consumer<T> display, Consumer<String> showFailure) {
        int request = ++fileBrowserRequest;
        fileBrowserWorker.execute(() -> {
            try {
                T result = read.run(new Library(this, tree));
                handler.post(() -> { if (!destroyed && request == fileBrowserRequest) display.accept(result); });
            } catch (IOException | RuntimeException failure) {
                String message = failure instanceof IOException && failure.getMessage() != null
                        ? failure.getMessage() : getString(R.string.text_could_not_read_files_check_folder_access);
                handler.post(() -> { if (!destroyed && request == fileBrowserRequest) showFailure.accept(message); });
            }
        });
    }

    private void manageWorkFolder(Library.Work work, String group, boolean browse) {
        Uri tree = Library.treeForDocument(Uri.parse(work.tracks.get(0).uri));
        readFiles(tree, library -> {
            if (!work.numbered() && group.isEmpty()) return Collections.singletonList(library.entry(library.rootId()));
            String path = work.numbered() && !group.isEmpty() ? work.source + "/" + group
                    : group.isEmpty() ? work.source : group;
            List<Library.Entry> folders = library.folderPath(path);
            if (work.numbered() && !work.identity.isEmpty()) {
                String workFolderId = DocumentsContract.getDocumentId(Uri.parse(work.identity));
                if (folders.stream().noneMatch(entry -> entry.id.equals(workFolderId))) {
                    throw new IOException(getString(R.string.text_the_work_folder_changed_rescan_it));
                }
            }
            return folders;
        }, path -> {
            Library.Entry entry = path.get(path.size() - 1);
            if (browse) openFileBrowser(tree, path);
            else folderOptions(tree, entry, filePathLabel(path), () -> openFileBrowser(tree, path));
        });
    }

    private void folderOptions(Uri tree, Library.Entry entry, String path, Runnable open) {
        if (entry.id.equals(DocumentsContract.getTreeDocumentId(tree))) {
            open.run();
            return;
        }
        new AppDialog.Builder(this).setTitle(entry.name)
                .setItems(new String[]{getString(R.string.text_open_folder), getString(R.string.text_delete_folder)}, (dialog, choice) -> {
                    if (choice == 0) open.run();
                    else confirmDelete(tree, entry, path);
                }).setNegativeButton(getString(R.string.text_cancel), null).show();
    }

    private static String filePathLabel(List<Library.Entry> path) {
        StringJoiner label = new StringJoiner("/");
        for (Library.Entry entry : path) label.add(entry.name);
        return label.toString();
    }

    private void openFileBrowser(Uri tree, List<Library.Entry> path) {
        if (!"files".equals(page)) filesBack = page;
        fileTree = tree;
        filePath = new ArrayList<>(path);
        show("files");
    }

    private void fileBack() {
        if (filePath.size() <= 1) show(filesBack);
        else openFileBrowser(fileTree, filePath.subList(0, filePath.size() - 1));
    }

    private void renderFiles() {
        LinearLayout nav = row(root);
        boolean nested = filePath.size() > 1;
        button(nav, nested ? getString(R.string.text_up_one_level) : getString(R.string.text_back), this::fileBack).setLayoutParams(new LinearLayout.LayoutParams(dp(54), dp(44)));
        TextView title = heading(column(nav), getString(R.string.text_file_manager));
        title.setTextSize(24);
        if (nested) button(nav, getString(R.string.text_close), () -> show(filesBack)).setLayoutParams(new LinearLayout.LayoutParams(dp(54), dp(44)));
        Uri tree = fileTree;
        List<Library.Entry> path = filePath;
        if (tree == null || path.isEmpty()) return;
        HorizontalScrollView breadcrumb = new HorizontalScrollView(this);
        breadcrumb.setHorizontalScrollBarEnabled(false);
        LinearLayout steps = new LinearLayout(this);
        steps.setGravity(Gravity.CENTER_VERTICAL);
        breadcrumb.addView(steps, new HorizontalScrollView.LayoutParams(-2, -2));
        root.addView(breadcrumb, new LinearLayout.LayoutParams(-1, -2));
        for (int i = 0; i < path.size(); i++) {
            int depth = i + 1;
            if (i > 0) caption(steps, " › ").setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
            Button crumb = button(steps, path.get(i).name, null);
            crumb.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(44)));
            crumb.setMaxWidth(dp(200));
            crumb.setSingleLine(true);
            crumb.setEllipsize(TextUtils.TruncateAt.END);
            if (depth == path.size()) { highlight(crumb); crumb.setSelected(true); }
            else crumb.setOnClickListener(view -> openFileBrowser(tree, path.subList(0, depth)));
        }
        breadcrumb.post(() -> breadcrumb.scrollTo(steps.getWidth(), 0));
        LinearLayout content = scroll(root);
        TextView status = caption(content, getString(R.string.text_reading_files));
        Library.Entry folder = path.get(path.size() - 1);
        String currentPath = filePathLabel(path);
        readFiles(tree, library -> library.children(folder.id), entries -> {
            if (!"files".equals(page) || filePath != path) return;
            if (entries.isEmpty()) { status.setText(getString(R.string.text_this_folder_is_empty)); return; }
            status.setText(entries.size() + getString(R.string.text_items_long_press_or_tap_to_manage));
            entries.sort(Comparator.comparing(entry -> !entry.directory));
            for (Library.Entry entry : entries) {
                LinearLayout item = cardRow(content);
                LinearLayout labels = column(item);
                TextView name = text(labels, entry.name, 16);
                name.setMaxLines(2);
                name.setEllipsize(TextUtils.TruncateAt.END);
                caption(labels, entry.directory ? getString(R.string.text_folder) : getString(R.string.text_file));
                Runnable open = () -> {
                    List<Library.Entry> next = new ArrayList<>(path);
                    next.add(entry);
                    openFileBrowser(tree, next);
                };
                String target = currentPath + "/" + entry.name;
                Runnable actions = () -> {
                    if (entry.directory) folderOptions(tree, entry, target, open);
                    else new AppDialog.Builder(this).setTitle(entry.name)
                            .setItems(new String[]{getString(R.string.text_delete_file)}, (dialog, choice) -> confirmDelete(tree, entry, target))
                            .setNegativeButton(getString(R.string.text_cancel), null).show();
                };
                item.setFocusable(true);
                item.setOnClickListener(view -> { if (entry.directory) open.run(); else actions.run(); });
                item.setOnLongClickListener(view -> { actions.run(); return true; });
                Button menu = button(item, "⋯", actions);
                menu.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(44)));
                menu.setContentDescription(getString(R.string.text_manage) + entry.name);
            }
            ScrollView scroll = (ScrollView) content.getParent();
            int position = scrollPositions.getOrDefault(pageKey(), 0);
            scroll.post(() -> scroll.scrollTo(0, position));
        }, failure -> {
            if (!"files".equals(page) || filePath != path) return;
            status.setText(failure);
            button(content, getString(R.string.text_reload), this::render);
        });
    }

    private void confirmDelete(Uri tree, Library.Entry entry, String path) {
        if (jobs.scanning) { toast(getString(R.string.text_wait_for_the_current_folder_operation_to_finish)); return; }
        if (!directories.containsKey(tree.toString())) { toast(getString(R.string.text_add_an_audio_folder_first)); return; }
        boolean writable = getContentResolver().getPersistedUriPermissions().stream()
                .anyMatch(permission -> permission.isWritePermission() && tree.equals(permission.getUri()));
        if (!writable) {
            new AppDialog.Builder(this).setTitle(getString(R.string.text_folder_write_access_needed))
                    .setMessage(getString(R.string.text_grant_access_again_to) + directoryName(tree.toString()) + getString(R.string.text_in_the_system_picker_then_long_press_again_to_de))
                    .setNegativeButton(getString(R.string.text_cancel), null).setPositiveButton(getString(R.string.text_grant_access_again), (dialog, choice) -> chooseTree(tree)).show();
            return;
        }
        String message = entry.directory ? getString(R.string.text_the_folder_and_everything_in_it_will_be_deleted_) : getString(R.string.text_this_file_will_be_deleted_from_device_storage);
        new AppDialog.Builder(this).setTitle(getString(R.string.delete_target, entry.name))
                .setMessage(getString(R.string.text_location) + path + "\n\n" + message + getString(R.string.text_you_cannot_undo_this_in_the_app))
                .setNegativeButton(getString(R.string.text_cancel), null).setPositiveButton(getString(R.string.text_delete), (dialog, choice) -> {
                    if (!jobs.delete(tree, entry, success -> {
                        if (destroyed) return;
                        synchronizeJobs(true);
                    })) { toast(getString(R.string.text_wait_for_the_current_folder_operation_to_finish)); return; }
                    synchronizeJobs(false);
                }).show();
    }

    private void choose() {
        chooseTree(null);
    }

    private static final int DIRECTORY_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;

    private void chooseTree(Uri existing) {
        if (jobs.scanning) return;
        writeGrantTree = existing;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(DIRECTORY_GRANT_FLAGS | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (existing != null) intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUriUsingTree(existing, DocumentsContract.getTreeDocumentId(existing)));
        startActivityForResult(intent, 1);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1) return;
        Uri expected = writeGrantTree;
        writeGrantTree = null;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri tree = data.getData();
        if (expected != null && !expected.equals(tree)) { toast(getString(R.string.text_select_the_audio_folder_you_originally_added)); return; }
        try {
            int flags = data.getFlags() & DIRECTORY_GRANT_FLAGS;
            getContentResolver().takePersistableUriPermission(tree, flags);
            if (expected == null) scan(tree);
            else toast((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                    ? getString(R.string.text_access_granted_long_press_again_to_delete) : getString(R.string.text_this_location_only_grants_read_access));
        } catch (SecurityException e) { toast(getString(R.string.text_could_not_retain_access_choose_a_local_audio_fol)); }
    }

    private void scan(Uri tree) {
        if (!jobs.scan(tree)) return;
        synchronizeJobs(false);
        if (jobs.scanAdded && !"manage".equals(page)) {
            returnAfterImport = true;
            openSettingsSection(SettingsSection.DIRECTORIES);
        } else render();
    }

    private void metadata() {
        if (batchActive || selected == null) return;
        startBatch(Collections.singletonList(Metadata.onlineId(selected.id)), false);
    }

    private void editMetadata() {
        String id = selected.id;
        JSONObject info = Metadata.saved(prefs, id);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), 0, dp(20), 0);
        String[] labels = {getString(R.string.text_work_title), getString(R.string.text_circle), getString(R.string.text_cast)};
        String[] keys = {"title", "circle", "actors"};
        EditText[] inputs = new EditText[3];
        for (int i = 0; i < keys.length; i++) {
            caption(form,labels[i]);
            inputs[i] = new EditText(this);
            inputs[i].setMinLines(i==0?2:1);inputs[i].setMaxLines(4);
            inputs[i].setHint(labels[i]);
            inputs[i].setText(info.optString(keys[i]));
            form.addView(inputs[i]);
        }
        new AppDialog.Builder(this).setTitle(getString(R.string.text_edit_work_information)).setView(form)
                .setNegativeButton(getString(R.string.text_cancel), null).setPositiveButton(getString(R.string.text_save), (dialog, which) -> {
                    synchronized (Metadata.class) {
                        JSONObject latest = Metadata.saved(prefs, id);
                        try {
                            for (int i = 0; i < keys.length; i++) {
                                latest.put(keys[i], inputs[i].getText().toString().trim());
                                latest.put("manual_" + keys[i], true);
                            }
                            prefs.edit().putString(Metadata.key(id), latest.toString()).apply();
                        } catch (JSONException e) { toast(getString(R.string.text_could_not_save)); }
                    }
                    render();
                }).show();
    }

    private String title(Library.Work work) { return title(work.id); }

    private String title(String id) {
        return title(id, Metadata.saved(prefs, id));
    }

    private String title(String id, JSONObject info) {
        if (Library.UNNAMED.equals(id)) return getString(R.string.text_untitled_work);
        String translated = Translation.cached(info, uiLanguage);
        if (!translated.isEmpty()) return translated;
        String original = info.optString("title");
        return original.isEmpty() ? id : original;
    }    private boolean favorite(Library.Work work) { return prefs.getBoolean("favorite:" + Metadata.onlineId(work.id), false); }
    private void show(String target) { fileBrowserRequest++; page = target; dragging = false; render(); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private static String time(long ms) { return String.format(Locale.ROOT, "%d:%02d", ms / 60000, ms / 1000 % 60); }
    private static void setText(TextView view, String value) {
        if (view instanceof IconButton) ((IconButton) view).setIcon(value);
        else if (!value.contentEquals(view.getText())) view.setText(value);
    }

    private static String fileName(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }

    private RippleDrawable clickable(int color, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x30ffffff), shape(color, radius), null);
    }

    private void styleButton(Button button, boolean emphasized) {
        Motion.press(button);
        button.setBackground(clickable(emphasized ? ACCENT : ELEVATED, 12));
        button.setTextColor(emphasized ? ON_ACCENT : INK);
    }

    private Button highlight(Button button) { styleButton(button, true); return button; }

    private LinearLayout panel(LinearLayout parent) { return surface(parent, false); }
    private LinearLayout cardRow(LinearLayout parent) {
        LinearLayout card = surface(parent, true);
        Motion.press(card);
        return card;
    }

    private LinearLayout surface(LinearLayout parent, boolean horizontal) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(horizontal ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        if (horizontal) view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(12), dp(10), dp(12), dp(10));
        view.setBackground(horizontal ? clickable(SURFACE, 16) : shape(SURFACE, 16));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(0, dp(5), 0, dp(5));
        parent.addView(view, params);
        return view;
    }

    private LinearLayout column(LinearLayout parent) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        parent.addView(column, new LinearLayout.LayoutParams(0, -2, 1));
        return column;
    }

    private void addPreviewableCover(LinearLayout parent, String id, int size) {
        bindPreviewableCover(addCoverTile(parent, null, size), id, parent::performLongClick);
    }

    private void bindPreviewableCover(CoverTile tile, String id, Runnable onLongPress) {
        Object binding = new Object();
        tile.setTag(binding);
        tile.display(null);
        tile.setContentDescription(null);
        tile.setOnClickListener(null);
        tile.setOnLongClickListener(null);
        tile.setClickable(false);
        tile.setLongClickable(false);
        loadWorkCover(id, false, thumbnail -> {
            if (tile.getTag() != binding || thumbnail == null) return;
            tile.display(thumbnail);
            tile.setContentDescription(getString(R.string.text_view_cover));
            tile.setOnClickListener(view -> showCoverPreview(id, thumbnail));
            tile.setOnLongClickListener(view -> { onLongPress.run(); return true; });
            Motion.press(tile);
        });
    }

    private void showCoverPreview(String id, Bitmap thumbnail) {
        Dialog preview = new Dialog(this);
        preview.requestWindowFeature(Window.FEATURE_NO_TITLE);
        FrameLayout content = new FrameLayout(this);
        content.setBackgroundColor(0xff0c0f17);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setImageBitmap(thumbnail);
        image.setContentDescription(title(id) + getString(R.string.text_cover));
        image.setOnClickListener(view -> preview.dismiss());
        image.setPadding(dp(12), dp(52), dp(12), dp(24));
        content.addView(image, new FrameLayout.LayoutParams(-1, -1));

        Button close = new Button(this);
        close.setText(getString(R.string.text_close));
        close.setTextSize(13);
        close.setOnClickListener(view -> preview.dismiss());
        styleButton(close, false);
        FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(dp(64), dp(44), Gravity.TOP | Gravity.END);
        corner.setMargins(dp(8), dp(8), dp(12), 0);
        content.addView(close, corner);
        preview.setContentView(content);
        Window window = preview.getWindow();
        if (window != null) {
            Motion.window(window, R.style.MotionPanel);
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(-1, -1);
        }
        preview.show();

        File file = Metadata.cover(this, id);
        int maxSize = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        workCoverWorker.execute(() -> {
            Bitmap cover = decodeCover(file, maxSize);
            if (cover == null) return;
            handler.post(() -> {
                if (!destroyed && preview.isShowing()) image.setImageBitmap(cover);
            });
        });
    }

    private void loadWorkCover(String id, boolean large, Consumer<Bitmap> display) {
        String key = (large ? "large-work:" : "work:") + Metadata.onlineId(id);
        File file = Metadata.cover(this, id);
        int size = large ? 1024 : dp(160);
        loadArt(key, workCoverWorker, () -> file.isFile() ? decodeCover(file, size) : null, display);
    }

    private void loadArt(String key, ExecutorService worker, Supplier<Bitmap> decode, Consumer<Bitmap> display) {
        Bitmap cached = covers.get(key);
        if (cached != null || unavailableArt.contains(key)) { display.accept(cached); return; }
        List<Consumer<Bitmap>> waiting = pendingArt.computeIfAbsent(key, unused -> new ArrayList<>());
        waiting.add(display);
        if (waiting.size() > 1) return;
        worker.execute(() -> {
            Bitmap bitmap;
            try { bitmap = decode.get(); }
            catch (RuntimeException failure) { bitmap = null; }
            Bitmap result = bitmap;
            handler.post(() -> {
                if (destroyed || pendingArt.get(key) != waiting) return;
                pendingArt.remove(key);
                if (result == null) unavailableArt.add(key);
                else covers.put(key, result);
                for (Consumer<Bitmap> view : waiting) view.accept(result);
            });
        });
    }

    private static Bitmap decodeCover(File cover, int maxSize) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(cover.getPath(), bounds);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maxSize) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeFile(cover.getPath(), options);
    }
    private final class CoverTile extends FrameLayout {
        private final TextView placeholder;
        private final ImageView image;

        CoverTile() {
            super(MainActivity.this);
            placeholder = new TextView(MainActivity.this);
            placeholder.setGravity(Gravity.CENTER);
            placeholder.setTextColor(ACCENT);
            placeholder.setTextSize(24);
            placeholder.setText("♫");
            addView(placeholder, new FrameLayout.LayoutParams(-1, -1));
            image = new ImageView(MainActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(image, new FrameLayout.LayoutParams(-1, -1));
            setBackground(shape(ELEVATED, 12));
            setClipToOutline(true);
        }

        void display(Bitmap bitmap) {
            image.setImageBitmap(bitmap);
            image.setVisibility(bitmap == null ? View.INVISIBLE : View.VISIBLE);
            placeholder.setVisibility(bitmap == null ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private CoverTile addCoverTile(LinearLayout parent, Bitmap bitmap, int size) {
        CoverTile tile = new CoverTile();
        tile.display(bitmap);
        parent.addView(tile, new LinearLayout.LayoutParams(dp(size), dp(size)));
        return tile;
    }

    private LinearLayout scroll(LinearLayout parent) {
        ScrollView scroll = new ScrollView(this);
        parent.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        return body;
    }

    private LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private Button button(LinearLayout parent, String label, Runnable click) {
        Button button = IconButton.supports(this, label) ? new IconButton(this, label) : new Button(this);
        if (!(button instanceof IconButton)) button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(6), dp(4), dp(6), dp(4));
        styleButton(button, false);
        if (click != null) button.setOnClickListener(view -> click.run());
        LinearLayout.LayoutParams params;
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
            params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMargins(dp(2), dp(4), dp(2), dp(4));
        } else {
            params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(0, dp(4), 0, dp(4));
        }
        parent.addView(button, params);
        return button;
    }

    private TextView text(LinearLayout parent, String label, int size) {
        TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(size);
        text.setTextColor(INK);
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    private TextView caption(LinearLayout parent, String label) {
        TextView caption = text(parent, label, 13);
        caption.setTextColor(MUTED);
        caption.setPadding(0, dp(3), 0, dp(3));
        return caption;
    }

    private TextView heading(LinearLayout parent, String label) {
        TextView heading = text(parent, label, 30);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return heading;
    }

    private static String encode(List<Library.Work> works) throws JSONException { return LibraryIndex.encode(works); }
    private static List<Library.Work> decode(String json) throws JSONException { return LibraryIndex.decode(json); }

    @Override public void onBackPressed() {
        if (librarySearch != null && librarySearch.hasFocus()) {
            librarySearch.clearFocus();
            getSystemService(android.view.inputmethod.InputMethodManager.class)
                    .hideSoftInputFromWindow(librarySearch.getWindowToken(), 0);
            return;
        }
        if ("files".equals(page)) fileBack();
        else if ("queue".equals(page)) show(queueBack);
        else if ("manage".equals(page)) backFromSettings();
        else if ("player".equals(page)) show(playerBack);
        else if ("history".equals(page)) showHomeSection(0);
        else if (!"library".equals(page)) show("library");
        else super.onBackPressed();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        capturePagePosition();
        state.putString("page", page);
        state.putString("settingsBack", settingsBack);
        state.putString("settingsSection", settingsSection.name());
        state.putBoolean("returnAfterImport", returnAfterImport);
        state.putParcelable("writeGrantTree", writeGrantTree);
        state.putString("filesBack", filesBack);
        state.putParcelable("fileTree", fileTree);
        ArrayList<String> fileIds = new ArrayList<>(), fileNames = new ArrayList<>();
        for (Library.Entry folder : filePath) {
            fileIds.add(folder.id);
            fileNames.add(folder.name);
        }
        state.putStringArrayList("fileIds", fileIds);
        state.putStringArrayList("fileNames", fileNames);
        state.putBoolean("frequentHistory", frequentHistory);
        state.putString("playerBack", playerBack);
        state.putString("queueBack", queueBack);
        state.putString("query", query);
        state.putString("circleFilter", circleFilter);
        state.putString("actorFilter", actorFilter);
        state.putBoolean("favoritesOnly", favoritesOnly);
        state.putString("selectedUri", selected == null ? "" : selected.tracks.get(0).uri);
        state.putParcelable("lyrics", savedLyrics);
        state.putString("lyricUri", savedLyricUri);
        state.putBoolean("follow", savedFollow);
        state.putString("libraryAnchor", libraryAnchor);
        state.putInt("libraryOffset", libraryAnchorOffset);
        state.putInt("libraryIndex", libraryAnchorIndex);
        Bundle positions = new Bundle();
        for (Map.Entry<String, Integer> entry : scrollPositions.entrySet()) positions.putInt(entry.getKey(), entry.getValue());
        state.putBundle("scrollPositions", positions);
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        unregisterReceiver(usbEvents);
        destroyed = true;
        releaseHomeVelocity();
        for (android.animation.Animator color : lyricColors.values()) color.cancel();
        lyricColors.clear();
        if (screenHost != null) {
            for (int i = 0; i < screenHost.getChildCount(); i++) screenHost.getChildAt(i).animate().cancel();
        }
        if (bound) unbindService(connection);
        handler.removeCallbacksAndMessages(null);
        queueArtWorker.shutdownNow();
        workCoverWorker.shutdownNow();
        fileBrowserWorker.shutdownNow();
        super.onDestroy();
    }
}










