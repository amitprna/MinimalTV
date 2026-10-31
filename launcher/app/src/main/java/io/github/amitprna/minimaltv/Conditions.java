package io.github.amitprna.minimaltv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * What StandBy shows besides the time: the 5-day forecast and air quality for a place
 * (Open-Meteo, no key) and the room's temperature and humidity from a Tuya Wi-Fi sensor
 * through Tuya Cloud. Private settings live in files pushed to the TV, never in the app:
 * files/location.json {"name","lat","lon"} (else no forecast) and files/tuya.json
 * {"clientId","clientSecret","deviceId","endpoint"} (else no room reading).
 *
 * Readings are cached in preferences so the screensaver shows them at once, and refreshed
 * only while StandBy is on screen.
 */
final class Conditions {
    /** One forecast day: label ("Today", "Fri"), icon name, high and low in °C. */
    static final class Day {
        final String label, icon;
        final int hi, lo;

        Day(String label, String icon, int hi, int lo) {
            this.label = label;
            this.icon = icon;
            this.hi = hi;
            this.lo = lo;
        }
    }

    private static final String TAG = "TvHome";
    private static final long WEATHER_EVERY = 30 * 60_000L, SENSOR_EVERY = 10 * 60_000L, CHECK_EVERY = 5 * 60_000L;

    private static Conditions instance;

    static synchronized Conditions get(Context context) {
        if (instance == null) instance = new Conditions(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Runnable> listeners = new CopyOnWriteArraySet<>();
    private final Runnable tick = this::tick;
    private int users;

    private volatile List<Day> days = Collections.emptyList();
    private volatile long weatherAt;
    private volatile int aqi = -1;
    private volatile float indoorT = Float.NaN, indoorH = Float.NaN;
    private volatile long sensorAt;
    private boolean fetching;
    private String place; // null until location.json gives one
    private double lat, lon;

    private Conditions(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("conditions", Context.MODE_PRIVATE);
        try {
            days = parseDays(new JSONObject(prefs.getString("weather", "{}")));
        } catch (Exception ignored) {
            // no cache yet
        }
        weatherAt = prefs.getLong("weatherAt", 0);
        aqi = prefs.getInt("aqi", -1);
        indoorT = prefs.getFloat("indoorT", Float.NaN);
        indoorH = prefs.getFloat("indoorH", Float.NaN);
        sensorAt = prefs.getLong("sensorAt", 0);
        File where = new File(context.getExternalFilesDir(null), "location.json");
        if (where.isFile()) {
            try {
                JSONObject o = new JSONObject(new String(Files.readAllBytes(where.toPath()), StandardCharsets.UTF_8));
                lat = o.getDouble("lat");
                lon = o.getDouble("lon");
                place = o.optString("name", "");
            } catch (Exception e) {
                Log.w(TAG, "location.json unreadable; no forecast", e);
            }
        }
    }

    List<Day> days() { return place != null ? days : Collections.emptyList(); }

    /** The forecast's place name (top-left of StandBy), or null when no location is set. */
    String place() { return place; }

    /** US AQI for the place (Open-Meteo air quality), or -1 when unknown or older than a day. */
    int aqi() { return place != null && fresh(weatherAt, 24) ? aqi : -1; }

    /** Indoor temperature, or NaN when the sensor has never answered (or the last reading is a day old). */
    float indoorTemp() { return fresh(sensorAt, 24) ? indoorT : Float.NaN; }

    float indoorHumidity() { return fresh(sensorAt, 24) ? indoorH : Float.NaN; }

    private static boolean fresh(long at, int hours) { return System.currentTimeMillis() - at < hours * 3_600_000L; }

    void listen(Runnable r) { listeners.add(r); }

    void unlisten(Runnable r) { listeners.remove(r); }

    /** StandBy (or the screensaver) is showing: refresh now and every few minutes until stop(). */
    void start() {
        if (users++ == 0) tick();
    }

    void stop() {
        if (users > 0 && --users == 0) main.removeCallbacks(tick);
    }

    private void tick() {
        refresh();
        main.removeCallbacks(tick);
        if (users > 0) main.postDelayed(tick, CHECK_EVERY);
    }

    /** Fetches whatever is stale, off the main thread. */
    void refresh() {
        long now = System.currentTimeMillis();
        boolean weather = place != null && (now - weatherAt > WEATHER_EVERY || aqi < 0), sensor = now - sensorAt > SENSOR_EVERY;
        if (fetching || (!weather && !sensor)) return;
        fetching = true;
        worker.execute(() -> {
            boolean changed = false;
            if (weather) changed |= fetchWeather();
            if (sensor) changed |= fetchSensor();
            boolean notify = changed;
            main.post(() -> {
                fetching = false;
                if (notify) for (Runnable r : listeners) r.run();
            });
        });
    }

    // ---- Open-Meteo ----

    private boolean fetchWeather() {
        try {
            String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=5", lat, lon);
            JSONObject json = new JSONObject(get(url, null));
            List<Day> parsed = parseDays(json);
            if (parsed.isEmpty()) return false;
            days = parsed;
            try {
                String air = String.format(Locale.ROOT, "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=%.4f"
                        + "&longitude=%.4f&current=us_aqi&timezone=auto", lat, lon);
                aqi = new JSONObject(get(air, null)).getJSONObject("current").getInt("us_aqi");
            } catch (Exception e) {
                Log.w(TAG, "air quality unavailable", e);
            }
            weatherAt = System.currentTimeMillis();
            prefs.edit().putString("weather", json.toString()).putLong("weatherAt", weatherAt).putInt("aqi", aqi).apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "weather unavailable", e);
            return false;
        }
    }

    private static List<Day> parseDays(JSONObject json) throws Exception {
        JSONObject d = json.optJSONObject("daily");
        if (d == null) return Collections.emptyList();
        JSONArray time = d.getJSONArray("time"), code = d.getJSONArray("weather_code");
        JSONArray hi = d.getJSONArray("temperature_2m_max"), lo = d.getJSONArray("temperature_2m_min");
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT), out = new SimpleDateFormat("EEE", Locale.getDefault());
        String today = in.format(new java.util.Date());
        List<Day> days = new ArrayList<>();
        for (int i = 0; i < time.length(); i++) {
            String date = time.getString(i);
            if (date.compareTo(today) < 0) continue; // a cached forecast from an earlier day
            String label = date.equals(today) ? "Today" : out.format(in.parse(date));
            days.add(new Day(label, icon(code.getInt(i)), (int) Math.round(hi.getDouble(i)), (int) Math.round(lo.getDouble(i))));
        }
        return days;
    }

    /** WMO weather code to one of the wx_* icons. */
    static String icon(int code) {
        if (code == 0) return "sun";
        if (code <= 2) return "partly";
        if (code == 3) return "cloud";
        if (code == 45 || code == 48) return "fog";
        if (code >= 51 && code <= 57) return "drizzle";
        if ((code >= 61 && code <= 67)) return "rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "snow";
        if (code >= 80 && code <= 82) return "showers";
        if (code >= 95) return "storm";
        return "cloud";
    }

    // ---- Tuya Cloud ----

    private boolean fetchSensor() {
        File config = new File(context.getExternalFilesDir(null), "tuya.json");
        if (!config.isFile()) return false;
        try {
            JSONObject c = new JSONObject(new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8));
            String id = c.getString("clientId"), secret = c.getString("clientSecret"), device = c.getString("deviceId");
            String host = "https://" + c.optString("endpoint", "openapi.tuyaus.com");

            String tokenPath = "/v1.0/token?grant_type=1";
            JSONObject token = new JSONObject(get(host + tokenPath, tuyaHeaders(id, secret, "", tokenPath)));
            if (!token.optBoolean("success")) throw new IllegalStateException("token: " + token.optString("msg"));
            String access = token.getJSONObject("result").getString("access_token");

            String statusPath = "/v1.0/devices/" + device + "/status";
            JSONObject status = new JSONObject(get(host + statusPath, tuyaHeaders(id, secret, access, statusPath)));
            if (!status.optBoolean("success")) throw new IllegalStateException("status: " + status.optString("msg"));

            float t = Float.NaN, h = Float.NaN;
            JSONArray items = status.getJSONArray("result");
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.getJSONObject(i);
                String code = it.optString("code");
                Object value = it.opt("value");
                if (!(value instanceof Number)) continue; // battery state, switches and the like
                double v = ((Number) value).doubleValue();
                // Many Tuya sensors report tenths (263 = 26.3 °C).
                if (code.equals("va_temperature") || code.equals("temp_current") || code.equals("temperature")) t = (float) (v > 90 ? v / 10 : v);
                else if (code.equals("va_humidity") || code.equals("humidity_value") || code.equals("humidity")) h = (float) (v > 100 ? v / 10 : v);
            }
            if (Float.isNaN(t)) return false;
            indoorT = t;
            indoorH = h;
            sensorAt = System.currentTimeMillis();
            prefs.edit().putFloat("indoorT", t).putFloat("indoorH", h).putLong("sensorAt", sensorAt).apply();
            return true;
        } catch (Exception e) {
            Log.w(TAG, "sensor unavailable", e);
            return false;
        }
    }

    /** Tuya OpenAPI request signature (HMAC-SHA256 over client id, token, time and the request). */
    private static String[][] tuyaHeaders(String clientId, String secret, String token, String path) throws Exception {
        String t = Long.toString(System.currentTimeMillis());
        String emptyBody = hex(MessageDigest.getInstance("SHA-256").digest(new byte[0]));
        String toSign = clientId + token + t + "GET\n" + emptyBody + "\n\n" + path;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sign = hex(mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8))).toUpperCase(Locale.ROOT);
        List<String[]> h = new ArrayList<>();
        h.add(new String[] {"client_id", clientId});
        h.add(new String[] {"sign", sign});
        h.add(new String[] {"t", t});
        h.add(new String[] {"sign_method", "HMAC-SHA256"});
        if (!token.isEmpty()) h.add(new String[] {"access_token", token});
        return h.toArray(new String[0][]);
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    private static String get(String url, String[][] headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            if (headers != null) for (String[] h : headers) conn.setRequestProperty(h[0], h[1]);
            try (InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }
}
