package de.harmssi.thorconquer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Command deck for the Thor's bottom screen (display 4, 1240x1080), drawn as the game's own sidebar.
 *
 * Tab bar (options, credits, status) · radar in the game's radar housing with REPAIR / SELL / MAP ·
 * order pad (control groups, select, stop, guard, scatter, primary, jumps, speed, quick save) ·
 * power bar · two build strips.
 *
 * The interface art (sidebar, radar frame, tabs, buttons, power bar, fonts) is read at runtime from the
 * player's own game files by the engine (common/thor_deck.inc) - none of it is part of this app. Until
 * it has arrived, and where a game has no art for a part, the deck draws plain bevels in the same layout.
 *
 * All data is a snapshot from the engine (common/thor.cpp); every action goes back as a command and runs
 * through the same engine code as the original sidebar, radar or keyboard command.
 */
public class ThorDeck extends View {
    // States as THOR_STATE_* in common/thor.h
    static final int ST_AVAILABLE = 0, ST_BUILDING = 1, ST_ON_HOLD = 2, ST_READY = 3, ST_DISABLED = 4, ST_CHARGING = 5;
    static final int FL_RADAR = 1, FL_REPAIR = 2, FL_SELL = 4, FL_PLACING = 8, FL_TARGETING = 16, FL_REPAIR_OK = 32,
            FL_AUTOPILOT = 64, FL_ALERT_KNOWN = 128, FL_ALERT = 256, FL_PRIMARY_OK = 512, FL_SAVE_OK = 1024,
            FL_QUICKSAVE = 2048;
    static final int CMD_TAKE_CONTROL = 7, CMD_JUMP = 8, CMD_GROUP = 9, CMD_ORDER = 10, CMD_SPEED = 11, CMD_QUICK = 12;
    static final int SDL_SCANCODE_ESCAPE = 41;
    static final int BASE = 2;     // age + serial before the layout
    static final int HEADER = 28;  // THOR_HEADER_INTS

    // Interface art keys (common/thor.h)
    static final int KEY_FONT = 0x7E << 16, KEY_ART = 0x7F << 16, FONT_CELL = 20;
    static final int ART_SIDE_TOP = 0, ART_RADAR = 4, ART_RADAR_FRAME = 5, ART_REPAIR = 6, ART_SELL = 7, ART_MAP = 8,
            ART_POWERBAR = 9, ART_POWER = 10, ART_TABS = 11, ART_STRIPUP = 12, ART_STRIPDN = 13;
    static final int FONT_SMALL = 0, FONT_TAB = 1;

    // Buttons
    static final int B_MENU = 0, B_STATUS = 1, B_REPAIR = 2, B_SELL = 3, B_MAP = 4, B_GROUP = 5 /* ..8 */,
            B_SELECT = 9, B_STOP = 10, B_GUARD = 11, B_SCATTER = 12, B_PRIMARY = 13, B_DESELECT = 14, B_UNITS = 15,
            B_BASE = 16, B_ALERT = 17, B_SPEED = 18, B_SAVE = 19, B_LOAD = 20, B_UP = 21 /* ,22 */, B_DOWN = 23 /* ,24 */,
            B_COUNT = 25;

    static final class Item {
        int column, index, key, cost, state, progress;
        final RectF bounds = new RectF();
    }

    /** One of the game's bitmap fonts: an atlas of ASCII 32..127 printed by the engine. */
    static final class GameFont {
        Bitmap atlas;
        final int[] left = new int[96], width = new int[96];
        int top, height; // rows that capitals and digits use
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
    private final GameFont[] fonts = new GameFont[2];
    private final int[] cameoBuf = new int[256 * 256];
    private final int[] cameoWh = new int[2];

    private boolean inGame;
    private int credits, power, drain, flags, faction;
    private int groupBits, speed, selected, activeGroup;
    private final int[] groupCount = new int[4];
    private float shownCredits;
    private int mapX, mapY, mapW, mapH;
    private int viewX, viewY, viewW, viewH; // 1/16 cells
    private final List<Item> items = new ArrayList<>();
    private List<Item> hitItems = new ArrayList<>();
    private final float[] columnScroll = new float[2];
    private final float[] columnRowH = new float[2];
    private String hint = "";
    private long hintAt;
    private int pendingCancelKey = -1;
    private long pendingCancelAt;
    // Demo effects: radar pulse ("radar jump") and a tap ripple on a cameo (no text).
    private int radarPulseSerial = -1, highlightSerial = -1, highlightKey;
    private long radarPulseAt, highlightAt;

    /** Deck layout: false = large cameos (2 per column), true = compact (3 per column). */
    private final boolean compact;
    /** Tiberian Dawn (GDI/Nod) instead of Red Alert (Allies/Soviet). */
    private final boolean td;

    // Layout (design units on a 1240x1080 grid; one pixel of the game's art = 3 design units)
    private float s = 1f, k = 3f;
    private final RectF[] btn = new RectF[B_COUNT];
    private final RectF topBlock = new RectF(), radarArea = new RectF(), creditsTab = new RectF();
    private final RectF powerArea = new RectF(), statusLine = new RectF();
    private final RectF[] columnPanel = {new RectF(), new RectF()};
    private final RectF[] columnArea = {new RectF(), new RectF()};
    private boolean radarZoom;
    private final RectF radarLock = new RectF(); // map window while a finger is on the zoomed radar
    // Controller focus (L1/R1 move, A builds); the ring fades out after a few seconds.
    private int focusIndex = -1;
    private long focusAt;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(); // no filtering: crisp pixel art
    private final Paint texPaint = new Paint();
    private final Map<Bitmap, BitmapShader> faces = new HashMap<>();
    private final Matrix texMatrix = new Matrix();
    private final Rect srcRect = new Rect();
    private final RectF dstRect = new RectF();
    private Paint plainText;

    // Touch
    private Item downItem;
    private int downColumn = -1, downBtn = -1;
    private boolean downRadar, longFired, scrolled;
    private float downY, lastY;
    private long lastRadarCmd;
    private int flashKey = -1, flashBtn = -1;
    private long flashAt;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (downItem != null && !scrolled) {
                longFired = true;
                cancel(downItem);
            } else if (downBtn >= 0) {
                longFired = hold(downBtn);
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
        texPaint.setFilterBitmap(false);
        for (int i = 0; i < B_COUNT; i++) {
            btn[i] = new RectF();
        }
        setBackgroundColor(0xFF000000);
    }

    private final Map<Integer, String> strings = new HashMap<>();

    /** App strings in ASCII capitals: that is what the game's fonts hold. */
    private String str(int id) {
        String v = strings.get(id);
        if (v == null) {
            v = ascii(getContext().getString(id));
            strings.put(id, v);
        }
        return v;
    }

    private static String ascii(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("[^\\x20-\\x7E]", "");
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
        inGame = n >= BASE + HEADER && snap[BASE] >= 4 && snap[BASE + 1] == 1 && snap[0] < 1500;
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
            groupBits = snap[BASE + 20];
            for (int g = 0; g < 4; g++) {
                groupCount[g] = snap[BASE + 21 + g];
            }
            speed = snap[BASE + 25];
            selected = snap[BASE + 26];
            activeGroup = snap[BASE + 27];
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
                // Cancel = two right clicks in the engine: hold first, abandon once it is on hold.
                if (it.key == pendingCancelKey && it.state == ST_ON_HOLD) {
                    pendingCancelKey = -1;
                    NativeBridge.pushCommand(NativeBridge.CMD_BUILD_CLICK, it.column, it.index, (it.key << 1) | 1);
                }
            }
            if (SystemClock.uptimeMillis() - pendingCancelAt > 1500) {
                pendingCancelKey = -1;
            }
            if (!wasInGame) {
                shownCredits = credits;
                radarZoom = false;
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
            paletteHash = h; // palette changed (fade, theater): reload cameos and art
            cameos.clear();
            faces.clear();
            fonts[0] = fonts[1] = null;
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

    /** One frame of the game's own interface art for the player's side, or null. */
    private Bitmap art(int id, int frame) {
        return cameo(KEY_ART | (((faction & 1) << 7 | id) << 8) | frame);
    }

    private GameFont font(int which) {
        if (fonts[which] != null) {
            return fonts[which];
        }
        Bitmap atlas = cameo(KEY_FONT | which);
        if (atlas == null) {
            return null;
        }
        GameFont f = new GameFont();
        f.atlas = atlas;
        int[] px = new int[atlas.getWidth() * atlas.getHeight()];
        atlas.getPixels(px, 0, atlas.getWidth(), 0, 0, atlas.getWidth(), atlas.getHeight());
        int top = FONT_CELL, bottom = 0;
        for (int ch = 1; ch < 96; ch++) {
            int cx = (ch % 16) * FONT_CELL, cy = (ch / 16) * FONT_CELL, l = FONT_CELL, r = -1;
            boolean caps = (ch >= 'A' - 32 && ch <= 'Z' - 32) || (ch >= '0' - 32 && ch <= '9' - 32);
            for (int y = 0; y < FONT_CELL; y++) {
                for (int x = 0; x < FONT_CELL; x++) {
                    if (px[(cy + y) * atlas.getWidth() + cx + x] != 0) {
                        l = Math.min(l, x);
                        r = Math.max(r, x);
                        if (caps) {
                            top = Math.min(top, y);
                            bottom = Math.max(bottom, y);
                        }
                    }
                }
            }
            f.left[ch] = r < 0 ? 0 : l;
            f.width[ch] = r < 0 ? 0 : r - l + 1;
        }
        if (bottom <= top) {
            return null; // empty atlas: keep the plain text
        }
        f.width[0] = Math.max(2, f.width['n' - 32] * 2 / 3); // space
        f.top = top;
        f.height = bottom - top + 1;
        fonts[which] = f;
        return f;
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

    // ------------------------------------------------------------------ text in the game's fonts

    private float textWidth(int which, String text, float px) {
        GameFont f = font(which);
        if (f == null) {
            plainText.setTextSize(px * (which == FONT_TAB ? 11 : 8));
            return plainText.measureText(text);
        }
        float w = 0;
        for (int i = 0; i < text.length(); i++) {
            int ch = text.charAt(i) - 32;
            w += ((ch >= 0 && ch < 96 ? f.width[ch] : f.width[0]) + 1) * px;
        }
        return w - px;
    }

    /** Text with its capitals centred on cy. align: 0 = left at x, 1 = centred on x, 2 = right at x. */
    private void text(Canvas c, int which, String text, float x, float cy, float px, int align, int alpha) {
        float w = textWidth(which, text, px);
        if (align == 1) {
            x -= w / 2;
        } else if (align == 2) {
            x -= w;
        }
        GameFont f = font(which);
        if (f == null) {
            plainText.setAlpha(alpha);
            c.drawText(text, x, cy + plainText.getTextSize() * 0.36f, plainText);
            return;
        }
        float top = cy - (f.top + f.height / 2f) * px;
        x = Math.round(x);
        top = Math.round(top);
        bmpPaint.setAlpha(alpha);
        for (int i = 0; i < text.length(); i++) {
            int ch = text.charAt(i) - 32;
            if (ch < 0 || ch >= 96) {
                ch = 0;
            }
            if (ch != 0 && f.width[ch] > 0) {
                int sx = (ch % 16) * FONT_CELL + f.left[ch], sy = (ch / 16) * FONT_CELL;
                srcRect.set(sx, sy, sx + f.width[ch], sy + FONT_CELL);
                dstRect.set(x, top, x + f.width[ch] * px, top + FONT_CELL * px);
                c.drawBitmap(f.atlas, srcRect, dstRect, bmpPaint);
            }
            x += (f.width[ch] + 1) * px;
        }
        bmpPaint.setAlpha(255);
    }

    /** Text that shrinks (whole font pixels first) until it fits maxW. */
    private void textFit(Canvas c, int which, String text, float x, float cy, float px, int align, int alpha, float maxW) {
        while (px > s && textWidth(which, text, px) > maxW) {
            px -= s / 2;
        }
        text(c, which, text, x, cy, px, align, alpha);
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        s = Math.min(w / 1240f, h / 1080f);
        k = 3 * s;
        float tabH = 16 * k, leftW = 160 * k;
        btn[B_MENU].set(0, 0, leftW, tabH);
        creditsTab.set(leftW, 0, 2 * leftW, tabH);
        btn[B_STATUS].set(2 * leftW, 0, w, tabH);

        // The sidebar's top part: radar housing and the REPAIR / SELL / MAP row, at the art's own positions.
        float topH = (td ? 166 : 160) * k;
        topBlock.set(0, tabH, leftW, tabH + topH);
        if (td) {
            radarArea.set(16 * k, tabH + 7 * k, (16 + 128) * k, tabH + (7 + 128) * k);
            setArtRect(btn[B_REPAIR], 4, 146, 49, 16);
            setArtRect(btn[B_SELL], 57, 146, 49, 16);
            setArtRect(btn[B_MAP], 110, 146, 46, 16);
        } else {
            radarArea.set(6 * k, tabH + 7 * k, (6 + 146) * k, tabH + (7 + 130) * k);
            setArtRect(btn[B_REPAIR], 18, 134, 41, 18);
            setArtRect(btn[B_SELL], 63, 134, 41, 18);
            setArtRect(btn[B_MAP], 108, 134, 41, 18);
        }

        // Order pad: one row of four groups, four rows of three orders.
        float m = 8 * s, padTop = topBlock.bottom + m, rowH = (h - padTop - m * 5) / 5f;
        float gw = (leftW - m * 5) / 4f;
        for (int g = 0; g < 4; g++) {
            btn[B_GROUP + g].set(m + g * (gw + m), padTop, m + g * (gw + m) + gw, padTop + rowH);
        }
        float bw = (leftW - m * 4) / 3f;
        for (int i = 0; i < 12; i++) {
            float l = m + (i % 3) * (bw + m), t = padTop + (1 + i / 3) * (rowH + m);
            btn[B_SELECT + i].set(l, t, l + bw, t + rowH);
        }

        // Right: power bar, two build strips with their scroll buttons, status line.
        float statusH = 44 * s;
        statusLine.set(leftW, h - statusH, w, h);
        powerArea.set(leftW + m, tabH + m, leftW + 76 * s, statusLine.top - m);
        float colX = powerArea.right + m, colW = (w - m - colX - m) / 2f, arrowH = 28 * k; // the arrows' art is 27 high
        for (int i = 0; i < 2; i++) {
            float l = colX + i * (colW + m);
            columnPanel[i].set(l, tabH + m, l + colW, statusLine.top - m);
            columnArea[i].set(l + 2 * k, tabH + m + 36 * s, l + colW - 2 * k, columnPanel[i].bottom - arrowH - 2 * k);
            float mid = columnPanel[i].centerX(), ay = columnPanel[i].bottom - arrowH - k;
            btn[B_UP + i].set(mid - 34 * k, ay, mid - k, ay + arrowH);
            btn[B_DOWN + i].set(mid + k, ay, mid + 34 * k, ay + arrowH);
        }
        plainText = Ui.text(Ui.interBold(), 24 * s, 0xFFE0E0E0);
    }

    /** Rect in art pixels relative to the sidebar's top left corner (below the tab bar). */
    private void setArtRect(RectF r, int x, int y, int w, int h) {
        r.set(x * k, topBlock.top + y * k, (x + w) * k, topBlock.top + (y + h) * k);
    }

    // ------------------------------------------------------------------ drawing

    private int light() {
        return td ? 0xFF3CA428 : (faction == 1 ? 0xFFB0A090 : 0xFFA8A8B0);
    }

    private int face() {
        return td ? 0xFF10200C : (faction == 1 ? 0xFF4A3A34 : 0xFF484C54);
    }

    private int dark() {
        return td ? 0xFF0C3008 : 0xFF18181C;
    }

    /** Bevelled box in whole art pixels: raised (button), or sunken (slot, pressed button). */
    private void bevel(Canvas c, RectF r, boolean sunken, int fill) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(sunken ? dark() : light());
        c.drawRect(r, paint);
        paint.setColor(sunken ? light() : dark());
        c.drawRect(r.left + k, r.top + k, r.right, r.bottom, paint);
        paint.setColor(fill);
        c.drawRect(r.left + k, r.top + k, r.right - k, r.bottom - k, paint);
    }

    /** Button for the order pad: bevel with the tab bar's own metal as its face (pressed = the pressed tab). */
    private void button(Canvas c, RectF r, boolean down) {
        bevel(c, r, down, face());
        Bitmap tab = art(ART_TABS, down ? 1 : 0);
        if (tab == null || tab.getWidth() < 64 || tab.getHeight() < 10) {
            return;
        }
        BitmapShader sh = faces.get(tab);
        if (sh == null) { // the plain middle of the tab, without its knob and edges
            Bitmap crop = Bitmap.createBitmap(tab, 22, 3, tab.getWidth() - 34, tab.getHeight() - 6);
            sh = new BitmapShader(crop, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR);
            faces.put(tab, sh);
        }
        texMatrix.setScale(k, k);
        texMatrix.postTranslate(r.left, r.top);
        sh.setLocalMatrix(texMatrix);
        texPaint.setShader(sh);
        c.drawRect(r.left + k, r.top + k, r.right - k, r.bottom - k, texPaint);
    }

    private void drawArt(Canvas c, Bitmap b, float x, float y) {
        dstRect.set(x, y, x + b.getWidth() * k, y + b.getHeight() * k);
        c.drawBitmap(b, null, dstRect, bmpPaint);
    }

    /** Art stretched over r, keeping `edge` art pixels at the left and right ends unstretched. */
    private void drawSliced(Canvas c, Bitmap b, RectF r, int edge) {
        int bw = b.getWidth(), bh = b.getHeight();
        float e = Math.min(edge * k, r.width() / 2);
        srcRect.set(0, 0, edge, bh);
        dstRect.set(r.left, r.top, r.left + e, r.bottom);
        c.drawBitmap(b, srcRect, dstRect, bmpPaint);
        srcRect.set(edge, 0, bw - edge, bh);
        dstRect.set(r.left + e, r.top, r.right - e, r.bottom);
        c.drawBitmap(b, srcRect, dstRect, bmpPaint);
        srcRect.set(bw - edge, 0, bw, bh);
        dstRect.set(r.right - e, r.top, r.right, r.bottom);
        c.drawBitmap(b, srcRect, dstRect, bmpPaint);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (!inGame) {
            drawIdle(c, w, h);
            return;
        }
        drawTabs(c);
        drawTop(c);
        drawPad(c);
        drawPower(c);
        for (int col = 0; col < 2; col++) {
            drawColumn(c, col);
        }
        drawStatus(c);
        hitItems = new ArrayList<>(items);
        if (flashKey != -1 || flashBtn != -1) {
            if (SystemClock.uptimeMillis() - flashAt > 180) {
                flashKey = -1;
                flashBtn = -1;
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
        String sub = getContext().getString(R.string.deck_standby);
        c.drawText(sub, cx - l.measureText(sub) / 2, cy + 134 * s, l);
        Paint b = Ui.text(Ui.inter(), 21 * s, Ui.TEXT_2);
        String hintText = getContext().getString(R.string.deck_standby_hint);
        c.drawText(hintText, cx - b.measureText(hintText) / 2, cy + 200 * s, b);
    }

    private boolean pressed(int b) {
        return flashBtn == b || (downBtn == b && !scrolled);
    }

    private void drawTab(Canvas c, RectF r, int frame, int pressedFrame, boolean down) {
        Bitmap b = art(ART_TABS, down ? pressedFrame : frame);
        if (b == null) {
            b = art(ART_TABS, frame);
        }
        if (b != null) {
            drawSliced(c, b, r, 12);
        } else {
            bevel(c, r, down, face());
        }
    }

    private void drawTabs(Canvas c) {
        // Options tab = the game's own menu button
        drawTab(c, btn[B_MENU], 0, 1, pressed(B_MENU));
        text(c, FONT_TAB, str(R.string.deck_options), btn[B_MENU].centerX(), btn[B_MENU].centerY(), k, 1, 255);

        // Credits tab with the ticking counter
        drawTab(c, creditsTab, td ? 0 : 2, td ? 0 : 2, false);
        text(c, FONT_TAB, String.valueOf(Math.round(shownCredits)), creditsTab.centerX(), creditsTab.centerY(), k, 1, 255);

        // Status tab: side and what the top screen is waiting for
        boolean autopilot = (flags & FL_AUTOPILOT) != 0;
        String side = td ? (faction == 1 ? str(R.string.deck_side_nod) : str(R.string.deck_side_gdi))
                : (faction == 1 ? str(R.string.deck_side_soviet) : str(R.string.deck_side_allies));
        String mode = autopilot ? str(R.string.deck_autopilot) : (flags & FL_PLACING) != 0 ? str(R.string.deck_tab_place)
                : (flags & FL_TARGETING) != 0 ? str(R.string.deck_tab_target) : (flags & FL_REPAIR) != 0 ? str(R.string.deck_repair)
                : (flags & FL_SELL) != 0 ? str(R.string.deck_sell) : side;
        boolean blink = autopilot && (SystemClock.uptimeMillis() / 500) % 2 == 0;
        drawTab(c, btn[B_STATUS], td ? 0 : 2, 1, pressed(B_STATUS) || blink);
        textFit(c, FONT_TAB, mode, btn[B_STATUS].centerX(), btn[B_STATUS].centerY(), k, 1, 255, btn[B_STATUS].width() - 12 * k);
        if (autopilot) {
            postInvalidateDelayed(250);
        }
    }

    private void drawArtButton(Canvas c, int b, int artId, int label, boolean on, boolean enabled) {
        RectF r = btn[b];
        Bitmap a = art(artId, !enabled ? 2 : (on || pressed(b)) ? 1 : 0);
        if (a == null && !enabled) {
            a = art(artId, 0);
        }
        if (a != null) {
            r.right = r.left + a.getWidth() * k; // touch area = the art
            r.bottom = r.top + a.getHeight() * k;
            bmpPaint.setAlpha(enabled || art(artId, 2) != null ? 255 : 110);
            drawArt(c, a, r.left, r.top);
            bmpPaint.setAlpha(255);
        } else {
            bevel(c, r, on || pressed(b), face());
            textFit(c, FONT_SMALL, str(label), r.centerX(), r.centerY(), 2 * s, 1, enabled ? 255 : 110, r.width() - 4 * k);
        }
    }

    /** Radar housing with the map in it, and the sidebar's own three buttons. */
    private void drawTop(Canvas c) {
        boolean active = (flags & FL_RADAR) != 0 && radarBitmap != null && mapW > 0;
        Bitmap side = art(ART_SIDE_TOP, 0);
        if (side != null) {
            if (td) { // the sidebar art starts below the radar: only its button row is used here
                c.save();
                c.clipRect(topBlock);
                drawArt(c, side, 0, topBlock.top + 144 * k);
                c.restore();
            } else {
                drawArt(c, side, 0, topBlock.top);
            }
        } else {
            bevel(c, topBlock, false, face());
        }
        Bitmap housing = td ? art(ART_RADAR, active ? 22 : 0) : (active ? art(ART_RADAR_FRAME, 1) : art(ART_RADAR, 0));
        if (housing != null) {
            drawArt(c, housing, 0, topBlock.top);
        } else {
            RectF slot = new RectF(radarArea.left - k, radarArea.top - k, radarArea.right + k, radarArea.bottom + k);
            bevel(c, slot, true, 0xFF000000);
        }
        if (active) {
            drawRadar(c);
        }
        drawArtButton(c, B_REPAIR, ART_REPAIR, R.string.deck_repair, (flags & FL_REPAIR) != 0, (flags & FL_REPAIR_OK) != 0);
        drawArtButton(c, B_SELL, ART_SELL, R.string.deck_sell, (flags & FL_SELL) != 0, true);
        drawArtButton(c, B_MAP, ART_MAP, R.string.deck_map, radarZoom, active);
    }

    /** Part of the map (in cells, relative to the map's corner) that the radar shows. */
    private void radarWindow(RectF out) {
        if (downRadar && !radarLock.isEmpty()) {
            out.set(radarLock);
            return;
        }
        if (!radarZoom) {
            out.set(0, 0, mapW, mapH);
            return;
        }
        float ww = mapW / 2f, wh = mapH / 2f;
        float cx = viewX / 16f - mapX + viewW / 32f, cy = viewY / 16f - mapY + viewH / 32f;
        float l = Math.max(0, Math.min(mapW - ww, cx - ww / 2)), t = Math.max(0, Math.min(mapH - wh, cy - wh / 2));
        out.set(l, t, l + ww, t + wh);
    }

    /** Where the radar window is drawn: correct aspect ratio inside the housing. */
    private RectF radarDst(RectF win) {
        float f = Math.min(radarArea.width() / Math.max(1f, win.width()), radarArea.height() / Math.max(1f, win.height()));
        float w = win.width() * f, h = win.height() * f;
        float l = radarArea.centerX() - w / 2, t = radarArea.centerY() - h / 2;
        return new RectF(l, t, l + w, t + h);
    }

    private void drawRadar(Canvas c) {
        RectF win = new RectF();
        radarWindow(win);
        RectF dst = radarDst(win);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF000000);
        c.drawRect(radarArea, paint);
        srcRect.set(Math.round(win.left), Math.round(win.top), Math.round(win.right), Math.round(win.bottom));
        c.drawBitmap(radarBitmap, srcRect, dst, bmpPaint);
        float sx = dst.width() / win.width(), sy = dst.height() / win.height();
        float vx = dst.left + (viewX / 16f - mapX - win.left) * sx;
        float vy = dst.top + (viewY / 16f - mapY - win.top) * sy;
        RectF vr = new RectF(vx, vy, vx + viewW / 16f * sx, vy + viewH / 16f * sy);
        c.save();
        c.clipRect(dst);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(k);
        paint.setColor(0xFFFFFFFF); // the game's own view box is white, too
        c.drawRect(vr, paint);
        long age = SystemClock.uptimeMillis() - radarPulseAt;
        if (radarPulseAt > 0 && age < 900) {
            float t = age / 900f;
            paint.setStrokeWidth(3f * s * (1 - t) + 1f);
            paint.setColor(Ui.alpha(0xFFFFFFFF, 0.9f * (1 - t)));
            float r0 = Math.max(vr.width(), vr.height()) / 2f;
            c.drawCircle(vr.centerX(), vr.centerY(), r0 + t * radarArea.width() * 0.35f, paint);
            postInvalidateDelayed(16);
        }
        c.restore();
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawOrder(Canvas c, int b, String label, boolean lit, boolean enabled) {
        RectF r = btn[b];
        boolean down = pressed(b) && enabled;
        button(c, r, down || lit);
        float o = down ? k : 0;
        // the tab font: its dark edge keeps it readable on the metal
        textFit(c, FONT_TAB, label, r.centerX() + o, r.centerY() + o, k, 1, enabled ? 255 : 80, r.width() - 4 * k);
    }

    private void drawPad(Canvas c) {
        for (int g = 0; g < 4; g++) {
            RectF r = btn[B_GROUP + g];
            boolean has = (groupBits & (1 << g)) != 0, lit = activeGroup == g, down = pressed(B_GROUP + g);
            button(c, r, down || lit);
            text(c, FONT_TAB, String.valueOf(g + 1), r.centerX(), r.centerY() - (has ? 5 * k : 0), k, 1, has ? 255 : 80);
            if (has) {
                text(c, FONT_TAB, String.valueOf(groupCount[g]), r.centerX(), r.bottom - 8 * k, 2 * s, 1, 255);
            }
        }
        boolean sel = selected > 0;
        drawOrder(c, B_SELECT, str(R.string.deck_btn_select), false, true);
        drawOrder(c, B_STOP, str(R.string.deck_btn_stop), false, sel);
        drawOrder(c, B_GUARD, str(R.string.deck_btn_guard), false, sel);
        drawOrder(c, B_SCATTER, str(R.string.deck_btn_scatter), false, sel);
        drawOrder(c, B_PRIMARY, str(R.string.deck_btn_primary), false, (flags & FL_PRIMARY_OK) != 0);
        drawOrder(c, B_DESELECT, str(R.string.deck_btn_deselect), false, sel || (flags & (FL_REPAIR | FL_SELL)) != 0);
        drawOrder(c, B_UNITS, str(R.string.deck_btn_units), false, true);
        drawOrder(c, B_BASE, str(R.string.deck_btn_base), false, true);
        // Base under attack: blinks for 30 s, then stays tappable (last attack spot).
        boolean alert = (flags & FL_ALERT) != 0 && (SystemClock.uptimeMillis() / 400) % 2 == 0;
        drawOrder(c, B_ALERT, str(R.string.deck_btn_alert), alert, (flags & FL_ALERT_KNOWN) != 0);
        if ((flags & FL_ALERT) != 0) {
            postInvalidateDelayed(200);
        }
        drawOrder(c, B_SPEED, str(R.string.deck_btn_speed) + " " + shownSpeed(), false, true);
        drawOrder(c, B_SAVE, str(R.string.deck_btn_save), false, (flags & FL_SAVE_OK) != 0);
        drawOrder(c, B_LOAD, str(R.string.deck_btn_load), false, (flags & FL_QUICKSAVE) != 0);
    }

    /** 1 (slowest) .. 6 (fastest); the engine counts the other way round. */
    private int shownSpeed() {
        return Math.max(1, Math.min(6, 7 - speed));
    }

    /** The game's power bar: same scale as PowerClass::Power_Height, drain marker beside it. */
    private void drawPower(Canvas c) {
        int range = td ? 220 : 153, factor = td ? 6 : 5;
        float ph = powerHeight(power, range, factor), dh = powerHeight(drain, range, factor);
        int level = drain > power * 2 ? 2 : drain > power ? 1 : 0;
        Bitmap b0 = art(ART_POWERBAR, 0), b1 = art(ART_POWERBAR, 1);
        if (b0 == null || b1 == null) {
            RectF r = new RectF(powerArea.left + 4 * k, powerArea.top, powerArea.right - 4 * k, powerArea.bottom);
            bevel(c, r, true, 0xFF000000);
            float u = (r.height() - 2 * k) / range;
            paint.setColor(level == 2 ? 0xFFD02020 : level == 1 ? 0xFFD8C020 : 0xFF30B030);
            c.drawRect(r.left + k, r.bottom - k - ph * u, r.right - k, r.bottom - k, paint);
            paint.setColor(0xFFFFFFFF);
            c.drawRect(r.left - 2 * k, r.bottom - k - dh * u - k / 2, r.right + 2 * k, r.bottom - k - dh * u + k / 2, paint);
            return;
        }
        int second = td ? 100 : 112, total = second + b1.getHeight();
        float x = powerArea.centerX() - b0.getWidth() * k / 2f, y = powerArea.top;
        drawArt(c, b0, x, y);
        drawArt(c, b1, x, y + second * k);
        float bottom;
        if (td) { // the filled bar is art, too: its lower part is laid over the empty one
            bottom = y + (total - 1) * k;
            Bitmap f0 = art(ART_POWERBAR, 2 + level * 2), f1 = art(ART_POWERBAR, 3 + level * 2);
            if (f0 != null && f1 != null && ph > 0) {
                c.save();
                c.clipRect(x, bottom - ph * k, x + b0.getWidth() * k, bottom + k);
                drawArt(c, f0, x, y);
                drawArt(c, f1, x, y + second * k);
                c.restore();
            }
        } else {
            bottom = y + 175 * k;
            if (ph > 0) {
                int[][] idx = {{4, 3}, {211, 214}, {230, 235}};
                paint.setColor(palette[idx[level][0]] | 0xFF000000);
                c.drawRect(x + 10 * k, bottom - ph * k, x + 12 * k, bottom + k, paint);
                paint.setColor(palette[idx[level][1]] | 0xFF000000);
                c.drawRect(x + 12 * k, bottom - ph * k, x + 14 * k, bottom + k, paint);
            }
        }
        Bitmap marker = art(ART_POWER, 0);
        if (marker != null) {
            drawArt(c, marker, x + (td ? 0 : 2 * k), bottom - (dh + 4) * k);
        }
        float ty = Math.min(powerArea.bottom - 10 * s, y + total * k + 14 * s);
        textFit(c, FONT_SMALL, String.valueOf(power), powerArea.centerX(), ty, 2 * s, 1, 255, powerArea.width());
        textFit(c, FONT_SMALL, String.valueOf(drain), powerArea.centerX(), ty + 22 * s, 2 * s, 1, level > 0 ? 255 : 150,
                powerArea.width());
    }

    private static float powerHeight(int value, int range, int factor) {
        float v = 0;
        for (int left = value; left > 0; left -= 100) {
            v += (range - 2 - v) / factor * Math.min(left, 100) / 100f;
        }
        return Math.max(0, Math.min(range - 2, v));
    }

    private void drawColumn(Canvas c, int col) {
        RectF panel = columnPanel[col], area = columnArea[col];
        bevel(c, panel, true, 0xFF000000);
        int count = 0;
        for (Item it : items) {
            if (it.column == col) {
                count++;
            }
        }
        float hy = panel.top + 20 * s;
        text(c, FONT_SMALL, col == 0 ? str(R.string.deck_structures) : str(R.string.deck_units), panel.left + 4 * k, hy, k, 0, 255);
        text(c, FONT_SMALL, String.valueOf(count), panel.right - 4 * k, hy, k, 2, 160);

        int per = compact ? 3 : 2;
        float gap = 2 * k;
        float cw = (area.width() - (per - 1) * gap) / per;
        float ch = cw * 0.75f;            // 64x48 cameo aspect
        float cellH = ch + 26 * s + gap;  // cost line below
        columnRowH[col] = cellH;
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
            it.bounds.set(left, top, left + cw, top + ch + 24 * s);
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
        drawArrow(c, B_UP + col, ART_STRIPUP, columnScroll[col] > 0, "^");
        drawArrow(c, B_DOWN + col, ART_STRIPDN, columnScroll[col] < maxScroll, "v");
    }

    private void drawArrow(Canvas c, int b, int artId, boolean enabled, String plain) {
        RectF r = btn[b];
        Bitmap a = art(artId, !enabled ? 2 : pressed(b) ? 1 : 0);
        if (a == null) {
            a = art(artId, 0);
        }
        if (a != null) {
            // centred in its half of the column, at the art's own size
            float x = r.centerX() - a.getWidth() * k / 2f, y = r.centerY() - a.getHeight() * k / 2f;
            drawArt(c, a, x, y);
        } else {
            bevel(c, r, pressed(b), face());
            text(c, FONT_SMALL, plain, r.centerX(), r.centerY(), k, 1, enabled ? 255 : 90);
        }
    }

    private void drawTile(Canvas c, Item it, RectF dst) {
        Bitmap b = cameo(it.key);
        c.save();
        c.clipRect(dst);
        if (b != null) {
            c.drawBitmap(b, null, dst, bmpPaint);
        } else {
            paint.setColor(face());
            c.drawRect(dst, paint);
            textFit(c, FONT_SMALL, ascii(name(it.key)), dst.centerX(), dst.centerY(), 2 * s, 1, 255, dst.width() - 2 * k);
        }
        if (it.state == ST_DISABLED) {
            paint.setColor(0xB0000000);
            c.drawRect(dst, paint);
        } else if (it.state == ST_BUILDING || it.state == ST_ON_HOLD || it.state == ST_CHARGING) {
            // the sidebar's clock: the remaining part stays dark, clockwise from 12 o'clock
            float done = it.progress / 1000f;
            float rad = (float) Math.hypot(dst.width(), dst.height());
            RectF oval = new RectF(dst.centerX() - rad, dst.centerY() - rad, dst.centerX() + rad, dst.centerY() + rad);
            paint.setColor(0x98000000);
            c.drawArc(oval, -90 + 360f * done, 360f * (1 - done), true, paint);
        }
        c.restore();

        boolean flash = flashKey == it.key;
        if (flash || it.state == ST_READY) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(k);
            paint.setColor(flash ? 0xFFFFFFFF : light());
            c.drawRect(dst.left + k / 2, dst.top + k / 2, dst.right - k / 2, dst.bottom - k / 2, paint);
            paint.setStyle(Paint.Style.FILL);
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
            paint.setStrokeWidth(k);
            paint.setColor(0xFFFFFFFF);
            c.drawRect(dst.left - k / 2, dst.top - k / 2, dst.right + k / 2, dst.bottom + k / 2, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        // READY / HOLD across the cameo, like the sidebar
        String word = it.state == ST_READY ? str(R.string.deck_ready) : it.state == ST_ON_HOLD ? str(R.string.deck_on_hold) : null;
        if (word != null) {
            float tw = textWidth(FONT_SMALL, word, k);
            paint.setColor(0xA0000000);
            c.drawRect(dst.centerX() - tw / 2 - 2 * k, dst.centerY() - 5 * k, dst.centerX() + tw / 2 + 2 * k, dst.centerY() + 5 * k, paint);
            text(c, FONT_SMALL, word, dst.centerX(), dst.centerY(), k, 1, 255);
        }
        if (it.cost > 0) {
            boolean dim = it.state == ST_DISABLED || it.cost > credits;
            text(c, FONT_SMALL, "$" + it.cost, dst.left + k, dst.bottom + 13 * s, 2 * s, 0, dim ? 110 : 255);
        }
    }

    private void drawStatus(Canvas c) {
        bevel(c, statusLine, true, 0xFF000000);
        String line;
        if ((flags & FL_AUTOPILOT) != 0) {
            line = str(R.string.deck_s_autopilot);
        } else if ((flags & FL_PLACING) != 0) {
            line = str(R.string.deck_s_place);
        } else if ((flags & FL_TARGETING) != 0) {
            line = str(R.string.deck_s_target);
        } else if ((flags & FL_REPAIR) != 0) {
            line = str(R.string.deck_s_repair);
        } else if ((flags & FL_SELL) != 0) {
            line = str(R.string.deck_s_sell);
        } else if (!hint.isEmpty() && SystemClock.uptimeMillis() - hintAt < 4000) {
            line = hint;
            postInvalidateDelayed(500);
        } else {
            line = str(R.string.deck_s_ready);
        }
        textFit(c, FONT_SMALL, line, statusLine.left + 4 * k, statusLine.centerY(), k, 0, 255, statusLine.width() - 8 * k);
    }

    private void say(String text) {
        hint = ascii(text);
        hintAt = SystemClock.uptimeMillis();
        invalidate();
    }

    private void say(int id, Object... args) {
        say(getContext().getString(id, args));
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
        say(right ? getContext().getString(R.string.deck_act_hold, n)
                : (it.state == ST_READY ? getContext().getString(R.string.deck_act_deploy, n) : n));
        flashKey = it.key;
        flashAt = SystemClock.uptimeMillis();
    }

    /** Long press on a cameo: give the item up (the engine wants a hold first, see poll()). */
    private void cancel(Item it) {
        if (it.state != ST_BUILDING && it.state != ST_ON_HOLD && it.state != ST_READY) {
            return;
        }
        if (it.state == ST_BUILDING) {
            pendingCancelKey = it.key;
            pendingCancelAt = SystemClock.uptimeMillis();
        }
        NativeBridge.pushCommand(NativeBridge.CMD_BUILD_CLICK, it.column, it.index, (it.key << 1) | 1);
        say(R.string.deck_act_cancel, name(it.key));
        flashKey = it.key;
        flashAt = SystemClock.uptimeMillis();
    }

    private void radarAt(float x, float y) {
        if ((flags & FL_RADAR) == 0 || mapW <= 0) {
            return;
        }
        RectF win = new RectF();
        radarWindow(win);
        RectF dst = radarDst(win);
        if (!dst.contains(x, y)) {
            return;
        }
        int cx = mapX + (int) (win.left + (x - dst.left) / dst.width() * win.width());
        int cy = mapY + (int) (win.top + (y - dst.top) / dst.height() * win.height());
        NativeBridge.pushCommand(NativeBridge.CMD_RADAR, cx, cy, 0);
    }

    private boolean enabled(int b) {
        switch (b) {
            case B_STATUS:
                return (flags & FL_AUTOPILOT) != 0;
            case B_REPAIR:
                return (flags & FL_REPAIR_OK) != 0;
            case B_MAP:
                return (flags & FL_RADAR) != 0;
            case B_STOP:
            case B_GUARD:
            case B_SCATTER:
                return selected > 0;
            case B_PRIMARY:
                return (flags & FL_PRIMARY_OK) != 0;
            case B_DESELECT:
                return selected > 0 || (flags & (FL_REPAIR | FL_SELL)) != 0;
            case B_ALERT:
                return (flags & FL_ALERT_KNOWN) != 0;
            default:
                return true;
        }
    }

    private void tap(int b) {
        if (b >= B_GROUP && b < B_GROUP + 4) {
            int g = b - B_GROUP;
            if ((groupBits & (1 << g)) == 0) {
                say(R.string.deck_hint_group_empty, g + 1);
            } else {
                // tap = pick the group, tap again = look at it
                NativeBridge.pushCommand(CMD_GROUP, g, activeGroup == g ? 2 : 0, 0);
            }
            return;
        }
        switch (b) {
            case B_MENU:
                NativeBridge.pushCommand(NativeBridge.CMD_KEY, SDL_SCANCODE_ESCAPE, 0, 0);
                break;
            case B_STATUS:
                NativeBridge.pushCommand(CMD_TAKE_CONTROL, 0, 0, 0);
                break;
            case B_REPAIR:
                NativeBridge.pushCommand(NativeBridge.CMD_REPAIR, 0, 0, 0);
                break;
            case B_SELL:
                NativeBridge.pushCommand(NativeBridge.CMD_SELL, 0, 0, 0);
                break;
            case B_MAP:
                radarZoom = !radarZoom;
                break;
            case B_SELECT:
                NativeBridge.pushCommand(CMD_JUMP, 2, 0, 0);
                say(R.string.deck_hint_select);
                break;
            case B_STOP:
            case B_GUARD:
            case B_SCATTER:
                NativeBridge.pushCommand(CMD_ORDER, b - B_STOP, 0, 0);
                break;
            case B_PRIMARY:
                NativeBridge.pushCommand(CMD_ORDER, 5, 0, 0);
                break;
            case B_DESELECT:
                NativeBridge.pushCommand(CMD_ORDER, 4, 0, 0);
                break;
            case B_UNITS:
                NativeBridge.pushCommand(CMD_JUMP, 0, 0, 0);
                break;
            case B_BASE:
                NativeBridge.pushCommand(CMD_JUMP, 1, 0, 0);
                break;
            case B_ALERT:
                NativeBridge.pushCommand(CMD_JUMP, 4, 0, 0);
                break;
            case B_SPEED: // one step faster, after the fastest the slowest
                NativeBridge.pushCommand(CMD_SPEED, speed <= 1 ? 6 : Math.min(6, speed - 1), 0, 0);
                break;
            case B_SAVE:
                if ((flags & FL_SAVE_OK) != 0) {
                    NativeBridge.pushCommand(CMD_QUICK, 0, 0, 0);
                    say(R.string.deck_hint_saved);
                } else {
                    say(R.string.deck_hint_campaign);
                }
                break;
            case B_LOAD:
                say((flags & FL_QUICKSAVE) != 0 ? R.string.deck_hint_load : (flags & FL_SAVE_OK) != 0
                        ? R.string.deck_hint_no_save : R.string.deck_hint_campaign);
                break;
            case B_UP:
            case B_UP + 1:
                columnScroll[b - B_UP] -= columnRowH[b - B_UP];
                break;
            case B_DOWN:
            case B_DOWN + 1:
                columnScroll[b - B_DOWN] += columnRowH[b - B_DOWN];
                break;
            default:
                break;
        }
    }

    /** Long press on a button. Returns false if the button has no hold action (a tap follows on release). */
    private boolean hold(int b) {
        if (b >= B_GROUP && b < B_GROUP + 4) {
            if (selected > 0) {
                NativeBridge.pushCommand(CMD_GROUP, b - B_GROUP, 1, 0);
                say(R.string.deck_hint_group_set, b - B_GROUP + 1);
            } else {
                say(R.string.deck_hint_group_none);
            }
        } else if (b == B_SELECT) {
            NativeBridge.pushCommand(CMD_ORDER, 3, 0, 0);
            say(R.string.deck_hint_army);
        } else if (b == B_LOAD && (flags & FL_QUICKSAVE) != 0) {
            NativeBridge.pushCommand(CMD_QUICK, 1, 0, 0);
            say(R.string.deck_hint_loaded);
        } else {
            return false;
        }
        flashBtn = b;
        flashAt = SystemClock.uptimeMillis();
        invalidate();
        return true;
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
                downBtn = -1;
                downColumn = -1;
                for (int b = 0; b < B_COUNT; b++) {
                    if (btn[b].contains(x, y)) {
                        downBtn = b;
                    }
                }
                downRadar = downBtn < 0 && radarArea.contains(x, y);
                if (downRadar) {
                    radarLock.setEmpty();
                    radarWindow(radarLock);
                    radarAt(x, y);
                    lastRadarCmd = SystemClock.uptimeMillis();
                } else if (downBtn >= 0) {
                    handler.postDelayed(longPress, 450);
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
                invalidate();
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
                if (downBtn >= 0 && !longFired && btn[downBtn].contains(x, y)) {
                    if (enabled(downBtn)) {
                        flashBtn = downBtn;
                        flashAt = SystemClock.uptimeMillis();
                        tap(downBtn);
                    }
                } else if (downItem != null && !longFired && !scrolled && downItem.bounds.contains(x, y)) {
                    // A tap on an item under construction puts it on hold (like a right click), a tap on hold resumes.
                    click(downItem, downItem.state == ST_BUILDING);
                }
                // fall through
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                downItem = null;
                downRadar = false;
                downBtn = -1;
                downColumn = -1;
                invalidate();
                return true;
            default:
                return true;
        }
    }
}
