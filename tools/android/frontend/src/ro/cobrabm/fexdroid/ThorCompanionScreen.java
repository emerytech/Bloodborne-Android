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
        if (panel != null) panel.tickStatus();
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
                + "Controls reference, Artwork, live Status and Couchside. The physical gamepad still drives the game on the upper screen. "
                + "The top swipe-in drawer stays available too, so the same game menu is reachable from either screen. "
                + "The panel closes when you leave the game activity and returns when you resume. Enable Hunter’s Atlas to use its second-screen map instead. Choosing controls or artwork from the Thor companion menu switches the map off; it can be enabled again in Hunter’s Atlas. "
                + "No game assets, saves or generated native game code are bundled. This preview has its own private save/settings storage; "
                + "it does not import or replace another installed Bloodborne app.\n\nCouchside: the ultimate living room gaming PC remote at couchside.tv. Please support ducvd89’s Bloodborne port via the link by the title. Optional tip for this Thor integration: ko-fi.com/ets3d.")
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
        ImageView background; View shade; TextView body; ScrollView scroll; View couchsideBox;
        ScrollView menuBox; LinearLayout menuList; View statusBox; TextView statusText;
        boolean statusVisible; long lastCpuBusy = -1, lastCpuTotal = -1;
        String activeTab = "art";
        CompanionPresentation(Context context, Display display) { super(context, display); }
        int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
        TextView text(String value, int size) {
            TextView t = new TextView(getContext()); t.setText(value); t.setTextSize(size); t.setTextColor(0xffe5ddcd); t.setLineSpacing(dp(2), 1); return t;
        }
        void button(LinearLayout row, String label, Runnable action) {
            Button b = new Button(getContext()); b.setText(label); b.setTextSize(12); b.setAllCaps(false); b.setTextColor(0xffe5ddcd);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xc3242930));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(44), 1); p.setMargins(dp(2), dp(3), dp(2), dp(3));
            row.addView(b, p); b.setOnClickListener(view -> action.run());
        }
        @Override protected void onCreate(Bundle state) {
            super.onCreate(state);
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            FrameLayout root = new FrameLayout(getContext()); root.setBackgroundColor(0xff101923);
            background = new ImageView(getContext()); background.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(background, new FrameLayout.LayoutParams(-1, -1));
            shade = new View(getContext()); root.addView(shade, new FrameLayout.LayoutParams(-1, -1));
            LinearLayout content = new LinearLayout(getContext()); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(12), dp(8), dp(12), dp(8));
            root.addView(content, new FrameLayout.LayoutParams(-1, -1));
            TextView title = text("HUNTER’S COMPANION", 21); title.setTextColor(0xffb59b71); title.setTypeface(Typeface.SERIF, Typeface.BOLD); content.addView(title);
            content.addView(text("THOR INTEGRATED PREVIEW · UNOFFICIAL", 10));
            LinearLayout tabs1 = new LinearLayout(getContext()); content.addView(tabs1);
            button(tabs1, "Menu", () -> toggle("menu"));
            button(tabs1, "Controls", () -> toggle("controls"));
            button(tabs1, "Atlas", () -> openMapDirect.run());
            LinearLayout tabs2 = new LinearLayout(getContext()); content.addView(tabs2);
            button(tabs2, "Status", () -> toggle("status"));
            button(tabs2, "Couchside", () -> toggle("couchside"));
            button(tabs2, "Help", () -> help(owner));
            scroll = new ScrollView(getContext()); body = text("", 16); body.setPadding(0, dp(6), 0, dp(6)); scroll.addView(body);
            content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            menuBox = new ScrollView(getContext()); menuList = new LinearLayout(getContext()); menuList.setOrientation(LinearLayout.VERTICAL);
            menuBox.addView(menuList); menuBox.setVisibility(View.GONE);
            content.addView(menuBox, new LinearLayout.LayoutParams(-1, 0, 1));
            statusBox = buildStatusBox(); statusBox.setVisibility(View.GONE);
            content.addView(statusBox, new LinearLayout.LayoutParams(-1, 0, 1));
            couchsideBox = buildCouchsideBox(); couchsideBox.setVisibility(View.GONE);
            content.addView(couchsideBox, new LinearLayout.LayoutParams(-1, 0, 1));
            content.addView(text("Same app · both screens · artwork is home · tap a tab to open, tap it again to close", 10));
            setContentView(root); window.setLayout(-1, -1); showArt();
        }
        void showOnly(View which) {
            statusVisible = false;
            if (scroll != null) scroll.setVisibility(which == scroll ? View.VISIBLE : View.GONE);
            if (menuBox != null) menuBox.setVisibility(which == menuBox ? View.VISIBLE : View.GONE);
            if (statusBox != null) statusBox.setVisibility(which == statusBox ? View.VISIBLE : View.GONE);
            if (couchsideBox != null) couchsideBox.setVisibility(which == couchsideBox ? View.VISIBLE : View.GONE);
        }
        /** Tabs toggle against the art-room home: tap opens the view, tap the same tab again closes to artwork. */
        void toggle(String tab) {
            if (tab.equals(activeTab)) { showArt(); return; }
            switch (tab) {
                case "menu": showMenu(); break;
                case "controls": showControls(); break;
                case "status": showStatus(); break;
                case "couchside": showCouchside(); break;
                default: showArt();
            }
        }
        /** Dispatcher for the game-menu "Thor companion" dialog, which sets the artwork/controls preference. */
        void showMode() {
            if (prefs.getBoolean("thor_companion_art", true)) showArt(); else showControls();
        }
        /** Home view: the art room. The panel opens here and every tab closes back to it. */
        void showArt() {
            showOnly(scroll); activeTab = "art";
            shade.setBackgroundColor(0x40080b10);
            body.setGravity(Gravity.CENTER);
            body.setText("Your journey awaits.\n\nRest at a lamp. The Dream keeps your echoes.");
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
            TextView head = text("STATUS", 12); head.setTextColor(0xffc6ad76); head.setTypeface(Typeface.DEFAULT_BOLD); head.setPadding(dp(2), 0, 0, dp(8)); col.addView(head);
            statusText = text("…", 16); statusText.setLineSpacing(dp(7), 1); statusText.setTypeface(Typeface.MONOSPACE); col.addView(statusText);
            TextView bl = text("Panel brightness", 12); bl.setTextColor(0xffb59b71);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-2, -2); blp.topMargin = dp(16); bl.setLayoutParams(blp); col.addView(bl);
            SeekBar sb = new SeekBar(getContext()); sb.setMax(100);
            int saved = prefs.getInt("thor_companion_brightness", 80); sb.setProgress(saved); applyBrightness(saved);
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean u) { applyBrightness(Math.max(5, p)); }
                @Override public void onStartTrackingTouch(SeekBar s) { }
                @Override public void onStopTrackingTouch(SeekBar s) { prefs.edit().putInt("thor_companion_brightness", Math.max(5, s.getProgress())).apply(); }
            });
            col.addView(sb, new LinearLayout.LayoutParams(-1, -2));
            return col;
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
            StringBuilder sb = new StringBuilder();
            sb.append(new SimpleDateFormat("EEE  h:mm:ss a", Locale.getDefault()).format(new Date())).append('\n');
            float[] p = perfStats();
            if (p != null) sb.append(String.format(Locale.US, "FPS   %.0f   avg %.0f   1%% low %.0f\n", p[0], p[1], p[2]));
            else sb.append("FPS   —  (start the game)\n");
            int cpu = readCpuPercent();
            sb.append("CPU   ").append(cpu < 0 ? "—" : cpu + "%");
            String soc = readSocTemp(); if (soc != null) sb.append("      SOC ").append(soc);
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
        }
        String readSocTemp() {
            float max = Float.NaN;
            try {
                java.io.File[] zs = new java.io.File("/sys/class/thermal").listFiles();
                if (zs != null) for (java.io.File z : zs) {
                    if (!z.getName().startsWith("thermal_zone")) continue;
                    try (BufferedReader r = new BufferedReader(new FileReader(new java.io.File(z, "temp")))) {
                        String t = r.readLine();
                        if (t != null) { float c = Long.parseLong(t.trim()) / 1000f; if (c > 20 && c < 150 && (Float.isNaN(max) || c > max)) max = c; }
                    } catch (Exception ignored) { }
                }
            } catch (Exception ignored) { }
            return Float.isNaN(max) ? null : String.format(Locale.US, "%.0f°C", max);
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
        /** Couchside tab: the QR shows only while this tab is selected (not persisted), so it is not on screen
         *  the whole time. The lower screen is a touchscreen, so the card also opens couchside.tv when tapped. */
        void showCouchside() {
            showOnly(couchsideBox); activeTab = "couchside"; shade.setBackgroundColor(0xcf080b10);
        }
        View buildCouchsideBox() {
            LinearLayout col = new LinearLayout(getContext()); col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER); col.setPadding(dp(8), dp(6), dp(8), dp(6));
            TextView head = text("COUCHSIDE", 18); head.setTextColor(0xffb59b71); head.setTypeface(Typeface.SERIF, Typeface.BOLD);
            head.setGravity(Gravity.CENTER); col.addView(head);
            TextView tag = text("The ultimate living-room gaming PC remote", 12); tag.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2); tp.bottomMargin = dp(10); tag.setLayoutParams(tp);
            col.addView(tag);
            ImageView qr = new ImageView(getContext());
            qr.setImageResource(getContext().getResources().getIdentifier("couchside_qr", "drawable", getContext().getPackageName()));
            qr.setBackgroundColor(0xffffffff); qr.setPadding(dp(6), dp(6), dp(6), dp(6));
            col.addView(qr, new LinearLayout.LayoutParams(dp(150), dp(150)));
            TextView cap = text("Scan to open couchside.tv  ·  or tap here", 11); cap.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2); cp.topMargin = dp(10); cap.setLayoutParams(cp);
            col.addView(cap);
            col.setClickable(true); col.setOnClickListener(v -> openSponsor(owner));
            return col;
        }
    }
}
