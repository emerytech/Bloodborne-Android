// SPDX-License-Identifier: GPL-3.0-or-later
package ro.cobrabm.fexdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Presentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.display.DisplayManager;
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
import android.widget.TextView;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Same app/process as the game. Non-focusable Presentation keeps physical controls on the game. */
final class ThorCompanionScreen implements DisplayManager.DisplayListener {
    static final String DISCLOSURE = "Full disclosure: I had this companion integration written entirely with an LLM "
        + "so I could play Bloodborne on my AYN Thor quickly and easily. I’m sharing it so others can avoid repeating "
        + "the work and spending their own tokens on the same setup. This is a personal experimental project. "
        + "The Bloodborne Android port and its tools are the work of ducvd89 and their upstream contributors; "
        + "I am not claiming authorship of them.";
    private final Activity owner;
    private final Supplier<String> gameDirectory;
    private final BooleanSupplier atlasUsesScreen;
    private final Runnable chooseFolder, choosePanel, openMap;
    private final DisplayManager displays;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService images = Executors.newSingleThreadExecutor();
    private CompanionPresentation panel;
    private boolean resumed, destroyed, listening;
    private String imagePath;
    private int imageGeneration;
    private final Runnable tick = new Runnable() {
        @Override public void run() { if (resumed && !destroyed) { refresh(); main.postDelayed(this, 1000); } }
    };

    ThorCompanionScreen(Activity owner, Supplier<String> directory, BooleanSupplier atlas, Runnable chooseFolder, Runnable choosePanel, Runnable openMap) {
        this.owner = owner; gameDirectory = directory; atlasUsesScreen = atlas; this.chooseFolder = chooseFolder; this.choosePanel = choosePanel; this.openMap = openMap;
        displays = (DisplayManager)owner.getSystemService(Context.DISPLAY_SERVICE);
        prefs = owner.getSharedPreferences("bloodborne", Context.MODE_PRIVATE);
    }
    void resume() {
        if (destroyed) return;
        resumed = true;
        if (!listening) { displays.registerDisplayListener(this, main); listening = true; }
        main.removeCallbacks(tick); tick.run();
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
                + "there is no separate companion app or installer. Controls are a reference, not touch input. Artwork is read from your own files. "
                + "The panel closes when you leave the game activity and returns when you resume. Enable Hunter’s Atlas to use its second-screen map instead. Choosing controls or artwork from the Thor companion menu switches the map off; it can be enabled again in Hunter’s Atlas. "
                + "No game assets, saves or generated native game code are bundled. This preview has its own private save/settings storage; "
                + "it does not import or replace another installed Bloodborne app.\n\nCouchside: the ultimate living room gaming PC remote at couchside.tv. Please support ducvd89\u2019s Bloodborne port via the link by the title. Optional tip for this Thor integration: ko-fi.com/ets3d.")
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
        ImageView background; View shade; TextView body;
        CompanionPresentation(Context context, Display display) { super(context, display); }
        int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
        TextView text(String value, int size) {
            TextView t = new TextView(getContext()); t.setText(value); t.setTextSize(size); t.setTextColor(0xffe5ddcd); t.setLineSpacing(dp(2), 1); return t;
        }
        void button(LinearLayout row, String label, Runnable action) {
            Button b = new Button(getContext()); b.setText(label); b.setTextSize(12); b.setAllCaps(false); b.setTextColor(0xffe5ddcd);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xc3242930));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1); p.setMargins(dp(2), dp(4), dp(2), dp(4));
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
            TextView title = text("HUNTER’S COMPANION", 23); title.setTextColor(0xffb59b71); title.setTypeface(Typeface.SERIF, Typeface.BOLD); content.addView(title);
            content.addView(text("THOR INTEGRATED PREVIEW · UNOFFICIAL", 10));
            LinearLayout tabs = new LinearLayout(getContext()); content.addView(tabs);
            button(tabs, "Controls", () -> { prefs.edit().putBoolean("thor_companion_art", false).apply(); showMode(); });
            button(tabs, "Artwork", () -> { prefs.edit().putBoolean("thor_companion_art", true).apply(); showMode(); });
            button(tabs, "Help", () -> help(owner));
            ScrollView scroll = new ScrollView(getContext()); body = text("", 16); body.setPadding(0, dp(6), 0, dp(6)); scroll.addView(body);
            content.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            content.addView(text("Same game app · physical controls stay on the upper screen", 10));
            setContentView(root); window.setLayout(-1, -1); showMode();
        }
        void showMode() {
            boolean art = prefs.getBoolean("thor_companion_art", false);
            shade.setBackgroundColor(art ? 0x40080b10 : 0xcf080b10);
            body.setGravity(art ? Gravity.CENTER : Gravity.START);
            body.setText(art ? "Your journey awaits." : "COMBAT\nR1 · Attack       R2 · Heavy / hold to charge\nL1 · Transform   L2 · Left-hand weapon\n\nFACE BUTTONS — BY POSITION\nTop △ · Blood vial / heal\nRight ○ · Dodge / hold to run\nBottom × · Interact / confirm\nLeft □ · Use selected item\n\nMOVEMENT\nLeft stick · Move      Right stick · Camera\nR3 · Lock on / center camera\nD-pad down · Cycle quick items\nD-pad left / right · Switch weapons");
        }
    }
}
