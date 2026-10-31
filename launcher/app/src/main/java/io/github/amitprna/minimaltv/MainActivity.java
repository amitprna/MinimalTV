package io.github.amitprna.minimaltv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** TV Home: CleanFrame's photographic Home, its Settings, and a StandBy clock. */
public class MainActivity extends Activity implements HomeView.Host {

    private static final String TAG = "TvHome";
    static final String YOUTUBE = "com.google.android.youtube.tv";
    static final String PRIME_VIDEO = "com.amazon.amazonvideo.livingroom";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> thumbs = new HashMap<>();
    private final BroadcastReceiver clockTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            home.invalidate();
            // Day/night is re-checked every minute and whenever the clock or timezone changes: at
            // boot the clock is often wrong until network time arrives, and timers stall in sleep.
            if (!home.hasPanels() && look.applySchedule()) commitLook();
            else if (!Intent.ACTION_TIME_TICK.equals(intent.getAction())) schedulePeriodSwitch();
        }
    };
    private final Runnable rotate = this::rotateWallpaper;
    private final Runnable periodSwitch = this::onPeriodSwitch;

    private SharedPreferences prefs;
    private List<Wallpaper> walls;
    private Look look;
    private Fonts fonts;
    private HomeView home;
    private LiveScene scene;
    private Rect screen;
    private String composedKey;
    private boolean lostFocusInStandby;
    private float standbyT; // eased StandBy background progress, 0 = Home

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("home", MODE_PRIVATE);
        walls = Wallpaper.load(this);
        look = Look.load(prefs, walls.get(0).id);
        look.applySchedule();
        if ("open".equals(look.rotation) && look.photo()) {
            advanceWallpaper(); // "Each time Home opens" advances on load
            look.save(prefs);
        }
        fonts = new Fonts(getAssets());
        screen = getWindowManager().getCurrentWindowMetrics().getBounds();
        home = new HomeView(this, this, fonts, look, walls, findWall(look.wall));
        scene = new LiveScene(this);
        scene.onShowing(home::videoShowing);
        scene.onSettled(home::sceneSettled);
        FrameLayout root = new FrameLayout(this);
        root.addView(scene.view); // beneath Home: seen through a live backdrop or StandBy's veil
        root.addView(home);
        setContentView(root);
        home.requestFocus();

        worker.execute(() -> {
            // Cut every live scene's stills up front, so Settings has thumbnails before first use.
            for (Wallpaper w : walls) {
                if (!w.live()) continue;
                try {
                    LiveWall.ensure(this, w);
                } catch (Exception e) {
                    Log.e(TAG, "stills failed for " + w.id, e);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(clockTick, f);
        refreshApps(); // apps may have been installed or removed while we were away
        if (look.applySchedule()) commitLook(); // day turned to night (or back) while away
        schedulePeriodSwitch();
        ensureBitmaps();
        home.scheduleIdle();
        scheduleRotation();
        updateScene();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(clockTick);
        home.cancelIdle();
        main.removeCallbacks(rotate);
        main.removeCallbacks(periodSwitch);
        scene.stop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String capture = intent.getStringExtra("capture");
        if (capture != null) {
            capture(capture, intent.getStringExtra("wall"), intent.getStringExtra("layer"));
            return;
        }
        // Home pressed while Home is showing: back to the plain Home view.
        home.closeAllPanels();
        home.exitStandby();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            // The screensaver (or anything over Home) plays its own copy; never decode twice.
            if (home.inStandby()) lostFocusInStandby = true;
            scene.stop();
            return;
        }
        // Returning from the system screensaver lands in StandBy; animate back to Home.
        if (lostFocusInStandby) {
            lostFocusInStandby = false;
            home.exitStandby();
        }
        updateScene();
    }

    /** Live wallpaper on the Photo background. */
    private boolean liveHome() { return look.photo() && findWall(look.wall).live(); }

    /** The scene Home or StandBy plays: the wallpaper if live, else the first live scene (rain). */
    private Wallpaper liveWall() {
        Wallpaper w = findWall(look.wall);
        if (w.live()) return w;
        for (Wallpaper x : walls) if (x.live()) return x;
        return null;
    }

    /** Plays the video while Home or StandBy shows it, and only then. */
    private void updateScene() {
        scene.setScene(liveWall());
        scene.setVolume(look.sceneVolume());
        scene.setStandby(home.inStandby(), home.inStandby() && standbyT >= 1, look.liveStandby() ? look.standbyBlur : 0, liveHome());
        boolean want = hasWindowFocus() && (liveHome() || (home.inStandby() && look.liveStandby()));
        if (want) scene.start();
        else scene.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }

    // ---- HomeView.Host ----

    @Override
    public void launch(Intent intent) {
        try {
            startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "launch failed: " + intent, e);
        }
    }

    @Override
    public void openAndroidSettings() {
        launch(new Intent(Settings.ACTION_SETTINGS));
    }

    @Override
    public void commitLook() {
        look.applySchedule();
        look.save(prefs);
        home.wall = findWall(look.wall);
        home.lookChanged();
        refreshApps();
        ensureBitmaps();
        scheduleRotation();
        schedulePeriodSwitch();
        updateScene();
    }

    @Override
    public List<Tile> installedApps() {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<Tile> apps = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(query, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            Intent launch = new Intent(query).setClassName(pkg, ri.activityInfo.name);
            apps.add(new Tile(pkg, ri.loadLabel(pm).toString(), markFor(pkg, ri.loadIcon(pm)), launch));
        }
        apps.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return apps;
    }

    @Override
    public void previewBackdrop(Look candidate, int width, int height, Consumer<Bitmap> done) {
        Wallpaper w = findWall(candidate.wall);
        worker.execute(() -> {
            try {
                if (w.live()) LiveWall.ensure(this, w);
                Bitmap b = Backdrop.compose(this, candidate, w, width, height, true);
                main.post(() -> done.accept(b));
            } catch (Exception e) {
                Log.e(TAG, "preview failed for " + w.id, e);
            }
        });
    }

    @Override
    public Bitmap thumb(Wallpaper w) {
        Bitmap b = thumbs.get(w.id);
        if (b == null) {
            try {
                b = Backdrop.decode(this, w.thumb(), 1);
                thumbs.put(w.id, b);
            } catch (Exception e) {
                Log.w(TAG, "thumb missing for " + w.id, e);
            }
        }
        return b;
    }

    @Override
    public void standbyProgress(float t) {
        // Only the StandBy scene blurs: its pre-blurred copy starts as StandBy begins, hidden under
        // the sharp clip, and takes over once StandBy is fully in; leaving hands back at once.
        standbyT = t;
        scene.setStandby(home.inStandby(), home.inStandby() && t >= 1, look.liveStandby() ? look.standbyBlur : 0, liveHome());
    }

    @Override
    public void releasePanelCaches() {
        thumbs.clear(); // not recycled: the last Settings frame may still reference them
    }

    @Override
    public void standbyChanged(boolean on) {
        updateScene();
        standbyDim(on);
    }

    /**
     * StandBy's backlight. The TV blanks one frame whenever the backlight changes, so it changes
     * only while the screen is dark: dimming as StandBy starts, brightening the instant a key
     * wakes it (not once Home is back), and never re-applied when already set.
     */
    @Override
    public void standbyDim(boolean dim) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        float want = dim ? look.standbyBrightness() : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (lp.screenBrightness == want) return;
        lp.screenBrightness = want;
        getWindow().setAttributes(lp);
    }

    /** Settings.Secure "sleep_timeout": how long the TV stays on (dreaming) after the last key press. */
    @Override
    public int sleepHours() {
        int ms = Settings.Secure.getInt(getContentResolver(), "sleep_timeout", -1);
        return ms <= 0 || ms >= 24 * 3_600_000 ? 0 : Math.round(ms / 3_600_000f);
    }

    @Override
    public void setSleepHours(int hours) {
        try {
            // Needs WRITE_SECURE_SETTINGS, granted once over ADB (pm grant).
            Settings.Secure.putInt(getContentResolver(), "sleep_timeout", hours <= 0 ? -1 : hours * 3_600_000);
        } catch (SecurityException e) {
            Log.w(TAG, "sleep timer not writable; grant WRITE_SECURE_SETTINGS", e);
        }
    }

    // ---- helpers ----

    /**
     * Saves Home, as it looks now, over a full-size still of a live wallpaper to
     * files/captures/<name>.png. For README screenshots only: the video layer can't be screenshotted.
     * adb shell am start -n io.github.amitprna.minimaltv/.MainActivity --es capture home --es wall live-rain
     * Optional --es layer ui|standby renders just the interface on transparency (see HomeView).
     */
    private void capture(String name, String wallId, String layer) {
        Wallpaper w = findWall(wallId != null ? wallId : look.wall);
        worker.execute(() -> {
            try {
                if (w.live()) LiveWall.ensure(this, w);
                Bitmap still = Backdrop.decode(this, w.photo(), 1);
                java.io.File dir = getExternalFilesDir("captures");
                main.post(() -> {
                    try {
                        home.capture(still, layer, new java.io.File(dir, name + ".png"));
                    } catch (Exception e) {
                        Log.e(TAG, "capture failed", e);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "capture failed", e);
            }
        });
    }

    private void refreshApps() {
        PackageManager pm = getPackageManager();
        List<Tile> tiles = new ArrayList<>();
        for (String pkg : look.homeApps) {
            Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
            if (launch == null) continue; // saved but not installed: keep the ID, skip the tile
            try {
                CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                tiles.add(new Tile(pkg, label.toString(), markFor(pkg, pm.getApplicationIcon(pkg)), launch));
            } catch (PackageManager.NameNotFoundException ignored) {
                // Uninstalled between lookup and load.
            }
        }
        home.setApps(tiles, home.selectedApp());
    }

    /** Known brands use monochrome marks (ink bounds in a 240px box); others their own icon. */
    private Mark markFor(String pkg, Drawable fallback) {
        switch (pkg) {
            case "com.netflix.ninja":
                return Mark.brand(getDrawable(R.drawable.mark_netflix), 240, 54, 0, 132, 240, .8215f);
            case "org.videolan.vlc":
                return Mark.brand(getDrawable(R.drawable.mark_vlc), 240, 14, 0, 212, 240, .45f);
            case YOUTUBE:
                return Mark.brand(getDrawable(R.drawable.mark_youtube), 240, 0, 35, 240, 170, .8811f);
            case PRIME_VIDEO:
                return Mark.brand(getDrawable(R.drawable.mark_primevideo), 240, 0, 83, 240, 74, .2442f);
            default:
                return Mark.icon(fallback);
        }
    }

    /** Re-bakes the backdrop (and the blurred fields) off the main thread when the look needs it. */
    private void ensureBitmaps() {
        Look l = look.copy();
        Wallpaper w = findWall(l.wall);
        String key = l.backdropKey();
        if (key.equals(composedKey)) return;
        composedKey = key;
        worker.execute(() -> {
            try {
                if (w.live()) LiveWall.ensure(this, w); // poster and fields come from its stills
                Bitmap bg = Backdrop.compose(this, l, w, screen.width(), screen.height(), false);
                Bitmap field = l.photo() && !l.dock() ? Backdrop.decode(this, w.field(), 1) : null;
                Bitmap frost = l.photo() ? Backdrop.decode(this, w.frost(), 1) : null;
                // StandBy shows the photo under a veil; half size is plenty and a quarter of the memory.
                Bitmap photo = l.photo() ? Backdrop.decode(this, w.photo(), 2) : null;
                // The still that stands in until the video draws (the same half-size photo).
                Bitmap poster = w.live() ? photo != null ? photo : Backdrop.decode(this, w.photo(), 2) : null;
                main.post(() -> {
                    if (!key.equals(composedKey)) return;
                    home.setBitmaps(w, bg, field, frost, photo);
                    if (poster != null) home.setLivePoster(poster);
                });
            } catch (Exception e) {
                Log.e(TAG, "backdrop failed for " + w.id, e);
            }
        });
    }

    private void scheduleRotation() {
        main.removeCallbacks(rotate);
        if (!look.photo()) return;
        try {
            int minutes = Integer.parseInt(look.rotation);
            main.postDelayed(rotate, minutes * 60_000L);
        } catch (NumberFormatException ignored) {
            // "fixed" and "open" have no timer
        }
    }

    private void rotateWallpaper() {
        if (home.hasPanels()) {
            scheduleRotation(); // never swap the photo under an open panel
            return;
        }
        advanceWallpaper();
        commitLook();
    }

    /** Rotation moves the current period's wallpaper (day or night) to the next one. */
    private void advanceWallpaper() {
        Wallpaper w = findWall(look.wall);
        String next = walls.get((walls.indexOf(w) + 1) % walls.size()).id;
        if (look.isDay()) look.dayWall = next;
        else look.nightWall = next;
        look.applySchedule();
    }

    /** Re-arms the timer for the next day/night boundary. */
    private void schedulePeriodSwitch() {
        main.removeCallbacks(periodSwitch);
        main.postDelayed(periodSwitch, look.msToNextSwitch() + 1000);
    }

    private void onPeriodSwitch() {
        if (look.applySchedule()) commitLook(); // commitLook re-arms the timer
        else schedulePeriodSwitch();
    }

    private Wallpaper findWall(String id) {
        for (Wallpaper w : walls) if (w.id.equals(id)) return w;
        return walls.get(0);
    }
}
