// SPDX-License-Identifier: GPL-3.0-or-later
package ro.cobrabm.fexdroid;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Presentation;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Same app/process as the game. The Presentation is non-focusable so the physical gamepad keeps driving the
 *  game, but the lower screen is a touchscreen: its tabs, game menu and controls are tap-driven. */
final class ThorCompanionScreen implements DisplayManager.DisplayListener {
    static final String DISCLOSURE = "Full disclosure: I had this companion integration written entirely with an LLM "
        + "so I could play Bloodborne on my AYN Thor quickly and easily. I’m sharing it so others can avoid repeating "
        + "the work and spending their own tokens on the same setup. This is a personal experimental project. "
        + "The Bloodborne Android port and its tools are the work of ducvd89 and their upstream contributors; "
        + "I am not claiming authorship of them.";

    /** One row of the game menu, mirrored onto the lower screen. Shares the same Runnable as the top drawer. */
    static final class MenuAction {
        final String icon, title, detail;
        final Runnable action;
        MenuAction(String icon, String title, String detail, Runnable action) {
            this.icon = icon; this.title = title; this.detail = detail; this.action = action;
        }
    }

    private final Activity owner;
    private final Supplier<String> gameDirectory;
    private final BooleanSupplier atlasUsesScreen;
    private final Runnable chooseFolder, choosePanel, openMap, openMapDirect;
    private final DisplayManager displays;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private CompanionPresentation panel;
    private List<MenuAction> menu;
    private boolean resumed, destroyed, listening;
    private String imagePath;
    private int imageGeneration;
    private final Runnable tick = new Runnable() {
        @Override public void run() { if (resumed && !destroyed) { refresh(); main.postDelayed(this, 1000); } }
    };

    // Perf overlay: sample the game's presented-frame counter (BbPresent) at vsync for FPS / average / 1% low.
    private final float[] frameMs = new float[600];
    private int frameMsCount, frameMsHead;
    private long lastFrames = -1, lastFrameNanos;
    private boolean sampling;
    private final android.view.Choreographer.FrameCallback frameCb = new android.view.Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!resumed || destroyed) { sampling = false; return; }
            sampleFrame(frameTimeNanos);
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    };

    ThorCompanionScreen(Activity owner, Supplier<String> directory, BooleanSupplier atlas, Runnable chooseFolder, Runnable choosePanel, Runnable openMap, Runnable openMapDirect) {
        this.owner = owner; gameDirectory = directory; atlasUsesScreen = atlas; this.chooseFolder = chooseFolder; this.choosePanel = choosePanel; this.openMap = openMap; this.openMapDirect = openMapDirect;
        displays = (DisplayManager)owner.getSystemService(Context.DISPLAY_SERVICE);
        prefs = owner.getSharedPreferences("bloodborne", Context.MODE_PRIVATE);
    }
    /** The game menu rows to mirror on the lower screen. The top swipe-drawer keeps its own copy; both share
     *  these Runnables, so an action behaves the same from either screen. */
    void setMenu(List<MenuAction> actions) { menu = actions; }
    /** Set by MainActivity: applies a one-tap graphics preset (writes bbport.ini and restarts the game). */
    private java.util.function.Consumer<String> presetApplier;
    void setPresetApplier(java.util.function.Consumer<String> f) { presetApplier = f; }
    /** Set by MainActivity: the graphics preset active in bbport.ini now ("performance"/"balanced"/"quality") or null. */
    private Supplier<String> activePreset;
    void setActivePreset(Supplier<String> f) { activePreset = f; }
    /** Set by MainActivity: supplies a fresh read-only snapshot of the game's event flags (or null when the game
     *  was not launched with the Atlas), for the Trophies tab's boss tracking. */
    private Supplier<AtlasFeatures.Flags> flagSource;
    void setFlagSource(Supplier<AtlasFeatures.Flags> f) { flagSource = f; }
    private TrophyProgress trophyProgress;
    void setTrophySource(TrophyProgress p) { trophyProgress = p; }
    /** Set by MainActivity: toggle pause, read paused state, read whether a game is running — for the always-visible
     *  Pause button in the companion header. */
    private Runnable pauseToggle;
    private BooleanSupplier pausedState, pauseRunning;
    void setPauseControl(Runnable toggle, BooleanSupplier paused, BooleanSupplier running) {
        pauseToggle = toggle; pausedState = paused; pauseRunning = running;
    }
    /** MainActivity calls this when pause state changes so the header button relabels at once (it also refreshes each tick). */
    void onPauseChanged() { main.post(() -> { if (panel != null) panel.updatePauseButton(); }); }
    /** The edge-swipe that opens the top game drawer also brings the same menu onto the lower screen; closing it
     *  returns the lower screen to the artwork home. */
    void showMenuTab() { main.post(() -> { if (panel != null) panel.showMenu(); }); }
    void showArtHome() { main.post(() -> { if (panel != null) panel.showArt(); }); }
    /** Companion swipe gestures, run by MainActivity: left = Atlas on the lower screen (no pause); up = pause and
     *  open the full map on the upper screen; down = close that map and resume. */
    private Runnable gestureLeft, gestureUp, gestureDown;
    void setGestures(Runnable left, Runnable up, Runnable down) { gestureLeft = left; gestureUp = up; gestureDown = down; }
    /** Pause/resume to a wanted state (for the Help tab, which pauses while open). */
    private java.util.function.Consumer<Boolean> pauseSetter;
    void setPauseSetter(java.util.function.Consumer<Boolean> f) { pauseSetter = f; }
    /** Rotating discovery tips on the artwork home screen — a fresh one each time you return home. */
    static final String[] TIPS = {
        "Swipe in from the left edge of the game screen to open the menu.",
        "Double-tap the game screen — or this Status area — to pause and resume.",
        "The ⏸ Pause button up top works from any tab, anytime.",
        "Tap a tab to open it; tap the same tab again to come back here.",
        "Tune tab: one-tap Performance, Balanced and Quality graphics presets.",
        "Status tab: live FPS, 1% low, a frame-time graph and chip temperature.",
        "Turn on the on-game HUD from the Tune tab for an FPS overlay on the game.",
        "Check for updates from the game menu — new builds install in place.",
        "Atlas opens the Hunter’s map right here on the lower screen.",
    };
    private int tipIndex;
    /** Highest valid SoC thermal-zone reading in °C, or NaN. Shared by the panel Status tab and the on-game HUD. */
    static float readSocTempC() {
        float max = Float.NaN;
        try {
            File[] zs = new File("/sys/class/thermal").listFiles();
            if (zs != null) for (File z : zs) {
                if (!z.getName().startsWith("thermal_zone")) continue;
                try (BufferedReader r = new BufferedReader(new FileReader(new File(z, "temp")))) {
                    String t = r.readLine();
                    if (t != null) { float c = Long.parseLong(t.trim()) / 1000f; if (c > 20 && c < 150 && (Float.isNaN(max) || c > max)) max = c; }
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return max;
    }
    /** {current, average, 1%-low} FPS from the companion's frame-time window, or null — read by the on-game HUD. */
    float[] fpsForHud() { return perfStats(); }
    void resume() {
        if (destroyed) return;
        resumed = true;
        if (!listening) { displays.registerDisplayListener(this, main); listening = true; }
        main.removeCallbacks(tick); tick.run();
        startSampling();
    }
    void stop() { resumed = false; main.removeCallbacks(tick); dismiss(); }
    void destroy() {
        destroyed = true; stop(); imageGeneration++;
        if (listening) { displays.unregisterDisplayListener(this); listening = false; }
        images.shutdownNow();
    }
    private void dismiss() {
        if (panel != null) { panel.dismiss(); panel = null; imagePath = null; imageGeneration++; }
    }
    private void startSampling() {
        if (sampling) return;
        sampling = true; lastFrames = -1; frameMsCount = 0; frameMsHead = 0;
        android.view.Choreographer.getInstance().postFrameCallback(frameCb);
    }
    private void sampleFrame(long nowNanos) {
        long f = BbPresent.framesIfLoaded();
        if (f <= 0) { lastFrames = f; lastFrameNanos = nowNanos; frameMsCount = 0; return; }
        if (lastFrames >= 0 && f > lastFrames && nowNanos > lastFrameNanos) {
            long nf = f - lastFrames;
            float per = (nowNanos - lastFrameNanos) / 1_000_000f / nf;
            if (per > 0 && per < 1000) { int push = (int) Math.min(nf, 4); for (int i = 0; i < push; i++) pushFrameMs(per); }
        }
        lastFrames = f; lastFrameNanos = nowNanos;
    }
    private void pushFrameMs(float ms) {
        frameMs[frameMsHead] = ms; frameMsHead = (frameMsHead + 1) % frameMs.length;
        if (frameMsCount < frameMs.length) frameMsCount++;
    }
    /** {current, average, 1%-low} FPS from the frame-time window, or null until enough frames are sampled. */
    float[] perfStats() {
        int n = frameMsCount; if (n < 15) return null;
        int cap = frameMs.length, start = (n < cap) ? 0 : frameMsHead;
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = frameMs[(start + i) % cap];
        int k = Math.min(30, n); float cs = 0; for (int i = n - k; i < n; i++) cs += a[i]; float curMs = cs / k;
        float[] s = a.clone(); java.util.Arrays.sort(s);
        float sum = 0; for (float v : s) sum += v; float avgMs = sum / n;
        float p99 = s[Math.min(n - 1, (int) Math.ceil(0.99 * n) - 1)];
        return new float[]{ 1000f / curMs, 1000f / avgMs, 1000f / p99 };
    }
    void refresh() {
        if (destroyed || !resumed || !prefs.getBoolean("thor_companion_enabled", true) || atlasUsesScreen.getAsBoolean()) { dismiss(); return; }
        Display target = null;
        int gameDisplay = owner.getWindowManager().getDefaultDisplay().getDisplayId();
        for (Display display : displays.getDisplays()) {
            if (display.isValid() && display.getDisplayId() != gameDisplay && "Screen-2".equals(display.getName())) { target = display; break; }
        }
        if (target == null) { dismiss(); return; }
        if (panel != null && panel.getDisplay().getDisplayId() != target.getDisplayId()) dismiss();
        if (panel == null) {
            try { panel = new CompanionPresentation(owner, target); panel.show(); }
            catch (RuntimeException unavailable) { panel = null; return; }
        }
        String directory = gameDirectory.get();
        String path = directory == null ? "" : new File(directory, "sce_sys/pic1.png").getAbsolutePath();
        if (!path.equals(imagePath)) loadArt(path);
        if (panel != null) { panel.tickStatus(); panel.updatePauseButton(); panel.pollTrophies(); }
    }
    private void loadArt(String path) {
        imagePath = path; final int generation = ++imageGeneration;
        if (panel != null) panel.background.setImageDrawable(null);
        images.execute(() -> {
            Bitmap bitmap = null;
            try {
                if (!path.isEmpty()) {
                    BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, options); options.inJustDecodeBounds = false; options.inSampleSize = 1;
                    while (options.outWidth / options.inSampleSize > 1600 || options.outHeight / options.inSampleSize > 1600) options.inSampleSize *= 2;
                    bitmap = BitmapFactory.decodeFile(path, options);
                }
            } catch (RuntimeException ignored) { }
            final Bitmap result = bitmap;
            main.post(() -> { if (!destroyed && generation == imageGeneration && panel != null) panel.background.setImageBitmap(result); });
        });
    }
    void settings() {
        String[] options = {"Show controls below", "Show artwork below", "Hunter’s Atlas map", "Hide companion", "Choose game folder", "About / full disclosure"};
        new AlertDialog.Builder(owner).setTitle("Thor companion — same app")
            .setItems(options, (dialog, index) -> {
                if (index < 2) {
                    choosePanel.run();
                    prefs.edit().putBoolean("thor_companion_enabled", true).putBoolean("thor_companion_art", index == 1).apply();
                    refresh(); if (panel != null) panel.showMode();
                } else if (index == 2) { openMap.run(); }
                else if (index == 3) { prefs.edit().putBoolean("thor_companion_enabled", false).apply(); refresh(); }
                else if (index == 4) chooseFolder.run(); else help(owner);
            }).setNegativeButton("Close", null).show();
    }
    private void help(Context context) {
        new AlertDialog.Builder(context).setTitle("One app · two screens")
            .setMessage(DISCLOSURE + "\n\nThis lower panel is built into the game app. It uses the game folder you already selected; "
                + "there is no separate companion app or installer. The lower screen is a touchscreen: use its tabs for the game Menu, "
                + "Controls reference, Artwork, live Status and Tune (the on-game HUD and quick graphics presets). The physical gamepad still drives the game on the upper screen. "
                + "The top swipe-in drawer stays available too, so the same game menu is reachable from either screen. "
                + "The panel closes when you leave the game activity and returns when you resume. Enable Hunter’s Atlas to use its second-screen map instead. Choosing controls or artwork from the Thor companion menu switches the map off; it can be enabled again in Hunter’s Atlas. "
                + "No game assets, saves or generated native game code are bundled. This preview has its own private save/settings storage; "
                + "it does not import or replace another installed Bloodborne app.\n\nCouchside: the ultimate living room gaming PC remote at couchside.tv. Like this Thor build? An optional tip goes to the developer via the badge by the title: ko-fi.com/ets3d. The Bloodborne Android port itself is ducvd89’s work (github.com/ducvd89/Bloodborne-Android).")
            .setNeutralButton("Couchside", (dialog, which) -> openSponsor(context))
            .setPositiveButton("Got it", null).show();
    }
    static void openSponsor(Context context) {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://couchside.tv"));
        try { context.startActivity(intent); }
        catch (android.content.ActivityNotFoundException noBrowser) {
            new AlertDialog.Builder(context).setTitle("Couchside")
                .setMessage("The ultimate living room gaming PC remote at https://couchside.tv")
                .setPositiveButton("OK", null).show();
        }
    }
    static void openKofi(Context context) {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://ko-fi.com/ets3d"));
        try { context.startActivity(intent); }
        catch (android.content.ActivityNotFoundException noBrowser) {
            new AlertDialog.Builder(context).setTitle("Support")
                .setMessage("Optional tip for this Thor integration at https://ko-fi.com/ets3d")
                .setPositiveButton("OK", null).show();
        }
    }
    @Override public void onDisplayAdded(int id) { refresh(); }
    @Override public void onDisplayRemoved(int id) { refresh(); }
    @Override public void onDisplayChanged(int id) { refresh(); }

    private final class CompanionPresentation extends Presentation {
        ImageView background; View shade; TextView body; ScrollView scroll; View tuneBox;
        ScrollView menuBox; LinearLayout menuList; View statusBox; TextView statusText;
        FrameGraphView graph; Button hudBtn, pauseBtn;
        java.util.LinkedHashMap<String,Button> presetButtons; TextView presetActiveLabel;
        View trophiesBox; LinearLayout trophiesList; TextView trophiesHead; boolean trophiesVisible, trophySeeded;
        java.util.Set<Integer> trophyState = new java.util.HashSet<>();
        final java.util.Set<Integer> trophyExpanded = new java.util.HashSet<>();
        boolean statusVisible, pausedForHelp; long lastCpuBusy = -1, lastCpuTotal = -1;
        String activeTab = "art";
        CompanionPresentation(Context context, Display display) { super(context, display); }
        int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
        TextView text(String value, int size) {
            TextView t = new TextView(getContext()); t.setText(value); t.setTextSize(size); t.setTextColor(0xffe5ddcd); t.setLineSpacing(dp(2), 1); return t;
        }
        Button button(LinearLayout row, String label, Runnable action) {
            Button b = new Button(getContext()); b.setText(label); b.setTextSize(12); b.setAllCaps(false); b.setTextColor(0xffe5ddcd);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xc3242930));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1); p.setMargins(dp(2), dp(3), dp(2), dp(3));
            row.addView(b, p); b.setOnClickListener(view -> action.run());
            return b;
        }
        void updatePauseButton() {
            if (pauseBtn == null) return;
            boolean running = pauseRunning != null && pauseRunning.getAsBoolean();
            boolean paused = pausedState != null && pausedState.getAsBoolean();
            pauseBtn.setEnabled(running); pauseBtn.setAlpha(running ? 1f : 0.4f);
            pauseBtn.setText(paused ? "▶ Resume" : "⏸ Pause");
        }
        /** Tints the preset button matching bbport.ini now (gold) and names the active preset below the row. */
        void highlightActivePreset() {
            if (presetButtons == null) return;
            String active = null;
            if (activePreset != null) { try { active = activePreset.get(); } catch (RuntimeException ignored) { } }
            for (java.util.Map.Entry<String,Button> e : presetButtons.entrySet()) {
                boolean on = e.getKey().equals(active);
                e.getValue().setBackgroundTintList(android.content.res.ColorStateList.valueOf(on ? 0xffb59b71 : 0xc3242930));
                e.getValue().setTextColor(on ? 0xff101923 : 0xffe5ddcd);
            }
            if (presetActiveLabel != null) {
                String name = "performance".equals(active) ? "Performance" : "balanced".equals(active) ? "Balanced"
                    : "quality".equals(active) ? "Quality" : null;
                presetActiveLabel.setText(name == null ? "Active: custom settings" : "Active: " + name);
            }
        }
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            Window window = getWindow();
            // Non-focusable (the gamepad keeps driving the game) but touch-MODAL: while the panel is up it owns the
            // lower screen's touches, so swipe gestures aren't stolen by the launcher that runs behind it.
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            final boolean[] flung = { false };
            final android.view.GestureDetector panelGestures = new android.view.GestureDetector(getContext(),
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onFling(android.view.MotionEvent e1, android.view.MotionEvent e2, float vx, float vy) {
                        if (e1 == null || e2 == null) return false;
                        float dx = e2.getX() - e1.getX(), dy = e2.getY() - e1.getY(), min = dp(40);
                        // Posted so enabling Atlas doesn't dismiss the panel out from under the gesture being delivered.
                        if (Math.abs(dx) > Math.abs(dy) && dx < -min && gestureLeft != null) { flung[0] = true; main.post(gestureLeft); return true; }
                        if (Math.abs(dy) >= Math.abs(dx) && Math.abs(dy) > min) {
                            if (dy < 0 && gestureUp != null) { flung[0] = true; main.post(gestureUp); return true; }
                            if (dy > 0 && gestureDown != null) { flung[0] = true; main.post(gestureDown); return true; }
                        }
                        return false;
                    }
                });
            // Observe every touch; children still handle their own taps, scrolls and the slider. A fling fires onFling
            // above; a slower but deliberate VERTICAL swipe is caught on UP (horizontal stays fling-only so the
            // horizontal brightness slider is never mistaken for a swipe-left). flung[] dedupes the two paths.
            FrameLayout root = new FrameLayout(getContext()) {
                float dX, dY; long dT;
                @Override public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
                    int a = ev.getActionMasked();
                    if (a == android.view.MotionEvent.ACTION_DOWN) { flung[0] = false; dX = ev.getX(); dY = ev.getY(); dT = android.os.SystemClock.uptimeMillis(); }
                    panelGestures.onTouchEvent(ev);
                    if (a == android.view.MotionEvent.ACTION_UP && !flung[0]) {
                        float dx = ev.getX() - dX, dy = ev.getY() - dY; long dt = android.os.SystemClock.uptimeMillis() - dT;
                        if (dt < 700 && Math.abs(dy) > dp(64) && Math.abs(dy) > Math.abs(dx) * 1.5f) {
                            if (dy < 0 && gestureUp != null) main.post(gestureUp);
                            else if (dy > 0 && gestureDown != null) main.post(gestureDown);
                        }
                    }
                    return super.dispatchTouchEvent(ev);
                }
            };
            root.setBackgroundColor(0xff101923);
            background = new ImageView(getContext()); background.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(background, new FrameLayout.LayoutParams(-1, -1));
            shade = new View(getContext()); root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout content = new LinearLayout(getContext()); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(12), dp(8), dp(12), dp(8));
            root.addView(content, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout titleRow = new LinearLayout(getContext()); titleRow.setOrientation(LinearLayout.HORIZONTAL); titleRow.setGravity(Gravity.CENTER_VERTICAL); content.addView(titleRow);
            TextView title = text("HUNTER’S COMPANION", 21); title.setTextColor(0xffb59b71); title.setTypeface(Typeface.SERIF, Typeface.BOLD);
            titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            // Always-visible pause/resume, reachable from any tab (not only the game Menu).
            pauseBtn = new Button(getContext()); pauseBtn.setAllCaps(false); pauseBtn.setTextSize(13); pauseBtn.setTextColor(0xffe5ddcd);
            pauseBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xc3242930)); pauseBtn.setText("⏸ Pause");
            pauseBtn.setOnClickListener(v -> { if (pauseToggle != null) pauseToggle.run(); updatePauseButton(); });
            titleRow.addView(pauseBtn, new LinearLayout.LayoutParams(-2, dp(40)));
            content.addView(text("THOR INTEGRATED PREVIEW · UNOFFICIAL", 10));
            updatePauseButton();
            LinearLayout tabs1 = new LinearLayout(getContext()); content.addView(tabs1);
            button(tabs1, "Menu", () -> toggle("menu"));
            button(tabs1, "Controls", () -> toggle("controls"));
            button(tabs1, "Atlas", () -> openMapDirect.run());
            LinearLayout tabs2 = new LinearLayout(getContext()); content.addView(tabs2);
            button(tabs2, "Status", () -> toggle("status"));
            button(tabs2, "Tune", () -> toggle("tune"));
            button(tabs2, "Help", () -> toggle("help"));
            LinearLayout tabs3 = new LinearLayout(getContext()); content.addView(tabs3);
            button(tabs3, "Trophies", () -> toggle("trophies"));
            scroll = new ScrollView(getContext()); body = text("", 16); body.setPadding(0, dp(6), 0, dp(6)); scroll.addView(body);
            content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            menuBox = new ScrollView(getContext()); menuList = new LinearLayout(getContext()); menuList.setOrientation(LinearLayout.VERTICAL);
            menuBox.addView(menuList); menuBox.setVisibility(View.GONE);
            content.addView(menuBox, new LinearLayout.LayoutParams(-1, 0, 1));
            statusBox = buildStatusBox(); statusBox.setVisibility(View.GONE);
            content.addView(statusBox, new LinearLayout.LayoutParams(-1, 0, 1));
            tuneBox = buildTuneBox(); tuneBox.setVisibility(View.GONE);
            content.addView(tuneBox, new LinearLayout.LayoutParams(-1, 0, 1));
            trophiesBox = buildTrophiesBox(); trophiesBox.setVisibility(View.GONE);
            content.addView(trophiesBox, new LinearLayout.LayoutParams(-1, 0, 1));
            content.addView(text("Same app · both screens · artwork is home · tap a tab to open, tap it again to close", 10));
            setContentView(root); window.setLayout(-1, -1); showArt();
        }
        void showOnly(View which) {
            statusVisible = false; trophiesVisible = false;
            if (scroll != null) scroll.setVisibility(which == scroll ? View.VISIBLE : View.GONE);
            if (menuBox != null) menuBox.setVisibility(which == menuBox ? View.VISIBLE : View.GONE);
            if (statusBox != null) statusBox.setVisibility(which == statusBox ? View.VISIBLE : View.GONE);
            if (tuneBox != null) tuneBox.setVisibility(which == tuneBox ? View.VISIBLE : View.GONE);
            if (trophiesBox != null) trophiesBox.setVisibility(which == trophiesBox ? View.VISIBLE : View.GONE);
        }
        /** Tabs toggle against the art-room home: tap opens the view, tap the same tab again closes to artwork. */
        void toggle(String tab) {
            if (!"help".equals(tab) && pausedForHelp) resumeHelp();
            if (tab.equals(activeTab)) { showArt(); return; }
            switch (tab) {
                case "menu": showMenu(); break;
                case "controls": showControls(); break;
                case "status": showStatus(); break;
                case "tune": showTune(); break;
                case "help": showHelp(); break;
                case "trophies": showTrophies(); break;
                default: showArt();
            }
        }
        void resumeHelp() { pausedForHelp = false; if (pauseSetter != null) pauseSetter.accept(false); }
        /** Dispatcher for the game-menu "Thor companion" dialog, which sets the artwork/controls preference. */
        void showMode() {
            if (prefs.getBoolean("thor_companion_art", true)) showArt(); else showControls();
        }
        /** Help tab: shows on this lower screen and pauses the game while open (tap Help again, or any tab, to resume). */
        void showHelp() {
            showOnly(scroll); activeTab = "help"; shade.setBackgroundColor(0xef080b10);
            body.setGravity(Gravity.START);
            body.setText("GAME PAUSED while Help is open — tap Help again (or any tab) to resume.\n\n"
                + "ONE APP · TWO SCREENS\nThe game runs on the upper screen; this touchscreen is its companion. Tap a tab to open it, tap the same tab again to return to the artwork home.\n\n"
                + "GESTURES\n"
                + "• Swipe in from the left edge of the upper screen to open the game menu (it also shows here).\n"
                + "• Double-tap the upper screen — or the Status tab — to pause and resume.\n"
                + "• Swipe left here to put the Hunter's Atlas map on this lower screen while you keep playing up top.\n"
                + "• Swipe up here to pause and open the full map on the upper screen; swipe up again to close it and resume.\n"
                + "• The ⏸ Pause button up top works from any tab.\n\n"
                + "TABS\nMenu — the game menu, mirrored here.  Controls — the button layout.  Status — live FPS, 1% low, a frame-time graph and temperature.  Tune — the on-game HUD and one-tap graphics presets.  Atlas — the Hunter's map.\n\n"
                + DISCLOSURE + "\n\n"
                + "Couchside, the ultimate living-room gaming PC remote, is at couchside.tv. The Bloodborne Android port is ducvd89's work (github.com/ducvd89/Bloodborne-Android). An optional tip for this Thor build: ko-fi.com/ets3d.");
            pausedForHelp = true; if (pauseSetter != null) pauseSetter.accept(true);
        }
        /** Trophies tab: the full PS4 trophy board, read from the game's own trophy save (TrophyProgress). */
        void showTrophies() {
            showOnly(trophiesBox); activeTab = "trophies"; trophiesVisible = true; shade.setBackgroundColor(0xdf080b10);
            renderTrophies(currentUnlocked());
        }
        View buildTrophiesBox() {
            LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(6), dp(8), dp(6), dp(8));
            trophiesHead = text("TROPHIES", 12); trophiesHead.setTextColor(0xffc6ad76); trophiesHead.setTypeface(Typeface.DEFAULT_BOLD); trophiesHead.setPadding(dp(2), 0, 0, dp(8)); col.addView(trophiesHead);
            ScrollView sv = new ScrollView(getContext()); trophiesList = new LinearLayout(getContext()); trophiesList.setOrientation(LinearLayout.VERTICAL); sv.addView(trophiesList);
            col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
            return col;
        }
        /** Unlocked trophy ids from the game's trophy save; empty (never null) when nothing is tracked yet. */
        java.util.Set<Integer> currentUnlocked() {
            try { return trophyProgress == null ? java.util.Collections.<Integer>emptySet() : trophyProgress.unlocked(); }
            catch (RuntimeException e) { return java.util.Collections.<Integer>emptySet(); }
        }
        TrophyCatalog.Trophy byId(int id) { for (TrophyCatalog.Trophy t : TrophyCatalog.TROPHIES) if (t.id == id) return t; return null; }
        /** Each tick: read the unlocks, celebrate any new one, and refresh the board when it is showing. */
        void pollTrophies() {
            java.util.Set<Integer> now = currentUnlocked();
            if (!trophySeeded) { trophyState = new java.util.HashSet<>(now); trophySeeded = true; if (trophiesVisible) renderTrophies(now); return; }
            if (now.equals(trophyState)) return; // nothing changed: leave the board, its scroll and any open description alone
            for (Integer id : now) if (!trophyState.contains(id)) { TrophyCatalog.Trophy t = byId(id); if (t != null) flourish(t); }
            trophyState = new java.util.HashSet<>(now);
            if (trophiesVisible) renderTrophies(now);
        }
        void renderTrophies(java.util.Set<Integer> unlocked) {
            if (trophiesList == null || trophiesHead == null) return;
            trophiesList.removeAllViews();
            int got = 0; for (TrophyCatalog.Trophy t : TrophyCatalog.TROPHIES) if (unlocked.contains(t.id)) got++;
            trophiesHead.setText("TROPHIES   " + got + " / " + TrophyCatalog.total());
            TextView hint = text("Tap a trophy for what it is. Unlocks appear here as the game awards them.", 10);
            hint.setTextColor(0xff7c828c); hint.setPadding(dp(4), 0, dp(4), dp(8)); trophiesList.addView(hint);
            boolean dlcHeaded = false;
            for (TrophyCatalog.Trophy t : TrophyCatalog.TROPHIES) {
                if (t.dlc && !dlcHeaded) { trophiesList.addView(sectionLabel("THE OLD HUNTERS")); dlcHeaded = true; }
                trophiesList.addView(trophyRow(t, unlocked.contains(t.id)));
            }
        }
        TextView sectionLabel(String s) {
            TextView t = text(s, 10); t.setTextColor(0xff8c93a0); t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(dp(4), dp(12), 0, dp(5)); return t;
        }
        View trophyRow(TrophyCatalog.Trophy t, boolean got) {
            int accent = TrophyCatalog.gradeColor(t.grade);
            LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(8), dp(10), dp(8));
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(got ? (accent & 0x2effffff) : 0x12ffffff); bg.setCornerRadius(dp(10));
            if (got) bg.setStroke(dp(1), (accent & 0x66ffffff));
            row.setBackground(bg);
            LinearLayout head = new LinearLayout(getContext()); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
            TextView mark = text(got ? "🏆" : "○", 16); mark.setTextColor(got ? accent : 0xff6f7178); mark.setGravity(Gravity.CENTER);
            head.addView(mark, new LinearLayout.LayoutParams(dp(26), -2));
            LinearLayout cold = new LinearLayout(getContext()); cold.setOrientation(LinearLayout.VERTICAL); cold.setPadding(dp(8), 0, 0, 0);
            TextView nm = text(t.name, 14); nm.setTextColor(got ? 0xfff0e6cd : 0xffb8bcc2); if (got) nm.setTypeface(Typeface.DEFAULT_BOLD); cold.addView(nm);
            TextView sub = text(TrophyCatalog.gradeName(t.grade) + (got ? " · Unlocked" : ""), 10); sub.setTextColor(got ? accent : 0xff8c93a0); cold.addView(sub);
            head.addView(cold, new LinearLayout.LayoutParams(0, -2, 1));
            TextView info = text("ⓘ", 14); info.setTextColor(0xff6f7178); info.setPadding(dp(6), 0, dp(2), 0); head.addView(info);
            row.addView(head);
            TextView desc = text(t.desc, 11); desc.setTextColor(0xffc3c9d1); desc.setPadding(dp(34), dp(6), dp(4), dp(2));
            desc.setVisibility(trophyExpanded.contains(t.id) ? View.VISIBLE : View.GONE); row.addView(desc);
            row.setOnClickListener(v -> {
                boolean open = desc.getVisibility() == View.VISIBLE;
                desc.setVisibility(open ? View.GONE : View.VISIBLE);
                if (open) trophyExpanded.remove(t.id); else trophyExpanded.add(t.id);
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.bottomMargin = dp(5); row.setLayoutParams(rp);
            return row;
        }
        void flourish(TrophyCatalog.Trophy t) {
            android.widget.Toast.makeText(owner, "🏆  " + t.name + " — " + TrophyCatalog.gradeName(t.grade) + " trophy!", android.widget.Toast.LENGTH_LONG).show();
            try { android.os.Vibrator v = (android.os.Vibrator) owner.getSystemService(Context.VIBRATOR_SERVICE); if (v != null) v.vibrate(140); } catch (Exception ignored) { }
        }
        /** Home view: the art room. The panel opens here and every tab closes back to it. */
        void showArt() {
            if (pausedForHelp) resumeHelp();
            showOnly(scroll); activeTab = "art";
            shade.setBackgroundColor(0x40080b10);
            body.setGravity(Gravity.CENTER);
            String tip = TIPS[Math.floorMod(tipIndex++, TIPS.length)];
            body.setText("Your journey awaits.\n\nRest at a lamp. The Dream keeps your echoes.\n\n✦ Did you know?\n" + tip);
        }
        void showControls() {
            showOnly(scroll); activeTab = "controls";
            shade.setBackgroundColor(0xcf080b10);
            body.setGravity(Gravity.START);
            body.setText("COMBAT\nR1 · Attack       R2 · Heavy / hold to charge\nL1 · Transform    L2 · Left-hand (firearm / shield)\nParry · Fire L2 as the enemy strikes, then R1 up close for a visceral attack\nRally · Attack right after taking damage to win health back\n\nFACE BUTTONS — BY POSITION\nTop △ · Blood vial / heal\nRight ○ · Dodge / quickstep / hold to run\nBottom × · Interact / confirm\nLeft □ · Use selected quick item\n\nMOVEMENT & CAMERA\nLeft stick · Move      Right stick · Camera\nR3 · Lock on / center camera\nD-pad up · Cycle hunter tools\nD-pad down · Cycle quick items\nD-pad left / right · Switch left / right weapon\n\nMENUS\nOptions · Pause / inventory      Touchpad · Gestures\nHold Options at a lamp · Return to the Hunter's Dream\n\nQUICK TIPS\nDied? Your Blood Echoes wait where you fell, or on the enemy that took them.\nInsight is spent at the Bath Messengers and quietly reshapes the night.\nLook for shortcuts; most loop back to a lamp you already lit.");
        }
        /** Menu tab: the game drawer mirrored onto the lower screen. Rebuilt on open so titles reflect current
         *  state. Actions share the top drawer's Runnables; any dialog they open appears on the upper screen. */
        void showMenu() {
            if (menuList != null) {
                menuList.removeAllViews();
                TextView h = text("GAME MENU · tap an item", 12); h.setTextColor(0xffc6ad76); h.setTypeface(Typeface.DEFAULT_BOLD);
                h.setPadding(dp(4), dp(2), 0, dp(8)); menuList.addView(h);
                if (menu == null || menu.isEmpty()) {
                    menuList.addView(text("Game menu unavailable.", 13));
                } else {
                    for (MenuAction a : menu) menuList.addView(menuRow(a));
                }
            }
            showOnly(menuBox); activeTab = "menu"; shade.setBackgroundColor(0xdf080b10);
        }
        View menuRow(MenuAction a) {
            LinearLayout row = new LinearLayout(getContext()); row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10), dp(9), dp(10), dp(9)); row.setClickable(true);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0x22c6ad76); bg.setCornerRadius(dp(10)); row.setBackground(bg);
            ImageView ic = new ImageView(getContext());
            int id = a.icon == null ? 0 : getContext().getResources().getIdentifier(a.icon, "drawable", getContext().getPackageName());
            if (id != 0) ic.setImageResource(id);
            ic.setColorFilter(0xffc6ad76);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(22), dp(22)); ip.rightMargin = dp(12);
            row.addView(ic, ip);
            LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL);
            col.addView(text(a.title, 14));
            if (a.detail != null && !a.detail.isEmpty()) { TextView d = text(a.detail, 10); d.setTextColor(0xff9aa0a8); col.addView(d); }
            row.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> { try { a.action.run(); } catch (RuntimeException ignored) { } });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2); rp.bottomMargin = dp(6); row.setLayoutParams(rp);
            return row;
        }
        /** Status tab: live CPU, RAM, battery/temperature and clock (updated each second), plus a brightness
         *  slider for this lower screen. CPU and some readings are best-effort and show — when unavailable. */
        void showStatus() {
            showOnly(statusBox); activeTab = "status"; statusVisible = true; shade.setBackgroundColor(0xdf080b10); updateStatus();
        }
        View buildStatusBox() {
            LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(6), dp(8), dp(6), dp(8));
            TextView head = text("STATUS", 12); head.setTextColor(0xffc6ad76); head.setTypeface(Typeface.DEFAULT_BOLD); head.setPadding(dp(2), 0, 0, dp(6)); col.addView(head);
            statusText = text("…", 16); statusText.setLineSpacing(dp(7), 1); statusText.setTypeface(Typeface.MONOSPACE); col.addView(statusText);
            // Live frame-time graph: a sparkline of recent frame times with 60 and 30 FPS reference lines.
            TextView gl = text("FRAME TIME  ·  green 60fps  amber 30fps", 10); gl.setTextColor(0xffb59b71);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2); glp.topMargin = dp(12); gl.setLayoutParams(glp); col.addView(gl);
            graph = new FrameGraphView(getContext());
            LinearLayout.LayoutParams grp = new LinearLayout.LayoutParams(-1, dp(72)); grp.topMargin = dp(4); col.addView(graph, grp);
            TextView bl = text("Panel brightness", 12); bl.setTextColor(0xffb59b71);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2); blp.topMargin = dp(18); bl.setLayoutParams(blp); col.addView(bl);
            SeekBar sb = new SeekBar(getContext()); sb.setMax(100);
            int saved = prefs.getInt("thor_companion_brightness", 80); sb.setProgress(saved); applyBrightness(saved);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean u) { applyBrightness(Math.max(5, p)); }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { prefs.edit().putInt("thor_companion_brightness", Math.max(5, s.getProgress())).apply(); }
            });
            col.addView(sb, new LinearLayout.LayoutParams(-1, -2));
            // Double-tap anywhere in the readout (not the slider) to pause/resume — pause with a thumb on the bottom
            // screen. Same thor_tap_pause setting; the slider keeps working (it consumes its touches first).
            final android.view.GestureDetector statusTap = new android.view.GestureDetector(getContext(),
                new android.view.GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onDoubleTap(android.view.MotionEvent e) {
                        if (pauseToggle != null && prefs.getBoolean("thor_tap_pause", true)) pauseToggle.run();
                        return true;
                    }
                });
            col.setOnTouchListener((v, e) -> { statusTap.onTouchEvent(e); return true; });
            return col;
        }
        String hudModeLabel(int mode) { return mode == 1 ? "FPS only" : mode == 2 ? "Full" : "Off"; }
        void applyPreset(String key) {
            if (presetApplier == null) return;
            try { presetApplier.accept(key); } catch (RuntimeException ignored) { }
            android.widget.Toast.makeText(getContext(), key.substring(0, 1).toUpperCase(Locale.US) + key.substring(1) + " preset — restarting", android.widget.Toast.LENGTH_SHORT).show();
            highlightActivePreset();
        }
        void applyBrightness(int pct) {
            Window w = getWindow(); if (w == null) return;
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.screenBrightness = Math.max(0.05f, Math.min(1f, pct / 100f));
            w.setAttributes(lp);
        }
        void tickStatus() { if (statusVisible) updateStatus(); }
        void updateStatus() {
            if (statusText == null) return;
            if (graph != null) graph.invalidate();
            StringBuilder sb = new StringBuilder();
            sb.append(new SimpleDateFormat("EEE  h:mm:ss a", Locale.getDefault()).format(new Date())).append('\n');
            float[] p = perfStats();
            if (p != null) sb.append(String.format(Locale.US, "FPS   %.0f   avg %.0f   1%% low %.0f\n", p[0], p[1], p[2]));
            else sb.append("FPS   —  (start the game)\n");
            int cpu = readCpuPercent();
            sb.append("CPU   ").append(cpu < 0 ? "—" : cpu + "%");
            float tempC = readSocTempC();
            if (!Float.isNaN(tempC)) {
                sb.append(String.format(Locale.US, "      SOC %.0f°C", tempC));
                if (tempC >= 92) sb.append("  ⚠ THROTTLING");
                else if (tempC >= 85) sb.append("  ⚠ HOT");
            }
            sb.append('\n');
            try {
                ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); am.getMemoryInfo(mi);
                long usedMb = (mi.totalMem - mi.availMem) / 1048576L, totalMb = mi.totalMem / 1048576L;
                sb.append("RAM   ").append(usedMb).append(" / ").append(totalMb).append(" MB\n");
            } catch (Exception e) { sb.append("RAM   —\n"); }
            try {
                Intent b = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (b != null) {
                    int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    int pct = lvl < 0 ? -1 : Math.round(lvl * 100f / Math.max(1, scale));
                    int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
                    sb.append("BATT  ").append(pct < 0 ? "—" : pct + "%");
                    if (t != Integer.MIN_VALUE) sb.append("     ").append(String.format(Locale.US, "%.1f°C", t / 10f));
                    sb.append('\n');
                }
            } catch (Exception ignored) { }
            statusText.setText(sb.toString().trim());
            statusText.setTextColor(!Float.isNaN(tempC) && tempC >= 92 ? 0xffe0534b : !Float.isNaN(tempC) && tempC >= 85 ? 0xffe0a44b : 0xffe5ddcd);
        }
        String readSocTemp() {
            float c = readSocTempC();
            return Float.isNaN(c) ? null : String.format(Locale.US, "%.0f°C", c);
        }
        int readCpuPercent() {
            try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
                String line = r.readLine();
                if (line == null || !line.startsWith("cpu ")) return -1;
                String[] p = line.trim().split("\\s+");
                long total = 0, idle = 0;
                for (int i = 1; i < p.length; i++) { long v = Long.parseLong(p[i]); total += v; if (i == 4 || i == 5) idle += v; }
                long busy = total - idle;
                int pct = -1;
                if (lastCpuTotal >= 0 && total > lastCpuTotal) pct = Math.round((busy - lastCpuBusy) * 100f / (total - lastCpuTotal));
                lastCpuBusy = busy; lastCpuTotal = total;
                return pct < 0 ? -1 : Math.min(100, pct);
            } catch (Exception e) { return -1; }
        }
        /** Tune tab (in the former Couchside slot): the on-game HUD toggle and the one-tap graphics presets.
         *  Kept off the Status tab so the live readout there is never pushed off screen. */
        void showTune() {
            showOnly(tuneBox); activeTab = "tune"; shade.setBackgroundColor(0xdf080b10); highlightActivePreset();
        }
        View buildTuneBox() {
            LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(8), dp(8), dp(8), dp(8));
            TextView head = text("TUNE", 12); head.setTextColor(0xffc6ad76); head.setTypeface(Typeface.DEFAULT_BOLD); head.setPadding(dp(2), 0, 0, dp(10)); col.addView(head);
            // On-game HUD: a corner FPS/temp overlay on the upper (game) screen. Cycles Off / FPS only / Full.
            TextView hl = text("On-game HUD (upper screen)", 12); hl.setTextColor(0xffb59b71); col.addView(hl);
            hudBtn = new Button(getContext()); hudBtn.setTextSize(13); hudBtn.setAllCaps(false); hudBtn.setTextColor(0xffe5ddcd);
            hudBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xc3242930));
            hudBtn.setText("On-game HUD: " + hudModeLabel(prefs.getInt("thor_hud", 0)));
            hudBtn.setOnClickListener(v -> {
                int mode = (prefs.getInt("thor_hud", 0) + 1) % 3;
                prefs.edit().putInt("thor_hud", mode).apply();
                hudBtn.setText("On-game HUD: " + hudModeLabel(mode));
            });
            LinearLayout.LayoutParams hbp = new LinearLayout.LayoutParams(-1, dp(46)); hbp.topMargin = dp(4); col.addView(hudBtn, hbp);
            TextView hn = text("Off  ·  FPS only  ·  Full (adds average, 1% low and temperature)", 9); hn.setTextColor(0xff8c93a0); col.addView(hn);
            // Quick graphics presets: one tap writes bbport.ini and restarts the game on the upper screen.
            TextView pl = text("Quick graphics  ·  applies and restarts the game", 12); pl.setTextColor(0xffb59b71);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2); plp.topMargin = dp(18); pl.setLayoutParams(plp); col.addView(pl);
            LinearLayout presets = new LinearLayout(getContext()); col.addView(presets);
            presetButtons = new java.util.LinkedHashMap<>();
            presetButtons.put("performance", button(presets, "Performance", () -> applyPreset("performance")));
            presetButtons.put("balanced", button(presets, "Balanced", () -> applyPreset("balanced")));
            presetButtons.put("quality", button(presets, "Quality", () -> applyPreset("quality")));
            presetActiveLabel = text("", 11); presetActiveLabel.setTextColor(0xffc6ad76);
            LinearLayout.LayoutParams palp = new LinearLayout.LayoutParams(-2, -2); palp.topMargin = dp(6); presetActiveLabel.setLayoutParams(palp); col.addView(presetActiveLabel);
            TextView pn = text("Performance   720p · GSR · 60 fps\nBalanced   1080p · GSR · 45 fps\nQuality   1080p · FSR 3.1 + frame-gen · 30 fps", 10);
            pn.setTextColor(0xff8c93a0); pn.setLineSpacing(dp(3), 1); LinearLayout.LayoutParams pnp = new LinearLayout.LayoutParams(-2, -2); pnp.topMargin = dp(6); pn.setLayoutParams(pnp); col.addView(pn);
            highlightActivePreset();
            ScrollView sv = new ScrollView(getContext()); sv.addView(col); return sv;
        }
        /** Draws the recent frame times (ms) as a sparkline, with 60 FPS (16.7 ms) and 30 FPS (33.3 ms) guide lines.
         *  Reads the outer ring buffer directly; bars are green at/above ~55 FPS, amber above ~28, red below. */
        final class FrameGraphView extends View {
            private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final android.graphics.Paint guide = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            FrameGraphView(Context c) { super(c); setBackgroundColor(0x33050709); }
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                float w = getWidth(), h = getHeight(), maxMs = 50f; // y scale: 0..50 ms (>= 20 FPS stays on-chart)
                guide.setStrokeWidth(Math.max(1f, dp(1) * 0.7f));
                guide.setColor(0x6657c77a); float y60 = h - (1000f / 60f) / maxMs * h; canvas.drawLine(0, y60, w, y60, guide);
                guide.setColor(0x66e0a44b); float y30 = h - (1000f / 30f) / maxMs * h; canvas.drawLine(0, y30, w, y30, guide);
                int n = frameMsCount; if (n < 2) return;
                int cap = frameMs.length, show = Math.min(n, 150), start = frameMsHead - show;
                float bw = w / show;
                for (int i = 0; i < show; i++) {
                    float ms = frameMs[((start + i) % cap + cap) % cap];
                    if (ms <= 0) continue;
                    float bh = Math.min(h, ms / maxMs * h);
                    fill.setColor(ms <= 1000f / 55f ? 0xff57c77a : ms <= 1000f / 28f ? 0xffe0a44b : 0xffe0534b);
                    canvas.drawRect(i * bw, h - bh, (i + 1) * bw - (bw > 2 ? 1 : 0), h, fill);
                }
            }
        }
    }
}
