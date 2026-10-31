package io.github.amitprna.minimaltv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Home, its panels and the StandBy clock on one canvas. Everything is laid out in
 * CleanFrame's 1920 x 1080 stage pixels; onDraw scales once to the real screen.
 */
final class HomeView extends View {

    interface Host {
        void launch(Intent intent);
        void openAndroidSettings();
        /** Look changed: persist it and refresh whatever depends on it. */
        void commitLook();
        List<Tile> installedApps();
        /** Async backdrop at preview size for a candidate look (Settings preview). */
        void previewBackdrop(Look candidate, int width, int height, java.util.function.Consumer<Bitmap> done);
        Bitmap thumb(Wallpaper w);
        /** StandBy shown or hidden (window brightness follows). */
        void standbyChanged(boolean on);
        /** StandBy's backlight dimming on or off. */
        void standbyDim(boolean dim);
        /** Eased StandBy background progress 0..1, for effects that follow the transition. */
        void standbyProgress(float t);
        /** TV sleep timer in hours (0 = never), read from and written to system settings. */
        int sleepHours();
        void setSleepHours(int hours);
        /** Panels closed: drop thumbnails and previews they loaded. */
        void releasePanelCaches();
    }

    final Host host;
    final Fonts fonts;
    final Look look;
    final List<Wallpaper> walls;
    final HomeRenderer renderer;
    private Standby standby;
    private final PathInterpolator ease = new PathInterpolator(.2f, .7f, .2f, 1f);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final HomeRenderer.Focus focus = new HomeRenderer.Focus();
    private final ValueAnimator focusAnim = ValueAnimator.ofFloat(0, 1);
    private final Deque<Panel> panels = new ArrayDeque<>();

    Wallpaper wall;
    private List<Tile> apps = List.of();
    private Bitmap backdrop, field, frost, photo, livePoster;
    private float posterAlpha = 1;
    private ValueAnimator posterAnim;
    private float scale = 1;

    // StandBy
    private float standbyT;          // 0 = Home, 1 = StandBy
    private boolean inStandby;
    private ValueAnimator standbyAnim;
    private final Runnable idle = this::enterStandby;
    private final Runnable minuteTick = this::onMinute;
    private float coverFade;          // extra darkening while the video switches clips
    private static final float DIP = .55f;
    private ValueAnimator coverAnim;
    private TextPaint morphPaint;

    HomeView(Context context, Host host, Fonts fonts, Look look, List<Wallpaper> walls, Wallpaper wall) {
        super(context);
        this.host = host;
        this.fonts = fonts;
        this.look = look;
        this.walls = walls;
        this.wall = wall;
        renderer = new HomeRenderer(context, fonts);
        renderer.setLook(look, wall);
        standby = new Standby(context, fonts, look.font, look.clock24);
        setFocusable(true);
        setFocusableInTouchMode(true);

        focusAnim.setDuration(180);
        focusAnim.addUpdateListener(a -> {
            focus.e = ease.getInterpolation((float) a.getAnimatedValue());
            invalidate();
        });
    }

    // ---- state from the activity ----

    void setApps(List<Tile> tiles, int selected) {
        apps = tiles;
        int i = Math.max(0, Math.min(selected, tiles.size() - 1));
        focus.app = focus.fromApp = i;
        if (tiles.isEmpty()) focus.zone = focus.fromZone = HomeRenderer.ZONE_CONTROLS;
        invalidate();
    }

    List<Tile> apps() { return apps; }

    int selectedApp() { return focus.app; }

    /** New look/wallpaper bitmaps. Old bitmaps are dropped, not recycled: a frame may still hold them. */
    void setBitmaps(Wallpaper w, Bitmap backdrop, Bitmap field, Bitmap frost, Bitmap photo) {
        this.wall = w;
        this.photo = photo;
        this.backdrop = backdrop;
        this.field = field;
        this.frost = frost;
        renderer.setLook(look, w);
        invalidate();
    }

    Bitmap frost() { return frost; }

    Bitmap backdrop() { return backdrop; }

    /** Half-size still of the live wallpaper; stands in until (and wherever) video is not showing. */
    void setLivePoster(Bitmap poster) {
        livePoster = poster;
        invalidate();
    }

    Bitmap livePoster() { return livePoster; }

    /** The still under Home's translucent backdrop when the wallpaper is live. */
    private Bitmap homePoster() { return look.photo() && wall.live() ? livePoster : null; }

    /** The video beneath has started (or stopped) drawing frames; hand over from the poster. */
    void videoShowing(boolean showing) {
        if (posterAnim != null) posterAnim.cancel();
        if (!showing) {
            posterAlpha = 1;
            invalidate();
            return;
        }
        posterAnim = ValueAnimator.ofFloat(posterAlpha, 0);
        posterAnim.setDuration(700);
        posterAnim.addUpdateListener(a -> {
            posterAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        posterAnim.start();
    }

    void lookChanged() {
        renderer.setLook(look, wall);
        standby = new Standby(getContext(), fonts, look.font, look.clock24); // StandBy follows the Home font and clock
        morphPaint = null;
        invalidate();
    }

    // ---- panels ----

    void push(Panel p) {
        panels.push(p);
        cancelIdle();
        invalidate();
    }

    void pop() {
        Panel p = panels.poll();
        if (p != null) p.onClose();
        Panel top = panels.peek();
        if (top != null) top.onResume();
        else {
            host.releasePanelCaches();
            scheduleIdle();
        }
        invalidate();
    }

    void closeAllPanels() {
        if (panels.isEmpty()) return;
        while (!panels.isEmpty()) panels.pop().onClose();
        host.releasePanelCaches();
        invalidate();
    }

    boolean hasPanels() { return !panels.isEmpty(); }

    // ---- drawing ----

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        scale = w / Gfx.STAGE_W;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.save();
        c.scale(scale, scale);
        Panel top = panels.peek();
        if (top != null) {
            top.draw(c);
        } else {
            drawHome(c);
        }
        c.restore();
    }

    /**
     * Renders a 1920 x 1080 PNG (see MainActivity#capture). layer "home": Home over the still;
     * "ui": Home's interface alone on transparency; "standby": StandBy's veil and clock alone, to
     * lay over the video for demo clips.
     */
    void capture(Bitmap still, String layer, java.io.File out) throws java.io.IOException {
        Bitmap b = Bitmap.createBitmap(Math.round(Gfx.STAGE_W), Math.round(Gfx.STAGE_H), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        if ("standby".equals(layer)) {
            c.drawColor(Gfx.ink(4, 4, 6, 1 - look.standbyPhoto / 100f));
            standby.draw(c, 1);
        } else {
            drawHomeScene(c, backdrop, renderer, focus, 0, "ui".equals(layer) ? null : still, 1);
        }
        try (java.io.FileOutputStream o = new java.io.FileOutputStream(out)) {
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
        }
    }

    /** The full Home scene into stage coordinates (also used by Settings' preview). */
    void drawHomeScene(Canvas c, Bitmap bg, HomeRenderer r, HomeRenderer.Focus f, float exit,
                       Bitmap poster, float posterOpacity) {
        RectF stage = new RectF(0, 0, Gfx.STAGE_W, Gfx.STAGE_H);
        if (poster != null && posterOpacity > 0) {
            // Live wallpaper: the backdrop is translucent over the video, or over this still.
            bitmapPaint.setAlpha(Math.round(255 * posterOpacity));
            c.drawBitmap(poster, null, stage, bitmapPaint);
            bitmapPaint.setAlpha(255);
        }
        if (bg != null) c.drawBitmap(bg, null, stage, bitmapPaint);
        else if (!r.ink().photo) c.drawColor(r.ink().ground); // a photo look may bake nothing over its scene
        r.draw(c, apps, f, field, exit);
    }

    private void drawHome(Canvas c) {
        float t = standbyT;
        float exit = Gfx.span(t, 0, .55f);
        float bg = ease.getInterpolation(Gfx.span(t, 0, .6f));
        if (t > 0 && look.liveStandby()) {
            // The video plays in a layer beneath this view: Home dissolves to reveal it, then a
            // dark veil keeps only standbyPhoto% of it visible. While the layer switches between
            // the sharp and the blurred clip (video layers can't fade), the scene dips a little
            // darker through a translucent veil, so the switch reads as part of the dimming.
            // Arriving, the dip only rises over the last stretch.
            float cover = coverFade * (inStandby ? Gfx.span(bg, .55f, 1) : 1);
            if (cover > 0) c.drawColor(Gfx.ink(4, 4, 6, DIP * cover));
            if (bg < 1) {
                int layer = c.saveLayerAlpha(0, 0, Gfx.STAGE_W, Gfx.STAGE_H, Math.round(255 * (1 - bg)));
                drawHomeScene(c, backdrop, renderer, focus, exit, homePoster(), posterAlpha);
                c.restoreToCount(layer);
            }
            c.drawColor(Gfx.ink(4, 4, 6, (1 - look.standbyPhoto / 100f) * bg));
        } else {
            drawHomeScene(c, backdrop, renderer, focus, exit, homePoster(), posterAlpha);
            if (t <= 0) return;
            // Blurred StandBy uses the pre-blurred frost field instead of the sharp photo.
            Bitmap scene = look.photoStandby() ? (look.standbyBlur > 0 && frost != null ? frost : photo) : null;
            standby.drawBackground(c, scene, look.standbyPhoto / 100f, bg);
        }
        drawClockMorph(c, t);
        if (standby.draw(c, Gfx.span(t, .12f, 1))) postInvalidateOnAnimation(); // a card is flipping
    }

    /** The digital clock lifts off Home and grows into the flip panel as it dissolves. */
    private void drawClockMorph(Canvas c, float t) {
        float m = ease.getInterpolation(Gfx.span(t, 0, .5f));
        float a = 1 - Gfx.span(t, .15f, .5f);
        if (a <= 0) return;
        if (morphPaint == null) {
            morphPaint = Gfx.text(fonts.get(look.font, Fonts.Role.CLOCK), 48, -1.5f, Standby.GREY);
            morphPaint.setTextAlign(Paint.Align.CENTER);
        }
        RectF from = renderer.clockBounds();
        float x = Gfx.lerp(from.centerX(), Standby.PANEL.centerX(), m), y = Gfx.lerp(from.centerY(), Standby.PANEL.centerY(), m);
        float sc = Gfx.lerp(renderer.clockSize() / 48, Standby.DIGIT_PX / 48, m);
        c.save();
        c.scale(sc, sc, x, y);
        morphPaint.setColor(Gfx.withAlpha(Standby.GREY, a));
        c.drawText(renderer.clockText(), x, Gfx.baseline(morphPaint, y - 32, 64), morphPaint);
        c.restore();
    }

    // ---- StandBy ----

    private final Runnable conditionsChanged = this::invalidate;

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Conditions.get(getContext()).listen(conditionsChanged);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        Conditions.get(getContext()).unlisten(conditionsChanged);
    }

    void scheduleIdle() {
        removeCallbacks(idle);
        if (look.standby > 0 && panels.isEmpty() && !inStandby) postDelayed(idle, look.standby * 60_000L);
    }

    void cancelIdle() { removeCallbacks(idle); }

    void enterStandby() {
        if (inStandby || !panels.isEmpty()) return;
        inStandby = true;
        raiseCover();
        Conditions.get(getContext()).start(); // forecast and room reading stay fresh while shown
        host.standbyChanged(true);
        animateStandby(1, 1400);
        post(minuteTick);
    }

    void exitStandby() {
        if (!inStandby) return;
        inStandby = false;
        host.standbyDim(false); // brighten now, while the screen is still dark StandBy
        raiseCover();
        Conditions.get(getContext()).stop();
        removeCallbacks(minuteTick);
        animateStandby(0, 520);
        scheduleIdle();
    }

    boolean inStandby() { return inStandby; }

    /** A clip switch is coming: deepen the veil over the video (only a blurred StandBy switches). */
    private void raiseCover() {
        if (coverAnim != null) coverAnim.cancel();
        coverFade = look.liveStandby() && look.standbyBlur > 0 ? 1 : 0;
    }

    /** The video has switched clips: lift the extra darkening. */
    void sceneSettled() {
        if (coverFade <= 0) return;
        if (coverAnim != null) coverAnim.cancel();
        coverAnim = ValueAnimator.ofFloat(coverFade, 0);
        coverAnim.setDuration(450);
        coverAnim.addUpdateListener(a -> {
            coverFade = (float) a.getAnimatedValue();
            invalidate();
        });
        coverAnim.start();
    }

    private void animateStandby(float to, long ms) {
        if (standbyAnim != null) standbyAnim.cancel();
        standbyAnim = ValueAnimator.ofFloat(standbyT, to);
        standbyAnim.setDuration(Math.round(ms * Math.abs(to - standbyT)) + 1);
        standbyAnim.setInterpolator(null); // choreography eases per element
        standbyAnim.addUpdateListener(a -> {
            standbyT = (float) a.getAnimatedValue();
            host.standbyProgress(ease.getInterpolation(Gfx.span(standbyT, 0, .6f)));
            invalidate();
        });
        if (to == 0) {
            // Only once Home is fully back: stop the video and restore brightness.
            standbyAnim.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(android.animation.Animator a) { cancelled = true; }

                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (!cancelled && !inStandby) host.standbyChanged(false);
                }
            });
        }
        standbyAnim.start();
    }

    /** Redraw at each minute boundary: the cards flip (and the layout drifts) once a minute. */
    private void onMinute() {
        invalidate();
        long now = System.currentTimeMillis();
        postDelayed(minuteTick, 60_000 - now % 60_000 + 20);
    }

    // ---- input ----

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
        // Held OK and Back act once until released.
        if ((ok || keyCode == KeyEvent.KEYCODE_BACK) && event.getRepeatCount() > 0) return true;
        if (!isNavigationKey(keyCode, ok)) return super.onKeyDown(keyCode, event);

        if (inStandby) {
            exitStandby(); // any key wakes Home; it is not also acted on
            return true;
        }
        Panel top = panels.peek();
        if (top != null) {
            if (!top.key(keyCode, ok, event) && keyCode == KeyEvent.KEYCODE_BACK) pop();
            invalidate();
            return true;
        }
        scheduleIdle();
        return homeKey(keyCode, ok);
    }

    private static boolean isNavigationKey(int keyCode, boolean ok) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return ok;
        }
    }

    private boolean homeKey(int keyCode, boolean ok) {
        boolean dock = look.dock();
        int n = apps.size();
        boolean onApps = focus.zone == HomeRenderer.ZONE_APPS;
        // Dock: apps move left/right; the rail on the right is reached with Up or past the last app.
        // List: apps move up/down. Controls stack vertically on the right in both.
        int next = dock ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_DOWN;
        int prev = dock ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_UP;
        int toControls = dock ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_RIGHT;
        int toApps = KeyEvent.KEYCODE_DPAD_LEFT;
        int ctrlNext = KeyEvent.KEYCODE_DPAD_DOWN;
        int ctrlPrev = KeyEvent.KEYCODE_DPAD_UP;

        if (ok) {
            if (onApps && n > 0) host.launch(apps.get(focus.app).intent);
            else if (!onApps) control(focus.control);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!onApps && n > 0) move(HomeRenderer.ZONE_APPS, focus.app, focus.control);
            return true; // Home has nowhere further back to go
        }
        if (onApps) {
            if (keyCode == next && focus.app < n - 1) move(focus.zone, focus.app + 1, focus.control);
            else if (keyCode == prev && focus.app > 0) move(focus.zone, focus.app - 1, focus.control);
            else if (keyCode == toControls || (dock && keyCode == next)) move(HomeRenderer.ZONE_CONTROLS, focus.app, focus.control);
        } else {
            if (keyCode == ctrlNext && focus.control < 2) move(focus.zone, focus.app, focus.control + 1);
            else if (keyCode == ctrlPrev && focus.control > 0) move(focus.zone, focus.app, focus.control - 1);
            else if (keyCode == toApps && n > 0) move(HomeRenderer.ZONE_APPS, focus.app, focus.control);
        }
        return true;
    }

    private void control(int i) {
        switch (i) {
            case 0: enterStandby(); break;
            case 1: push(new AllAppsPanel(this)); break;
            default: push(new SettingsPanel(this));
        }
    }

    private void move(int zone, int app, int control) {
        focus.set(zone, app, control);
        focusAnim.cancel();
        focusAnim.start();
    }

    void drawSheetBackground(Canvas c) {
        if (look.photo() && frost != null) {
            c.drawBitmap(frost, null, new RectF(0, 0, Gfx.STAGE_W, Gfx.STAGE_H), bitmapPaint);
            c.drawColor(Gfx.ink(8, 12, 14, .42f)); // .sheet-veil
        } else {
            c.drawColor(renderer.ink().ground);
        }
    }
}
