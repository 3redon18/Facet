package com.facet.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.io.File;

/** Face.Pen on an Android Canvas. */
final class AndroidPen implements Face.Pen {
    private static Typeface[] faces;
    private static Bitmap photo;
    private static long photoStamp = -1;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Canvas c;
    private int w, h;
    private final Context ctx;

    AndroidPen(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        if (faces == null) {
            faces = new Typeface[12];
            faces[0] = Typeface.create("sans-serif-light", Typeface.NORMAL);
            faces[1] = Typeface.create(Typeface.SERIF, Typeface.NORMAL);
            faces[2] = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
            try { faces[3] = Typeface.createFromAsset(this.ctx.getAssets(), "silkscreen.ttf"); } catch (Exception e) { faces[3] = Typeface.MONOSPACE; }
            faces[4] = Typeface.create("sans-serif", Typeface.NORMAL);
            faces[5] = Typeface.create("sans-serif-medium", Typeface.NORMAL);
            faces[6] = Typeface.create("sans-serif-black", Typeface.NORMAL);
            faces[7] = Typeface.create("sans-serif-thin", Typeface.NORMAL);
            faces[8] = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
            faces[9] = Typeface.create("casual", Typeface.NORMAL);
            faces[10] = Typeface.create("cursive", Typeface.NORMAL);
            faces[11] = Typeface.create(Typeface.SERIF, Typeface.BOLD);
        }
    }

    void begin(Canvas c, int w, int h) {
        this.c = c; this.w = w; this.h = h;
    }

    static File photoFile(Context ctx) {
        return new File(ctx.getFilesDir(), "background.jpg");
    }

    static void forgetPhoto() {
        photo = null;
        photoStamp = -1;
    }

    public int w() { return w; }
    public int h() { return h; }

    public void gradient(int top, int bottom) {
        fill.setShader(new LinearGradient(0, 0, 0, h, 0xFF000000 | top, 0xFF000000 | bottom, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
    }

    public boolean photo(int dim) {
        File f = photoFile(ctx);
        if (!f.exists()) return false;
        if (photo == null || photoStamp != f.lastModified()) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            photo = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            photoStamp = f.lastModified();
            if (photo == null) return false;
        }
        float k = Math.max(w / (float) photo.getWidth(), h / (float) photo.getHeight());
        float pw = photo.getWidth() * k, ph = photo.getHeight() * k;
        Paint bp = new Paint(Paint.FILTER_BITMAP_FLAG);
        c.drawBitmap(photo, new Rect(0, 0, photo.getWidth(), photo.getHeight()),
                new RectF((w - pw) / 2, (h - ph) / 2, (w + pw) / 2, (h + ph) / 2), bp);
        fill.setColor((dim * 255 / 100) << 24);
        c.drawRect(0, 0, w, h, fill);
        return true;
    }

    public void text(String s, float x, float base, float size, int font, int argb, int align) {
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(faces[font]);
        paint.setTextSize(size);
        paint.setColor(argb);
        paint.setTextAlign(align == 0 ? Paint.Align.CENTER : align < 0 ? Paint.Align.LEFT : Paint.Align.RIGHT);
        c.drawText(s, x, base, paint);
    }

    /** Clear glass: a soft shadow, a see-through frosted fill that fades down, and a bright rim. */
    public void glass(String s, float x, float base, float size, int font, int align) {
        paint.setTypeface(faces[font]);
        paint.setTextSize(size);
        paint.setTextAlign(align == 0 ? Paint.Align.CENTER : align < 0 ? Paint.Align.LEFT : Paint.Align.RIGHT);
        float top = base - size * 0.75f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x26FFFFFF);
        paint.setShadowLayer(size * 0.07f, 0, size * 0.035f, 0x55000000);
        c.drawText(s, x, base, paint);
        paint.clearShadowLayer();
        paint.setShader(new LinearGradient(0, top, 0, base, 0x80FFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP));
        c.drawText(s, x, base, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, size * 0.013f));
        paint.setShader(new LinearGradient(0, top, 0, base, 0xFFFFFFFF, 0x66FFFFFF, Shader.TileMode.CLAMP));
        c.drawText(s, x, base, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
    }

    static Typeface face(Context ctx, int font) {
        new AndroidPen(ctx);
        return faces[font];
    }

    public float measure(String s, float size, int font) {
        paint.setTypeface(faces[font]);
        paint.setTextSize(size);
        return paint.measureText(s);
    }

    public void circle(float cx, float cy, float r, int argb) {
        fill.setColor(argb);
        c.drawCircle(cx, cy, r, fill);
    }

    public void roundRect(float l, float t, float r, float b, float rad, int argb) {
        fill.setColor(argb);
        c.drawRoundRect(new RectF(l, t, r, b), rad, rad, fill);
    }

    public void icon(Object icon, float l, float t, float size, int argb) {
        if (icon instanceof Bitmap) {
            Paint ip = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
            ip.setColorFilter(new PorterDuffColorFilter(argb, PorterDuff.Mode.SRC_IN));
            Bitmap b = (Bitmap) icon;
            c.drawBitmap(b, new Rect(0, 0, b.getWidth(), b.getHeight()), new RectF(l, t, l + size, t + size), ip);
        } else {
            fill.setColor(argb);
            c.drawCircle(l + size / 2, t + size / 2, size * 0.3f, fill);
        }
    }

    public void rect(float l, float t, float r, float b, int argb) {
        fill.setColor(argb);
        c.drawRect(l, t, r, b, fill);
    }
}
