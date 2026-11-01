package de.harmssi.thorconquer;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/**
 * Self-made abstract emblems (no EA/Westwood artwork).
 *   chevron()  – Thor Conquer mark: a stacked command chevron inside a hexagon
 *   redAlert() – concentric alert rings with a diagonal strike
 *   tiberium() – a cluster of faceted crystal shards
 */
final class Emblem {
    private Emblem() {}

    private static Path hexagon(float cx, float cy, float r) {
        Path p = new Path();
        for (int i = 0; i < 6; i++) {
            double a = Math.toRadians(60 * i - 90);
            float x = cx + (float) (r * Math.cos(a)), y = cy + (float) (r * Math.sin(a));
            if (i == 0) {
                p.moveTo(x, y);
            } else {
                p.lineTo(x, y);
            }
        }
        p.close();
        return p;
    }

    static void chevron(Canvas c, float cx, float cy, float r, int color, Paint p) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.MITER);
        p.setStrokeWidth(r * 0.06f);
        p.setColor(Ui.alpha(color, 0.55f));
        c.drawPath(hexagon(cx, cy, r), p);
        p.setStyle(Paint.Style.FILL);
        p.setColor(color);
        float w = r * 0.62f, t = r * 0.20f;
        for (int i = 0; i < 2; i++) {
            float y = cy - r * 0.30f + i * r * 0.40f;
            Path ch = new Path();
            ch.moveTo(cx - w, y);
            ch.lineTo(cx, y + w * 0.62f);
            ch.lineTo(cx + w, y);
            ch.lineTo(cx + w, y + t);
            ch.lineTo(cx, y + w * 0.62f + t);
            ch.lineTo(cx - w, y + t);
            ch.close();
            p.setColor(i == 0 ? color : Ui.alpha(color, 0.55f));
            c.drawPath(ch, p);
        }
    }

    static void redAlert(Canvas c, float cx, float cy, float r, int color, Paint p) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.05f);
        p.setColor(Ui.alpha(color, 0.35f));
        c.drawCircle(cx, cy, r, p);
        p.setColor(Ui.alpha(color, 0.6f));
        c.drawCircle(cx, cy, r * 0.70f, p);
        p.setStrokeWidth(r * 0.08f);
        p.setColor(color);
        RectF arc = new RectF(cx - r * 0.42f, cy - r * 0.42f, cx + r * 0.42f, cy + r * 0.42f);
        c.drawArc(arc, 200, 140, false, p);
        c.drawArc(arc, 20, 140, false, p);
        // diagonal strike
        p.setStyle(Paint.Style.FILL);
        Path s = new Path();
        s.moveTo(cx + r * 0.10f, cy - r * 0.95f);
        s.lineTo(cx - r * 0.28f, cy + r * 0.06f);
        s.lineTo(cx + r * 0.02f, cy + r * 0.06f);
        s.lineTo(cx - r * 0.12f, cy + r * 0.95f);
        s.lineTo(cx + r * 0.30f, cy - r * 0.12f);
        s.lineTo(cx, cy - r * 0.12f);
        s.close();
        c.drawPath(s, p);
    }

    static void tiberium(Canvas c, float cx, float cy, float r, int color, Paint p) {
        p.setShader(null);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(r * 0.05f);
        p.setColor(Ui.alpha(color, 0.35f));
        c.drawPath(hexagon(cx, cy, r), p);
        p.setStyle(Paint.Style.FILL);
        float[][] shards = {
                // x offset, height, width, tilt
                {0f, 1.10f, 0.26f, 0f},
                {-0.36f, 0.78f, 0.22f, -0.18f},
                {0.36f, 0.84f, 0.22f, 0.16f},
                {-0.62f, 0.48f, 0.17f, -0.30f},
                {0.62f, 0.52f, 0.17f, 0.28f},
        };
        float base = cy + r * 0.52f;
        for (int i = shards.length - 1; i >= 0; i--) {
            float[] sh = shards[i];
            float x = cx + sh[0] * r, h = sh[1] * r, w = sh[2] * r, tilt = sh[3] * r;
            Path s = new Path();
            s.moveTo(x - w / 2, base);
            s.lineTo(x - w / 2 + tilt * 0.6f, base - h * 0.72f);
            s.lineTo(x + tilt, base - h);
            s.lineTo(x + w / 2 + tilt * 0.6f, base - h * 0.72f);
            s.lineTo(x + w / 2, base);
            s.close();
            p.setColor(i == 0 ? color : Ui.alpha(color, 0.45f + 0.1f * (shards.length - i)));
            c.drawPath(s, p);
        }
        p.setColor(Ui.alpha(color, 0.5f));
        c.drawRect(cx - r * 0.8f, base, cx + r * 0.8f, base + r * 0.05f, p);
    }
}
