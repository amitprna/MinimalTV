package io.github.amitprna.minimaltv;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;

/**
 * Drawing helpers shared by Home, panels and StandBy. All geometry is in CleanFrame stage
 * pixels (1920 x 1080); callers scale the canvas once, so nothing here multiplies by a factor.
 */
final class Gfx {
    private Gfx() {}

    static final float STAGE_W = 1920, STAGE_H = 1080;

    /** Text paint sized in stage px with CSS letter-spacing in px. */
    static TextPaint text(Typeface face, float px, float trackingPx, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(face);
        p.setTextSize(px);
        p.setLetterSpacing(trackingPx / px);
        p.setColor(color);
        return p;
    }

    /** Baseline for text vertically centred in a CSS line box. */
    static float baseline(Paint p, float top, float lineHeight) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return top + (lineHeight - (fm.descent - fm.ascent)) / 2 - fm.ascent;
    }

    static void drawText(Canvas c, CharSequence s, float x, float top, float lineHeight, float maxWidth, TextPaint p) {
        CharSequence t = maxWidth > 0 ? TextUtils.ellipsize(s, p, maxWidth, TextUtils.TruncateAt.END) : s;
        c.drawText(t, 0, t.length(), x, baseline(p, top, lineHeight), p);
    }

    /** CleanFrame text-shadow "0 1px 2px rgba(14,10,6,.40)" (approximate single layer). */
    static void photoShadow(Paint p, float alpha) {
        p.setShadowLayer(3, 0, 1.5f, Color.argb(Math.round(110 * alpha), 14, 10, 6));
    }

    /** Glass focus plate: 170deg white gradient and a top hairline. */
    static void glassPlate(Canvas c, Paint p, RectF r, float radius, float opacity, float[] stops3, float mid) {
        double angle = Math.toRadians(170);
        float dx = (float) Math.sin(angle), dy = (float) -Math.cos(angle);
        float len = Math.abs(r.width() * dx) + Math.abs(r.height() * dy);
        float cx = r.centerX(), cy = r.centerY();
        p.setColor(Color.WHITE); // the paint's own alpha would scale the shader
        p.setShader(new LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2,
                new int[] {white(stops3[0] * opacity), white(stops3[1] * opacity), white(stops3[2] * opacity)},
                new float[] {0, mid, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, p);
        p.setShader(null);
        hairline(c, p, r, radius, white(.34f * opacity));
    }

    static final float[] PLATE = {.30f, .16f, .22f};
    static final float[] PLATE_QUIET = {.30f, .10f, .08f};
    static final float[] PLATE_FROST = {.26f, .08f, .10f};
    static final float[] PLATE_LIFT = {.44f, .27f, .34f};

    static void flatPlate(Canvas c, Paint p, RectF r, float radius, int color, int edge, float opacity) {
        p.setShader(null);
        p.setColor(withAlpha(color, Color.alpha(color) / 255f * opacity));
        c.drawRoundRect(r, radius, radius, p);
        if (edge != 0) hairline(c, p, r, radius, withAlpha(edge, Color.alpha(edge) / 255f * opacity));
    }

    /** CSS "box-shadow: inset 0 1px 0": a 1px band following the top radius. */
    static void hairline(Canvas c, Paint p, RectF rect, float radius, int color) {
        edgeBand(c, p, rect, radius, 1, color);
    }

    /** CSS "box-shadow: inset 0 dy 0": a band along the top edge (dy > 0) or the bottom (dy < 0). */
    static void edgeBand(Canvas c, Paint p, RectF rect, float radius, float dy, int color) {
        Path outer = new Path();
        outer.addRoundRect(rect, radius, radius, Path.Direction.CW);
        Path inner = new Path();
        RectF shifted = new RectF(rect);
        shifted.offset(0, dy);
        inner.addRoundRect(shifted, radius, radius, Path.Direction.CW);
        outer.op(inner, Path.Op.DIFFERENCE);
        p.setShader(null);
        p.setColor(color);
        c.drawPath(outer, p);
    }

    static void linearV(Canvas c, Paint p, float left, float top, float right, float bottom, int[] colors, float[] stops) {
        p.setColor(Color.WHITE);
        p.setShader(new LinearGradient(0, top, 0, bottom, colors, stops, Shader.TileMode.CLAMP));
        c.drawRect(left, top, right, bottom, p);
        p.setShader(null);
    }

    static void linearH(Canvas c, Paint p, float left, float top, float right, float bottom, int[] colors, float[] stops) {
        p.setColor(Color.WHITE);
        p.setShader(new LinearGradient(left, 0, right, 0, colors, stops, Shader.TileMode.CLAMP));
        c.drawRect(left, top, right, bottom, p);
        p.setShader(null);
    }

    /** CSS radial-gradient(ellipse rx ry at cx cy, a, b 55%, transparent). */
    static void radial(Canvas c, Paint p, float cx, float cy, float rx, float ry, int a, int b) {
        RadialGradient g = new RadialGradient(0, 0, 1,
                new int[] {a, b, b & 0x00FFFFFF}, new float[] {0, .55f, 1}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(rx, ry);
        m.postTranslate(cx, cy);
        g.setLocalMatrix(m);
        p.setColor(Color.WHITE);
        p.setShader(g);
        c.drawRect(cx - rx, cy - ry, cx + rx, cy + ry, p);
        p.setShader(null);
    }

    /** Draws a stroke icon with CleanFrame's offset underlay (translate(0 .5) in icon units). */
    static void icon(Canvas c, Drawable ink, Drawable under, float cx, float cy, float size, int color, int halo, float alpha) {
        int l = Math.round(cx - size / 2), t = Math.round(cy - size / 2), s = Math.round(size);
        int a = Math.round(alpha * 255);
        if (halo != 0 && under != null) {
            int shift = Math.max(1, Math.round(.5f * size / 24));
            under.setBounds(l, t + shift, l + s, t + shift + s);
            under.setTint(halo);
            under.setAlpha(a);
            under.draw(c);
        }
        ink.setBounds(l, t, l + s, t + s);
        ink.setTint(color);
        ink.setAlpha(a);
        ink.draw(c);
    }

    static int ink(int r, int g, int b, float alpha) {
        return Color.argb(Math.round(clamp(alpha, 0, 1) * 255), r, g, b);
    }

    static int white(float alpha) { return ink(255, 255, 255, alpha); }

    static int withAlpha(int color, float alpha) {
        return (color & 0x00FFFFFF) | (Math.round(clamp(alpha, 0, 1) * 255) << 24);
    }

    static float lerp(float a, float b, float t) { return a + (b - a) * t; }

    static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Progress of a sub-range [from, to] within an overall 0..1 animation. */
    static float span(float t, float from, float to) { return clamp((t - from) / (to - from), 0, 1); }
}
