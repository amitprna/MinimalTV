package io.github.amitprna.minimaltv;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One Home background and its offline ink choices: a CleanFrame photograph (assets/walls.json)
 * or a live video, whose stills are derived on the TV by {@link LiveWall}.
 */
final class Wallpaper {
    final String id;
    final String video;       // scene file of a looping video, or null for a photograph
    final String sound;       // scene file of its ambience, or null
    final String name;
    final boolean light;      // tone-light: the photo needs heavier shading
    final boolean dockQuiet;  // db-quiet: softer focus plate
    final int appInk;         // dock labels and marks
    final boolean tlMist;     // clock corner gets a radial halo
    final int clockInk;
    final String trPol;       // light | mist | dark (top-right controls)
    final int utilInk;
    final float utilRest;
    final float utilPeer;
    final int utilHalo;
    final String brPol;       // light | mist | dark (bottom-right controls on List)
    final int listInk;
    final float listRest;
    final float listPeer;
    final int listHalo;
    final float frostVeil;    // Frost material tint strength
    final int listColumn;     // List layout left-column darkening rung (0-3)

    private Wallpaper(JSONObject o) throws Exception {
        id = o.getString("id");
        video = o.optString("video", null);
        sound = o.optString("sound", null);
        name = o.getString("name");
        light = "light".equals(o.getString("tone"));
        dockQuiet = o.getBoolean("dockQuiet");
        appInk = Color.parseColor(o.getString("appInk"));
        tlMist = "mist".equals(o.getString("tlPol"));
        clockInk = Color.parseColor(o.getString("tlInk"));
        trPol = o.getString("trPol");
        utilInk = Color.parseColor(o.getString("trInk"));
        utilRest = (float) o.getDouble("trRest");
        utilPeer = (float) o.getDouble("trPeer");
        utilHalo = parseRgba(o.getString("trHalo"));
        brPol = o.getString("brPol");
        listInk = Color.parseColor(o.getString("brInk"));
        listRest = (float) o.getDouble("brRest");
        listPeer = (float) o.getDouble("brPeer");
        listHalo = parseRgba(o.getString("brHalo"));
        frostVeil = (float) o.getDouble("frostVeil");
        listColumn = o.getInt("listColumn");
    }

    String photo() { return "walls/" + id + ".jpg"; }
    String frost() { return "frost/" + id + ".jpg"; }
    String field() { return "fields/" + id + ".jpg"; }
    String thumb() { return "thumbs/" + id + ".jpg"; }

    boolean live() { return video != null; }

    /** Live wallpapers on the TV first (rain, the default), then the bundled photographs. */
    static List<Wallpaper> load(Context context) {
        List<Wallpaper> out = new ArrayList<>();
        try {
            for (JSONObject o : LiveWall.entries(context)) out.add(new Wallpaper(o));
        } catch (Exception e) {
            throw new IllegalStateException("live wallpaper entry is invalid", e);
        }
        try (InputStream in = context.getAssets().open("walls.json")) {
            JSONArray arr = new JSONArray(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            for (int i = 0; i < arr.length(); i++) out.add(new Wallpaper(arr.getJSONObject(i)));
        } catch (Exception e) {
            throw new IllegalStateException("walls.json is missing or invalid", e);
        }
        return out;
    }

    /** Parses CSS "rgba(r,g,b,a)". */
    static int parseRgba(String css) {
        String[] p = css.substring(css.indexOf('(') + 1, css.indexOf(')')).split(",");
        return Color.argb(Math.round(Float.parseFloat(p[3].trim()) * 255),
                Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
    }
}
