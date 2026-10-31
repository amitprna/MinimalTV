package io.github.amitprna.minimaltv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Apps on Home: the Home order on the left (OK picks an app up, Up/Down moves it, OK places it;
 * Right reaches Remove) and installed apps not on Home on the right (OK adds).
 */
final class EditorPanel extends Panel {
    private static final float HOME_X = 112, HOME_W = 880, OTHER_X = 1040, OTHER_W = 768;
    private static final float ROWS_TOP = 264, ROW_H = 72, PITCH = 80, REMOVE_W = 304;
    private static final int VISIBLE = 9;
    private static final int NAME = 0, REMOVE = 1, OTHER = 2;

    private final Chrome chrome;
    private final List<Tile> installed;
    private List<Tile> onHome, others;
    private int zone = NAME, homeIndex, otherIndex;
    private boolean lifted;
    private List<String> beforeLift;
    private String status;

    EditorPanel(HomeView home) {
        super(home);
        chrome = new Chrome(home.getContext(), home.fonts, home.look, home.renderer.ink());
        installed = home.host.installedApps();
        rebuild();
    }

    private void rebuild() {
        onHome = new ArrayList<>();
        for (String pkg : home.look.homeApps) {
            for (Tile t : installed) if (t.pkg.equals(pkg)) onHome.add(t);
        }
        others = new ArrayList<>();
        for (Tile t : installed) if (!home.look.homeApps.contains(t.pkg)) others.add(t);
        homeIndex = Gfx.clampInt(homeIndex, 0, Math.max(0, onHome.size() - 1));
        otherIndex = Gfx.clampInt(otherIndex, 0, Math.max(0, others.size() - 1));
    }

    @Override
    void draw(Canvas c) {
        home.drawSheetBackground(c);
        chrome.title(c, "Apps on Home");
        chrome.label.setColor(chrome.faint);
        Gfx.drawText(c, "On Home", 140, 216, 36, 0, chrome.label);
        Gfx.drawText(c, "Not on Home", OTHER_X + 28, 216, 36, 0, chrome.label);

        int first = firstVisible(homeIndex, onHome.size());
        for (int i = first; i < Math.min(onHome.size(), first + VISIBLE); i++) {
            float y = ROWS_TOP + (i - first) * PITCH;
            boolean active = i == homeIndex && zone != OTHER;
            float nameW = active && !lifted ? HOME_W - REMOVE_W - 8 : HOME_W;
            RectF name = new RectF(HOME_X, y, HOME_X + nameW, y + ROW_H);
            if (active && (zone == NAME || lifted)) chrome.plate(c, name, 24, lifted);
            chrome.rowSmall.setTextAlign(Paint.Align.RIGHT);
            chrome.rowSmall.setColor(active ? chrome.ink : chrome.mute);
            c.drawText(Integer.toString(i + 1), HOME_X + 16 + 36, Gfx.baseline(chrome.rowSmall, y + 18, 36), chrome.rowSmall);
            chrome.rowSmall.setTextAlign(Paint.Align.LEFT);
            Tile t = onHome.get(i);
            t.mark.draw(c, HOME_X + 72, y + 14, 56, 44, 36, chrome.ink, 255);
            chrome.row.setColor(chrome.ink);
            Gfx.drawText(c, t.label, HOME_X + 148, y + 14, 44, nameW - 148 - 64, chrome.row);
            if (active && lifted) chrome.icon(c, chrome.updown, name.right - 40, name.centerY(), 34, chrome.ink);
            if (active && !lifted) {
                RectF rm = new RectF(HOME_X + HOME_W - REMOVE_W, y, HOME_X + HOME_W, y + ROW_H);
                boolean f = zone == REMOVE;
                if (f) chrome.solid(c, rm, 24);
                chrome.row.setTextAlign(Paint.Align.CENTER);
                chrome.row.setTextSize(30);
                chrome.row.setColor(f ? chrome.solidInk : chrome.mute);
                c.drawText("Remove from Home", rm.centerX(), Gfx.baseline(chrome.row, y + 14, 44), chrome.row);
                chrome.row.setTextSize(34);
                chrome.row.setTextAlign(Paint.Align.LEFT);
            }
        }

        int firstOther = firstVisible(otherIndex, others.size());
        if (others.isEmpty()) {
            chrome.row.setColor(chrome.faint);
            Gfx.drawText(c, "Every app is on Home", OTHER_X + 28, ROWS_TOP + 14, 44, 0, chrome.row);
        }
        for (int i = firstOther; i < Math.min(others.size(), firstOther + VISIBLE); i++) {
            float y = ROWS_TOP + (i - firstOther) * PITCH;
            RectF r = new RectF(OTHER_X, y, OTHER_X + OTHER_W, y + ROW_H);
            boolean f = zone == OTHER && i == otherIndex;
            if (f) chrome.plate(c, r, 24, false);
            Tile t = others.get(i);
            t.mark.draw(c, OTHER_X + 24, y + 14, 56, 44, 36, chrome.ink, f ? 255 : 210);
            chrome.row.setColor(f ? chrome.ink : chrome.mute);
            Gfx.drawText(c, t.label, OTHER_X + 100, y + 14, 44, OTHER_W - 260, chrome.row);
            if (f) {
                chrome.rowSmall.setTextAlign(Paint.Align.RIGHT);
                chrome.rowSmall.setColor(chrome.ink);
                c.drawText("Add", r.right - 24, Gfx.baseline(chrome.rowSmall, y + 18, 36), chrome.rowSmall);
                chrome.rowSmall.setTextAlign(Paint.Align.LEFT);
            }
        }

        chrome.hint.setColor(status != null ? 0xFFFFD9A8 : chrome.mute);
        String hint = status != null ? status
                : lifted ? "Up/Down moves the app  ·  OK places it  ·  Back cancels"
                : "OK picks an app up to reorder  ·  Right removes  ·  Back closes";
        Gfx.drawText(c, hint, 140, 1000, 36, 0, chrome.hint);
    }

    private static int firstVisible(int index, int n) {
        return Math.max(0, Math.min(index - (VISIBLE - 1) / 2, n - VISIBLE));
    }

    @Override
    boolean key(int keyCode, boolean ok, KeyEvent event) {
        status = null;
        if (lifted) return liftedKey(keyCode, ok);
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                return false;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (zone == OTHER) otherIndex = Math.max(0, otherIndex - 1);
                else homeIndex = Math.max(0, homeIndex - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (zone == OTHER) otherIndex = Math.min(others.size() - 1, otherIndex + 1);
                else homeIndex = Math.min(onHome.size() - 1, homeIndex + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (zone == NAME) zone = REMOVE;
                else if (zone == REMOVE && !others.isEmpty()) zone = OTHER;
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (zone == OTHER) zone = onHome.isEmpty() ? OTHER : REMOVE;
                else if (zone == REMOVE) zone = NAME;
                return true;
            default:
                if (!ok) return true;
                if (zone == NAME && !onHome.isEmpty()) {
                    lifted = true;
                    beforeLift = new ArrayList<>(home.look.homeApps);
                } else if (zone == REMOVE) {
                    remove();
                } else if (zone == OTHER && !others.isEmpty()) {
                    add();
                }
                return true;
        }
    }

    private boolean liftedKey(int keyCode, boolean ok) {
        List<String> order = home.look.homeApps;
        int i = order.indexOf(onHome.get(homeIndex).pkg);
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && homeIndex > 0) {
            swap(order, i, order.indexOf(onHome.get(homeIndex - 1).pkg));
            homeIndex--;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && homeIndex < onHome.size() - 1) {
            swap(order, i, order.indexOf(onHome.get(homeIndex + 1).pkg));
            homeIndex++;
        } else if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Back puts an unplaced app back without saving the move.
            String pkg = onHome.get(homeIndex).pkg;
            order.clear();
            order.addAll(beforeLift);
            lifted = false;
            rebuild();
            for (int k = 0; k < onHome.size(); k++) if (onHome.get(k).pkg.equals(pkg)) homeIndex = k;
            return true;
        } else if (ok) {
            lifted = false;
            home.host.commitLook();
            return true;
        }
        rebuild();
        return true;
    }

    private void remove() {
        if (onHome.size() <= 1) {
            status = "Home needs at least one app.";
            return;
        }
        String pkg = onHome.get(homeIndex).pkg;
        home.look.homeApps.remove(pkg);
        home.host.commitLook();
        rebuild();
        // Focus follows the app into Not on Home, so OK can add it straight back.
        for (int k = 0; k < others.size(); k++) if (others.get(k).pkg.equals(pkg)) otherIndex = k;
        zone = OTHER;
    }

    private void add() {
        if (onHome.size() >= Look.MAX_HOME_APPS) {
            status = "Home holds up to " + Look.MAX_HOME_APPS + " apps.";
            return;
        }
        String pkg = others.get(otherIndex).pkg;
        home.look.homeApps.add(pkg);
        home.host.commitLook();
        rebuild();
        // Focus follows the app to its Remove control, so another OK reverses the addition.
        homeIndex = onHome.size() - 1;
        zone = REMOVE;
    }

    private static void swap(List<String> l, int a, int b) {
        String t = l.get(a);
        l.set(a, l.get(b));
        l.set(b, t);
    }
}
