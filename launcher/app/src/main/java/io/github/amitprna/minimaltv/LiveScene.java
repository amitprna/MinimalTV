package io.github.amitprna.minimaltv;

import android.content.Context;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;

import java.util.Objects;

/**
 * A looping video, with an optional ambience track, behind Home or StandBy. Decoders and audio
 * only run while the scene is wanted: {@link #start()} when it shows, {@link #stop()} once gone.
 *
 * Video plays in SurfaceViews, so frames go straight to the TV's video layer and the app draws
 * nothing per frame. A SurfaceView can't be blurred, so StandBy's blur comes from small
 * pre-blurred copies of each clip (name-blur30.mp4, name-blur60.mp4, same length and loop),
 * played in a second SurfaceView above the sharp one.
 *
 * Video layers behind a window can only be shown or hidden, not faded, and on some TVs a layer
 * moved or paused stops updating (paused, its decoder is reclaimed after ~30 s). So the sharp
 * clip never moves or pauses while Home has it: it keeps playing full-screen underneath. As
 * StandBy begins, the blurred copy is prepared (held, not yet drawing); once StandBy is in it
 * starts, in step with the sharp clip, and covers it with its first frame. Leaving StandBy just
 * hides the blurred layer, revealing the sharp clip already running. {@link #onSettled} reports
 * each switch landing; Home deepens its veil over the moment.
 */
final class LiveScene {
    /** Add this to the layout (full screen), beneath everything else. */
    final FrameLayout view;
    private final Context context;
    private final Player sharp, soft;
    private String video; // scene files (see LiveWall); null until a scene is set
    private boolean wanted;
    private boolean standby;       // StandBy's clip (blurred if it has one) rather than Home's
    private boolean warm;          // StandBy is arriving: prepare the blurred clip, held
    private int blurPercent;       // StandBy blur: 0, 30 or 60
    private Player target;         // the player that should end up showing
    private boolean homeLive = true;
    // Ambience track looped alongside the video (the clips themselves are silent).
    private String soundFile;
    private MediaPlayer sound;
    private float volume;
    private android.animation.ValueAnimator soundFade;
    private java.util.function.Consumer<Boolean> showing = s -> {};
    private Runnable settled = () -> {};

    /** Told true when the sharp video starts drawing, false when it stops (Home's poster covers the gap). */
    void onShowing(java.util.function.Consumer<Boolean> listener) { showing = listener; }

    /** Told when a switch between Home's and StandBy's clip has finished (the new one is drawing). */
    void onSettled(Runnable listener) { settled = listener; }

    LiveScene(Context context) {
        this.context = context;
        view = new FrameLayout(context);
        sharp = new Player(false);
        soft = new Player(true);
        FrameLayout.LayoutParams full = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        view.addView(sharp.surface, full);
        // The blurred clip stops 2px short of the bottom: a sliver of the sharp clip stays on screen
        // (under StandBy's veil), so the TV keeps its video plane live and leaving StandBy is instant.
        // Fully covered, the plane is switched off and turning it back on shows a black frame.
        FrameLayout.LayoutParams short2 = new FrameLayout.LayoutParams(full);
        short2.bottomMargin = 2;
        view.addView(soft.surface, short2);
        sharp.surface.setVisibility(android.view.View.INVISIBLE);
        soft.surface.setVisibility(android.view.View.INVISIBLE);
    }

    /** Plays this live wallpaper's video and ambience; a different scene restarts on the new clips. */
    void setScene(Wallpaper w) {
        if (w == null || !w.live() || (w.video.equals(video) && Objects.equals(w.sound, soundFile))) return;
        video = w.video;
        boolean soundChanged = !Objects.equals(w.sound, soundFile);
        soundFile = w.sound;
        if (soundChanged) releaseSound();
        apply();
    }

    void start() {
        wanted = true;
        apply();
    }

    void stop() {
        wanted = false;
        apply();
    }

    /**
     * Which clip should show: StandBy's (with its blur 0, 30 or 60) or Home's, and whether Home
     * itself shows the video. {@code arriving} prepares StandBy's blurred clip ahead of time.
     */
    void setStandby(boolean arriving, boolean standby, int blur, boolean homeLive) {
        warm = arriving;
        this.standby = standby;
        blurPercent = blur;
        this.homeLive = homeLive;
        apply();
    }

    private String softFile() {
        if (video == null || blurPercent <= 0) return null;
        String f = video.replace(".mp4", blurPercent >= 60 ? "-blur60.mp4" : "-blur30.mp4");
        return LiveWall.available(context, f) ? f : null;
    }

    /** Starts and stops the two players (and the ambience) to match the wanted state. */
    private void apply() {
        String softFile = softFile();
        Player before = target;
        boolean softOn = wanted && softFile != null && (standby || warm);
        // Home's sharp clip keeps running through StandBy (covered), so leaving StandBy is instant.
        boolean sharpOn = wanted && video != null && (homeLive || softFile == null && (standby || warm));
        target = !wanted || video == null ? null : standby && softFile != null ? soft : sharpOn ? sharp : null;
        sharp.play(sharpOn ? video : null, false);
        soft.play(softOn ? softFile : null, !standby); // arriving: prepared, held until StandBy is in
        if (target != null && target != before && target.rendering) settled.run();
        if (wanted && video != null) openSound();
        else releaseSound();
    }

    /** One video in its own SurfaceView. */
    private final class Player implements SurfaceHolder.Callback {
        final SurfaceView surface;
        private MediaPlayer mp;
        private String file, playing;
        private boolean ready, rendering, prepared, started, hold;

        Player(boolean overlay) {
            surface = new SurfaceView(context);
            // The blurred clip sits above the sharp one; both stay beneath the window.
            if (overlay) surface.setZOrderMediaOverlay(true);
            surface.getHolder().addCallback(this);
        }

        /**
         * Plays this file (null stops and hides the layer, so no stale frame stays on screen).
         * {@code hold} prepares it without starting; a later call without hold starts it.
         */
        void play(String f, boolean hold) {
            file = f;
            this.hold = hold;
            if (mp != null && !Objects.equals(f, playing)) release();
            surface.setVisibility(f == null ? android.view.View.INVISIBLE : android.view.View.VISIBLE);
            if (f == null) return;
            if (mp == null) {
                if (ready) open();
            } else if (prepared && !started && !hold) {
                begin();
            }
        }

        /** Starts playback, joining the other clip at the same moment (both share one timeline). */
        private void begin() {
            started = true;
            Player other = this == sharp ? soft : sharp;
            if (other.mp != null && other.rendering) mp.seekTo(other.mp.getCurrentPosition() + 80, MediaPlayer.SEEK_CLOSEST);
            mp.start();
        }

        private void open() {
            try {
                MediaPlayer p = new MediaPlayer();
                LiveWall.setDataSource(context, p, file);
                p.setDisplay(surface.getHolder());
                p.setLooping(true);
                p.setVolume(0, 0);
                p.setOnPreparedListener(x -> {
                    prepared = true;
                    if (!hold) begin();
                });
                p.setOnInfoListener((x, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        rendering = true;
                        if (this == sharp) showing.accept(true);
                        if (this == target) settled.run();
                    }
                    return false;
                });
                p.setOnErrorListener((x, what, extra) -> {
                    Log.w("TvHome", "live scene error " + what + "/" + extra + " in " + playing);
                    release();
                    return true;
                });
                p.prepareAsync();
                mp = p;
                playing = file;
            } catch (Exception e) {
                Log.w("TvHome", "live scene unavailable: " + file, e);
            }
        }

        private void release() {
            if (mp == null) return;
            mp.release();
            mp = null;
            playing = null;
            prepared = started = false;
            if (rendering && this == sharp) showing.accept(false);
            rendering = false;
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            ready = true;
            if (file != null && mp == null) open();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            ready = false;
            release();
        }
    }

    // ---- ambience ----

    /**
     * Ambience volume, 0..1 relative to the TV's own volume. 0 releases the audio player;
     * raising it while the scene plays starts the sound with a gentle fade.
     */
    void setVolume(float v) {
        volume = v;
        if (v <= 0) {
            releaseSound();
        } else if (sound != null) {
            if (soundFade != null) soundFade.cancel();
            sound.setVolume(v, v);
        } else if (wanted && video != null) {
            openSound();
        }
    }

    private void openSound() {
        if (soundFile == null || volume <= 0 || sound != null) return;
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
            LiveWall.setDataSource(context, mp, soundFile);
            mp.setLooping(true);
            mp.setVolume(0, 0);
            mp.setOnPreparedListener(p -> {
                p.start();
                // The ambience eases in over 2 s instead of starting abruptly.
                soundFade = android.animation.ValueAnimator.ofFloat(0, volume);
                soundFade.setDuration(2000);
                soundFade.addUpdateListener(a -> {
                    if (sound == p) {
                        float v = (float) a.getAnimatedValue();
                        p.setVolume(v, v);
                    }
                });
                soundFade.start();
            });
            mp.setOnErrorListener((p, what, extra) -> {
                Log.w("TvHome", "ambience error " + what + "/" + extra);
                releaseSound();
                return true;
            });
            mp.prepareAsync();
            sound = mp;
        } catch (Exception e) {
            Log.w("TvHome", "ambience unavailable: " + soundFile, e);
        }
    }

    private void releaseSound() {
        if (soundFade != null) soundFade.cancel();
        soundFade = null;
        if (sound != null) {
            sound.release();
            sound = null;
        }
    }
}
