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
import android.widget.EditText;
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
    // While the Kit tab is open, poll the live stats ~7x/sec so the HP bar tracks combat (the native
    // publisher writes 20x/sec; the 1s refresh tick alone made VITALITY lag). Self-stops off the Kit tab.
    private final Runnable hudPoll = new Runnable() {
        @Override public void run() {
            if (destroyed || !resumed || panel == null || !"hud".equals(panel.activeTab)) return;
            panel.pollHud();
            main.postDelayed(this, 140);
        }
    };
    void startHudPoll() { main.removeCallbacks(hudPoll); main.post(hudPoll); }

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
    /** Set by MainActivity: supplies the player's live figures (HP, blood echoes) from the game's shm, or
     *  null when the game was not launched with the Atlas, for the Kit HUD. */
    private Supplier<AtlasStats> statsSource;
    void setStatsSource(Supplier<AtlasStats> f) { statsSource = f; }
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
    /** A representative SoC temperature in °C (or NaN). The hottest *non-CPU-core* thermal zone — the
     *  platform/GPU/memory sensors, close to the device's own skin reading — rather than the single
     *  hottest CPU core, which spikes 10-15°C higher under emulation and over-reports "throttling".
     *  Falls back to the overall max if nothing but CPU zones is readable. Shared by the Status tab and HUD. */
    static float readSocTempC() {
        float best = Float.NaN, anyMax = Float.NaN;
        try {
            File[] zs = new File("/sys/class/thermal").listFiles();
            if (zs != null) for (File z : zs) {
                if (!z.getName().startsWith("thermal_zone")) continue;
                String type = "";
                try (BufferedReader r = new BufferedReader(new FileReader(new File(z, "type")))) {
                    String t = r.readLine(); if (t != null) type = t.trim().toLowerCase(java.util.Locale.ROOT);
                } catch (Exception ignored) { }
                try (BufferedReader r = new BufferedReader(new FileReader(new File(z, "temp")))) {
                    String t = r.readLine();
                    if (t == null) continue;
                    float c = Long.parseLong(t.trim()) / 1000f;
                    if (!(c > 20 && c < 150)) continue;
                    if (Float.isNaN(anyMax) || c > anyMax) anyMax = c;
                    if (type.startsWith("cpu")) continue; // skip the CPU core/subsystem zones (they run hottest)
                    if (Float.isNaN(best) || c > best) best = c;
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return !Float.isNaN(best) ? best : anyMax;
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
        if (panel != null) { panel.tickStatus(); panel.updatePauseButton(); panel.pollTrophies(); panel.pollHud(); panel.tickKitPerf(); }
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
    /** "More Bloodborne": links to free fan games on itch.io. Links only — nothing is bundled, and these are not
     *  affiliated with this app, FromSoftware or Sony. Supports their creators by sending players to their pages. */
    static void openMoreBloodborne(Context context) {
        new AlertDialog.Builder(context).setTitle("Other Bloodborne Stuff I Love")
            .setMessage("Free fan games inspired by Bloodborne — PC only, not affiliated with this app and nothing is bundled here. "
                + "Visit their creators' pages on itch.io:\n\n"
                + "• Bloodborne PSX — a PS1-style demake by Lilith Walther\n"
                + "• Yarntown — a top-down demake by Max Mraz")
            .setNeutralButton("Bloodborne PSX", (dialog, which) -> openLink(context, "https://b0tster.itch.io/bbpsx", "Bloodborne PSX"))
            .setNegativeButton("Yarntown", (dialog, which) -> openLink(context, "https://maxatrillionator.itch.io/yarntown", "Yarntown"))
            .setPositiveButton("Close", null).show();
    }
    /** Opens a URL in the browser, with a fallback dialog showing the address when no browser handles it. */
    static void openLink(Context context, String url, String title) {
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
        try { context.startActivity(intent); }
        catch (android.content.ActivityNotFoundException noBrowser) {
            new AlertDialog.Builder(context).setTitle(title).setMessage(url).setPositiveButton("OK", null).show();
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
        View journalBox; TextView journalView;
        View hudBox;
        View hpFill, hpEmpty, staFill, staEmpty; TextView hpLabel, echoesVal, insightVal, vialsVal, bulletsVal; // live Kit-HUD refs
        final TextView[] invCount = new TextView[10], invName = new TextView[10]; // inventory-slot refs (count + item name)
        TextView kitPerf; // compact performance strip under the Kit inventory
        LinearLayout cTitle, cTabs1, cTabs2, cTabs3; TextView cSubtitle, cFooter;
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
                    // Double-tap anywhere on the lower screen to pause/resume (same thor_tap_pause setting as the
                    // game screen). Observed in dispatchTouchEvent below, so children keep their own taps/slider.
                    @Override public boolean onDoubleTap(android.view.MotionEvent e) {
                        if (pauseToggle != null && prefs.getBoolean("thor_tap_pause", true)) { pauseToggle.run(); updatePauseButton(); }
                        return false;
                    }
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
            LinearLayout titleRow = new LinearLayout(getContext()); titleRow.setOrientation(LinearLayout.HORIZONTAL); titleRow.setGravity(Gravity.CENTER_VERTICAL); content.addView(titleRow); cTitle = titleRow;
            TextView title = text("HUNTER’S COMPANION", 21); title.setTextColor(0xffb59b71); title.setTypeface(Typeface.SERIF, Typeface.BOLD);
            titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            // Always-visible pause/resume, reachable from any tab (not only the game Menu).
            pauseBtn = new Button(getContext()); pauseBtn.setAllCaps(false); pauseBtn.setTextSize(13); pauseBtn.setTextColor(0xffe5ddcd);
            pauseBtn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xc3242930)); pauseBtn.setText("⏸ Pause");
            pauseBtn.setOnClickListener(v -> { if (pauseToggle != null) pauseToggle.run(); updatePauseButton(); });
            titleRow.addView(pauseBtn, new LinearLayout.LayoutParams(-2, dp(40)));
            cSubtitle = text("THOR INTEGRATED PREVIEW · UNOFFICIAL", 10); content.addView(cSubtitle);
            updatePauseButton();
            LinearLayout tabs1 = new LinearLayout(getContext()); content.addView(tabs1); cTabs1 = tabs1;
            button(tabs1, "Menu", () -> toggle("menu"));
            button(tabs1, "Controls", () -> toggle("controls"));
            button(tabs1, "Atlas", () -> openMapDirect.run());
            LinearLayout tabs2 = new LinearLayout(getContext()); content.addView(tabs2); cTabs2 = tabs2;
            button(tabs2, "Status", () -> toggle("status"));
            button(tabs2, "Tune", () -> toggle("tune"));
            button(tabs2, "Help", () -> toggle("help"));
            LinearLayout tabs3 = new LinearLayout(getContext()); content.addView(tabs3); cTabs3 = tabs3;
            button(tabs3, "Kit", () -> toggle("hud"));
            button(tabs3, "Trophies", () -> toggle("trophies"));
            button(tabs3, "Journal", () -> toggle("journal"));
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
            journalBox = buildJournalBox(); journalBox.setVisibility(View.GONE);
            content.addView(journalBox, new LinearLayout.LayoutParams(-1, 0, 1));
            hudBox = buildHudBox(); hudBox.setVisibility(View.GONE);
            content.addView(hudBox, new LinearLayout.LayoutParams(-1, 0, 1));
            cFooter = text("Same app · both screens · artwork is home · tap a tab to open, tap it again to close", 10); content.addView(cFooter);
            setContentView(root); window.setLayout(-1, -1); showArt();
        }
        void showOnly(View which) {
            statusVisible = false; trophiesVisible = false;
            if (scroll != null) scroll.setVisibility(which == scroll ? View.VISIBLE : View.GONE);
            if (menuBox != null) menuBox.setVisibility(which == menuBox ? View.VISIBLE : View.GONE);
            if (statusBox != null) statusBox.setVisibility(which == statusBox ? View.VISIBLE : View.GONE);
            if (tuneBox != null) tuneBox.setVisibility(which == tuneBox ? View.VISIBLE : View.GONE);
            if (trophiesBox != null) trophiesBox.setVisibility(which == trophiesBox ? View.VISIBLE : View.GONE);
            if (journalBox != null) journalBox.setVisibility(which == journalBox ? View.VISIBLE : View.GONE);
            if (hudBox != null) hudBox.setVisibility(which == hudBox ? View.VISIBLE : View.GONE);
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
                case "journal": showJournal(); break;
                case "hud": showHud(); break;
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
        /** Journal tab: a notes scratchpad kept in the app's settings (thor_journal). This lower panel is
         *  non-focusable so the gamepad keeps driving the game, so it takes no soft keyboard; editing opens a
         *  dialog on the main (focusable) screen and the saved text is shown back here. */
        void showJournal() {
            showOnly(journalBox); activeTab = "journal"; shade.setBackgroundColor(0xdf080b10);
            refreshJournal();
        }
        View buildJournalBox() {
            LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(6), dp(8), dp(6), dp(8));
            LinearLayout head = new LinearLayout(getContext()); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
            TextView h = text("JOURNAL", 12); h.setTextColor(0xffc6ad76); h.setTypeface(Typeface.DEFAULT_BOLD);
            head.addView(h, new LinearLayout.LayoutParams(0, -2, 1));
            Button edit = new Button(getContext()); edit.setAllCaps(false); edit.setTextSize(12); edit.setText("✎ Edit notes"); edit.setTextColor(0xffe5ddcd);
            edit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xc3242930)); edit.setOnClickListener(v -> editJournal());
            head.addView(edit, new LinearLayout.LayoutParams(-2, dp(38)));
            col.addView(head);
            ScrollView sv = new ScrollView(getContext()); journalView = text("", 14); journalView.setPadding(dp(4), dp(8), dp(4), dp(8)); sv.addView(journalView);
            col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
            return col;
        }
        void refreshJournal() {
            if (journalView == null) return;
            String notes = prefs.getString("thor_journal", "");
            boolean empty = notes.trim().isEmpty();
            journalView.setText(empty ? "No notes yet. Tap “Edit notes” to jot down NPC quests, item spots, chalice glyphs or passwords — kept on this device." : notes);
            journalView.setTextColor(empty ? 0xff8c93a0 : 0xfff0e6cd);
        }
        /** Edits the notes on the main screen, which can take the keyboard, then shows the result back on this panel. */
        void editJournal() {
            final EditText in = new EditText(owner);
            in.setText(prefs.getString("thor_journal", ""));
            in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            in.setGravity(Gravity.TOP | Gravity.START); in.setMinLines(6); in.setSelection(in.getText().length());
            FrameLayout wrap = new FrameLayout(owner); int p = dp(18); wrap.setPadding(p, dp(8), p, 0); wrap.addView(in);
            new AlertDialog.Builder(owner).setTitle("Hunter's journal").setView(wrap)
                .setPositiveButton("Save", (d, w) -> { prefs.edit().putString("thor_journal", in.getText().toString()).apply(); refreshJournal(); })
                .setNegativeButton("Cancel", null).show();
        }
        // ---- Kit tab: a Bloodborne-styled HUD. Phase 1 is the look (placeholder values); the live items and
        //      counts come from a later native change that reads the game's own inventory. ----
        /** Kit opens fullscreen: hide the title/tabs/footer so the whole lower screen is the HUD; the Back button restores them. */
        void showHud() { setChrome(false); showOnly(hudBox); activeTab = "hud"; shade.setBackgroundColor(0xef060607); pollHud(); updateKitPerf(); startHudPoll(); }
        void setChrome(boolean v) {
            int vis = v ? View.VISIBLE : View.GONE;
            if (cTitle != null) cTitle.setVisibility(vis);
            if (cSubtitle != null) cSubtitle.setVisibility(vis);
            if (cTabs1 != null) cTabs1.setVisibility(vis);
            if (cTabs2 != null) cTabs2.setVisibility(vis);
            if (cTabs3 != null) cTabs3.setVisibility(vis);
            if (cFooter != null) cFooter.setVisibility(vis);
        }
        View buildHudBox() {
            LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(10), dp(6), dp(10), dp(6));
            LinearLayout head = new LinearLayout(getContext()); head.setOrientation(LinearLayout.HORIZONTAL); head.setGravity(Gravity.CENTER_VERTICAL);
            Button back = new Button(getContext()); back.setAllCaps(false); back.setTextSize(13); back.setTextColor(0xffe5ddcd);
            back.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xc3242930)); back.setText("‹ Back");
            back.setOnClickListener(v -> showArt());
            head.addView(back, new LinearLayout.LayoutParams(-2, dp(40)));
            TextView cap = text("THE HUNTER", 14); cap.setTextColor(0xffc6ad76); cap.setTypeface(Typeface.SERIF, Typeface.BOLD); cap.setLetterSpacing(0.18f); cap.setPadding(dp(12), 0, 0, 0); head.addView(cap);
            col.addView(head);
            LinearLayout in = new LinearLayout(getContext()); in.setOrientation(LinearLayout.VERTICAL);
            in.addView(hudBar("VITALITY", 0xff3a0f0f, 0xffb33a3a, 0.01f, 1));
            in.addView(hudBar("STAMINA", 0xff1f3a28, 0xff5aa36a, 0.01f, 2));
            LinearLayout stats = rowBox(dp(8));
            stats.addView(hudCounter("◈", "BLOOD ECHOES", "—", 0xffd9c79a, 1), weight());
            stats.addView(hudCounter("◉", "INSIGHT", "—", 0xff9bbcd6, 2), weight());
            in.addView(stats);
            LinearLayout ammo = rowBox(dp(8));
            ammo.addView(hudCounter("❤", "BLOOD VIALS", "—", 0xffc2524f, 3), weight());
            ammo.addView(hudCounter("◆", "QUICKSILVER", "—", 0xff7fa7d6, 4), weight());
            in.addView(ammo);
            in.addView(section("INVENTORY"));
            for (int r = 0; r < 2; r++) {
                LinearLayout row = rowBox(dp(2));
                for (int c = 0; c < 5; c++) row.addView(invSlot(r * 5 + c), weight());
                in.addView(row);
            }
            // A one-line performance readout under the inventory: the same figures the Status tab shows,
            // so the player can watch how the game runs without leaving the Kit view. Updated on the 1s tick.
            in.addView(section("PERFORMANCE"));
            kitPerf = text("—", 11); kitPerf.setTypeface(Typeface.MONOSPACE); kitPerf.setTextColor(0xffbfae8c);
            kitPerf.setPadding(dp(2), 0, 0, dp(2)); in.addView(kitPerf);
            ScrollView sv = new ScrollView(getContext()); sv.addView(in);
            col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
            return col;
        }
        LinearLayout rowBox(int topPad) { LinearLayout r = new LinearLayout(getContext()); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, topPad, 0, 0); return r; }
        TextView section(String s) { TextView t = text(s, 11); t.setTextColor(0xff8c7a5a); t.setTypeface(Typeface.DEFAULT_BOLD); t.setLetterSpacing(0.1f); t.setPadding(dp(2), dp(9), 0, dp(4)); return t; }
        LinearLayout.LayoutParams weight() { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1); p.setMargins(dp(3), dp(3), dp(3), dp(3)); return p; }
        View hudBar(String label, int back, int fill, float frac, int which) {
            LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(0, dp(2), 0, dp(1));
            TextView l = text(label, 9); l.setTextColor(0xff8c7a5a); l.setTypeface(Typeface.DEFAULT_BOLD); l.setLetterSpacing(0.12f); col.addView(l);
            LinearLayout track = new LinearLayout(getContext()); track.setOrientation(LinearLayout.HORIZONTAL);
            android.graphics.drawable.GradientDrawable tb = new android.graphics.drawable.GradientDrawable(); tb.setColor(0xff161210); tb.setCornerRadius(dp(3)); tb.setStroke(dp(1), 0xff2a2018); track.setBackground(tb);
            View f = new View(getContext()); android.graphics.drawable.GradientDrawable fb = new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, new int[]{back, fill}); fb.setCornerRadius(dp(3)); f.setBackground(fb);
            track.addView(f, new LinearLayout.LayoutParams(0, dp(12), Math.max(0.01f, frac)));
            View e = new View(getContext()); track.addView(e, new LinearLayout.LayoutParams(0, dp(12), Math.max(0.01f, 1f - frac)));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, dp(12)); tp.topMargin = dp(1); col.addView(track, tp);
            if (which == 1) { hpFill = f; hpEmpty = e; hpLabel = l; }
            else if (which == 2) { staFill = f; staEmpty = e; }
            return col;
        }
        /** An inventory cell whose count (in the frame) and item name (below) are updated live in pollHud. */
        View invSlot(int i) {
            LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL); col.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout frame = new FrameLayout(getContext());
            android.graphics.drawable.GradientDrawable fb = new android.graphics.drawable.GradientDrawable(); fb.setColor(0xff13100d); fb.setCornerRadius(dp(6)); fb.setStroke(dp(1), 0xff3a3024); frame.setBackground(fb);
            TextView g = text("", 18); g.setTextColor(0xffe8dfc9); g.setTypeface(Typeface.SERIF, Typeface.BOLD); g.setGravity(Gravity.CENTER); frame.addView(g, new FrameLayout.LayoutParams(-1, -1));
            col.addView(frame, new LinearLayout.LayoutParams(-1, dp(44)));
            TextView l = text("", 8); l.setTextColor(0xff8c7a5a); l.setGravity(Gravity.CENTER); l.setMaxLines(1); l.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(l, new LinearLayout.LayoutParams(-1, -2));
            invCount[i] = g; invName[i] = l;
            return col;
        }
        View hudCounter(String icon, String label, String value, int iconColor, int capture) {
            LinearLayout box = new LinearLayout(getContext()); box.setOrientation(LinearLayout.HORIZONTAL); box.setGravity(Gravity.CENTER_VERTICAL);
            android.graphics.drawable.GradientDrawable bb = new android.graphics.drawable.GradientDrawable(); bb.setColor(0xff13100d); bb.setCornerRadius(dp(8)); bb.setStroke(dp(1), 0xff3a3024); box.setBackground(bb); box.setPadding(dp(10), dp(4), dp(10), dp(4));
            TextView ic = text(icon, 16); ic.setTextColor(iconColor); box.addView(ic);
            LinearLayout t = new LinearLayout(getContext()); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(dp(8), 0, 0, 0);
            TextView val = text(value, 16); val.setTextColor(0xffe8dfc9); val.setTypeface(Typeface.SERIF, Typeface.BOLD); t.addView(val);
            TextView lab = text(label, 8); lab.setTextColor(0xff8c7a5a); lab.setLetterSpacing(0.08f); t.addView(lab);
            box.addView(t);
            if (capture == 1) echoesVal = val;
            else if (capture == 2) insightVal = val;
            else if (capture == 3) vialsVal = val;
            else if (capture == 4) bulletsVal = val;
            return box;
        }
        /** Each companion tick: pull the player's live figures and update the Kit HUD. VITALITY and BLOOD
         *  ECHOES are read from the game's memory (the port's own offsets); the other gauges stay as
         *  placeholders until their offsets are known. Cheap, so it runs whether or not the Kit is open. */
        void pollHud() {
            if (hpFill == null || statsSource == null) return;
            AtlasStats s = statsSource.get();
            boolean hp = s != null && s.hpValid && s.maxHp > 0;
            setBarFrac(hpFill, hpEmpty, hp ? (float) s.curHp / s.maxHp : 0.01f);
            if (hpLabel != null) hpLabel.setText(hp ? "VITALITY   " + s.curHp + " / " + s.maxHp : "VITALITY");
            boolean sp = s != null && s.spValid && s.maxSp > 0;
            if (staFill != null) setBarFrac(staFill, staEmpty, sp ? (float) s.curSp / s.maxSp : 0.01f);
            if (echoesVal != null) echoesVal.setText(s != null && s.echoesValid ? fmt(s.echoes) : "—");
            if (insightVal != null) insightVal.setText(s != null && s.insightValid ? String.valueOf(s.insight) : "—");
            if (vialsVal != null) vialsVal.setText(s != null && s.vialsValid ? String.valueOf(s.vials) : "—");
            if (bulletsVal != null) bulletsVal.setText(s != null && s.bulletsValid ? String.valueOf(s.bullets) : "—");
            int gn = s != null ? s.goodsN : 0;
            for (int i = 0; i < 10 && invCount[i] != null; i++) {
                if (s != null && i < gn) {
                    invCount[i].setText(String.valueOf(s.goodsCounts[i]));
                    invName[i].setText(BbItems.name(s.goodsIds[i]));
                } else { invCount[i].setText(""); invName[i].setText(""); }
            }
        }
        void setBarFrac(View fill, View empty, float frac) {
            frac = Math.max(0.01f, Math.min(1f, frac));
            LinearLayout.LayoutParams fp = (LinearLayout.LayoutParams) fill.getLayoutParams(); fp.weight = frac; fill.setLayoutParams(fp);
            LinearLayout.LayoutParams ep = (LinearLayout.LayoutParams) empty.getLayoutParams(); ep.weight = Math.max(0.01f, 1f - frac); empty.setLayoutParams(ep);
        }
        String fmt(int v) { return String.format(Locale.US, "%,d", v); }
        /** Home view: the art room. The panel opens here and every tab closes back to it. */
        void showArt() {
            setChrome(true);
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
            // Double-tap to pause/resume works across the whole lower screen now (panelGestures.onDoubleTap),
            // so the Status tab needs no handler of its own; the brightness slider keeps its touches.
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
        /** Adreno busy percent from the kgsl sysfs (no root needed on the Thor), or -1. */
        int readGpuPercent() {
            try (BufferedReader r = new BufferedReader(new FileReader("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage"))) {
                String s = r.readLine();
                if (s != null) { java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(s); if (m.find()) return Math.min(100, Integer.parseInt(m.group(1))); }
            } catch (Exception ignored) { }
            try (BufferedReader r = new BufferedReader(new FileReader("/sys/class/kgsl/kgsl-3d0/gpubusy"))) {
                String s = r.readLine();
                if (s != null) { String[] p = s.trim().split("\\s+"); if (p.length >= 2) { long busy = Long.parseLong(p[0]), tot = Long.parseLong(p[1]); if (tot > 0) return (int) Math.min(100, busy * 100 / tot); } }
            } catch (Exception ignored) { }
            return -1;
        }
        void tickKitPerf() { if ("hud".equals(activeTab) && kitPerf != null) updateKitPerf(); }
        /** One compact line under the Kit inventory: FPS · frametime · SoC temp · CPU% · GPU% · RAM · battery.
         *  Reuses the Status tab's readers; refreshed on the 1s tick while the Kit tab is open. */
        void updateKitPerf() {
            if (kitPerf == null) return;
            StringBuilder sb = new StringBuilder();
            float[] p = perfStats();
            if (p != null) sb.append(String.format(Locale.US, "%.0f fps · %.1f ms", p[0], 1000f / Math.max(0.001f, p[0])));
            else sb.append("— fps");
            float tempC = readSocTempC();
            if (!Float.isNaN(tempC)) sb.append(String.format(Locale.US, " · %.0f°C", tempC));
            int cpu = readCpuPercent(); sb.append(" · CPU ").append(cpu < 0 ? "—" : cpu + "%");
            int gpu = readGpuPercent(); sb.append(" · GPU ").append(gpu < 0 ? "—" : gpu + "%");
            try {
                ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); am.getMemoryInfo(mi);
                double usedG = (mi.totalMem - mi.availMem) / 1073741824.0;
                sb.append(String.format(Locale.US, " · RAM %.1fG", usedG));
            } catch (Exception ignored) { }
            try {
                Intent b = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (b != null) {
                    int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                    int pct = lvl < 0 ? -1 : Math.round(lvl * 100f / Math.max(1, scale));
                    sb.append(" · BAT ").append(pct < 0 ? "—" : pct + "%");
                }
            } catch (Exception ignored) { }
            kitPerf.setText(sb.toString());
            kitPerf.setTextColor(!Float.isNaN(tempC) && tempC >= 92 ? 0xffe0534b : !Float.isNaN(tempC) && tempC >= 85 ? 0xffe0a44b : 0xffbfae8c);
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
