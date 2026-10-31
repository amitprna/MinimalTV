package io.github.amitprna.minimaltv;

import android.content.Intent;

/** One launchable app as Home, All apps and the editor show it. */
final class Tile {
    final String pkg;
    final String label;
    final Mark mark;
    final Intent intent;

    Tile(String pkg, String label, Mark mark, Intent intent) {
        this.pkg = pkg;
        this.label = label;
        this.mark = mark;
        this.intent = intent;
    }
}
