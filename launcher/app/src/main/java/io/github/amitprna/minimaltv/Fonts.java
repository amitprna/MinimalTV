package io.github.amitprna.minimaltv;

import android.content.res.AssetManager;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/** Variable fonts at CleanFrame's per-role weights (ui, display, clock). */
final class Fonts {
    enum Role { UI, DISPLAY, CLOCK }

    private final AssetManager assets;
    private final Map<String, Typeface> cache = new HashMap<>();

    Fonts(AssetManager assets) { this.assets = assets; }

    static String name(String id) {
        switch (id) {
            case "geist": return "Geist";
            case "newsreader": return "Newsreader";
            default: return "Manrope";
        }
    }

    Typeface get(String id, Role role) {
        String key = id + "/" + role;
        Typeface t = cache.get(key);
        if (t == null) {
            t = new Typeface.Builder(assets, "fonts/" + name(id) + ".ttf")
                    .setFontVariationSettings(axes(id, role))
                    .build();
            cache.put(key, t);
        }
        return t;
    }

    /** A face at an explicit weight (the glass Dock's labels and time line). */
    Typeface weight(String id, int wght) {
        String key = id + "/w" + wght;
        Typeface t = cache.get(key);
        if (t == null) {
            String axes = "'wght' " + wght + ("newsreader".equals(id) ? ", 'opsz' 36" : "");
            t = new Typeface.Builder(assets, "fonts/" + name(id) + ".ttf").setFontVariationSettings(axes).build();
            cache.put(key, t);
        }
        return t;
    }

    /** Settings, All apps and the editor always use Manrope, as in CleanFrame. */
    Typeface chrome(Role role) { return get("manrope", role); }

    private static String axes(String id, Role role) {
        switch (id) {
            case "geist":
                return "'wght' " + (role == Role.UI ? 400 : 300);
            case "newsreader":
                return "'wght' 400, 'opsz' " + (role == Role.DISPLAY ? 72 : role == Role.CLOCK ? 48 : 36);
            default:
                return "'wght' " + (role == Role.DISPLAY ? 600 : 500);
        }
    }
}
