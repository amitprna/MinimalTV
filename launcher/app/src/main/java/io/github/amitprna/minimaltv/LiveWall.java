package io.github.amitprna.minimaltv;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The live wallpapers: looping silent videos with an ambience track each. The media is pushed
 * to the TV (Android/data/io.github.amitprna.minimaltv/files/scenes/) rather than bundled, so the APK stays
 * small; a bundled asset at scenes/<name> still works as a fallback.
 *
 * Home's glass needs stills (poster, blurred field), so they are cut once from a video frame on
 * the TV and cached in files/, beside the bundled photos' asset paths, and cut again whenever
 * the video is replaced. Nothing blurs at runtime after that.
 */
final class LiveWall {
    static final String RAIN = "live-rain", WATERFALL = "live-waterfall", RIVER = "live-river";
    /** The original live wallpaper; older saved looks refer to it. */
    static final String ID = RAIN;

    private LiveWall() {}

    /** Every live scene: id, name, video, ambience, and whether it is a bright daytime scene. */
    private static final String[][] SCENES = {
            {RAIN, "Rain room", "rain-room.mp4", "rain.ogg", "dark"},
            {WATERFALL, "Waterfall", "waterfall.mp4", "waterfall.ogg", "light"},
            {RIVER, "Forest river", "river.mp4", "river.ogg", "light"},
    };

    /** Entries in walls.json's format for the scenes whose video is on the TV. */
    static List<JSONObject> entries(Context context) throws Exception {
        List<JSONObject> out = new ArrayList<>();
        for (String[] s : SCENES) {
            if (!available(context, s[2])) continue;
            out.add(new JSONObject()
                    .put("id", s[0]).put("name", s[1]).put("video", s[2]).put("sound", s[3])
                    .put("tone", s[4]).put("dockQuiet", false).put("frostVeil", .20).put("listColumn", 2)
                    .put("appInk", "#f6f1e7").put("tlPol", "light").put("tlInk", "#f6f1e7")
                    .put("trPol", "light").put("trInk", "#f6f1e7").put("trRest", .66).put("trPeer", .46)
                    .put("trHalo", "rgba(14,10,6,.38)")
                    .put("brPol", "light").put("brInk", "#f6f1e7").put("brRest", .66).put("brPeer", .46)
                    .put("brHalo", "rgba(14,10,6,.38)"));
        }
        return out;
    }

    /** Where pushed scene media lives. */
    static File file(Context context, String name) {
        return new File(context.getExternalFilesDir("scenes"), name);
    }

    static boolean available(Context context, String name) {
        if (file(context, name).isFile()) return true;
        try (AssetFileDescriptor ignored = context.getAssets().openFd("scenes/" + name)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static void setDataSource(Context context, MediaPlayer mp, String name) throws IOException {
        File f = file(context, name);
        if (f.isFile()) {
            mp.setDataSource(f.getPath());
            return;
        }
        try (AssetFileDescriptor fd = context.getAssets().openFd("scenes/" + name)) {
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
        }
    }

    /** Cuts the stills if they are missing or older than the video. Runs on a worker thread. */
    static void ensure(Context context, Wallpaper w) throws IOException {
        File poster = new File(context.getFilesDir(), w.photo());
        File video = file(context, w.video);
        if (poster.exists() && poster.lastModified() >= video.lastModified()) return;

        MediaMetadataRetriever r = new MediaMetadataRetriever();
        Bitmap frame;
        try {
            if (video.isFile()) {
                r.setDataSource(video.getPath());
            } else {
                try (AssetFileDescriptor fd = context.getAssets().openFd("scenes/" + w.video)) {
                    r.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
                }
            }
            frame = r.getScaledFrameAtTime(2_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 1920, 1080);
        } finally {
            r.release();
        }
        if (frame == null) throw new IOException("no frame in " + w.video);
        Bitmap full = Bitmap.createScaledBitmap(frame, 1920, 1080, true);

        // Cheap, smooth blur: shrink hard, then grow back in two filtered steps.
        save(context, w.field(), Bitmap.createScaledBitmap(soften(full, 48), 960, 540, true));
        save(context, w.frost(), Bitmap.createScaledBitmap(soften(full, 96), 960, 540, true));
        save(context, w.thumb(), Bitmap.createScaledBitmap(full, 192, 108, true));
        save(context, w.photo(), full); // last: its presence marks the set complete
    }

    private static Bitmap soften(Bitmap src, int factor) {
        int w = src.getWidth(), h = src.getHeight();
        Bitmap tiny = Bitmap.createScaledBitmap(src, Math.max(1, w / factor), Math.max(1, h / factor), true);
        Bitmap mid = Bitmap.createScaledBitmap(tiny, Math.max(1, w / 8), Math.max(1, h / 8), true);
        return Bitmap.createScaledBitmap(mid, w, h, true);
    }

    private static void save(Context context, String path, Bitmap b) throws IOException {
        File f = new File(context.getFilesDir(), path);
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        try (FileOutputStream out = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 92, out);
        }
    }
}
