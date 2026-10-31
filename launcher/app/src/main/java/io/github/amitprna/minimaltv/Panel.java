package io.github.amitprna.minimaltv;

import android.graphics.Canvas;
import android.view.KeyEvent;

/** A full-screen sheet over Home (Settings, All apps, the editor). Draws in stage pixels. */
abstract class Panel {
    final HomeView home;

    Panel(HomeView home) { this.home = home; }

    abstract void draw(Canvas c);

    /** @return true when handled; Back is handled by HomeView unless consumed here. */
    abstract boolean key(int keyCode, boolean ok, KeyEvent event);

    void onClose() {}

    /** Re-enter after a child panel closes (refresh committed values). */
    void onResume() {}
}
