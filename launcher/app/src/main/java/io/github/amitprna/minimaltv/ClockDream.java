package io.github.amitprna.minimaltv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.util.List;

/**
 * The system screensaver: the same StandBy clock Home shows, so when the TV's idle timer
 * fires over Home the picture does not change, and elsewhere the TV still gets the clock.
 */
public class ClockDream extends DreamService {

    private LiveScene scene;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        ClockView view = new ClockView(this);
        float bright = view.look.standbyBrightness();
        if (bright >= 0) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = bright;
            getWindow().setAttributes(lp);
        }
        FrameLayout root = new FrameLayout(this);
        if (view.look.liveStandby() && view.liveWall != null) {
            scene = new LiveScene(this);
            view.onScene = scene::setScene; // day turns to night (or back) while dreaming
            scene.setScene(view.liveWall);
            scene.setStandby(false, true, view.look.standbyBlur, false); // the pre-blurred copy, straight away
            scene.setVolume(view.look.sceneVolume());
            root.addView(scene.view); // beneath the clock's dark veil
        }
        root.addView(view);
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (scene != null) scene.start();
    }

    @Override
    public void onDreamingStopped() {
        super.onDreamingStopped();
        if (scene != null) scene.stop();
    }

    private static final class ClockView extends View {
        final Look look;
        Wallpaper liveWall; // the scene for the current time of day (null if none is on the TV)
        java.util.function.Consumer<Wallpaper> onScene = w -> {};
        private final List<Wallpaper> walls;
        private final Standby standby;
        private final Runnable minuteTick = this::onMinute;
        private Bitmap photo;

        ClockView(Context context) {
            super(context);
            Fonts fonts = new Fonts(context.getAssets());
            walls = Wallpaper.load(context);
            look = Look.load(context.getSharedPreferences("home", MODE_PRIVATE), walls.get(0).id);
            look.applySchedule();
            for (Wallpaper w : walls) if (w.live() && (liveWall == null || w.id.equals(look.wall))) liveWall = w;
            standby = new Standby(context, fonts, look.font, look.clock24);
            if (look.photoStandby()) {
                for (Wallpaper w : walls) {
                    if (!w.id.equals(look.wall)) continue;
                    try {
                        // Half size is plenty under the veil; the frost field when blurred.
                        photo = Backdrop.decode(context, look.standbyBlur > 0 ? w.frost() : w.photo(), look.standbyBlur > 0 ? 1 : 2);
                    } catch (Exception e) {
                        Log.w("TvHome", "dream backdrop missing", e);
                    }
                }
            }
        }

        private final Runnable conditionsChanged = this::invalidate;

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            post(minuteTick);
            Conditions.get(getContext()).listen(conditionsChanged);
            Conditions.get(getContext()).start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            removeCallbacks(minuteTick);
            Conditions.get(getContext()).unlisten(conditionsChanged);
            Conditions.get(getContext()).stop();
        }

        /** Redraw at each minute boundary (the cards flip once a minute) and follow the day/night switch. */
        private void onMinute() {
            invalidate();
            if (look.applySchedule()) {
                for (Wallpaper w : walls) if (w.live() && w.id.equals(look.wall)) {
                    liveWall = w;
                    onScene.accept(w);
                }
            }
            long now = System.currentTimeMillis();
            postDelayed(minuteTick, 60_000 - now % 60_000 + 20);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth() / Gfx.STAGE_W;
            c.save();
            c.scale(s, s);
            if (look.liveStandby() && liveWall != null) {
                // Video plays beneath; keep only standbyPhoto% of it visible.
                c.drawColor(Gfx.ink(4, 4, 6, 1 - look.standbyPhoto / 100f));
            } else {
                standby.drawBackground(c, photo, look.standbyPhoto / 100f, 1);
            }
            if (standby.draw(c, 1)) postInvalidateOnAnimation(); // a card is flipping
            c.restore();
        }
    }
}
