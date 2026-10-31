package io.github.amitprna.minimaltv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;

import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Bakes everything static on Home into one bitmap: the photo and, for the List layout,
 * CleanFrame's scrims. Frames then only draw glass, text, icons and the moving focus. Any
 * output size works; the canvas is scaled from CleanFrame's 1920 x 1080 stage.
 */
final class Backdrop {
    private Backdrop() {}

    /**
     * @param poster draw a live wallpaper's still too (previews); on Home the video plays beneath
     * @return null when there is nothing to bake (a live wallpaper under the glass Dock)
     */
    static Bitmap compose(Context context, Look look, Wallpaper w, int width, int height, boolean poster)
            throws IOException {
        boolean photo = look.photo() && (!w.live() || poster);
        // The glass Dock adds no scrims: the scene shows at full strength, text carries its own shadow.
        if (look.photo() && look.dock() && !photo) return null;
        Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.scale(width / Gfx.STAGE_W, height / Gfx.STAGE_H);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        if (!look.photo()) {
            c.drawColor(Ink.of(look, w).ground);
            return out;
        }
        if (photo) drawAsset(c, context, w.photo(), new RectF(0, 0, Gfx.STAGE_W, Gfx.STAGE_H), p);
        if (look.dock()) return out;
        shade(c, p, w);
        listShading(c, p, w);
        corner(c, p, w.trPol, w.light, 1500, 104);
        corner(c, p, w.brPol, w.light, 1560, 878);
        return out;
    }

    /** .shade: top darkening. */
    private static void shade(Canvas c, Paint p, Wallpaper w) {
        float a0 = w.light ? .42f : .32f, a1 = w.light ? .20f : .14f, mid = w.light ? 160 : 150;
        Gfx.linearV(c, p, 0, 0, Gfx.STAGE_W, 300,
                new int[] {Gfx.ink(4, 10, 12, a0), Gfx.ink(4, 10, 12, a1), Gfx.ink(4, 10, 12, 0)},
                new float[] {0, mid / 300f, 1});
    }

    /** .ph-dim, .ph-scrim and the masked .ph-col behind the big List names. */
    private static void listShading(Canvas c, Paint p, Wallpaper w) {
        float[] dim = w.light ? new float[] {.34f, .24f, .14f} : new float[] {.26f, .16f, .08f};
        Gfx.linearH(c, p, 0, 0, Gfx.STAGE_W, Gfx.STAGE_H,
                new int[] {Gfx.ink(10, 8, 6, dim[0]), Gfx.ink(10, 8, 6, dim[1]), Gfx.ink(10, 8, 6, dim[2])},
                new float[] {0, .5f, 1});
        Gfx.linearV(c, p, 0, 600, Gfx.STAGE_W, 1080,
                new int[] {Gfx.ink(10, 8, 6, 0), Gfx.ink(10, 8, 6, .26f), Gfx.ink(10, 8, 6, .40f)},
                new float[] {0, .55f, 1});

        float col = w.listColumn >= 3 ? .32f : w.listColumn == 2 || w.light ? .28f : .18f;
        int layer = c.saveLayer(0, 160, 1040, 980, null);
        Gfx.linearH(c, p, 0, 160, 1040, 980,
                new int[] {Gfx.ink(10, 8, 6, col), Gfx.ink(10, 8, 6, col), Gfx.ink(10, 8, 6, 0)},
                new float[] {0, 600 / 1040f, 1});
        // mask: transparent 0, opaque 120px..700px, transparent at 820px
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        p.setColor(Color.WHITE);
        p.setShader(new LinearGradient(0, 160, 0, 980,
                new int[] {Color.TRANSPARENT, Color.BLACK, Color.BLACK, Color.TRANSPARENT},
                new float[] {0, 120 / 820f, 700 / 820f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 160, 1040, 980, p);
        p.setShader(null);
        p.setXfermode(null);
        c.restoreToCount(layer);
    }

    /** .ut-tr / .ut-br: radial dim behind a control corner, strength from its ink class. */
    private static void corner(Canvas c, Paint p, String pol, boolean light, float cx, float cy) {
        if ("dark".equals(pol)) return;
        boolean mist = "mist".equals(pol);
        float a = mist ? (light ? .44f : .34f) : (light ? .30f : .20f);
        float b = mist ? (light ? .28f : .20f) : (light ? .18f : .12f);
        Gfx.radial(c, p, cx, cy, 900, 260, Gfx.ink(10, 8, 6, a), Gfx.ink(10, 8, 6, b));
    }

    static void drawAsset(Canvas c, Context context, String path, RectF dst, Paint p) throws IOException {
        Bitmap b = decode(context, path, 1);
        c.drawBitmap(b, null, dst, p);
        b.recycle();
    }

    /** Stills cut on the TV (files/) win over bundled assets at the same path. */
    static Bitmap decode(Context context, String path, int sample) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        File cached = new File(context.getFilesDir(), path);
        try (InputStream in = cached.exists() ? new FileInputStream(cached) : context.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in, null, o);
        }
    }
}
