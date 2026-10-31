package io.github.amitprna.minimaltv;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

/**
 * An app's icon inside a fixed mark box. Brand marks use CleanFrame's optical sizing
 * (ink bounds measured in a 240px viewport); installed icons are fitted inside the box.
 */
final class Mark {
    private final Drawable drawable;
    private final boolean tinted;
    // n, x, y, w, h, fill — visible ink measured offline, as in CleanFrame's metrics.js
    private final float[] metrics;

    private Mark(Drawable drawable, boolean tinted, float[] metrics) {
        this.drawable = drawable;
        this.tinted = tinted;
        this.metrics = metrics;
    }

    static Mark brand(Drawable d, float... metrics) { return new Mark(d.mutate(), true, metrics); }

    static Mark icon(Drawable d) { return new Mark(d.mutate(), false, null); }

    /** Draws centred in the box at (left, top); size is CleanFrame's optical target. */
    void draw(Canvas c, float left, float top, float boxW, float boxH, float size, int ink, int alpha) {
        float drawW, drawH, x, y;
        if (metrics != null) {
            float n = metrics[0], mx = metrics[1], my = metrics[2], mw = metrics[3], mh = metrics[4];
            float weight = clamp((float) Math.pow(0.45 / metrics[5], 0.3), .84f, 1.25f);
            float k = Math.min(Math.min(boxW / mw, boxH / mh), size * weight / (float) Math.sqrt(mw * mh));
            drawW = drawH = n * k;
            x = left + boxW / 2 - (mx + mw / 2) * k;
            y = top + boxH / 2 - (my + mh / 2) * k;
        } else {
            float side = Math.min(boxW, boxH);
            drawW = drawH = side;
            x = left + (boxW - side) / 2;
            y = top + (boxH - side) / 2;
        }
        drawable.setBounds(Math.round(x), Math.round(y), Math.round(x + drawW), Math.round(y + drawH));
        if (tinted) drawable.setTint(ink);
        drawable.setAlpha(alpha);
        drawable.draw(c);
    }

    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
}
