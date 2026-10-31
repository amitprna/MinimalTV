package io.github.amitprna.minimaltv;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * CleanFrame-style Settings: a grouped overview, two-pane detail screens (choices left,
 * the committed Home preview right) and a wallpaper list whose preview follows focus.
 */
final class SettingsPanel extends Panel {
    private static final String[] GROUPS = {"Background", "Layout", "Typography", "Focus", "Clock", "Apps on Home", "TV settings"};
    private static final RectF PREVIEW = new RectF(858, 288, 858 + 1920 * .48f, 288 + 1080 * .48f);
    private static final float PANE_LEFT = 112, PANE_W = 700, CONTENT_TOP = 212, CONTENT_BOTTOM = 1010;

    private enum Mode { OVERVIEW, DETAIL, WALLPAPER }

    /** One setting with its choices; a link section opens another screen instead. */
    private static final class Section {
        String title;
        String[] labels, values;
        boolean vertical;
        Supplier<String> get;
        Consumer<String> set;
        BooleanSupplier enabled = () -> true;
        String reason;
        Supplier<String> linkValue;
        Runnable link;
    }

    /** A navigable line: a whole horizontal section, or one choice of a vertical one. */
    private static final class Line {
        final Section s;
        final int choice; // -1 for horizontal rows and links
        RectF rect;
        Line(Section s, int choice) { this.s = s; this.choice = choice; }
    }

    private Mode mode = Mode.OVERVIEW;
    private Chrome chrome;
    private int group;
    private final List<Section> sections = new ArrayList<>();
    private final List<Line> lines = new ArrayList<>();
    private int line, col;
    private float scroll;
    private final HomeRenderer.Focus previewFocus = new HomeRenderer.Focus();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    // Wallpaper picker, for the daytime or the night slot
    private boolean pickingDay;
    private int wallIndex;
    private Look candidate;
    private Bitmap candidateBg;
    private HomeRenderer candidateRenderer;
    private final Runnable loadCandidate = this::loadCandidate;

    SettingsPanel(HomeView home) {
        super(home);
        chrome = new Chrome(home.getContext(), home.fonts, home.look, home.renderer.ink());
    }

    @Override
    void onResume() { refreshChrome(); }

    private void refreshChrome() {
        chrome = new Chrome(home.getContext(), home.fonts, home.look, home.renderer.ink());
    }

    // ---- drawing ----

    @Override
    void draw(Canvas c) {
        home.drawSheetBackground(c);
        switch (mode) {
            case OVERVIEW: drawOverview(c); break;
            case DETAIL: drawDetail(c); break;
            default: drawWallpapers(c);
        }
        drawPreview(c);
    }

    private void drawOverview(Canvas c) {
        chrome.title(c, "Settings");
        for (int i = 0; i < GROUPS.length; i++) {
            RectF r = new RectF(PANE_LEFT, CONTENT_TOP + i * 106, PANE_LEFT + PANE_W, CONTENT_TOP + i * 106 + 100);
            boolean focus = i == group;
            if (focus) chrome.solid(c, r, 20);
            chrome.groupName.setColor(focus ? chrome.solidInk : chrome.ink);
            Gfx.drawText(c, GROUPS[i], r.left + 28, r.top + 10, 48, PANE_W - 120, chrome.groupName);
            chrome.groupValue.setColor(focus ? chrome.solidMute : chrome.mute);
            Gfx.drawText(c, summary(i), r.left + 28, r.top + 58, 34, PANE_W - 120, chrome.groupValue);
            chrome.icon(c, chrome.chevron, r.right - 44, r.centerY(), 30, focus ? chrome.solidMute : chrome.mute);
        }
        chrome.hint.setColor(chrome.mute);
        Gfx.drawText(c, "OK opens a group  ·  Back returns to Home", 140, 968, 36, 0, chrome.hint);
    }

    private String summary(int g) {
        Look l = home.look;
        switch (g) {
            case 0: return l.photo() ? wallName(l.dayWall) + " by day · " + wallName(l.nightWall) + " at night" : cap(l.bg);
            case 1: return cap(l.layout) + " · " + cap(l.apps) + " · Controls " + l.controls;
            case 2: return Fonts.name(l.font);
            case 3: return cap(l.focus);
            case 4: {
                int off = home.host.sleepHours();
                return (l.clock24 ? "24-hour" : "12-hour") + " · StandBy " + standbyLabel(l.standby).toLowerCase()
                        + " · TV off " + (off == 0 ? "never" : "after " + off + "h");
            }
            case 5: return home.apps().size() + (home.apps().size() == 1 ? " app" : " apps");
            default: return "Network, picture, sound and accounts";
        }
    }

    private void drawDetail(Canvas c) {
        chrome.title(c, GROUPS[group]);
        layoutLines();
        c.save();
        c.clipRect(0, CONTENT_TOP - 8, PANE_LEFT + PANE_W + 8, CONTENT_BOTTOM + 40);
        c.translate(0, -scroll);
        Section last = null;
        for (int i = 0; i < lines.size(); i++) {
            Line ln = lines.get(i);
            Section s = ln.s;
            boolean enabled = s.enabled.getAsBoolean();
            if (s != last) {
                chrome.label.setColor(enabled ? chrome.mute : chrome.faint);
                Gfx.drawText(c, s.title, 140, ln.rect.top - 48, 36, PANE_W, chrome.label);
                last = s;
            }
            boolean focused = i == line;
            if (s.link != null) {
                drawChoice(c, ln.rect, s.linkValue.get(), focused, false, enabled, chrome.choice, true);
            } else if (s.vertical) {
                String v = s.values[ln.choice];
                drawChoice(c, ln.rect, s.labels[ln.choice], focused, v.equals(s.get.get()), enabled,
                        fontPaint(s, ln.choice), false);
            } else {
                float gap = 12, w = (ln.rect.width() - gap * (s.labels.length - 1)) / s.labels.length;
                for (int k = 0; k < s.labels.length; k++) {
                    RectF r = new RectF(ln.rect.left + k * (w + gap), ln.rect.top, ln.rect.left + k * (w + gap) + w, ln.rect.bottom);
                    drawChoice(c, r, s.labels[k], focused && k == col, s.values[k].equals(s.get.get()), enabled, chrome.choiceH, false);
                }
            }
            if (!enabled && s.reason != null && (i + 1 == lines.size() || lines.get(i + 1).s != s)) {
                chrome.hint.setColor(chrome.faint);
                Gfx.drawText(c, s.reason, 140, ln.rect.bottom + 8, 36, PANE_W - 28, chrome.hint);
            }
        }
        c.restore();
    }

    /** Typography choices render in their own face. */
    private TextPaint fontPaint(Section s, int choice) {
        if (!"Font".equals(s.title)) return chrome.choice;
        TextPaint p = new TextPaint(chrome.choice);
        p.setTypeface(home.fonts.get(s.values[choice], Fonts.Role.UI));
        return p;
    }

    private void drawChoice(Canvas c, RectF r, String text, boolean focused, boolean applied, boolean enabled,
                            TextPaint p, boolean chevron) {
        if (focused) chrome.solid(c, r, 18);
        int color = focused ? (enabled ? chrome.solidInk : chrome.solidMute) : (enabled ? chrome.ink : chrome.faint);
        boolean compact = p == chrome.choiceH; // four-up rows (the start times) need every pixel
        float x = r.left + (compact ? 16 : 24);
        if (applied) {
            chrome.icon(c, chrome.tick, x + (compact ? 11 : 14), r.centerY(), compact ? 24 : 28, color);
            x += compact ? 30 : 44;
        }
        p.setColor(color);
        Gfx.drawText(c, text, x, r.top + (r.height() - 48) / 2, 48, r.right - x - (chevron ? 60 : compact ? 6 : 16), p);
        if (chevron) chrome.icon(c, chrome.chevron, r.right - 40, r.centerY(), 28, focused ? chrome.solidMute : chrome.mute);
    }

    private void drawWallpapers(Canvas c) {
        chrome.title(c, pickingDay ? "Daytime wallpaper" : "Night wallpaper");
        float rowH = 82;
        float top = CONTENT_TOP - wallScroll(rowH);
        c.save();
        c.clipRect(0, CONTENT_TOP - 8, PANE_LEFT + PANE_W + 8, CONTENT_BOTTOM + 40);
        for (int i = 0; i < home.walls.size(); i++) {
            float y = top + i * (rowH + 4);
            if (y + rowH < CONTENT_TOP - 8 || y > CONTENT_BOTTOM + 40) continue;
            Wallpaper w = home.walls.get(i);
            RectF r = new RectF(PANE_LEFT, y, PANE_LEFT + PANE_W, y + rowH);
            boolean focused = i == wallIndex, applied = w.id.equals(slot());
            if (focused) chrome.solid(c, r, 18);
            Bitmap thumb = home.host.thumb(w);
            RectF sw = new RectF(r.left + 20, r.top + 16, r.left + 20 + 89, r.top + 16 + 50);
            if (thumb != null) {
                Path clip = new Path();
                clip.addRoundRect(sw, 8, 8, Path.Direction.CW);
                c.save();
                c.clipPath(clip);
                c.drawBitmap(thumb, null, sw, paint);
                c.restore();
            }
            int color = focused ? chrome.solidInk : chrome.ink;
            chrome.choice.setColor(color);
            Gfx.drawText(c, w.name, sw.right + 24, r.top + (rowH - 48) / 2, 48, PANE_W - 220, chrome.choice);
            if (applied) chrome.icon(c, chrome.tick, r.right - 40, r.centerY(), 28, color);
        }
        c.restore();
    }

    private float wallScroll(float rowH) {
        float pitch = rowH + 4, visible = CONTENT_BOTTOM - CONTENT_TOP;
        float target = wallIndex * pitch - visible / 2 + rowH / 2;
        return Gfx.clamp(target, 0, Math.max(0, home.walls.size() * pitch - visible));
    }

    /** The committed Home (or the focused wallpaper candidate), scaled into the right pane. */
    private void drawPreview(Canvas c) {
        Path clip = new Path();
        clip.addRoundRect(PREVIEW, 32, 32, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        c.translate(PREVIEW.left, PREVIEW.top);
        c.scale(.48f, .48f);
        boolean showCandidate = mode == Mode.WALLPAPER && candidateBg != null && candidateRenderer != null;
        // No video in the preview: a candidate's backdrop includes its still; Home's own uses the poster.
        Bitmap poster = !showCandidate && home.look.photo() && home.wall.live() ? home.livePoster() : null;
        home.drawHomeScene(c, showCandidate ? candidateBg : home.backdrop(),
                showCandidate ? candidateRenderer : home.renderer, previewFocus, 0, poster, 1);
        c.restore();
        if (!home.look.photo()) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4);
            paint.setColor(Gfx.withAlpha(chrome.ink, .18f));
            c.drawRoundRect(PREVIEW, 32, 32, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    // ---- input ----

    @Override
    boolean key(int keyCode, boolean ok, KeyEvent event) {
        switch (mode) {
            case OVERVIEW: return overviewKey(keyCode, ok);
            case DETAIL: return detailKey(keyCode, ok);
            default: return wallpaperKey(keyCode, ok);
        }
    }

    private boolean overviewKey(int keyCode, boolean ok) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) group = Math.max(0, group - 1);
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) group = Math.min(GROUPS.length - 1, group + 1);
        else if (keyCode == KeyEvent.KEYCODE_BACK) return false;
        else if (ok) openGroup(group);
        return true;
    }

    private void openGroup(int g) {
        if (g == 5) {
            home.push(new EditorPanel(home));
            return;
        }
        if (g == 6) {
            home.host.openAndroidSettings();
            return;
        }
        buildSections(g);
        mode = Mode.DETAIL;
        scroll = 0;
        line = firstEnabled(0, 1);
        col = appliedCol(lines.get(line));
    }

    private boolean detailKey(int keyCode, boolean ok) {
        Line ln = lines.get(line);
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                mode = Mode.OVERVIEW;
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int dir = keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1;
                int next = firstEnabled(line + dir, dir);
                if (next >= 0) {
                    line = next;
                    col = appliedCol(lines.get(line));
                }
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (ln.choice < 0 && ln.s.link == null) col = Math.max(0, col - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (ln.choice < 0 && ln.s.link == null) col = Math.min(ln.s.labels.length - 1, col + 1);
                return true;
            default:
                if (!ok) return true;
                if (ln.s.link != null) {
                    ln.s.link.run();
                } else {
                    String v = ln.s.values[ln.choice >= 0 ? ln.choice : col];
                    if (!v.equals(ln.s.get.get())) {
                        ln.s.set.accept(v);
                        home.host.commitLook();
                        refreshChrome();
                    }
                }
                return true;
        }
    }

    private boolean wallpaperKey(int keyCode, boolean ok) {
        int n = home.walls.size();
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mode = Mode.DETAIL;
            candidateBg = null;
            home.removeCallbacks(loadCandidate);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            int next = Gfx.clampInt(wallIndex + (keyCode == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1), 0, n - 1);
            if (next != wallIndex) {
                wallIndex = next;
                home.removeCallbacks(loadCandidate);
                home.postDelayed(loadCandidate, 220); // wait for focus to settle before decoding
            }
        } else if (ok) {
            String id = home.walls.get(wallIndex).id;
            if (!id.equals(slot())) {
                if (pickingDay) home.look.dayWall = id;
                else home.look.nightWall = id;
                home.host.commitLook();
                refreshChrome();
            }
        }
        return true;
    }

    private void openWallpapers(boolean day) {
        mode = Mode.WALLPAPER;
        pickingDay = day;
        wallIndex = 0;
        for (int i = 0; i < home.walls.size(); i++) if (home.walls.get(i).id.equals(slot())) wallIndex = i;
        candidateBg = null;
    }

    private String slot() { return pickingDay ? home.look.dayWall : home.look.nightWall; }

    /** Name of a saved wallpaper id; one whose scene is not on the TV shows as the fallback. */
    private String wallName(String id) {
        for (Wallpaper w : home.walls) if (w.id.equals(id)) return w.name;
        return home.walls.get(0).name;
    }

    /** "06:00" or "6 AM", following the clock format. */
    private String timeLabel(int minutes) {
        int h = minutes / 60;
        if (home.look.clock24) return String.format(java.util.Locale.ROOT, "%02d:%02d", h, minutes % 60);
        return (h % 12 == 0 ? 12 : h % 12) + (h < 12 ? " AM" : " PM");
    }

    private Section timeChoice(String title, int[] options, Supplier<Integer> get, Consumer<Integer> set) {
        String[] labels = new String[options.length], values = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            labels[i] = timeLabel(options[i]);
            values[i] = Integer.toString(options[i]);
        }
        Section s = choice(title, labels, values, false, () -> Integer.toString(get.get()), v -> set.accept(Integer.parseInt(v)));
        s.enabled = home.look::photo;
        return s;
    }

    private void loadCandidate() {
        Wallpaper w = home.walls.get(wallIndex);
        Look l = home.look.copy();
        l.wall = w.id;
        candidate = l;
        home.host.previewBackdrop(l, Math.round(PREVIEW.width()), Math.round(PREVIEW.height()), bmp -> {
            if (candidate != l || mode != Mode.WALLPAPER) return;
            HomeRenderer r = new HomeRenderer(home.getContext(), home.fonts);
            r.setLook(l, w);
            candidateRenderer = r;
            candidateBg = bmp;
            home.invalidate();
        });
    }

    // ---- sections ----

    private void buildSections(int g) {
        sections.clear();
        Look l = home.look;
        switch (g) {
            case 0: {
                choice("Background", new String[] {"Photo", "Dark", "Light"}, Look.BG, false, () -> l.bg, v -> l.bg = v);
                for (boolean day : new boolean[] {true, false}) {
                    Section wall = new Section();
                    wall.title = day ? "Daytime wallpaper" : "Night wallpaper";
                    wall.linkValue = () -> wallName(day ? l.dayWall : l.nightWall);
                    wall.link = () -> openWallpapers(day);
                    wall.enabled = l::photo;
                    wall.reason = "Wallpapers apply to the Photo background.";
                    sections.add(wall);
                }
                timeChoice("Daytime starts", Look.DAY_STARTS, () -> l.dayStart, v -> l.dayStart = v);
                timeChoice("Night starts", Look.NIGHT_STARTS, () -> l.nightStart, v -> l.nightStart = v);
                Section rot = choice("Rotation",
                        new String[] {"Fixed", "Each time Home opens", "Every 5 minutes", "Every 15 minutes", "Every hour"},
                        Look.ROTATION, true, () -> l.rotation, v -> l.rotation = v);
                rot.enabled = l::photo;
                Section sound = choice("Scene sound", new String[] {"Off", "Low", "Medium", "High"},
                        new String[] {"off", "low", "medium", "high"}, false, () -> l.sceneSound, v -> l.sceneSound = v);
                sound.enabled = () -> (l.photo() && home.wall.live()) || l.liveStandby();
                sound.reason = "Plays with a live wallpaper or the live StandBy scene.";
                break;
            }
            case 1:
                choice("Layout", new String[] {"Dock", "List"}, Look.LAYOUT, false, () -> l.layout, v -> l.layout = v);
                choice("Apps", new String[] {"Icons", "Both", "Text"}, Look.PRESENTATION, false, () -> l.apps, v -> l.apps = v);
                Section controls = choice("Home controls", new String[] {"Icons", "Both", "Text"}, Look.PRESENTATION, false,
                        () -> l.controls, v -> l.controls = v);
                controls.enabled = () -> !l.dock();
                controls.reason = "The Dock's side rail shows icons, naming the focused one.";
                break;
            case 2:
                choice("Font", new String[] {"Manrope", "Geist", "Newsreader"}, Look.FONT, true, () -> l.font, v -> l.font = v);
                break;
            case 3: {
                Section focus = choice("Focus", new String[] {"Glass", "Type"}, Look.FOCUS, false, () -> l.focus, v -> l.focus = v);
                focus.enabled = () -> !l.dock();
                focus.reason = "The Dock lifts and underlines the focused app.";
                break;
            }
            default: {
                choice("Clock format", new String[] {"24-hour", "12-hour"}, new String[] {"24", "12"}, false,
                        () -> l.clock24 ? "24" : "12", v -> l.clock24 = "24".equals(v));
                String[] values = new String[Look.STANDBY.length], labels = new String[Look.STANDBY.length];
                for (int i = 0; i < values.length; i++) {
                    values[i] = Integer.toString(Look.STANDBY[i]);
                    labels[i] = standbyLabel(Look.STANDBY[i]);
                }
                choice("StandBy clock on Home", labels, values, true,
                        () -> Integer.toString(l.standby), v -> l.standby = Integer.parseInt(v));
                choice("StandBy scene", new String[] {"Live", "Still", "Dark"}, Look.STANDBY_SCENE, false,
                        () -> l.standbyScene, v -> l.standbyScene = v);
                Section visible = choice("Scene visibility", new String[] {"20%", "40%", "60%"},
                        new String[] {"20", "40", "60"}, false,
                        () -> Integer.toString(l.standbyPhoto), v -> l.standbyPhoto = Integer.parseInt(v));
                visible.enabled = () -> !"dark".equals(l.standbyScene);
                visible.reason = "Dark shows no scene.";
                Section blur = choice("Scene blur", new String[] {"Sharp", "30%", "60%"},
                        new String[] {"0", "30", "60"}, false,
                        () -> Integer.toString(l.standbyBlur), v -> l.standbyBlur = Integer.parseInt(v));
                blur.enabled = visible.enabled;
                choice("StandBy brightness", new String[] {"Low", "Medium", "Full"}, Look.STANDBY_BRIGHT, false,
                        () -> l.standbyBright, v -> l.standbyBright = v);
                // Not part of the look: the TV's own sleep timer, read live from system settings.
                choice("Turn TV off when idle",
                        new String[] {"After 1 hour", "After 2 hours", "After 4 hours", "After 8 hours", "Never"},
                        new String[] {"1", "2", "4", "8", "0"}, true,
                        () -> Integer.toString(home.host.sleepHours()), v -> home.host.setSleepHours(Integer.parseInt(v)));
            }
        }
        lines.clear();
        for (Section s : sections) {
            if (s.vertical) for (int k = 0; k < s.labels.length; k++) lines.add(new Line(s, k));
            else lines.add(new Line(s, -1));
        }
    }

    private Section choice(String title, String[] labels, String[] values, boolean vertical,
                           Supplier<String> get, Consumer<String> set) {
        Section s = new Section();
        s.title = title;
        s.labels = labels;
        s.values = values;
        s.vertical = vertical;
        s.get = get;
        s.set = set;
        sections.add(s);
        return s;
    }

    /** Positions every line (and keeps the focused one in view). */
    private void layoutLines() {
        float y = CONTENT_TOP;
        Section last = null;
        for (Line ln : lines) {
            if (ln.s != last) {
                if (last != null) y += 32 + (!last.enabled.getAsBoolean() && last.reason != null ? 44 : 0);
                y += 48; // section label
                last = ln.s;
            }
            float h = ln.choice < 0 && ln.s.link == null ? 92 : 78;
            ln.rect = new RectF(PANE_LEFT, y, PANE_LEFT + PANE_W, y + h);
            y += h + 4;
        }
        RectF f = lines.get(line).rect;
        if (f.bottom - scroll > CONTENT_BOTTOM) scroll = f.bottom - CONTENT_BOTTOM;
        if (f.top - 48 - scroll < CONTENT_TOP) scroll = Math.max(0, f.top - 48 - CONTENT_TOP);
    }

    private int firstEnabled(int from, int dir) {
        for (int i = from; i >= 0 && i < lines.size(); i += dir) {
            if (lines.get(i).s.enabled.getAsBoolean()) return i;
        }
        return from == 0 ? 0 : -1;
    }

    private static int appliedCol(Line ln) {
        if (ln.choice >= 0 || ln.s.link != null) return 0;
        String v = ln.s.get.get();
        for (int k = 0; k < ln.s.values.length; k++) if (ln.s.values[k].equals(v)) return k;
        return 0;
    }

    private static String standbyLabel(int minutes) {
        return minutes == 0 ? "Off" : "After " + minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private static String cap(String s) { return Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    @Override
    void onClose() {
        home.removeCallbacks(loadCandidate);
        candidateBg = null;
        candidateRenderer = null;
    }
}
