package de.harmssi.thorconquer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

/**
 * Thor Conquer design system ("command terminal").
 *
 * Near-black surfaces, quiet panels with 1-px lines, generous spacing, Inter for UI,
 * UPPERCASE micro labels with wide tracking, JetBrains Mono for numbers, 12/8 px radii,
 * thin gradient progress bars. Accent follows the faction: amber (Allied/GDI) or red (Soviet/Nod).
 */
final class Ui {
    private Ui() {}

    // Surfaces
    static final int BG = 0xFF08090B;
    static final int BG_2 = 0xFF0D0F12;
    static final int PANEL = 0xFF121418;
    static final int PANEL_2 = 0xFF181B20;
    static final int LINE = 0xFF252931;
    static final int LINE_2 = 0xFF323844;

    // Text
    static final int TEXT = 0xFFF2F2EF;
    static final int TEXT_2 = 0xFFA7ABB3;
    static final int TEXT_3 = 0xFF6A707B;

    // Accents
    static final int AMBER = 0xFFE8B04A;
    static final int AMBER_2 = 0xFFF3D27E;
    static final int RED = 0xFFE5484D;
    static final int RED_2 = 0xFFF08A6E;
    static final int GREEN = 0xFF4CC38A;
    static final int CORAL = 0xFFD8806A;

    static final float LABEL_TRACKING = 0.32f; // em
    static final float RADIUS = 12f;
    static final float RADIUS_SM = 8f;

    private static Typeface inter, interMedium, interSemi, interBold, mono, monoBold;

    static void init(Context ctx) {
        if (inter != null) {
            return;
        }
        inter = load(ctx, "fonts/Inter-Regular.ttf", Typeface.SANS_SERIF);
        interMedium = load(ctx, "fonts/Inter-Medium.ttf", inter);
        interSemi = load(ctx, "fonts/Inter-SemiBold.ttf", inter);
        interBold = load(ctx, "fonts/Inter-Bold.ttf", inter);
        mono = load(ctx, "fonts/JetBrainsMono-Regular.ttf", Typeface.MONOSPACE);
        monoBold = load(ctx, "fonts/JetBrainsMono-Bold.ttf", mono);
    }

    private static Typeface load(Context ctx, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(ctx.getAssets(), path);
        } catch (Throwable t) {
            return fallback;
        }
    }

    static Typeface inter() { return inter; }
    static Typeface interMedium() { return interMedium; }
    static Typeface interSemi() { return interSemi; }
    static Typeface interBold() { return interBold; }
    static Typeface mono() { return mono; }
    static Typeface monoBold() { return monoBold; }

    static int accent(int faction) {
        return faction == 1 ? RED : AMBER;
    }

    static int accent2(int faction) {
        return faction == 1 ? RED_2 : AMBER_2;
    }

    static int alpha(int color, float a) {
        return (color & 0x00FFFFFF) | (Math.round(a * 255) << 24);
    }

    /** Text paint for an UPPERCASE micro label. */
    static Paint label(float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(interSemi);
        p.setTextSize(size);
        p.setLetterSpacing(LABEL_TRACKING);
        p.setColor(color);
        return p;
    }

    static Paint text(Typeface tf, float size, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        return p;
    }

    /** Panel: filled rounded rect with a 1-px line. */
    static void panel(Canvas c, RectF r, float radius, int fill, int line, Paint scratch) {
        scratch.setStyle(Paint.Style.FILL);
        scratch.setShader(null);
        scratch.setColor(fill);
        c.drawRoundRect(r, radius, radius, scratch);
        scratch.setStyle(Paint.Style.STROKE);
        scratch.setStrokeWidth(1f);
        scratch.setColor(line);
        RectF in = new RectF(r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f);
        c.drawRoundRect(in, radius, radius, scratch);
        scratch.setStyle(Paint.Style.FILL);
    }

    /** Thin gradient progress bar (track + fill). */
    static void bar(Canvas c, RectF r, float fraction, int from, int to, Paint scratch) {
        float rad = r.height() / 2f;
        scratch.setStyle(Paint.Style.FILL);
        scratch.setShader(null);
        scratch.setColor(LINE);
        c.drawRoundRect(r, rad, rad, scratch);
        fraction = Math.max(0f, Math.min(1f, fraction));
        if (fraction > 0f) {
            RectF f = new RectF(r.left, r.top, r.left + r.width() * fraction, r.bottom);
            scratch.setShader(new LinearGradient(r.left, 0, r.right, 0, from, to, Shader.TileMode.CLAMP));
            c.drawRoundRect(f, rad, rad, scratch);
            scratch.setShader(null);
        }
    }

    /** Very faint scanlines (texture only, ~3 % opacity). */
    static void scanlines(Canvas c, float w, float h, Paint scratch) {
        scratch.setShader(null);
        scratch.setStyle(Paint.Style.FILL);
        scratch.setColor(0x08FFFFFF);
        for (float y = 0; y < h; y += 4f) {
            c.drawRect(0, y, w, y + 1f, scratch);
        }
    }

    /** Faint tactical grid. */
    static void grid(Canvas c, float w, float h, float step, Paint scratch) {
        scratch.setShader(null);
        scratch.setStyle(Paint.Style.STROKE);
        scratch.setStrokeWidth(1f);
        scratch.setColor(0x0AFFFFFF);
        for (float x = step; x < w; x += step) {
            c.drawLine(x, 0, x, h, scratch);
        }
        for (float y = step; y < h; y += step) {
            c.drawLine(0, y, w, y, scratch);
        }
        scratch.setStyle(Paint.Style.FILL);
    }
}
