package io.github.amitprna.minimaltv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Draws Home in stage pixels, apart from the baked backdrop: the glass Dock (a clear shelf near
 * the bottom, a side rail on the right, the time under the shelf) or CleanFrame's List.
 * Shared by the live Home and the Settings preview.
 */
final class HomeRenderer {
    static final int ZONE_APPS = 0, ZONE_CONTROLS = 1;
    static final String[] CONTROL_NAMES = {"Clock", "All apps", "Settings"};

    /** Focus with its previous position, for a 180ms CleanFrame ease between them. */
    static final class Focus {
        int zone = ZONE_APPS, app, control;
        int fromZone = ZONE_APPS, fromApp, fromControl;
        float e = 1; // eased progress from "from" to current

        void set(int zone, int app, int control) {
            fromZone = this.zone;
            fromApp = this.app;
            fromControl = this.control;
            this.zone = zone;
            this.app = app;
            this.control = control;
            e = 0;
        }

        /** How focused a zone is mid-transition. */
        float amount(int z) {
            return Gfx.lerp(fromZone == z ? 1 : 0, zone == z ? 1 : 0, e);
        }

        float appFocus(int i) {
            float now = zone == ZONE_APPS && app == i ? 1 : 0;
            float before = fromZone == ZONE_APPS && fromApp == i ? 1 : 0;
            return Gfx.lerp(before, now, e);
        }

        float controlFocus(int i) {
            float now = zone == ZONE_CONTROLS && control == i ? 1 : 0;
            float before = fromZone == ZONE_CONTROLS && fromControl == i ? 1 : 0;
            return Gfx.lerp(before, now, e);
        }
    }

    // Glass Dock geometry (stage px)
    private static final float TILE_W = 220, TILE_H = 180, TILE_PITCH = 234, SHELF_PAD = 14;
    private static final float SHELF_TOP = 768, SHELF_RADIUS = 56;
    private static final float RAIL_X = 1716, RAIL_TOP = 360, RAIL_W = 108, RAIL_PAD = 10, RAIL_BTN = 88, RAIL_GAP = 12;
    private static final float TIME_TOP = 990, TIME_H = 44, TIME_PX = 34, TIME_GAP = 16, TIME_DOT = 5;
    private static final int DOCK_VISIBLE = 5, LIST_VISIBLE = 5;
    private static final float LIST_TOP = 220, LIST_PITCH = 128;

    private final Drawable[] controlInk = new Drawable[3], controlUnder = new Drawable[3];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Fonts fonts;

    private Look look;
    private Ink ink;
    private Wallpaper wall;
    private TextPaint clockPaint, namePaint, controlPaint, bigNamePaint;
    private TextPaint tileLabel, tileLabelOn, timePaint, timeRim, datePaint;
    private final java.text.SimpleDateFormat dateFormat = new java.text.SimpleDateFormat("EEE d MMM", Locale.getDefault());

    HomeRenderer(Context context, Fonts fonts) {
        this.fonts = fonts;
        int[] in = {R.drawable.ut_clock_ink, R.drawable.ut_apps_ink, R.drawable.ut_settings_ink};
        int[] un = {R.drawable.ut_clock_under, R.drawable.ut_apps_under, R.drawable.ut_settings_under};
        for (int i = 0; i < 3; i++) {
            controlInk[i] = context.getDrawable(in[i]).mutate();
            controlUnder[i] = context.getDrawable(un[i]).mutate();
        }
    }

    void setLook(Look look, Wallpaper wall) {
        this.look = look;
        this.wall = wall;
        this.ink = Ink.of(look, wall);
        clockPaint = Gfx.text(fonts.get(look.font, Fonts.Role.CLOCK), 48, -1.5f, ink.clock);
        clockPaint.setFontFeatureSettings("'tnum'");
        boolean text = "text".equals(look.apps);
        tileLabel = Gfx.text(fonts.weight(look.font, 500), text ? 36 : 28, 0, ink.app);
        tileLabel.setTextAlign(Paint.Align.CENTER);
        tileLabelOn = Gfx.text(fonts.weight(look.font, 700), text ? 38 : 30, 0, ink.app);
        tileLabelOn.setTextAlign(Paint.Align.CENTER);
        timePaint = Gfx.text(fonts.weight(look.font, 700), TIME_PX, -.5f, ink.clock);
        timePaint.setFontFeatureSettings("'tnum'");
        timeRim = new TextPaint(timePaint);
        timeRim.setStyle(Paint.Style.STROKE);
        timeRim.setStrokeWidth(1);
        datePaint = Gfx.text(fonts.weight(look.font, 600), TIME_PX, 0, ink.clock);
        namePaint = Gfx.text(fonts.get(look.font, Fonts.Role.DISPLAY), 40, -1f, ink.app);
        namePaint.setTextAlign(Paint.Align.CENTER);
        controlPaint = Gfx.text(fonts.get(look.font, Fonts.Role.UI), 28, -.2f, ink.control);
        bigNamePaint = Gfx.text(fonts.get(look.font, Fonts.Role.DISPLAY), 88, -1.8f, ink.app);
    }

    Ink ink() { return ink; }

    // ---- geometry ----

    /** The glass shelf in stage px, sized to its apps (null for List or no apps). */
    RectF dockRect(int count) {
        if (!look.dock() || count <= 0) return null;
        int vis = Math.min(DOCK_VISIBLE, count);
        float w = vis * TILE_PITCH - (TILE_PITCH - TILE_W) + 2 * SHELF_PAD;
        float x = Math.round((Gfx.STAGE_W - w) / 2);
        return new RectF(x, SHELF_TOP, x + w, SHELF_TOP + TILE_H + 2 * SHELF_PAD);
    }

    /** Control boxes in stage px: the Dock's rail buttons, or CleanFrame's List controls. */
    RectF[] controlRects() {
        RectF[] r = new RectF[3];
        if (look.dock()) {
            for (int i = 0; i < 3; i++) {
                float top = RAIL_TOP + RAIL_PAD + i * (RAIL_BTN + RAIL_GAP);
                r[i] = new RectF(RAIL_X + RAIL_PAD, top, RAIL_X + RAIL_PAD + RAIL_BTN, top + RAIL_BTN);
            }
            return r;
        }
        boolean icons = "icons".equals(look.controls), text = "text".equals(look.controls);
        for (int i = 0; i < 3; i++) {
            float label = controlPaint.measureText(CONTROL_NAMES[i]);
            float w = icons ? 64 : text ? 14 + label + 14 : 14 + 32 + 10 + label + 14;
            float top = 1080 - 112 - (3 - i) * 64;
            r[i] = new RectF(1798 - w, top, 1798, top + 64);
        }
        return r;
    }

    // ---- drawing ----

    /**
     * @param exit 0 = Home fully shown, 1 = Home dissolved into StandBy (dock sinks, controls fade)
     */
    void draw(Canvas c, List<Tile> apps, Focus f, Bitmap field, float exit) {
        float alpha = 1 - exit;
        if (alpha <= 0) return;
        if (look.dock()) {
            drawRail(c, f, alpha);
            c.save();
            c.translate(0, 120 * exit * exit); // shelf and time sink away as StandBy takes over
            drawDock(c, apps, f, alpha);
            drawTimeLine(c, alpha);
            c.restore();
        } else {
            drawClock(c, alpha);
            drawControls(c, f, alpha);
            drawList(c, apps, f, field, exit);
        }
    }

    String clockText() {
        Calendar now = Calendar.getInstance();
        int h = look.clock24 ? now.get(Calendar.HOUR_OF_DAY) : (now.get(Calendar.HOUR) == 0 ? 12 : now.get(Calendar.HOUR));
        return String.format(Locale.ROOT, look.clock24 ? "%02d:%02d" : "%d:%02d", h, now.get(Calendar.MINUTE));
    }

    String dateText() { return dateFormat.format(new java.util.Date()); }

    /** Where the digital clock sits, for the StandBy morph (stage px: left, top, right, bottom). */
    RectF clockBounds() {
        if (look.dock()) {
            float left = timeLineLeft();
            return new RectF(left, TIME_TOP, left + timePaint.measureText(clockText()), TIME_TOP + TIME_H);
        }
        float w = clockPaint.measureText(clockText());
        return new RectF(1780 - w, 112, 1780, 176);
    }

    /** The clock's text size in stage px (the morph starts from it). */
    float clockSize() { return look.dock() ? TIME_PX : 48; }

    private float timeLineLeft() {
        float w = timePaint.measureText(clockText()) + TIME_GAP + TIME_DOT + TIME_GAP + datePaint.measureText(dateText());
        return Math.round((Gfx.STAGE_W - w) / 2);
    }

    private void drawClock(Canvas c, float alpha) {
        clockPaint.setColor(Gfx.withAlpha(ink.clock, ink.clockAlpha * alpha));
        if (ink.shadows) clockPaint.setShadowLayer(3, 0, 1, Gfx.ink(0, 0, 0, .22f * alpha));
        else clockPaint.clearShadowLayer();
        clockPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText(clockText(), 1780, Gfx.baseline(clockPaint, 112, 64), clockPaint);
    }

    /** "19:52 · Thu 22 Oct" centred under the shelf: glass time, solid date, same size. */
    private void drawTimeLine(Canvas c, float alpha) {
        String time = clockText(), date = dateText();
        float x = timeLineLeft(), base = Gfx.baseline(datePaint, TIME_TOP, TIME_H);
        float tw = timePaint.measureText(time);
        if (ink.photo) {
            // Glass digits: a clear-to-frosted fill with a bright rim, lifted by a soft shadow.
            Paint.FontMetrics fm = timePaint.getFontMetrics();
            float top = base + fm.ascent * .78f;
            timePaint.setColor(Color.WHITE);
            timePaint.setShader(new LinearGradient(0, top, 0, base, new int[] {
                    Gfx.white(.78f * alpha), Gfx.white(.30f * alpha), Gfx.white(.55f * alpha)},
                    new float[] {0, .55f, 1}, Shader.TileMode.CLAMP));
            c.drawText(time, x, base, timePaint);
            timePaint.setShader(null);
            timeRim.setColor(Gfx.white(.70f * alpha));
            timeRim.setShadowLayer(6, 0, 2, Gfx.ink(0, 0, 0, .35f * alpha));
            c.drawText(time, x, base, timeRim);
        } else {
            timePaint.setColor(Gfx.withAlpha(ink.clock, ink.clockAlpha * alpha));
            c.drawText(time, x, base, timePaint);
        }
        x += tw + TIME_GAP;
        paint.setShader(null);
        paint.setColor(ink.photo ? Gfx.white(.5f * alpha) : Gfx.withAlpha(ink.clock, .5f * alpha));
        c.drawCircle(x + TIME_DOT / 2, TIME_TOP + TIME_H / 2, TIME_DOT / 2, paint);
        x += TIME_DOT + TIME_GAP;
        datePaint.setColor(ink.photo ? Gfx.white(.92f * alpha) : Gfx.withAlpha(ink.clock, ink.clockAlpha * alpha));
        if (ink.shadows) datePaint.setShadowLayer(6, 0, 1, Gfx.ink(0, 0, 0, .35f * alpha));
        else datePaint.clearShadowLayer();
        c.drawText(date, x, base, datePaint);
    }

    private void drawControls(Canvas c, Focus f, float alpha) {
        RectF[] r = controlRects();
        float on = f.amount(ZONE_CONTROLS);
        boolean icons = "icons".equals(look.controls), text = "text".equals(look.controls);

        if (look.glass() && on > 0) {
            RectF a = r[f.fromZone == ZONE_CONTROLS ? f.fromControl : f.control], b = r[f.control];
            float k = f.fromZone == ZONE_CONTROLS ? f.e : 1;
            RectF lens = new RectF(Gfx.lerp(a.left, b.left, k), Gfx.lerp(a.top, b.top, k) + 2,
                    Gfx.lerp(a.right, b.right, k), Gfx.lerp(a.top, b.top, k) + 62);
            plate(c, lens, 30, on * alpha, Gfx.PLATE);
        }
        for (int i = 0; i < 3; i++) {
            float focus = f.controlFocus(i);
            float rest = f.zone == ZONE_CONTROLS ? ink.controlPeer : ink.controlRest;
            float a = Gfx.lerp(rest, 1, focus) * alpha;
            RectF box = r[i];
            c.save();
            if (!look.glass()) {
                float sc = 1 + .05f * focus;
                c.scale(sc, sc, box.centerX(), box.centerY());
            }
            float iconSize = icons ? (i == 2 ? 34 : 36) : 32;
            if (!text) {
                float cx = icons ? box.centerX() : box.right - 14 - 16;
                Gfx.icon(c, controlInk[i], controlUnder[i], cx, box.centerY(), iconSize, ink.control,
                        ink.controlHalo, a);
            }
            if (!icons) {
                controlPaint.setColor(Gfx.withAlpha(ink.control, a));
                if (ink.shadows) Gfx.photoShadow(controlPaint, a); else controlPaint.clearShadowLayer();
                controlPaint.setTextAlign(Paint.Align.RIGHT);
                float x = text ? box.right - 14 : box.right - 14 - 32 - 18;
                c.drawText(CONTROL_NAMES[i], x, Gfx.baseline(controlPaint, box.top + 14, 36), controlPaint);
            }
            c.restore();
        }
        if (icons && on > 0) {
            // Icons-only controls reveal their name when focused.
            RectF box = r[f.control];
            controlPaint.setColor(Gfx.withAlpha(ink.control, on * alpha));
            if (ink.shadows) Gfx.photoShadow(controlPaint, on * alpha); else controlPaint.clearShadowLayer();
            controlPaint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(CONTROL_NAMES[f.control], box.left - 12, Gfx.baseline(controlPaint, box.top + 14, 36), controlPaint);
        }
    }

    /** The right-hand rail: a nearly invisible glass outline holding Clock, All apps, Settings. */
    private void drawRail(Canvas c, Focus f, float alpha) {
        RectF[] r = controlRects();
        RectF rail = new RectF(RAIL_X, RAIL_TOP, RAIL_X + RAIL_W, r[2].bottom + RAIL_PAD);
        float radius = RAIL_W / 2;
        if (ink.photo) {
            paint.setShader(null);
            paint.setColor(Gfx.white(.015f * alpha));
            c.drawRoundRect(rail, radius, radius, paint);
            rim(c, rail, radius, 1.5f, Gfx.white(.30f * alpha));
            Gfx.edgeBand(c, paint, rail, radius, 1.5f, Gfx.white(.45f * alpha));
        } else {
            Gfx.flatPlate(c, paint, rail, radius, ink.plateFlat, ink.plateEdge, alpha);
        }

        float on = f.amount(ZONE_CONTROLS);
        if (on > 0) {
            // A glass bead slides between the buttons.
            RectF a = r[f.fromZone == ZONE_CONTROLS ? f.fromControl : f.control], b = r[f.control];
            float k = f.fromZone == ZONE_CONTROLS ? f.e : 1;
            RectF bead = new RectF(a.left, Gfx.lerp(a.top, b.top, k), a.right, Gfx.lerp(a.bottom, b.bottom, k));
            if (ink.photo) {
                frost(c, bead, RAIL_BTN / 2, on * alpha, .20f); // a touch stronger on the slim rail
            } else {
                Gfx.flatPlate(c, paint, bead, RAIL_BTN / 2, ink.plateFlat, ink.plateEdge, on * alpha);
            }
        }
        float rest = Math.max(ink.controlRest, .82f);
        for (int i = 0; i < 3; i++) {
            float a = Gfx.lerp(rest, 1, f.controlFocus(i)) * alpha;
            Gfx.icon(c, controlInk[i], controlUnder[i], r[i].centerX(), r[i].centerY(), i == 2 ? 38 : 40,
                    ink.control, ink.controlHalo, a);
        }
        if (on > 0) {
            // The focused control names itself beside the rail.
            RectF box = r[f.control];
            controlPaint.setTextAlign(Paint.Align.RIGHT);
            controlPaint.setColor(Gfx.withAlpha(ink.control, on * alpha));
            if (ink.shadows) Gfx.photoShadow(controlPaint, on * alpha); else controlPaint.clearShadowLayer();
            c.drawText(CONTROL_NAMES[f.control], rail.left - 20, Gfx.baseline(controlPaint, box.centerY() - 18, 36), controlPaint);
        }
    }

    /** The clear glass shelf and its apps. Focus brightens and enlarges a tile, with an underline. */
    private void drawDock(Canvas c, List<Tile> apps, Focus f, float alpha) {
        RectF dock = dockRect(apps.size());
        if (dock == null) return;
        if (ink.photo) shelfGlass(c, dock, alpha);
        else Gfx.flatPlate(c, paint, dock, SHELF_RADIUS, ink.plateFlat, ink.plateEdge, alpha);

        float shelfX = dock.left + SHELF_PAD, tileTop = dock.top + SHELF_PAD;
        int vis = Math.min(DOCK_VISIBLE, apps.size());
        float scroll = Math.max(0, Math.min(f.app - (vis - 1), apps.size() - vis)) * TILE_PITCH;
        boolean icons = "icons".equals(look.apps), text = "text".equals(look.apps);

        c.save();
        c.clipRect(dock.left, dock.top - 40, dock.right, dock.bottom + 8);
        float on = f.amount(ZONE_APPS);
        if (on > 0 && ink.photo) {
            // A frosted plate slides to the focused app: milky tint, bright rim, lit top edge.
            // (The live video beneath can't be blurred, so the frost is a tint, not a true blur.)
            float k = f.fromZone == ZONE_APPS ? f.e : 1;
            float px = shelfX + Gfx.lerp(f.fromZone == ZONE_APPS ? f.fromApp : f.app, f.app, k) * TILE_PITCH - scroll;
            RectF plate = new RectF(px, tileTop, px + TILE_W, tileTop + TILE_H);
            frost(c, plate, SHELF_RADIUS - SHELF_PAD, on * alpha, .05f);
        }
        for (int i = 0; i < apps.size(); i++) {
            float left = shelfX + i * TILE_PITCH - scroll;
            if (left + TILE_W < dock.left || left > dock.right) continue;
            Tile tile = apps.get(i);
            float focus = f.appFocus(i);
            float a = alpha * Gfx.lerp(.82f, 1, focus);
            float cx = left + TILE_W / 2;
            if (!text) {
                float boxH = icons ? 96 : 76, markTop = tileTop + (icons ? 30 : 22) - 4 * focus;
                if (ink.photo && focus > 0) {
                    // Soft glow behind the focused mark.
                    Gfx.radial(c, paint, cx, markTop + boxH / 2, 90, 64,
                            Gfx.white(.16f * focus * alpha), Gfx.white(.06f * focus * alpha));
                }
                c.save();
                float sc = 1 + .18f * focus;
                c.scale(sc, sc, cx, markTop + boxH / 2);
                tile.mark.draw(c, cx - 68, markTop, 136, boxH, icons ? 80 : 60, ink.app, Math.round(a * 255));
                c.restore();
            }
            if (!icons) label(c, tile.label, cx, text ? tileTop + 56 : tileTop + 112, focus, a);
            if (focus > 0) {
                RectF bar = new RectF(cx - 18, tileTop + 158, cx + 18, tileTop + 163);
                if (ink.photo) {
                    Gfx.radial(c, paint, cx, bar.centerY(), 40, 12,
                            Gfx.white(.35f * focus * alpha), Gfx.white(.12f * focus * alpha));
                }
                paint.setShader(null);
                paint.setColor(Gfx.withAlpha(ink.app, focus * alpha));
                c.drawRoundRect(bar, 3, 3, paint);
            }
        }
        c.restore();

        if (icons && on > 0 && !apps.isEmpty()) {
            // Icons-only tiles name the focused app above the shelf.
            float x = shelfX + f.app * TILE_PITCH - scroll + TILE_W / 2;
            controlPaint.setTextAlign(Paint.Align.CENTER);
            controlPaint.setColor(Gfx.withAlpha(ink.app, on * alpha));
            if (ink.shadows) Gfx.photoShadow(controlPaint, on * alpha); else controlPaint.clearShadowLayer();
            c.drawText(apps.get(f.app).label, x, Gfx.baseline(controlPaint, dock.top - 48, 40), controlPaint);
        }
    }

    /** App name: regular at rest, crossfading to bold as focus arrives. */
    private void label(Canvas c, String text, float cx, float top, float focus, float alpha) {
        if (focus < 1) {
            tileLabel.setColor(Gfx.withAlpha(ink.app, alpha * (1 - focus)));
            if (ink.shadows) Gfx.photoShadow(tileLabel, alpha * (1 - focus)); else tileLabel.clearShadowLayer();
            Gfx.drawText(c, text, cx, top, 40, TILE_W - 16, tileLabel);
        }
        if (focus > 0) {
            tileLabelOn.setColor(Gfx.withAlpha(ink.app, alpha * focus));
            if (ink.shadows) Gfx.photoShadow(tileLabelOn, alpha * focus); else tileLabelOn.clearShadowLayer();
            Gfx.drawText(c, text, cx, top, 40, TILE_W - 8, tileLabelOn);
        }
    }

    /** Rim-only liquid glass: a faint sheen, a bright 2px rim and inner edge highlights. */
    private void shelfGlass(Canvas c, RectF r, float alpha) {
        double angle = Math.toRadians(160);
        float dx = (float) Math.sin(angle), dy = (float) -Math.cos(angle);
        float len = Math.abs(r.width() * dx) + Math.abs(r.height() * dy);
        float cx = r.centerX(), cy = r.centerY();
        paint.setColor(Color.WHITE);
        paint.setShader(new LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2,
                new int[] {Gfx.white(.07f * alpha), Gfx.white(0), Gfx.white(.04f * alpha)},
                new float[] {0, .45f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, SHELF_RADIUS, SHELF_RADIUS, paint);
        paint.setShader(null);
        rim(c, r, SHELF_RADIUS, 2, Gfx.white(.40f * alpha));
        RectF in = new RectF(r);
        in.inset(2, 2);
        Gfx.edgeBand(c, paint, in, SHELF_RADIUS - 2, 3, Gfx.white(.45f * alpha));
        Gfx.edgeBand(c, paint, in, SHELF_RADIUS - 2, -3, Gfx.white(.16f * alpha));
    }

    /** Translucent frosted glass for focus: milky tint, bright rim, lit top edge; {@code boost} adds opacity. */
    private void frost(Canvas c, RectF r, float radius, float a, float boost) {
        paint.setColor(Color.WHITE);
        paint.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom,
                new int[] {Gfx.white((.30f + boost) * a), Gfx.white((.20f + boost) * a), Gfx.white((.26f + boost) * a)},
                new float[] {0, .55f, 1}, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, radius, radius, paint);
        paint.setShader(null);
        rim(c, r, radius, 1.5f, Gfx.white((.45f + boost) * a));
        Gfx.edgeBand(c, paint, r, radius, 2, Gfx.white((.50f + boost) * a));
    }

    private void rim(Canvas c, RectF r, float radius, float width, int color) {
        RectF s = new RectF(r);
        s.inset(width / 2, width / 2);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        c.drawRoundRect(s, radius - width / 2, radius - width / 2, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawList(Canvas c, List<Tile> apps, Focus f, Bitmap field, float exit) {
        if (apps.isEmpty()) return;
        float alpha = 1 - exit;
        int n = apps.size();
        int first = Math.max(0, Math.min(f.app - 2, n - LIST_VISIBLE));
        float scroll = first * LIST_PITCH;
        boolean icons = "icons".equals(look.apps), text = "text".equals(look.apps);

        c.save();
        c.translate(-160 * exit * exit, 0); // names drift left as StandBy takes over
        float on = f.amount(ZONE_APPS);
        if (on > 0) {
            float k = f.fromZone == ZONE_APPS ? f.e : 1;
            int from = f.fromZone == ZONE_APPS ? f.fromApp : f.app;
            float y = LIST_TOP - 3 + Gfx.lerp(from, f.app, k) * LIST_PITCH - scroll;
            float w = Gfx.lerp(listPlateWidth(apps.get(from)), listPlateWidth(apps.get(f.app)), k);
            RectF r = new RectF(112, y, 112 + w, y + 134);
            if (look.glass()) {
                if (look.photo() && field != null) fieldPlate(c, r, field, on * alpha);
                else plate(c, r, 38, on * alpha, Gfx.PLATE);
            }
        }
        for (int i = 0; i < n; i++) {
            float top = LIST_TOP + i * LIST_PITCH - scroll;
            if (top < 72 - LIST_PITCH || top > 1016) continue;
            // List mask: names fade out toward the top and bottom of the list area.
            float fade = Gfx.span(top, 72, 220) * (1 - Gfx.span(top + LIST_PITCH, 860, 1016));
            float focus = f.appFocus(i);
            float a = alpha * fade * (look.glass() ? .84f + .16f * focus : Gfx.lerp(ink.typeRest, 1, focus));
            if (a <= 0) continue;
            Tile tile = apps.get(i);
            c.save();
            if (!look.glass()) {
                float sc = 1 + .05f * focus;
                c.scale(sc, sc, 112 + 28, top + 64);
            }
            if (!text) {
                if (icons) mark(c, tile, 112 + 28, top + 20, 88, 88, 72, focus, a);
                else mark(c, tile, 112 + 28, top + 32, 84, 64, 54, focus, a);
            }
            if (!icons || focus > 0) {
                bigNamePaint.setColor(Gfx.withAlpha(ink.app, icons ? a * focus : a));
                if (ink.shadows) bigNamePaint.setShadowLayer(8, 0, 2, Gfx.ink(14, 10, 6, .30f * a));
                else bigNamePaint.clearShadowLayer();
                float x = 112 + (text ? 28 : 128);
                Gfx.drawText(c, tile.label, x, top + 10, 104, 1076 - x + 112, bigNamePaint);
            }
            c.restore();
        }
        c.restore();
    }

    private float listPlateWidth(Tile t) {
        if ("icons".equals(look.apps)) return 144;
        boolean text = "text".equals(look.apps);
        float w = bigNamePaint.measureText(t.label) + (text ? 84 : 184);
        return Gfx.clamp(w, text ? 280 : 360, text ? 1076 : 1176);
    }

    /** List glass: the pre-blurred field seen through a rounded window, plus a veil. */
    private void fieldPlate(Canvas c, RectF r, Bitmap field, float opacity) {
        Path clip = new Path();
        clip.addRoundRect(r, 38, 38, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        bitmapPaint.setAlpha(Math.round(255 * opacity));
        c.drawBitmap(field, null, new RectF(0, 0, Gfx.STAGE_W, Gfx.STAGE_H), bitmapPaint);
        c.drawColor(Gfx.ink(12, 10, 8, (wall.light ? .20f : .12f) * opacity));
        c.restore();
        Gfx.hairline(c, paint, r, 38, Gfx.ink(255, 250, 240, .24f * opacity));
    }

    private void plate(Canvas c, RectF r, float radius, float opacity, float[] palette) {
        if (ink.photo) Gfx.glassPlate(c, paint, r, radius, opacity, palette, palette == Gfx.PLATE_QUIET ? .52f : .58f);
        else Gfx.flatPlate(c, paint, r, radius, ink.plateFlat, ink.plateEdge, opacity);
    }

    /** Focused marks lift 3px and grow 4% (CleanFrame's translateY(-3px) scale(1.04)). */
    private void mark(Canvas c, Tile t, float left, float top, float w, float h, float size, float focus, float alpha) {
        c.save();
        float sc = look.glass() ? 1 + .04f * focus : 1;
        c.scale(sc, sc, left + w / 2, top + h / 2);
        t.mark.draw(c, left, top, w, h, size, ink.app, Math.round(alpha * 255));
        c.restore();
    }
}
