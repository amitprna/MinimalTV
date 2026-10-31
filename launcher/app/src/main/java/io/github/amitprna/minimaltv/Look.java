package io.github.amitprna.minimaltv;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Saved Home appearance and app order (CleanFrame's look axes plus our clock options). */
final class Look {
    static final String[] BG = {"photo", "dark", "light"};
    static final String[] LAYOUT = {"dock", "list"};
    static final String[] PRESENTATION = {"icons", "both", "text"};
    static final String[] FONT = {"manrope", "geist", "newsreader"};
    static final String[] FOCUS = {"glass", "type"};
    static final String[] ROTATION = {"fixed", "open", "5", "15", "60"};
    static final int[] STANDBY = {0, 1, 5, 15};
    static final String[] STANDBY_BRIGHT = {"low", "medium", "full"};
    static final String[] STANDBY_SCENE = {"live", "photo", "dark"};
    static final String[] SCENE_SOUND = {"medium", "off", "low", "high"};
    static final int[] DAY_STARTS = {300, 360, 420, 480};       // minutes after midnight
    static final int[] NIGHT_STARTS = {1020, 1080, 1140, 1200};
    static final int MAX_HOME_APPS = 9;

    String bg = "photo";
    String wall; // the wallpaper showing now: dayWall or nightWall, from the time of day
    String dayWall = LiveWall.WATERFALL, nightWall = LiveWall.RAIN;
    int dayStart = 360, nightStart = 1080; // daytime is [dayStart, nightStart)
    String layout = "dock";
    String apps = "both";
    String controls = "icons";
    String font = "manrope";
    String focus = "glass";
    boolean clock24 = true;
    String rotation = "fixed";
    int standby = 1; // minutes idle on Home before the StandBy clock; 0 = never
    String standbyScene = "live"; // live rain video (default), the Home photo, or plain dark
    int standbyPhoto = 40; // % of the scene visible through StandBy's dark veil
    int standbyBlur = 30;  // % blur of the scene behind StandBy (0, 30, 60)
    String sceneSound = "medium"; // the live scene's ambience: off | low | medium | high
    String standbyBright = "medium";
    List<String> homeApps = new ArrayList<>(Arrays.asList("org.videolan.vlc", "com.google.android.youtube.tv", "com.netflix.ninja")); // missing apps are skipped

    boolean photo() { return "photo".equals(bg); }
    boolean dock() { return "dock".equals(layout); }
    boolean glass() { return "glass".equals(focus); }

    static Look load(SharedPreferences p, String defaultWall) {
        Look l = new Look();
        l.bg = pick(p.getString("bg", l.bg), BG);
        l.wall = p.getString("wall", defaultWall);
        l.layout = pick(p.getString("layout", l.layout), LAYOUT);
        l.apps = pick(p.getString("apps", l.apps), PRESENTATION);
        l.controls = pick(p.getString("controls", l.controls), PRESENTATION);
        l.font = pick(p.getString("font", l.font), FONT);
        l.focus = pick(p.getString("focus", l.focus), FOCUS);
        l.dayWall = p.getString("dayWall", l.dayWall);
        l.nightWall = p.getString("nightWall", l.nightWall);
        l.dayStart = p.getInt("dayStart", l.dayStart);
        l.nightStart = p.getInt("nightStart", l.nightStart);
        l.clock24 = p.getBoolean("clock24", l.clock24);
        l.rotation = pick(p.getString("rotation", l.rotation), ROTATION);
        l.standby = p.getInt("standby", l.standby);
        l.standbyScene = pick(p.getString("standbyScene", l.standbyScene), STANDBY_SCENE);
        l.standbyPhoto = p.getInt("standbyPhoto", l.standbyPhoto);
        if (l.standbyPhoto <= 0) l.standbyPhoto = 40; // "Dark" is now a scene, not a visibility
        l.standbyBlur = p.getInt("standbyBlur", l.standbyBlur);
        l.sceneSound = pick(p.getString("sceneSound", l.sceneSound), SCENE_SOUND);
        l.standbyBright = pick(p.getString("standbyBright", l.standbyBright), STANDBY_BRIGHT);
        String apps = p.getString("homeApps", null);
        if (apps != null && !apps.isEmpty()) l.homeApps = new ArrayList<>(Arrays.asList(apps.split(",")));
        return l;
    }

    void save(SharedPreferences p) {
        p.edit()
                .putString("bg", bg).putString("wall", wall).putString("layout", layout)
                .putString("apps", apps).putString("controls", controls).putString("font", font)
                .putString("focus", focus).putString("dayWall", dayWall).putString("nightWall", nightWall)
                .putInt("dayStart", dayStart).putInt("nightStart", nightStart)
                .putBoolean("clock24", clock24).putString("rotation", rotation).putInt("standby", standby)
                .putString("standbyScene", standbyScene).putInt("standbyPhoto", standbyPhoto)
                .putInt("standbyBlur", standbyBlur).putString("sceneSound", sceneSound).putString("standbyBright", standbyBright)
                .putString("homeApps", String.join(",", homeApps))
                .apply();
    }

    Look copy() {
        Look l = new Look();
        l.bg = bg; l.wall = wall; l.layout = layout; l.apps = apps; l.controls = controls; l.font = font;
        l.focus = focus; l.dayWall = dayWall; l.nightWall = nightWall; l.dayStart = dayStart;
        l.nightStart = nightStart; l.clock24 = clock24; l.rotation = rotation; l.standby = standby;
        l.standbyScene = standbyScene; l.standbyPhoto = standbyPhoto; l.standbyBright = standbyBright;
        l.standbyBlur = standbyBlur; l.sceneSound = sceneSound;
        l.homeApps = new ArrayList<>(homeApps);
        return l;
    }

    /** Everything baked into the backdrop bitmap; a change means re-composing it. */
    String backdropKey() {
        return bg + "|" + wall + "|" + layout;
    }

    boolean isDay() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        int m = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE);
        return dayStart < nightStart ? m >= dayStart && m < nightStart : m >= dayStart || m < nightStart;
    }

    /** Points wall at the day or night wallpaper for the current time; true if it changed. */
    boolean applySchedule() {
        String w = isDay() ? dayWall : nightWall;
        if (w.equals(wall)) return false;
        wall = w;
        return true;
    }

    /** Milliseconds until the next day/night switch. */
    long msToNextSwitch() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        long ms = ((now.get(java.util.Calendar.HOUR_OF_DAY) * 60L + now.get(java.util.Calendar.MINUTE)) * 60
                + now.get(java.util.Calendar.SECOND)) * 1000 + now.get(java.util.Calendar.MILLISECOND);
        long best = Long.MAX_VALUE;
        for (int start : new int[] {dayStart, nightStart}) {
            long d = start * 60_000L - ms;
            if (d <= 0) d += 24 * 3_600_000L;
            best = Math.min(best, d);
        }
        return best;
    }

    boolean liveStandby() { return "live".equals(standbyScene); }

    /** Ambience volume relative to the TV's volume: kept low, it is background rain. */
    float sceneVolume() {
        switch (sceneSound) {
            case "low": return .12f;
            case "medium": return .25f;
            case "high": return .45f;
            default: return 0f;
        }
    }

    /** Photo StandBy needs the Photo background; otherwise it falls back to dark. */
    boolean photoStandby() { return "photo".equals(standbyScene) && photo(); }

    /** Window brightness override while StandBy shows (-1 keeps the TV's own brightness). */
    float standbyBrightness() {
        switch (standbyBright) {
            case "low": return .12f;
            case "medium": return .40f;
            default: return -1f;
        }
    }

    private static String pick(String value, String[] allowed) {
        for (String a : allowed) if (a.equals(value)) return value;
        return allowed[0];
    }
}
