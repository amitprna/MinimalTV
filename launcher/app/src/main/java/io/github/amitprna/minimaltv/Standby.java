package io.github.amitprna.minimaltv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.animation.PathInterpolator;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The idle screen: centred frosted flip cards for hours and minutes, each turning only
 * when its own number changes; the date beneath; five small frosted forecast tiles; the place
 * and its air quality top-left; the room's temperature and humidity top-right. All over the
 * dimmed scene. {@code reveal} (0..1) choreographs the entrance.
 */
final class Standby {
    // Dark palette only: nothing reads as white even on a bright panel, so the idle screen
    // stays dim at night. Warm greys, a muted teal for the room reading.
    static final int GREY = 0xFF6E6A65;      // date, labels (and Home's clock as it morphs in)
    static final int INK = 0xFF8E8880;       // temperatures
    static final int DIGIT = 0xFF7E7972;     // the flip digits
    static final int FAINT = 0xFF4A4643;
    static final int TEAL = 0xFF5E8A84;

    private static final RectF HOUR = new RectF(480, 140, 940, 580), MINUTE = new RectF(980, 140, 1440, 580);
    /** Hours and minutes together: where Home's clock lands as it morphs in. */
    static final RectF PANEL = new RectF(HOUR.left, HOUR.top, MINUTE.right, MINUTE.bottom);
    static final float DIGIT_PX = 330;

    private static final float TILE_W = 170, TILE_H = 150, TILE_GAP = 28, TILE_TOP = 730;

    /** One flip card: the number showing and, mid-flip, the one turning away. */
    private static final class Flip {
        final float ms;
        String shown, previous;
        long start;

        Flip(float ms) { this.ms = ms; }

        /** @return flip progress, 1 once settled */
        float update(String now) {
            if (shown == null) shown = now;
            if (!now.equals(shown)) {
                previous = shown;
                shown = now;
                start = SystemClock.uptimeMillis();
            }
            float p = previous == null ? 1 : Gfx.clamp((SystemClock.uptimeMillis() - start) / ms, 0, 1);
            if (p >= 1) previous = null;
            return p;
        }
    }

    private final PathInterpolator ease = new PathInterpolator(.2f, .7f, .2f, 1f);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint digits, date, tileDay, tileTemp, corner, cornerTag;
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Conditions conditions;
    private final boolean clock24;
    private final Flip hourFlip = new Flip(640), minuteFlip = new Flip(640);
    private final float digitHalf; // half a digit's height, to centre digits on the split

    Standby(Context context, Fonts fonts, String fontId, boolean clock24) {
        this.clock24 = clock24;
        conditions = Conditions.get(context);
        digits = Gfx.text(fonts.weight(fontId, 600), DIGIT_PX, -6, DIGIT);
        digits.setTextAlign(Paint.Align.CENTER);
        digits.setFontFeatureSettings("'tnum'");
        Rect b = new Rect();
        digits.getTextBounds("0", 0, 1, b);
        digitHalf = b.height() / 2f;
        date = Gfx.text(fonts.weight(fontId, 500), 36, .2f, GREY);
        date.setTextAlign(Paint.Align.CENTER);
        tileDay = Gfx.text(fonts.weight(fontId, 600), 20, 1, GREY);
        tileDay.setTextAlign(Paint.Align.CENTER);
        tileTemp = Gfx.text(fonts.weight(fontId, 500), 28, 0, INK);
        tileTemp.setTextAlign(Paint.Align.CENTER);
        tileTemp.setFontFeatureSettings("'tnum'");
        corner = Gfx.text(fonts.weight(fontId, 500), 26, 0, INK);
        corner.setFontFeatureSettings("'tnum'");
        cornerTag = Gfx.text(fonts.weight(fontId, 600), 20, 1, FAINT);
        String[] names = {"sun", "partly", "cloud", "fog", "drizzle", "rain", "showers", "storm", "snow",
                "thermo", "drop", "pin", "air"};
        int[] ids = {R.drawable.wx_sun, R.drawable.wx_partly, R.drawable.wx_cloud, R.drawable.wx_fog, R.drawable.wx_drizzle,
                R.drawable.wx_rain, R.drawable.wx_showers, R.drawable.wx_storm, R.drawable.wx_snow,
                R.drawable.wx_thermo, R.drawable.wx_drop, R.drawable.wx_pin, R.drawable.wx_air};
        for (int i = 0; i < names.length; i++) icons.put(names[i], context.getDrawable(ids[i]).mutate());
    }

    /**
     * The wallpaper at {@code visibility} (0..1) under a dark veil; drawn by callers with
     * their own crossfade alpha.
     */
    void drawBackground(Canvas c, Bitmap photo, float visibility, float alpha) {
        if (photo != null && visibility > 0) {
            bitmapPaint.setAlpha(Math.round(255 * alpha));
            c.drawBitmap(photo, null, new RectF(0, 0, Gfx.STAGE_W, Gfx.STAGE_H), bitmapPaint);
            c.drawColor(Gfx.ink(4, 4, 6, (1 - visibility) * alpha));
        } else {
            c.drawColor(Gfx.ink(0, 0, 0, alpha));
        }
    }

    /** @return true while a card is mid-flip and the caller should draw the next frame too */
    boolean draw(Canvas c, float reveal) {
        if (reveal <= 0) return false;
        Calendar now = Calendar.getInstance();
        // Slow drift (up to ~12px) so nothing sits on the same pixels for hours.
        double m = System.currentTimeMillis() / 60_000.0;
        c.save();
        c.translate((float) (12 * Math.sin(m * .37)), (float) (9 * Math.cos(m * .29)));
        int h = clock24 ? now.get(Calendar.HOUR_OF_DAY) : (now.get(Calendar.HOUR) == 0 ? 12 : now.get(Calendar.HOUR));
        boolean flipping = card(c, HOUR, 44, String.format(Locale.ROOT, clock24 ? "%02d" : "%d", h), hourFlip, digits,
                ease.getInterpolation(Gfx.span(reveal, .10f, .55f)));
        flipping |= card(c, MINUTE, 44, String.format(Locale.ROOT, "%02d", now.get(Calendar.MINUTE)), minuteFlip, digits,
                ease.getInterpolation(Gfx.span(reveal, .16f, .60f)));
        float d = ease.getInterpolation(Gfx.span(reveal, .40f, .80f));
        date.setColor(Gfx.withAlpha(GREY, d));
        c.drawText(dateText(now), Gfx.STAGE_W / 2, Gfx.baseline(date, 620 + 16 * (1 - d), 48), date);
        drawTiles(c, reveal);
        float corners = Gfx.span(reveal, .55f, .90f);
        drawPlace(c, corners);
        drawIndoor(c, corners);
        c.restore();
        return flipping;
    }

    // ---- flip cards ----

    /** One frosted card with a split; a new number flips down over the old one. */
    private boolean card(Canvas c, RectF r, float radius, String text, Flip f, TextPaint p, float in) {
        float flip = f.update(text);
        if (in <= 0) return f.previous != null;
        c.save();
        float sc = .94f + .06f * in;
        c.scale(sc, sc, r.centerX(), r.centerY());
        glass(c, r, radius, in);
        float split = r.centerY();
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Gfx.ink(5, 5, 7, .6f * in));
        c.drawRect(r.left + radius / 2, split - 1, r.right - radius / 2, split + 1, paint);

        half(c, r, radius, f.shown, p, true, in, false, 0);                                // the new top, under the flap
        half(c, r, radius, f.previous != null ? f.previous : f.shown, p, false, in, false, 0); // the old bottom until it lands
        if (f.previous != null) {
            if (flip < .5f) {
                float k = flip * 2;
                c.save();
                c.scale(1, 1 - k * k, r.centerX(), split); // the old top folds down, accelerating
                half(c, r, radius, f.previous, p, true, in, true, k);
                c.restore();
            } else {
                float k = (flip - .5f) * 2;
                c.save();
                c.scale(1, 1 - (1 - k) * (1 - k), r.centerX(), split); // the new bottom settles
                half(c, r, radius, f.shown, p, false, in, true, 1 - k);
                c.restore();
            }
        }
        c.restore();
        return f.previous != null;
    }

    /** One half of a card; a moving flap gets an opaque backing and darkens as it turns edge-on. */
    private void half(Canvas c, RectF r, float radius, String text, TextPaint p, boolean top, float alpha, boolean flap, float turn) {
        float split = r.centerY();
        c.save();
        c.clipRect(r.left, top ? r.top : split + 1, r.right, top ? split - 1 : r.bottom);
        if (flap) {
            paint.setShader(null);
            paint.setColor(Gfx.ink(19, 18, 17, .94f * alpha));
            c.drawRoundRect(r, radius, radius, paint);
        }
        p.setColor(Gfx.withAlpha(DIGIT, alpha * (1 - .5f * turn)));
        c.drawText(text, r.centerX(), split + digitHalf, p);
        c.restore();
    }

    /** Warm frosted glass: a faint diagonal sheen, a hairline rim and a brighter top edge. */
    private void glass(Canvas c, RectF r, float radius, float a) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.WHITE);
        paint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[] {Gfx.ink(150, 140, 128, .10f * a), Gfx.ink(150, 140, 128, .03f * a), Gfx.ink(150, 140, 128, .06f * a)},
                new float[] {0, .5f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(Gfx.ink(150, 140, 128, .22f * a));
        RectF s = new RectF(r);
        s.inset(.75f, .75f);
        c.drawRoundRect(s, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);
        Gfx.edgeBand(c, paint, s, radius, 1.5f, Gfx.ink(170, 160, 148, .22f * a));
    }

    // ---- forecast, place and room ----

    private void drawTiles(Canvas c, float reveal) {
        List<Conditions.Day> days = conditions.days();
        int n = Math.min(5, days.size());
        if (n == 0) return;
        float x = (Gfx.STAGE_W - n * TILE_W - (n - 1) * TILE_GAP) / 2;
        for (int i = 0; i < n; i++, x += TILE_W + TILE_GAP) {
            float a = ease.getInterpolation(Gfx.span(reveal, .50f + i * .06f, .80f + i * .04f));
            if (a <= 0) continue;
            Conditions.Day d = days.get(i);
            RectF r = new RectF(x, TILE_TOP + 20 * (1 - a), x + TILE_W, TILE_TOP + 20 * (1 - a) + TILE_H);
            glass(c, r, 24, a);
            paint.setColor(Gfx.ink(5, 5, 7, .5f * a));
            c.drawRect(r.left + 12, r.top + 74, r.right - 12, r.top + 75, paint);
            tileDay.setColor(Gfx.withAlpha(GREY, a));
            c.drawText(d.label.toUpperCase(Locale.getDefault()), r.centerX(), Gfx.baseline(tileDay, r.top + 14, 28), tileDay);
            icon(c, d.icon, r.centerX(), r.top + 62, 36, GREY, a);
            tileTemp.setColor(Gfx.withAlpha(INK, a));
            c.drawText(d.hi + "°", r.centerX(), Gfx.baseline(tileTemp, r.top + 92, 40), tileTemp);
        }
    }

    /** "📍 Place   ≋ AQI 48 · Good", left-aligned in the top corner; the air icon takes the AQI band's muted colour. */
    private void drawPlace(Canvas c, float a) {
        String place = conditions.place();
        if (a <= 0 || place == null) return;
        float x = 120, y = 72, mid = y + 20;
        icon(c, "pin", x + 14, mid, 28, GREY, a);
        x += 28 + 12;
        corner.setColor(Gfx.withAlpha(GREY, a));
        c.drawText(place, x, Gfx.baseline(corner, y, 40), corner);
        x += corner.measureText(place);
        int aqi = conditions.aqi();
        if (aqi < 0) return;
        x += 28;
        icon(c, "air", x + 14, mid, 28, aqiColor(aqi), a);
        x += 28 + 12;
        String value = "AQI " + aqi;
        corner.setColor(Gfx.withAlpha(INK, a));
        c.drawText(value, x, Gfx.baseline(corner, y, 40), corner);
        x += corner.measureText(value) + 14;
        corner.setColor(Gfx.withAlpha(FAINT, a));
        c.drawText("·", x, Gfx.baseline(corner, y, 40), corner);
        x += corner.measureText("·") + 14;
        corner.setColor(Gfx.withAlpha(GREY, a));
        c.drawText(aqiLabel(aqi), x, Gfx.baseline(corner, y, 40), corner);
    }

    /** US AQI bands. */
    private static String aqiLabel(int aqi) {
        if (aqi <= 50) return "Good";
        if (aqi <= 100) return "Moderate";
        if (aqi <= 150) return "Unhealthy for some";
        if (aqi <= 200) return "Unhealthy";
        if (aqi <= 300) return "Very unhealthy";
        return "Hazardous";
    }

    private static int aqiColor(int aqi) {
        if (aqi <= 50) return TEAL;
        if (aqi <= 100) return 0xFF8F8448;
        if (aqi <= 150) return 0xFF94673A;
        if (aqi <= 200) return 0xFF8E4A40;
        if (aqi <= 300) return 0xFF7A4A72;
        return 0xFF6E3A3A;
    }

    /** "🌡 26.3° · 💧 68%  ROOM", right-aligned in the top corner. */
    private void drawIndoor(Canvas c, float a) {
        float t = conditions.indoorTemp(), h = conditions.indoorHumidity();
        if (a <= 0 || Float.isNaN(t)) return;
        String temp = String.format(Locale.ROOT, "%.1f°", t), hum = Float.isNaN(h) ? null : Math.round(h) + "%";
        float x = 1800, y = 72, mid = y + 20;
        cornerTag.setColor(Gfx.withAlpha(FAINT, a));
        x -= cornerTag.measureText("ROOM");
        c.drawText("ROOM", x, Gfx.baseline(cornerTag, y, 40), cornerTag);
        x -= 20;
        if (hum != null) {
            corner.setColor(Gfx.withAlpha(GREY, a));
            x -= corner.measureText(hum);
            c.drawText(hum, x, Gfx.baseline(corner, y, 40), corner);
            x -= 12 + 28;
            icon(c, "drop", x + 14, mid, 28, TEAL, a);
            x -= 14;
            corner.setColor(Gfx.withAlpha(FAINT, a));
            x -= corner.measureText("·");
            c.drawText("·", x, Gfx.baseline(corner, y, 40), corner);
            x -= 14;
        }
        corner.setColor(Gfx.withAlpha(INK, a));
        x -= corner.measureText(temp);
        c.drawText(temp, x, Gfx.baseline(corner, y, 40), corner);
        x -= 10 + 30;
        icon(c, "thermo", x + 15, mid, 30, TEAL, a);
    }

    private void icon(Canvas c, String name, float cx, float cy, float size, int color, float alpha) {
        Drawable d = icons.get(name);
        if (d == null) d = icons.get("cloud");
        int l = Math.round(cx - size / 2), t = Math.round(cy - size / 2), s = Math.round(size);
        d.setBounds(l, t, l + s, t + s);
        d.setTint(color);
        d.setAlpha(Math.round(255 * alpha));
        d.draw(c);
    }

    /** "Thursday, 22nd October". */
    static String dateText(Calendar now) {
        int d = now.get(Calendar.DAY_OF_MONTH);
        String suffix = d % 100 >= 11 && d % 100 <= 13 ? "th" : d % 10 == 1 ? "st" : d % 10 == 2 ? "nd" : d % 10 == 3 ? "rd" : "th";
        Locale l = Locale.getDefault();
        return now.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, l) + ", " + d + suffix + " "
                + now.getDisplayName(Calendar.MONTH, Calendar.LONG, l);
    }
}
