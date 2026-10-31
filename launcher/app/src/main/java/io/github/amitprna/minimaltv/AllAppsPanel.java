package io.github.amitprna.minimaltv;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.KeyEvent;

import java.util.List;

/** All apps: a five-column grid of installed TV apps; OK launches. */
final class AllAppsPanel extends Panel {
    private static final float LEFT = 112, TOP = 232, W = 320, H = 216, PITCH_X = 344, PITCH_Y = 240;
    private static final int COLS = 5, ROWS = 3;

    private final List<Tile> apps;
    private final Chrome chrome;
    private int index;

    AllAppsPanel(HomeView home) {
        super(home);
        apps = home.host.installedApps();
        chrome = new Chrome(home.getContext(), home.fonts, home.look, home.renderer.ink());
    }

    @Override
    void draw(Canvas c) {
        home.drawSheetBackground(c);
        chrome.title(c, "All apps");
        if (apps.isEmpty()) return;
        int firstRow = Math.max(0, index / COLS - (ROWS - 1));
        chrome.row.setTextAlign(Paint.Align.CENTER);
        for (int i = firstRow * COLS; i < Math.min(apps.size(), (firstRow + ROWS) * COLS); i++) {
            float left = LEFT + (i % COLS) * PITCH_X, top = TOP + (i / COLS - firstRow) * PITCH_Y;
            boolean focus = i == index;
            if (focus) chrome.plate(c, new RectF(left, top, left + W, top + H), 32, false);
            Tile t = apps.get(i);
            t.mark.draw(c, left + 100, top + 30 - (focus ? 3 : 0), 120, 72, 58, chrome.ink, focus ? 255 : 224);
            chrome.row.setColor(chrome.ink);
            Gfx.drawText(c, t.label, left + W / 2, top + 114, 44, W - 24, chrome.row);
        }
        chrome.row.setTextAlign(Paint.Align.LEFT);
    }

    @Override
    boolean key(int keyCode, boolean ok, KeyEvent event) {
        int n = apps.size();
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: if (index % COLS > 0) index--; return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (index % COLS < COLS - 1 && index + 1 < n) index++; return true;
            case KeyEvent.KEYCODE_DPAD_UP: if (index >= COLS) index -= COLS; return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: if (index + COLS < n) index += COLS; return true;
            case KeyEvent.KEYCODE_BACK: return false;
            default:
                if (ok && n > 0) {
                    home.pop();
                    home.host.launch(apps.get(index).intent);
                }
                return true;
        }
    }
}
