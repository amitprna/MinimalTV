package io.github.amitprna.minimaltv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

/** Panel typography and controls (CleanFrame sheets always use Manrope). */
final class Chrome {
    final int ink, mute, faint, solid, solidInk, solidMute;
    final TextPaint title, groupName, groupValue, label, choice, choiceH, hint, row, rowSmall;
    final Drawable chevron, tick, updown;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean photo;
    private final Ink homeInk;

    Chrome(Context context, Fonts fonts, Look look, Ink homeInk) {
        this.homeInk = homeInk;
        photo = look.photo();
        boolean light = "light".equals(look.bg);
        ink = light ? Ink.CHARCOAL : Ink.PAPER;
        mute = Gfx.withAlpha(ink, light ? .70f : .76f);
        faint = Gfx.withAlpha(ink, light ? .52f : .58f);
        solid = light ? Ink.CHARCOAL : 0xFFF3F3EF;
        solidInk = light ? 0xFFF2EFE8 : 0xFF141618;
        solidMute = Gfx.withAlpha(solidInk, .68f);

        android.graphics.Typeface ui = fonts.chrome(Fonts.Role.UI);
        title = Gfx.text(ui, 56, -1.6f, ink);
        groupName = Gfx.text(ui, 40, -.6f, ink);
        groupValue = Gfx.text(ui, 26, 0, mute);
        label = Gfx.text(ui, 28, -.2f, mute);
        choice = Gfx.text(ui, 38, -.5f, ink);
        choiceH = Gfx.text(ui, 34, -.4f, ink);
        hint = Gfx.text(ui, 26, 0, mute);
        row = Gfx.text(ui, 34, -.5f, ink);
        rowSmall = Gfx.text(ui, 28, 0, mute);

        chevron = context.getDrawable(R.drawable.chevron).mutate();
        tick = context.getDrawable(R.drawable.tick).mutate();
        updown = context.getDrawable(R.drawable.updown).mutate();
    }

    void title(Canvas c, String text) {
        title.setColor(ink);
        Gfx.drawText(c, text, 140, 72, 64, 1200, title);
    }

    /** Solid pill used for focused rows and buttons. */
    void solid(Canvas c, RectF r, float radius) {
        paint.setShader(null);
        paint.setColor(solid);
        c.drawRoundRect(r, radius, radius, paint);
    }

    /** CleanFrame's glass focus plate for app grids and lists. */
    void plate(Canvas c, RectF r, float radius, boolean lifted) {
        if (photo) Gfx.glassPlate(c, paint, r, radius, 1, lifted ? Gfx.PLATE_LIFT : Gfx.PLATE, .58f);
        else Gfx.flatPlate(c, paint, r, radius, lifted ? Gfx.withAlpha(homeInk.plateFlat, .15f) : homeInk.plateFlat, homeInk.plateEdge, 1);
    }

    void icon(Canvas c, Drawable d, float cx, float cy, float size, int color) {
        int s = Math.round(size), l = Math.round(cx - size / 2), t = Math.round(cy - size / 2);
        d.setBounds(l, t, l + s, t + s);
        d.setTint(color);
        d.setAlpha(255);
        d.draw(c);
    }

    void fill(Canvas c, RectF r, float radius, int color) {
        paint.setShader(null);
        paint.setColor(color);
        c.drawRoundRect(r, radius, radius, paint);
    }
}
