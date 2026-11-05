package de.harmssi.thorconquer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command deck for the Thor's bottom screen (display 4, 1240x1080).
 *
 * Header (credits, power, status) · framed radar · REPAIR / SELL / MENU · two build columns.
 * All data is a snapshot from the engine (common/thor.cpp); every action goes back as a command
 * and runs through the same engine code as a click on the original sidebar or radar.
 *
 * Layout is designed on a 1240x1080 grid and scaled to the actual view size.
 */
public class ThorDeck extends View {
    // States as THOR_STATE_* in common/thor.h
    static final int ST_AVAILABLE = 0, ST_BUILDING = 1, ST_ON_HOLD = 2, ST_READY = 3, ST_DISABLED = 4, ST_CHARGING = 5;
    static final int FL_RADAR = 1, FL_REPAIR = 2, FL_SELL = 4, FL_PLACING = 8, FL_TARGETING = 16, FL_REPAIR_OK = 32,
            FL_AUTOPILOT = 64, FL_ALERT_KNOWN = 128, FL_ALERT = 256;
    static final int CMD_TAKE_CONTROL = 7;
    static final int CMD_JUMP = 8;
    static final int SDL_SCANCODE_ESCAPE = 41;
    static final int BASE = 2;     // age + serial before the layout
    static final int HEADER = 20;  // THOR_HEADER_INTS

    static final class Item {
        int column, index, key, cost, state, progress;
        final RectF bounds = new RectF();
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int[] snap = new int[4096];
    private final int[] palette = new int[256];
    private int paletteHash;
    private final byte[] radarIdx = new byte[256 * 256];
    private final int[] radarWh = new int[2];
    private Bitmap radarBitmap;
    private int radarW, radarH;
    private final Map<Integer, Bitmap> cameos = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private final int[] cameoBuf = new int[256 * 256];
    private final int[] cameoWh = new int[2];

    private boolean inGame;
    private int credits, power, drain, flags, faction;
    private float shownCredits;
    private int mapX, mapY, mapW, mapH;
    private int viewX, viewY, viewW, viewH; // 1/16 cells
    private final List<Item> items = new ArrayList<>();
    private List<Item> hitItems = new ArrayList<>();
    private final float[] columnScroll = new float[2];
    private String lastAction = "";
    // Demo effects: radar pulse ("radar jump") and a tap ripple on a cameo (no text).
    private int radarPulseSerial = -1, highlightSerial = -1, highlightKey;
    private long radarPulseAt, highlightAt;
    private long lastActionAt;

    /** Deck layout: false = large cameos (2 per column), true = compact (3 per column). */
    private final boolean compact;
    /** Tiberian Dawn (GDI/Nod) instead of Red Alert (Allies/Soviet). */
    private final boolean td;

    // Layout (design units on a 1240x1080 grid)
    private float s = 1f;
    private final RectF header = new RectF();
    private final RectF radarPanel = new RectF(), radarArea = new RectF();
    private final RectF btnRepair = new RectF(), btnSell = new RectF(), btnMenu = new RectF();
    private final RectF btnUnits = new RectF(), btnBase = new RectF(), btnGroup = new RectF(), btnAlert = new RectF();
    private boolean buttonLongFired;
    private final RectF statusPanel = new RectF();
    private final RectF chipRect = new RectF();
    // Controller focus (L1/R1 move, A builds); the ring fades out after a few seconds.
    private int focusIndex = -1;
    private long focusAt;
    private final RectF[] columnPanel = {new RectF(), new RectF()};
    private final RectF[] columnArea = {new RectF(), new RectF()};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(); // no filtering: crisp pixel art
    private Paint microLabel, microLabelDim, monoBig, monoMid, monoSmall, bodyDim, badgeText;

    // Touch
    private Item downItem;
    private int downColumn = -1;
    private boolean downRadar, longFired, scrolled;
    private RectF downButton;
    private float downY, lastY;
    private long lastRadarCmd;
    private int flashKey = -1;
    private RectF flashButton;
    private long flashAt;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (downItem != null && !scrolled) {
                longFired = true;
                click(downItem, true); // hold / cancel
            }
        }
    };

    private final Runnable buttonLongPress = new Runnable() {
        @Override
        public void run() {
            if (downButton == btnUnits || downButton == btnGroup) {
                buttonLongFired = true;
                // UNITS: select own units on screen; GROUP: make the selection a group
                NativeBridge.pushCommand(CMD_JUMP, downButton == btnUnits ? 2 : 5, 0, 0);
                flashButton = downButton;
                flashAt = SystemClock.uptimeMillis();
                invalidate();
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            poll();
            handler.postDelayed(this, 66);
        }
    };

    public ThorDeck(Context context, boolean compact, boolean td) {
        super(context);
        this.compact = compact;
        this.td = td;
        Ui.init(context);
        bmpPaint.setFilterBitmap(false);
        setBackgroundColor(Ui.BG);
    }

    private final Map<Integer, String> strings = new HashMap<>();

    private String str(int id) {
        String v = strings.get(id);
        if (v == null) {
            v = getContext().getString(id);
            strings.put(id, v);
        }
        return v;
    }

    public void start() {
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    public void stop() {
        handler.removeCallbacks(tick);
        handler.removeCallbacks(longPress);
    }

    // ------------------------------------------------------------------ data

    private void poll() {
        int n;
        try {
            n = NativeBridge.snapshot(snap);
        } catch (Throwable t) {
            n = 0;
        }
        boolean wasInGame = inGame;
        inGame = n >= BASE + HEADER && snap[BASE] >= 3 && snap[BASE + 1] == 1 && snap[0] < 1500;
        if (inGame) {
            credits = snap[BASE + 2];
            power = snap[BASE + 3];
            drain = snap[BASE + 4];
            flags = snap[BASE + 5];
            mapX = snap[BASE + 6];
            mapY = snap[BASE + 7];
            mapW = snap[BASE + 8];
            mapH = snap[BASE + 9];
            viewX = snap[BASE + 10];
            viewY = snap[BASE + 11];
            viewW = snap[BASE + 12];
            viewH = snap[BASE + 13];
            faction = snap[BASE + 16];
            int pulse = snap[BASE + 17], hlKey = snap[BASE + 18], hlSerial = snap[BASE + 19];
            if (pulse != radarPulseSerial) {
                if (radarPulseSerial >= 0) {
                    radarPulseAt = SystemClock.uptimeMillis();
                }
                radarPulseSerial = pulse;
            }
            if (hlSerial != highlightSerial) {
                if (highlightSerial >= 0 && hlSerial != 0) {
                    highlightKey = hlKey;
                    highlightAt = SystemClock.uptimeMillis();
                }
                highlightSerial = hlSerial;
            }
            int count = snap[BASE + 14] + snap[BASE + 15];
            items.clear();
            int p = BASE + HEADER;
            for (int i = 0; i < count && p + 6 <= n; i++, p += 6) {
                Item it = new Item();
                it.column = snap[p];
                it.index = snap[p + 1];
                it.key = snap[p + 2];
                it.cost = snap[p + 3];
                it.state = snap[p + 4];
                it.progress = snap[p + 5];
                items.add(it);
            }
            if (!wasInGame) {
                shownCredits = credits;
            }
            updatePalette();
            updateRadar();
        }
        // ticking credits counter
        float diff = credits - shownCredits;
        if (Math.abs(diff) < 1f) {
            shownCredits = credits;
        } else {
            shownCredits += Math.signum(diff) * Math.max(1f, Math.abs(diff) * 0.18f);
        }
        if (inGame || wasInGame) {
            invalidate();
        }
    }

    private void updatePalette() {
        NativeBridge.palette(palette);
        int h = 1;
        for (int i = 0; i < 256; i += 3) {
            h = h * 31 + palette[i];
        }
        if (h != paletteHash) {
            paletteHash = h; // palette changed (fade, theater): reload cameos
            cameos.clear();
        }
    }

    private void updateRadar() {
        int n = NativeBridge.radar(radarIdx, radarWh);
        if (n <= 0) {
            return;
        }
        int w = radarWh[0], h = radarWh[1];
        if (radarBitmap == null || radarW != w || radarH != h) {
            radarBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            radarW = w;
            radarH = h;
        }
        int[] px = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int idx = radarIdx[i] & 0xFF;
            px[i] = idx == 0 ? 0xFF000000 : (palette[idx] | 0xFF000000);
        }
        radarBitmap.setPixels(px, 0, w, 0, 0, w, h);
    }

    private Bitmap cameo(int key) {
        Bitmap b = cameos.get(key);
        if (b != null) {
            return b;
        }
        int n = NativeBridge.cameo(key, cameoBuf, cameoWh);
        if (n <= 0) {
            return null; // not prepared yet - next snapshot
        }
        b = Bitmap.createBitmap(cameoBuf, cameoWh[0], cameoWh[1], Bitmap.Config.ARGB_8888);
        cameos.put(key, b);
        return b;
    }

    private String name(int key) {
        String n = names.get(key);
        if (n == null || n.isEmpty()) {
            n = NativeBridge.itemName(key);
            if (n != null && !n.isEmpty()) {
                names.put(key, n);
            }
        }
        return n == null ? "" : n;
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        s = Math.min(w / 1240f, h / 1080f);
        float m = 28 * s;
        header.set(0, 0, w, 116 * s);
        float top = header.bottom + 24 * s;
        float leftW = 520 * s;
        radarPanel.set(m, top, m + leftW, top + leftW - 36 * s);
        radarArea.set(radarPanel.left + 16 * s, radarPanel.top + 48 * s, radarPanel.right - 16 * s, radarPanel.bottom - 16 * s);
        float by = radarPanel.bottom + 16 * s, bh = 88 * s, gap = 10 * s;
        float bw = (leftW - 2 * gap) / 3f;
        btnRepair.set(m, by, m + bw, by + bh);
        btnSell.set(btnRepair.right + gap, by, btnRepair.right + gap + bw, by + bh);
        btnMenu.set(btnSell.right + gap, by, m + leftW, by + bh);
        float by2 = btnRepair.bottom + gap, bw2 = (leftW - 3 * gap) / 4f;
        btnUnits.set(m, by2, m + bw2, by2 + bh);
        btnBase.set(btnUnits.right + gap, by2, btnUnits.right + gap + bw2, by2 + bh);
        btnGroup.set(btnBase.right + gap, by2, btnBase.right + gap + bw2, by2 + bh);
        btnAlert.set(btnGroup.right + gap, by2, m + leftW, by2 + bh);
        statusPanel.set(m, btnUnits.bottom + 20 * s, m + leftW, h - m);
        float colX = radarPanel.right + 24 * s;
        float colW = (w - m - colX - 16 * s) / 2f;
        for (int i = 0; i < 2; i++) {
            float l = colX + i * (colW + 16 * s);
            columnPanel[i].set(l, top, l + colW, h - m);
            columnArea[i].set(l + 12 * s, top + 52 * s, l + colW - 12 * s, h - m - 12 * s);
        }

        microLabel = Ui.label(15 * s, Ui.TEXT_3);
        microLabelDim = Ui.label(13 * s, Ui.TEXT_3);
        monoBig = Ui.text(Ui.monoBold(), 46 * s, Ui.TEXT);
        monoMid = Ui.text(Ui.mono(), 24 * s, Ui.TEXT_2);
        monoSmall = Ui.text(Ui.mono(), 20 * s, Ui.TEXT_2);
        bodyDim = Ui.text(Ui.inter(), 21 * s, Ui.TEXT_2);
        badgeText = Ui.label(15 * s, Ui.BG);
        badgeText.setTypeface(Ui.interBold());
        badgeText.setLetterSpacing(0.16f);
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        Ui.grid(c, w, h, 40 * s, paint);
        if (!inGame) {
            drawIdle(c, w, h);
            Ui.scanlines(c, w, h, paint);
            return;
        }
        drawHeader(c, w);
        drawRadar(c);
        drawButton(c, btnRepair, str(R.string.deck_repair), (flags & FL_REPAIR) != 0, (flags & FL_REPAIR_OK) != 0, glyphWrench());
        drawButton(c, btnSell, str(R.string.deck_sell), (flags & FL_SELL) != 0, true, glyphDollar());
        drawButton(c, btnMenu, str(R.string.deck_menu), false, true, glyphMenu());
        drawButton(c, btnUnits, str(R.string.deck_btn_units), false, true, glyphUnits());
        drawButton(c, btnBase, str(R.string.deck_btn_base), false, true, glyphBase());
        drawButton(c, btnGroup, str(R.string.deck_btn_group), false, true, glyphGroup());
        // Base under attack: blinks for 30 s, then stays tappable (last attack spot).
        boolean alert = (flags & FL_ALERT) != 0 && (SystemClock.uptimeMillis() / 400) % 2 == 0;
        drawButton(c, btnAlert, str(R.string.deck_btn_alert), alert, (flags & FL_ALERT_KNOWN) != 0, glyphAlert());
        drawStatus(c);
        for (int col = 0; col < 2; col++) {
            drawColumn(c, col);
        }
        hitItems = new ArrayList<>(items);
        Ui.scanlines(c, w, h, paint);
        if (flashKey != -1 || flashButton != null) {
            if (SystemClock.uptimeMillis() - flashAt > 180) {
                flashKey = -1;
                flashButton = null;
            } else {
                postInvalidateDelayed(30);
            }
        }
    }

    private void drawIdle(Canvas c, int w, int h) {
        float cx = w / 2f, cy = h / 2f - 40 * s;
        Emblem.chevron(c, cx, cy - 60 * s, 90 * s, Ui.AMBER, paint);
        Paint t = Ui.text(Ui.interBold(), 44 * s, Ui.TEXT);
        t.setLetterSpacing(0.12f);
        String title = "THOR CONQUER";
        c.drawText(title, cx - t.measureText(title) / 2, cy + 90 * s, t);
        Paint l = Ui.label(16 * s, Ui.TEXT_3);
        String sub = str(R.string.deck_standby);
        c.drawText(sub, cx - l.measureText(sub) / 2, cy + 134 * s, l);
        String hint = str(R.string.deck_standby_hint);
        c.drawText(hint, cx - bodyDim.measureText(hint) / 2, cy + 200 * s, bodyDim);
    }

    private void drawHeader(Canvas c, int w) {
        int acc = Ui.accent(faction);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(Ui.BG_2);
        c.drawRect(header, paint);
        paint.setColor(Ui.LINE);
        c.drawRect(0, header.bottom - 1, w, header.bottom, paint);
        paint.setColor(Ui.alpha(acc, 0.85f));
        c.drawRect(0, 0, w, 3 * s, paint); // accent hairline

        float m = 28 * s;
        // CREDITS
        c.drawText(str(R.string.deck_credits), m, 40 * s, microLabel);
        String cr = String.format(java.util.Locale.ROOT, "%,d", Math.round(shownCredits)).replace(',', ' ');
        c.drawText("$", m, 92 * s, Ui.text(Ui.monoBold(), 46 * s, acc));
        c.drawText(cr, m + 34 * s, 92 * s, monoBig);

        // POWER
        float px = 340 * s, pw = 470 * s;
        c.drawText(str(R.string.deck_power), px, 40 * s, microLabel);
        boolean low = drain > power;
        String pv = power + " / " + drain;
        monoMid.setColor(low ? Ui.RED : Ui.TEXT_2);
        c.drawText(pv, px + pw - monoMid.measureText(pv), 40 * s, monoMid);
        int max = Math.max(1, Math.max(power, drain));
        RectF bar = new RectF(px, 62 * s, px + pw, 74 * s);
        if (low) {
            Ui.bar(c, bar, power / (float) max, Ui.RED, Ui.RED_2, paint);
        } else {
            Ui.bar(c, bar, power / (float) max, Ui.GREEN, 0xFF8FE3B4, paint);
        }
        float dx = px + pw * Math.min(1f, drain / (float) max); // drain marker
        paint.setColor(Ui.TEXT);
        c.drawRect(dx - 1.5f * s, 54 * s, dx + 1.5f * s, 82 * s, paint);
        c.drawText(low ? str(R.string.deck_low_power) : str(R.string.deck_output_drain), px, 104 * s, microLabelDim);

        // status chip
        String side = td ? (faction == 1 ? str(R.string.deck_side_nod) : str(R.string.deck_side_gdi)) : (faction == 1 ? str(R.string.deck_side_soviet) : str(R.string.deck_side_allies));
        boolean modal = (flags & (FL_PLACING | FL_TARGETING | FL_REPAIR | FL_SELL)) != 0;
        boolean autopilot = (flags & FL_AUTOPILOT) != 0;
        String mode = autopilot ? str(R.string.deck_autopilot) : (flags & FL_PLACING) != 0 ? str(R.string.deck_mode_place) : (flags & FL_TARGETING) != 0 ? str(R.string.deck_mode_target)
                : (flags & FL_REPAIR) != 0 ? str(R.string.deck_mode_repair) : (flags & FL_SELL) != 0 ? str(R.string.deck_mode_sell) : str(R.string.deck_online);
        if (autopilot) {
            modal = true;
        }
        Paint ml = Ui.label(15 * s, modal ? acc : Ui.TEXT_2);
        float rx = w - m;
        float tw = Math.max(ml.measureText(mode), microLabel.measureText(side));
        RectF chip = new RectF(rx - tw - 60 * s, 24 * s, rx, 92 * s);
        chipRect.set(chip);
        Ui.panel(c, chip, Ui.RADIUS_SM * s, autopilot ? Ui.alpha(acc, 0.12f) : Ui.PANEL, autopilot ? acc : Ui.LINE, paint);
        paint.setColor(autopilot ? acc : modal ? acc : Ui.GREEN);
        c.drawCircle(chip.left + 22 * s, chip.top + 25 * s, 5 * s, paint);
        c.drawText(side, chip.left + 38 * s, chip.top + 30 * s, microLabel);
        c.drawText(mode, chip.left + 38 * s, chip.top + 56 * s, ml);
    }

    private void drawRadar(Canvas c) {
        int acc = Ui.accent(faction);
        Ui.panel(c, radarPanel, Ui.RADIUS * s, Ui.PANEL, Ui.LINE, paint);
        c.drawText(str(R.string.deck_radar), radarPanel.left + 16 * s, radarPanel.top + 32 * s, microLabel);
        boolean active = (flags & FL_RADAR) != 0 && radarBitmap != null && mapW > 0;
        String st = active ? str(R.string.deck_online) : str(R.string.deck_offline);
        Paint sp = Ui.label(13 * s, active ? Ui.GREEN : Ui.TEXT_3);
        c.drawText(st, radarPanel.right - 16 * s - sp.measureText(st), radarPanel.top + 32 * s, sp);
        paint.setColor(0xFF000000);
        c.drawRoundRect(radarArea, Ui.RADIUS_SM * s, Ui.RADIUS_SM * s, paint);
        if (!active) {
            // "No signal": faint grid, slow sweep line, label - in the design system, no empty circle.
            c.save();
            c.clipRect(radarArea);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1f);
            paint.setColor(0x14FFFFFF);
            float step = radarArea.width() / 12f;
            for (float gx = radarArea.left + step; gx < radarArea.right; gx += step) {
                c.drawLine(gx, radarArea.top, gx, radarArea.bottom, paint);
            }
            for (float gy = radarArea.top + step; gy < radarArea.bottom; gy += step) {
                c.drawLine(radarArea.left, gy, radarArea.right, gy, paint);
            }
            float phase = (SystemClock.uptimeMillis() % 4000) / 4000f;
            float sy = radarArea.top + radarArea.height() * phase;
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new android.graphics.LinearGradient(0, sy - 60 * s, 0, sy, 0, Ui.alpha(acc, 0.10f),
                    android.graphics.Shader.TileMode.CLAMP));
            c.drawRect(radarArea.left, sy - 60 * s, radarArea.right, sy, paint);
            paint.setShader(null);
            c.restore();
            Paint ns = Ui.label(22 * s, Ui.TEXT_2);
            ns.setTypeface(Ui.interBold());
            String t1 = str(R.string.deck_no_signal);
            float t1w = ns.measureText(t1) - ns.getLetterSpacing() * ns.getTextSize();
            c.drawText(t1, radarArea.centerX() - t1w / 2, radarArea.centerY(), ns);
            String t = str(R.string.deck_radar_hint);
            c.drawText(t, radarArea.centerX() - bodyDim.measureText(t) / 2, radarArea.centerY() + 44 * s, bodyDim);
            postInvalidateDelayed(50);
            return;
        }
        RectF dst = radarDst();
        c.drawBitmap(radarBitmap, null, dst, bmpPaint);
        float sx = dst.width() / mapW, sy = dst.height() / mapH;
        float vx = dst.left + (viewX / 16f - mapX) * sx;
        float vy = dst.top + (viewY / 16f - mapY) * sy;
        RectF vr = new RectF(vx, vy, vx + viewW / 16f * sx, vy + viewH / 16f * sy);
        vr.intersect(dst);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f * s);
        paint.setColor(acc);
        c.drawRect(vr, paint);
        long age = SystemClock.uptimeMillis() - radarPulseAt;
        if (radarPulseAt > 0 && age < 900) {
            float t = age / 900f;
            paint.setStrokeWidth(3f * s * (1 - t) + 1f);
            paint.setColor(Ui.alpha(acc, 0.9f * (1 - t)));
            float r0 = Math.max(vr.width(), vr.height()) / 2f;
            c.drawCircle(vr.centerX(), vr.centerY(), r0 + t * radarArea.width() * 0.35f, paint);
            postInvalidateDelayed(16);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /** Radar image at the correct aspect ratio inside the radar area. */
    private RectF radarDst() {
        float k = Math.min(radarArea.width() / Math.max(1, mapW), radarArea.height() / Math.max(1, mapH));
        float w = mapW * k, h = mapH * k;
        float l = radarArea.centerX() - w / 2, t = radarArea.centerY() - h / 2;
        return new RectF(l, t, l + w, t + h);
    }

    private void drawButton(Canvas c, RectF r, String label, boolean active, boolean enabled, Path glyph) {
        int acc = Ui.accent(faction);
        boolean flash = flashButton == r;
        int fill = active ? Ui.alpha(acc, 0.16f) : (flash ? Ui.PANEL_2 : Ui.PANEL);
        Ui.panel(c, r, Ui.RADIUS * s, fill, active || flash ? acc : Ui.LINE, paint);
        int col = !enabled ? Ui.TEXT_3 : (active ? acc : Ui.TEXT);
        c.save();
        c.translate(r.centerX() - 14 * s, r.top + 16 * s);
        c.scale(s, s);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2.4f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(col);
        c.drawPath(glyph, paint);
        c.restore();
        paint.setStyle(Paint.Style.FILL);
        Paint lp = Ui.label(15 * s, col);
        float tw = lp.measureText(label) - lp.getLetterSpacing() * lp.getTextSize();
        if (tw > r.width() - 14 * s) { // long labels (DE/FR) on the narrow jump buttons
            lp.setTextSize(lp.getTextSize() * (r.width() - 14 * s) / tw);
            tw = lp.measureText(label) - lp.getLetterSpacing() * lp.getTextSize();
        }
        c.drawText(label, r.centerX() - tw / 2, r.bottom - 16 * s, lp);
    }

    private static Path glyphWrench() {
        Path p = new Path();
        p.moveTo(4, 28);
        p.lineTo(17, 15);
        p.addCircle(22, 10, 7, Path.Direction.CW);
        return p;
    }

    private static Path glyphDollar() {
        Path p = new Path();
        p.moveTo(22, 7);
        p.lineTo(10, 7);
        p.quadTo(4, 7, 4, 12);
        p.quadTo(4, 16, 10, 16);
        p.lineTo(18, 16);
        p.quadTo(24, 16, 24, 21);
        p.quadTo(24, 26, 18, 26);
        p.lineTo(5, 26);
        p.moveTo(14, 2);
        p.lineTo(14, 31);
        return p;
    }

    private static Path glyphUnits() {
        Path p = new Path();
        p.addCircle(14, 15, 9, Path.Direction.CW);
        p.moveTo(14, 1);
        p.lineTo(14, 8);
        p.moveTo(14, 22);
        p.lineTo(14, 29);
        p.moveTo(0, 15);
        p.lineTo(7, 15);
        p.moveTo(21, 15);
        p.lineTo(28, 15);
        return p;
    }

    private static Path glyphBase() {
        Path p = new Path();
        p.moveTo(2, 14);
        p.lineTo(14, 3);
        p.lineTo(26, 14);
        p.moveTo(6, 11);
        p.lineTo(6, 27);
        p.lineTo(22, 27);
        p.lineTo(22, 11);
        p.moveTo(11, 27);
        p.lineTo(11, 19);
        p.lineTo(17, 19);
        p.lineTo(17, 27);
        return p;
    }

    private static Path glyphGroup() {
        Path p = new Path();
        p.addRect(2, 4, 26, 28, Path.Direction.CW);
        p.moveTo(8, 12);
        p.lineTo(20, 12);
        p.moveTo(8, 20);
        p.lineTo(20, 20);
        return p;
    }

    private static Path glyphAlert() {
        Path p = new Path();
        p.moveTo(14, 2);
        p.lineTo(27, 27);
        p.lineTo(1, 27);
        p.close();
        p.moveTo(14, 11);
        p.lineTo(14, 18);
        p.moveTo(14, 22);
        p.lineTo(14, 23);
        return p;
    }

    private static Path glyphMenu() {
        Path p = new Path();
        p.moveTo(4, 8);
        p.lineTo(24, 8);
        p.moveTo(4, 16);
        p.lineTo(24, 16);
        p.moveTo(4, 24);
        p.lineTo(24, 24);
        return p;
    }

    private void drawStatus(Canvas c) {
        int acc = Ui.accent(faction);
        Ui.panel(c, statusPanel, Ui.RADIUS * s, Ui.PANEL, Ui.LINE, paint);
        float x = statusPanel.left + 18 * s, y = statusPanel.top + 34 * s;
        c.drawText(str(R.string.deck_status), x, y, microLabel);
        String headline, detail;
        boolean modal = true;
        if ((flags & FL_PLACING) != 0) {
            headline = str(R.string.deck_h_place);
            detail = str(R.string.deck_d_place);
        } else if ((flags & FL_TARGETING) != 0) {
            headline = str(R.string.deck_h_target);
            detail = str(R.string.deck_d_target);
        } else if ((flags & FL_REPAIR) != 0) {
            headline = str(R.string.deck_h_repair);
            detail = str(R.string.deck_d_repair);
        } else if ((flags & FL_SELL) != 0) {
            headline = str(R.string.deck_h_sell);
            detail = str(R.string.deck_d_sell);
        } else if ((flags & FL_AUTOPILOT) != 0) {
            headline = str(R.string.deck_h_autopilot);
            detail = str(R.string.deck_d_autopilot);
        } else {
            modal = false;
            if (!lastAction.isEmpty() && SystemClock.uptimeMillis() - lastActionAt < 4000) {
                headline = lastAction;
                detail = str(R.string.deck_d_action);
            } else {
                headline = str(R.string.deck_h_ready);
                detail = str(R.string.deck_d_ready);
            }
        }
        Paint hp = Ui.text(Ui.interSemi(), 26 * s, modal ? acc : Ui.TEXT);
        c.drawText(headline, x, y + 44 * s, hp);
        drawWrapped(c, detail, x, y + 76 * s, statusPanel.width() - 36 * s, bodyDim, 27 * s, 4);
    }

    private void drawWrapped(Canvas c, String text, float x, float y, float maxW, Paint p, float lh, int maxLines) {
        StringBuilder line = new StringBuilder();
        int lines = 0;
        for (String word : text.split(" ")) {
            String trial = line.length() == 0 ? word : line + " " + word;
            if (p.measureText(trial) > maxW && line.length() > 0) {
                c.drawText(line.toString(), x, y, p);
                y += lh;
                if (++lines >= maxLines) {
                    return;
                }
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(trial);
            }
        }
        c.drawText(line.toString(), x, y, p);
    }

    private void drawColumn(Canvas c, int col) {
        RectF panel = columnPanel[col], area = columnArea[col];
        Ui.panel(c, panel, Ui.RADIUS * s, Ui.PANEL, Ui.LINE, paint);
        int count = 0;
        for (Item it : items) {
            if (it.column == col) {
                count++;
            }
        }
        c.drawText(col == 0 ? str(R.string.deck_structures) : str(R.string.deck_units), panel.left + 16 * s, panel.top + 34 * s, microLabel);
        String cnt = String.valueOf(count);
        c.drawText(cnt, panel.right - 16 * s - monoSmall.measureText(cnt), panel.top + 34 * s, monoSmall);

        int per = compact ? 3 : 2;
        float gap = 10 * s;
        float cw = (area.width() - (per - 1) * gap) / per;
        float ch = cw * 0.75f;            // 64x48 cameo aspect
        float cellH = ch + 40 * s + gap;  // progress + cost line below
        float y0 = area.top - columnScroll[col];
        c.save();
        c.clipRect(area);
        int n = 0;
        for (Item it : items) {
            if (it.column != col) {
                continue;
            }
            float top = y0 + (n / per) * cellH;
            float left = area.left + (n % per) * (cw + gap);
            it.bounds.set(left, top, left + cw, top + ch + 36 * s);
            n++;
            if (top > area.bottom || top + cellH < area.top) {
                continue;
            }
            drawTile(c, it, new RectF(left, top, left + cw, top + ch));
        }
        c.restore();
        int rows = (n + per - 1) / per;
        float maxScroll = Math.max(0, rows * cellH - area.height());
        columnScroll[col] = Math.max(0, Math.min(columnScroll[col], maxScroll));
        if (maxScroll > 0) {
            float th = area.height() * area.height() / (rows * cellH);
            float ty = area.top + (area.height() - th) * (columnScroll[col] / maxScroll);
            paint.setColor(Ui.LINE_2);
            c.drawRoundRect(panel.right - 7 * s, ty, panel.right - 3 * s, ty + th, 2 * s, 2 * s, paint);
        }
    }

    private void drawTile(Canvas c, Item it, RectF dst) {
        int acc = Ui.accent(faction);
        float r = Ui.RADIUS_SM * s;
        Path clip = new Path();
        clip.addRoundRect(dst, r, r, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        Bitmap b = cameo(it.key);
        if (b != null) {
            c.drawBitmap(b, null, dst, bmpPaint);
        } else {
            paint.setColor(Ui.PANEL_2);
            c.drawRect(dst, paint);
            drawWrapped(c, name(it.key), dst.left + 8 * s, dst.top + 26 * s, dst.width() - 16 * s, bodyDim, 24 * s, 2);
        }
        if (it.state == ST_DISABLED) {
            paint.setColor(0xB0000000);
            c.drawRect(dst, paint);
        } else if (it.state == ST_BUILDING || it.state == ST_ON_HOLD || it.state == ST_CHARGING) {
            // build-progress sweep: the remaining part stays dark, clockwise from 12 o'clock
            float done = it.progress / 1000f;
            float rad = (float) Math.hypot(dst.width(), dst.height());
            RectF oval = new RectF(dst.centerX() - rad, dst.centerY() - rad, dst.centerX() + rad, dst.centerY() + rad);
            paint.setColor(it.state == ST_ON_HOLD ? 0xB8000000 : 0x96000000);
            c.drawArc(oval, -90 + 360f * done, 360f * (1 - done), true, paint);
        }
        c.restore();

        // frame
        boolean flash = flashKey == it.key;
        boolean strong = flash || it.state == ST_READY || it.state == ST_BUILDING;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth((strong ? 2.5f : 1f) * s);
        paint.setColor(flash ? acc : it.state == ST_READY ? Ui.GREEN : it.state == ST_BUILDING ? Ui.alpha(acc, 0.8f) : Ui.LINE_2);
        c.drawRoundRect(dst, r, r, paint);
        paint.setStyle(Paint.Style.FILL);
        if (flash) {
            paint.setColor(Ui.alpha(acc, 0.18f));
            c.drawRoundRect(dst, r, r, paint);
        }

        long hage = SystemClock.uptimeMillis() - highlightAt;
        if (highlightAt > 0 && it.key == highlightKey && hage < 700) {
            float t = hage / 700f;
            paint.setColor(Ui.alpha(Ui.TEXT, 0.35f * (1 - t)));
            c.drawCircle(dst.centerX(), dst.centerY(), dst.height() * (0.15f + 0.55f * t), paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2f * s);
            paint.setColor(Ui.alpha(Ui.TEXT, 0.8f * (1 - t)));
            c.drawCircle(dst.centerX(), dst.centerY(), dst.height() * (0.2f + 0.6f * t), paint);
            paint.setStyle(Paint.Style.FILL);
            postInvalidateDelayed(16);
        }
        if (focusIndex >= 0 && SystemClock.uptimeMillis() - focusAt < 4000 && focusIndex < hitItems.size()
                && hitItems.get(focusIndex).key == it.key && hitItems.get(focusIndex).column == it.column) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f * s);
            paint.setColor(Ui.TEXT);
            RectF fr = new RectF(dst.left - 5 * s, dst.top - 5 * s, dst.right + 5 * s, dst.bottom + 5 * s);
            c.drawRoundRect(fr, r + 4 * s, r + 4 * s, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        // badges
        if (it.state == ST_READY) {
            badge(c, dst, str(R.string.deck_ready), Ui.GREEN);
        } else if (it.state == ST_ON_HOLD) {
            badge(c, dst, str(R.string.deck_on_hold), acc);
        } else if (it.state == ST_BUILDING || it.state == ST_CHARGING) {
            String pct = (it.progress / 10) + "%";
            Paint pp = Ui.text(Ui.monoBold(), 24 * s, Ui.TEXT);
            float tw = pp.measureText(pct);
            paint.setColor(0xC0000000);
            c.drawRoundRect(dst.centerX() - tw / 2 - 8 * s, dst.centerY() - 20 * s, dst.centerX() + tw / 2 + 8 * s,
                    dst.centerY() + 12 * s, 6 * s, 6 * s, paint);
            c.drawText(pct, dst.centerX() - tw / 2, dst.centerY() + 4 * s, pp);
        }
        // thin gradient progress bar
        if (it.state == ST_BUILDING || it.state == ST_ON_HOLD || it.state == ST_CHARGING) {
            RectF bar = new RectF(dst.left, dst.bottom + 6 * s, dst.right, dst.bottom + 10 * s);
            Ui.bar(c, bar, it.progress / 1000f, acc, Ui.accent2(faction), paint);
        }
        // cost (mono), coral if not affordable
        if (it.cost > 0) {
            String cost = "$" + it.cost;
            monoSmall.setColor(it.state == ST_DISABLED ? Ui.TEXT_3 : (it.cost > credits ? Ui.CORAL : Ui.TEXT_2));
            c.drawText(cost, dst.left + 2 * s, dst.bottom + 34 * s, monoSmall);
            monoSmall.setColor(Ui.TEXT_2);
        }
    }

    private void badge(Canvas c, RectF dst, String text, int color) {
        float tw = badgeText.measureText(text) - badgeText.getLetterSpacing() * badgeText.getTextSize();
        RectF b = new RectF(dst.left + 6 * s, dst.top + 6 * s, dst.left + 6 * s + tw + 18 * s, dst.top + 34 * s);
        paint.setColor(color);
        c.drawRoundRect(b, 6 * s, 6 * s, paint);
        c.drawText(text, b.left + 9 * s, b.bottom - 9 * s, badgeText);
    }

    // ------------------------------------------------------------------ controller

    /** L1/R1: move the focus ring through the build items (structures first, then units). */
    public void moveFocus(int delta) {
        if (!inGame || hitItems.isEmpty()) {
            return;
        }
        boolean visible = focusIndex >= 0 && SystemClock.uptimeMillis() - focusAt < 4000;
        int n = hitItems.size();
        focusIndex = !visible ? (delta > 0 ? 0 : n - 1) : ((focusIndex + delta) % n + n) % n;
        focusAt = SystemClock.uptimeMillis();
        // keep the focused item visible
        Item it = hitItems.get(focusIndex);
        RectF area = columnArea[it.column];
        if (it.bounds.top < area.top) {
            columnScroll[it.column] -= area.top - it.bounds.top;
        } else if (it.bounds.bottom > area.bottom) {
            columnScroll[it.column] += it.bounds.bottom - area.bottom;
        }
        invalidate();
    }

    /** A while the focus ring is visible: same as tapping the item. */
    public boolean activateFocus() {
        if (focusIndex < 0 || SystemClock.uptimeMillis() - focusAt >= 4000 || focusIndex >= hitItems.size()) {
            return false;
        }
        Item it = hitItems.get(focusIndex);
        click(it, it.state == ST_BUILDING);
        focusAt = SystemClock.uptimeMillis();
        return true;
    }

    // ------------------------------------------------------------------ input

    private void click(Item it, boolean right) {
        NativeBridge.pushCommand(NativeBridge.CMD_BUILD_CLICK, it.column, it.index, (it.key << 1) | (right ? 1 : 0));
        String n = name(it.key);
        lastAction = right ? getContext().getString(R.string.deck_act_hold, n)
                : (it.state == ST_READY ? getContext().getString(R.string.deck_act_deploy, n) : n);
        lastActionAt = SystemClock.uptimeMillis();
        flashKey = it.key;
        flashAt = SystemClock.uptimeMillis();
        invalidate();
    }

    private void radarAt(float x, float y) {
        if ((flags & FL_RADAR) == 0 || mapW <= 0) {
            return;
        }
        RectF dst = radarDst();
        if (!dst.contains(x, y)) {
            return;
        }
        int cx = mapX + (int) ((x - dst.left) / dst.width() * mapW);
        int cy = mapY + (int) ((y - dst.top) / dst.height() * mapH);
        NativeBridge.pushCommand(NativeBridge.CMD_RADAR, cx, cy, 0);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!inGame) {
            return true;
        }
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = y;
                lastY = y;
                scrolled = longFired = false;
                downItem = null;
                downButton = null;
                downColumn = -1;
                downRadar = radarArea.contains(x, y);
                if ((flags & FL_AUTOPILOT) != 0 && chipRect.contains(x, y)) {
                    NativeBridge.pushCommand(CMD_TAKE_CONTROL, 0, 0, 0);
                    flashAt = SystemClock.uptimeMillis();
                    return true;
                }
                if (downRadar) {
                    radarAt(x, y);
                    lastRadarCmd = SystemClock.uptimeMillis();
                } else if (btnRepair.contains(x, y)) {
                    downButton = btnRepair;
                } else if (btnSell.contains(x, y)) {
                    downButton = btnSell;
                } else if (btnMenu.contains(x, y)) {
                    downButton = btnMenu;
                } else if (btnUnits.contains(x, y)) {
                    downButton = btnUnits;
                    buttonLongFired = false;
                    handler.postDelayed(buttonLongPress, 450);
                } else if (btnBase.contains(x, y)) {
                    downButton = btnBase;
                } else if (btnGroup.contains(x, y)) {
                    downButton = btnGroup;
                    buttonLongFired = false;
                    handler.postDelayed(buttonLongPress, 450);
                } else if (btnAlert.contains(x, y)) {
                    downButton = btnAlert;
                } else {
                    for (int col = 0; col < 2; col++) {
                        if (columnPanel[col].contains(x, y)) {
                            downColumn = col;
                        }
                    }
                    for (Item it : hitItems) {
                        if (columnArea[it.column].contains(x, y) && it.bounds.contains(x, y)) {
                            downItem = it;
                            handler.postDelayed(longPress, 450);
                            break;
                        }
                    }
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (downRadar) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastRadarCmd > 60) {
                        radarAt(x, y);
                        lastRadarCmd = now;
                    }
                } else if (downColumn >= 0) {
                    if (!scrolled && Math.abs(y - downY) > 24 * s) {
                        scrolled = true;
                        handler.removeCallbacks(longPress);
                    }
                    if (scrolled) {
                        columnScroll[downColumn] -= (y - lastY);
                        invalidate();
                    }
                }
                lastY = y;
                return true;
            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                handler.removeCallbacks(buttonLongPress);
                if ((downButton == btnUnits || downButton == btnGroup) && buttonLongFired) {
                    downButton = null;
                }
                if (downButton != null && downButton.contains(x, y)) {
                    flashButton = downButton;
                    flashAt = SystemClock.uptimeMillis();
                    if (downButton == btnRepair) {
                        NativeBridge.pushCommand(NativeBridge.CMD_REPAIR, 0, 0, 0);
                    } else if (downButton == btnSell) {
                        NativeBridge.pushCommand(NativeBridge.CMD_SELL, 0, 0, 0);
                    } else if (downButton == btnUnits) {
                        NativeBridge.pushCommand(CMD_JUMP, 0, 0, 0);
                    } else if (downButton == btnBase) {
                        NativeBridge.pushCommand(CMD_JUMP, 1, 0, 0);
                    } else if (downButton == btnGroup) {
                        NativeBridge.pushCommand(CMD_JUMP, 3, 0, 0);
                    } else if (downButton == btnAlert) {
                        NativeBridge.pushCommand(CMD_JUMP, 4, 0, 0);
                    } else {
                        NativeBridge.pushCommand(NativeBridge.CMD_KEY, SDL_SCANCODE_ESCAPE, 0, 0);
                    }
                    invalidate();
                } else if (downItem != null && !longFired && !scrolled && downItem.bounds.contains(x, y)) {
                    // A second tap on an item under construction puts it on hold (like a right click).
                    click(downItem, downItem.state == ST_BUILDING);
                }
                downItem = null;
                downRadar = false;
                downButton = null;
                downColumn = -1;
                return true;
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                downItem = null;
                downRadar = false;
                downButton = null;
                downColumn = -1;
                return true;
            default:
                return true;
        }
    }
}
