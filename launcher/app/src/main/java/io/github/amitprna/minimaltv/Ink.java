package io.github.amitprna.minimaltv;

import android.graphics.Color;

/** Colours for one look: per-photo offline inks, or CleanFrame's flat Dark/Light palettes. */
final class Ink {
    static final int PAPER = 0xFFF6F1E7;
    static final int CHARCOAL = 0xFF1D1A16;

    final boolean photo;
    final int ground;          // flat background colour
    final int app;             // app names and marks
    final int clock;
    final float clockAlpha;
    final boolean shadows;     // text shadows only over photographs
    final int control;         // Home controls (top-right on Dock, bottom-right on List)
    final float controlRest, controlPeer;
    final int controlHalo;     // 0 = no underlay
    final int plateFlat;       // focus plate on flat backgrounds
    final int plateEdge;       // hairline on flat dark plates (0 = none)
    final float typeRest;      // Type focus: unfocused app opacity

    private Ink(Look look, Wallpaper w) {
        photo = look.photo();
        boolean light = "light".equals(look.bg);
        if (photo) {
            ground = 0xFF0D0D0D;
            app = w.appInk;
            boolean list = !look.dock();
            clock = list ? w.utilInk : w.clockInk;
            clockAlpha = 1;
            shadows = true;
            control = list ? w.listInk : w.utilInk;
            controlRest = list ? w.listRest : w.utilRest;
            controlPeer = list ? w.listPeer : w.utilPeer;
            controlHalo = list ? w.listHalo : w.utilHalo;
            plateFlat = 0;
            plateEdge = 0;
            typeRest = .84f;
        } else {
            ground = light ? 0xFFF2EFE8 : 0xFF0D0D0D;
            app = light ? CHARCOAL : PAPER;
            clock = app;
            clockAlpha = .72f;
            shadows = false;
            control = app;
            controlRest = .62f;
            controlPeer = light ? .52f : .434f;
            controlHalo = 0;
            plateFlat = light ? Color.argb(18, 29, 26, 22) : Color.argb(20, 246, 241, 231);
            plateEdge = light ? 0 : Color.argb(26, 255, 250, 240);
            typeRest = .62f;
        }
    }

    static Ink of(Look look, Wallpaper w) { return new Ink(look, w); }

    /** Muted secondary text (settings values, hints). */
    int mute() { return Gfx.withAlpha(app, app == CHARCOAL ? .70f : .76f); }
}
