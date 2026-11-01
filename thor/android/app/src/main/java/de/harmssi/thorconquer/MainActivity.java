package de.harmssi.thorconquer;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * Launcher: two game cards, data checklist, settings and the data setup sheet.
 * Fully controller-navigable (D-pad / stick to move focus, A to confirm).
 */
public class MainActivity extends Activity {
    public static final String EXTRA_ERROR_GAME = "error_game";
    private static final String PREFS = "thorconquer";

    private float f = 1f; // scale: design grid is 1920x1080
    private SharedPreferences prefs;
    private final GameCard[] cards = new GameCard[2];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Ui.init(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        for (Game g : Game.values()) {
            g.ensureDirs(this);
        }
        int w = Math.max(getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().heightPixels);
        f = w / 1920f;
        setContentView(buildUi());
        hideSystemBars();
        handleIntent(getIntent());
    }

    private void hideSystemBars() {
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (GameCard c : cards) {
            if (c != null) {
                c.refresh();
            }
        }
    }

    private void handleIntent(Intent intent) {
        Game g = Game.fromId(intent.getStringExtra(EXTRA_ERROR_GAME));
        if (g != null) {
            intent.removeExtra(EXTRA_ERROR_GAME);
            showMissingData(g);
        }
    }

    private float px(float design) {
        return design * f;
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new Backdrop());

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding((int) px(72), (int) px(52), (int) px(72), (int) px(44));
        root.addView(main, new FrameLayout.LayoutParams(-1, -1));

        // Header
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        main.addView(head, new LinearLayout.LayoutParams(-1, -2));
        View mark = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(Canvas c) {
                Emblem.chevron(c, getWidth() / 2f, getHeight() / 2f, getHeight() * 0.46f, Ui.AMBER, p);
            }
        };
        head.addView(mark, new LinearLayout.LayoutParams((int) px(76), (int) px(76)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding((int) px(24), 0, 0, 0);
        TextView title = text("THOR CONQUER", Ui.interBold(), 40, Ui.TEXT);
        title.setLetterSpacing(0.14f);
        titles.addView(title);
        titles.addView(label(getString(R.string.tagline), 15, Ui.TEXT_3));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        String version = "";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = "v" + pi.versionName;
        } catch (Exception ignored) {
        }
        head.addView(text(version, Ui.mono(), 18, Ui.TEXT_3));

        // Body: two cards + settings
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, 0, 1f);
        bodyLp.topMargin = (int) px(44);
        main.addView(body, bodyLp);

        for (int i = 0; i < 2; i++) {
            Game g = Game.values()[i];
            GameCard card = new GameCard(this, g);
            cards[i] = card;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.rightMargin = (int) px(28);
            body.addView(card, lp);
        }
        body.addView(buildSettings(), new LinearLayout.LayoutParams((int) px(500), -1));

        // Footer
        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        foot.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams footLp = new LinearLayout.LayoutParams(-1, -2);
        footLp.topMargin = (int) px(28);
        main.addView(foot, footLp);
        TextView setup = pill(getString(R.string.data_setup));
        setup.setOnClickListener(v -> showDataSheet());
        foot.addView(setup);
        TextView about = pill(getString(R.string.about));
        about.setOnClickListener(v -> showAbout());
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(-2, -2);
        alp.leftMargin = (int) px(16);
        foot.addView(about, alp);
        View spacer = new View(this);
        foot.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        foot.addView(label(getString(R.string.controls_legend), 14, Ui.TEXT_3));

        cards[0].requestFocus();
        return root;
    }

    private View buildSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) px(32), (int) px(30), (int) px(32), (int) px(30));
        box.setBackground(panelBg(Ui.PANEL, Ui.LINE, Ui.RADIUS));
        box.addView(label(getString(R.string.settings), 15, Ui.TEXT_3));

        addSegment(box, R.string.set_resolution, "zoom", new int[]{R.string.res_wide, R.string.res_hd, R.string.res_dos}, new int[]{2, 0, -1}, 2);
        addSegment(box, R.string.set_videos, "skipvideo", new int[]{R.string.videos_play, R.string.videos_skip}, new int[]{0, 1}, 0);
        addSegment(box, R.string.set_screens, "single", new int[]{R.string.screens_dual, R.string.screens_single}, new int[]{0, 1}, 0);
        addSegment(box, R.string.set_deck, "compact", new int[]{R.string.deck_large, R.string.deck_compact}, new int[]{0, 1}, 0);
        addSegment(box, R.string.set_scroll, "scroll", new int[]{R.string.slow, R.string.normal, R.string.fast}, new int[]{60, 100, 160}, 100);
        addSegment(box, R.string.set_cursor, "cursor", new int[]{R.string.slow, R.string.normal, R.string.fast}, new int[]{6, 10, 16}, 10);
        addLanguageRow(box);
        addSegment(box, R.string.set_demo, "demo", new int[]{R.string.off, R.string.on}, new int[]{0, 1}, 0);
        addSegment(box, R.string.set_highlights, "highlights", new int[]{R.string.on, R.string.off}, new int[]{1, 0}, 1);

        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(box);
        return sv;
    }

    private void addSegment(LinearLayout box, int labelRes, String key, int[] names, int[] values, int def) {
        TextView l = label(getString(labelRes), 13, Ui.TEXT_3);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = (int) px(30);
        box.addView(l, llp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, (int) px(64));
        rlp.topMargin = (int) px(12);
        box.addView(row, rlp);
        TextView[] chips = new TextView[names.length];
        int current = prefs.getInt(key, def);
        for (int i = 0; i < names.length; i++) {
            final int value = values[i];
            TextView chip = text(getString(names[i]), Ui.interMedium(), 20, Ui.TEXT_2);
            chip.setGravity(Gravity.CENTER);
            chip.setFocusable(true);
            chip.setClickable(true);
            chips[i] = chip;
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i > 0) {
                clp.leftMargin = (int) px(8);
            }
            row.addView(chip, clp);
            chip.setOnClickListener(v -> {
                prefs.edit().putInt(key, value).apply();
                for (int j = 0; j < chips.length; j++) {
                    styleChip(chips[j], values[j] == value);
                }
            });
            styleChip(chip, value == current);
        }
    }

    /** Language picker: follows the system language by default (per-app language, Android 13+). */
    private void addLanguageRow(LinearLayout box) {
        TextView l = label(getString(R.string.set_language), 13, Ui.TEXT_3);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-2, -2);
        llp.topMargin = (int) px(30);
        box.addView(l, llp);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, (int) px(64));
        rlp.topMargin = (int) px(12);
        box.addView(row, rlp);
        final String[] tags = {"", "en", "de", "es", "fr"};
        final String[] names = {getString(R.string.lang_system), "EN", "DE", "ES", "FR"};
        android.app.LocaleManager lm = getSystemService(android.app.LocaleManager.class);
        String current = "";
        if (lm != null && !lm.getApplicationLocales().isEmpty()) {
            current = lm.getApplicationLocales().get(0).getLanguage();
        }
        TextView[] chips = new TextView[tags.length];
        for (int i = 0; i < tags.length; i++) {
            final String tag = tags[i];
            TextView chip = text(names[i], i == 0 ? Ui.interMedium() : Ui.mono(), 18, Ui.TEXT_2);
            chip.setGravity(Gravity.CENTER);
            chip.setFocusable(true);
            chip.setClickable(true);
            chips[i] = chip;
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -1, i == 0 ? 1.6f : 1f);
            if (i > 0) {
                clp.leftMargin = (int) px(8);
            }
            row.addView(chip, clp);
            styleChip(chip, tag.equals(current));
            chip.setOnClickListener(v -> {
                if (lm != null) {
                    lm.setApplicationLocales(tag.isEmpty() ? android.os.LocaleList.getEmptyLocaleList()
                            : android.os.LocaleList.forLanguageTags(tag));
                }
            });
        }
    }

    private void styleChip(TextView chip, boolean selected) {
        StateListDrawable s = new StateListDrawable();
        int fill = selected ? Ui.alpha(Ui.AMBER, 0.14f) : Ui.BG_2;
        int line = selected ? Ui.AMBER : Ui.LINE;
        s.addState(new int[]{android.R.attr.state_focused}, panelBg(Ui.PANEL_2, Ui.TEXT, Ui.RADIUS_SM, 2));
        s.addState(new int[]{android.R.attr.state_pressed}, panelBg(Ui.PANEL_2, Ui.AMBER, Ui.RADIUS_SM, 2));
        s.addState(new int[]{}, panelBg(fill, line, Ui.RADIUS_SM, selected ? 2 : 1));
        chip.setBackground(s);
        chip.setTextColor(selected ? Ui.AMBER_2 : Ui.TEXT_2);
    }

    private TextView pill(String s) {
        TextView t = label(s, 14, Ui.TEXT);
        t.setPadding((int) px(28), (int) px(18), (int) px(28), (int) px(18));
        t.setFocusable(true);
        t.setClickable(true);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, panelBg(Ui.PANEL_2, Ui.TEXT, Ui.RADIUS_SM, 2));
        bg.addState(new int[]{android.R.attr.state_pressed}, panelBg(Ui.PANEL_2, Ui.AMBER, Ui.RADIUS_SM, 2));
        bg.addState(new int[]{}, panelBg(Ui.PANEL, Ui.LINE, Ui.RADIUS_SM, 1));
        t.setBackground(bg);
        return t;
    }

    private GradientDrawable panelBg(int fill, int line, float radius) {
        return panelBg(fill, line, radius, 1);
    }

    private GradientDrawable panelBg(int fill, int line, float radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(px(radius));
        d.setStroke(Math.max(1, (int) (stroke * Math.max(1f, f))), line);
        return d;
    }

    private TextView text(String s, android.graphics.Typeface tf, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTypeface(tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    private TextView label(String s, float size, int color) {
        TextView t = text(s, Ui.interSemi(), size, color);
        t.setLetterSpacing(Ui.LABEL_TRACKING);
        t.setAllCaps(true);
        return t;
    }

    // ------------------------------------------------------------------ actions

    private void launch(Game g) {
        if (!g.missingRequired(this).isEmpty()) {
            showMissingData(g);
            return;
        }
        Intent i = new Intent(this, GameActivity.class);
        i.putExtra(GameActivity.EXTRA_GAME, g.id);
        int zoom = prefs.getInt("zoom", 2);
        i.putExtra(GameActivity.EXTRA_DOS, zoom == -1);
        i.putExtra(GameActivity.EXTRA_ZOOM, zoom == 2 ? 2 : 0);
        i.putExtra(GameActivity.EXTRA_SINGLE, prefs.getInt("single", 0) == 1);
        i.putExtra(GameActivity.EXTRA_DECK_COMPACT, prefs.getInt("compact", 0) == 1);
        i.putExtra(GameActivity.EXTRA_SCROLL, prefs.getInt("scroll", 100));
        i.putExtra(GameActivity.EXTRA_SKIP_MOVIES, prefs.getInt("skipvideo", 0) == 1);
        if (prefs.getInt("demo", 0) == 1) {
            i.putExtra(GameActivity.EXTRA_DEMO, true);
            i.putExtra(GameActivity.EXTRA_DEMO_HIGHLIGHTS, prefs.getInt("highlights", 1) == 1);
            i.putExtra(GameActivity.EXTRA_DEMO_OPPONENTS, 2);
        }
        i.putExtra(GameActivity.EXTRA_POINTER_SPEED, prefs.getInt("cursor", 10));
        // Always on the top screen (display 0).
        ActivityOptions opts = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        startActivity(i, opts.toBundle());
    }

    private Dialog sheet(String title, View content) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) px(48), (int) px(40), (int) px(48), (int) px(36));
        box.setBackground(panelBg(Ui.PANEL, Ui.LINE_2, Ui.RADIUS));
        TextView t = text(title, Ui.interBold(), 32, Ui.TEXT);
        box.addView(t);
        ScrollView sv = new ScrollView(this);
        sv.addView(content);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = (int) px(20);
        box.addView(sv, slp);
        TextView close = pill(getString(R.string.close));
        close.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.topMargin = (int) px(24);
        clp.gravity = Gravity.END;
        box.addView(close, clp);
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) px(1240), (int) px(900));
            w.setDimAmount(0.75f);
        }
        close.requestFocus();
        return d;
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private void addBlock(LinearLayout parent, String lbl, View content, float top) {
        TextView l = label(lbl, 13, Ui.TEXT_3);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = (int) px(top);
        parent.addView(l, lp);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = (int) px(10);
        parent.addView(content, cp);
    }

    private TextView code(String s) {
        TextView t = text(s, Ui.mono(), 19, Ui.TEXT);
        t.setLineSpacing(px(8), 1f);
        t.setPadding((int) px(20), (int) px(16), (int) px(20), (int) px(16));
        t.setBackground(panelBg(Ui.BG_2, Ui.LINE, Ui.RADIUS_SM));
        t.setTextIsSelectable(true);
        return t;
    }

    private TextView para(String s) {
        TextView t = text(s, Ui.inter(), 21, Ui.TEXT_2);
        t.setLineSpacing(px(8), 1f);
        return t;
    }

    private void showDataSheet() {
        LinearLayout c = column();
        c.addView(para(getString(R.string.sheet_intro)));
        addBlock(c, getString(R.string.sheet_where),
                code(Game.RED_ALERT.dataDir(this) + "\n" + Game.TIBERIAN_DAWN.dataDir(this)), 28);
        addBlock(c, getString(R.string.sheet_ra_files),
                code("REDALERT.MIX  EXPAND.MIX  EXPAND2.MIX  HIRES1.MIX  LORES1.MIX   (CD1)\n"
                        + "allied/MAIN.MIX            (CD1)\n"
                        + "counterstrike/MAIN.MIX     (COUNTERSTRIKE)\n"
                        + "aftermath/MAIN.MIX         (AFTERMATH)"), 28);
        TextView rlay = para(getString(R.string.sheet_ra_layout));
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(-1, -2);
        rlp2.topMargin = (int) px(12);
        c.addView(rlay, rlp2);
        addBlock(c, getString(R.string.sheet_td_files),
                code("CONQUER.MIX  CCLOCAL.MIX  TEMPERAT.MIX  DESERT.MIX  WINTER.MIX\n"
                        + "SOUNDS.MIX  SPEECH.MIX  SCORES.MIX  TRANSIT.MIX  UPDATE*.MIX ...\n"
                        + "gdi/GENERAL.MIX  gdi/MOVIES.MIX     (CD1)\n"
                        + "nod/GENERAL.MIX  nod/MOVIES.MIX     (CD2)"), 28);
        TextView lay = para(getString(R.string.sheet_td_layout));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
        llp.topMargin = (int) px(12);
        c.addView(lay, llp);
        addBlock(c, getString(R.string.sheet_adb), code("./thor/copy-data.sh        # RA + TD\n./thor/copy-data.sh ra     # Red Alert only"), 28);
        TextView hint = para(getString(R.string.sheet_adb_hint) + "\n\n" + getString(R.string.sheet_note));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, -2);
        hlp.topMargin = (int) px(14);
        c.addView(hint, hlp);
        sheet(getString(R.string.sheet_title), c).show();
    }

    private void showAbout() {
        LinearLayout c = column();
        c.addView(para(getString(R.string.about_body)));
        sheet(getString(R.string.about_title), c).show();
    }

    private void showMissingData(Game g) {
        List<String> miss = g.missingRequired(this);
        LinearLayout c = column();
        c.addView(para(getString(R.string.missing_body, getString(g.title),
                miss.isEmpty() ? "—" : String.join("  ", miss), g.dataDir(this).getAbsolutePath())));
        Dialog d = sheet(getString(R.string.missing_title), c);
        d.setOnDismissListener(x -> {
            for (GameCard card : cards) {
                card.refresh();
            }
        });
        d.show();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Thor controller: A confirms the focused element.
        if (keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            View v = getCurrentFocus();
            if (v != null) {
                v.performClick();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    // ------------------------------------------------------------------ views

    /** Background: near-black, faint grid, faint scanlines, soft warm glow behind the header. */
    private final class Backdrop extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        @Override
        public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height();
            c.drawColor(Ui.BG);
            p.setShader(new RadialGradient(px(140), px(90), px(900), Ui.alpha(Ui.AMBER, 0.10f), 0, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            Ui.grid(c, w, h, px(48), p);
            Ui.scanlines(c, w, h, p);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.OPAQUE;
        }
    }

    /** Large focusable card for one game: emblem, title, data checklist, content, PLAY bar. */
    private final class GameCard extends View {
        private final Game game;
        private final int accent;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private List<String> missing;
        private boolean[] contentOk;

        GameCard(Context ctx, Game g) {
            super(ctx);
            game = g;
            accent = g == Game.RED_ALERT ? Ui.RED : Ui.AMBER;
            setFocusable(true);
            setClickable(true);
            setOnClickListener(v -> launch(game));
            setOnFocusChangeListener((v, has) -> invalidate());
            refresh();
        }

        void refresh() {
            missing = game.missingRequired(getContext());
            contentOk = new boolean[game.content.length];
            for (int i = 0; i < game.content.length; i++) {
                Game.Content ct = game.content[i];
                contentOk[i] = ct.supported && missing.isEmpty() && game.has(getContext(), ct.file);
            }
            invalidate();
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            boolean focus = isFocused() || isPressed();
            RectF r = new RectF(0, 0, w, h);
            Ui.panel(c, r, px(Ui.RADIUS), focus ? Ui.PANEL_2 : Ui.PANEL, focus ? accent : Ui.LINE, p);
            if (focus) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(px(2));
                p.setColor(accent);
                c.drawRoundRect(new RectF(px(1), px(1), w - px(1), h - px(1)), px(Ui.RADIUS), px(Ui.RADIUS), p);
                p.setStyle(Paint.Style.FILL);
            }
            // emblem field with soft glow
            float ex = px(120), ey = px(140);
            p.setShader(new RadialGradient(ex, ey, px(180), Ui.alpha(accent, focus ? 0.22f : 0.12f), 0, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, px(360), px(320), p);
            p.setShader(null);
            if (game == Game.RED_ALERT) {
                Emblem.redAlert(c, ex, ey, px(74), accent, p);
            } else {
                Emblem.tiberium(c, ex, ey, px(74), accent, p);
            }
            float tx = px(236);
            Paint tp = Ui.text(Ui.interBold(), px(48), Ui.TEXT);
            c.drawText(getString(game.title), tx, px(130), tp);
            Paint sp = Ui.label(px(14), Ui.TEXT_3);
            String sub = getString(game.subtitle);
            while (sp.measureText(sub) > w - tx - px(24) && sp.getTextSize() > px(9)) {
                sp.setTextSize(sp.getTextSize() - 1);
            }
            c.drawText(sub, tx, px(170), sp);

            // status chip
            boolean ok = missing.isEmpty();
            String st = getString(ok ? R.string.data_ready : R.string.data_missing);
            Paint cp = Ui.label(px(13), ok ? Ui.GREEN : Ui.CORAL);
            float cw = cp.measureText(st) + px(44);
            RectF chip = new RectF(tx, px(196), tx + cw, px(236));
            Ui.panel(c, chip, px(Ui.RADIUS_SM), Ui.alpha(ok ? Ui.GREEN : Ui.CORAL, 0.10f), Ui.alpha(ok ? Ui.GREEN : Ui.CORAL, 0.5f), p);
            p.setColor(ok ? Ui.GREEN : Ui.CORAL);
            c.drawCircle(chip.left + px(18), chip.centerY(), px(4.5f), p);
            c.drawText(st, chip.left + px(32), chip.centerY() + px(5), cp);

            // checklist
            float x = px(40), y = px(320);
            p.setColor(Ui.LINE);
            c.drawRect(x, y - px(36), w - px(40), y - px(35), p);
            c.drawText(getString(R.string.section_files), x, y, Ui.label(px(13), Ui.TEXT_3));
            y += px(44);
            Paint mono = Ui.text(Ui.mono(), px(19), Ui.TEXT_2);
            int nreq = game.requiredFiles.length;
            int rows = nreq > 3 ? (nreq + 1) / 2 : nreq;
            float colW = (w - px(80)) / 2f;
            for (int i = 0; i < nreq; i++) {
                String req = game.requiredFiles[i];
                boolean has = game.has(getContext(), req);
                float cx = x + (i / rows) * colW, cy = y + (i % rows) * px(32);
                check(c, cx, cy, has);
                mono.setColor(has ? Ui.TEXT_2 : Ui.CORAL);
                c.drawText(Game.display(req), cx + px(34), cy, mono);
            }
            y += rows * px(32) + px(14);
            c.drawText(getString(R.string.section_content), x, y, Ui.label(px(13), Ui.TEXT_3));
            y += px(44);
            Paint body = Ui.text(Ui.interMedium(), px(21), Ui.TEXT);
            for (int i = 0; i < game.content.length; i++) {
                Game.Content ct = game.content[i];
                check(c, x, y, contentOk[i]);
                body.setColor(contentOk[i] ? Ui.TEXT : Ui.TEXT_3);
                String t = getString(ct.label) + (ct.supported ? "" : "  · " + getString(R.string.not_yet));
                c.drawText(t, x + px(36), y, body);
                y += px(36);
            }
            Paint hint = Ui.text(Ui.inter(), px(18), Ui.TEXT_3);
            if (y + px(8) < h - px(128)) {
                c.drawText(getString(R.string.content_hint), x, y + px(8), hint);
            }

            // PLAY bar
            RectF bar = new RectF(px(24), h - px(104), w - px(24), h - px(24));
            int fill = ok ? (focus ? accent : Ui.alpha(accent, 0.16f)) : Ui.BG_2;
            Ui.panel(c, bar, px(Ui.RADIUS_SM), fill, ok ? accent : Ui.LINE, p);
            Paint pl = Ui.label(px(20), ok ? (focus ? Ui.BG : accent) : Ui.TEXT_3);
            pl.setTypeface(Ui.interBold());
            String play = getString(ok ? R.string.play : R.string.data_setup);
            float pw = pl.measureText(play) - pl.getLetterSpacing() * pl.getTextSize();
            c.drawText(play, bar.centerX() - pw / 2, bar.centerY() + px(7), pl);
        }

        private void check(Canvas c, float x, float y, boolean ok) {
            float cy = y - px(7);
            if (ok) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(px(2.5f));
                p.setStrokeCap(Paint.Cap.ROUND);
                p.setColor(Ui.GREEN);
                c.drawLine(x + px(2), cy, x + px(8), cy + px(6), p);
                c.drawLine(x + px(8), cy + px(6), x + px(20), cy - px(7), p);
            } else {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(px(1.5f));
                p.setColor(Ui.CORAL);
                c.drawCircle(x + px(10), cy, px(8), p);
            }
            p.setStyle(Paint.Style.FILL);
        }
    }
}
