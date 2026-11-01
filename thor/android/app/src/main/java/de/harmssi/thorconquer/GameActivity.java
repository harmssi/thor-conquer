package de.harmssi.thorconquer;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.util.List;

/**
 * Startet Vanilla Conquer (SDL2) im Vollbild auf dem oberen Bildschirm (Display 0).
 *
 * Intent-Extras:
 *   game   = "ra" | "td"            (Pflicht, Standard "ra")
 *   dos    = boolean                (320x200-DOS-Modus statt 640x400, Standard false)
 *   force  = boolean                (trotz fehlender Daten starten - nur zum Testen)
 *
 * Laeuft im Prozess ":game"; beim Beenden wird der Prozess beendet, damit die Engine
 * (globale Zustaende, SDL2) beim naechsten Start frisch ist.
 *
 * Milestone 2: Sidebar/Radar sollen per android.app.Presentation auf Display 4
 * (unterer Bildschirm) wandern. Der Hook dafuer ist {@link #onEngineConfigured()}.
 */
public class GameActivity extends SDLActivity {
    public static final String TAG = "ThorConquer";
    public static final String EXTRA_GAME = "game";
    public static final String EXTRA_DOS = "dos";
    public static final String EXTRA_FORCE = "force";
    public static final String EXTRA_SINGLE = "single";
    public static final String EXTRA_DECK_COMPACT = "deck_compact";
    public static final String EXTRA_POINTER_SPEED = "pointer_speed";
    public static final String EXTRA_SCROLL = "scroll_percent";
    public static final String EXTRA_SKIP_MOVIES = "skip_movies";
    /** 2 = 960x540 shown at 2x (default), 0 = classic 640x400. */
    public static final String EXTRA_ZOOM = "zoom";
    // Demo / autopilot (recording): --ez demo true --ei map 0 --ei seed 1234 --ei opponents 2
    //                                --ei speed 3 --ez highlights true
    public static final String EXTRA_DEMO = "demo";
    public static final String EXTRA_DEMO_MAP = "map";
    public static final String EXTRA_DEMO_MAP_NAME = "map_name"; // --es map_name "Equal Opportunity"
    public static final String EXTRA_DEMO_SEED = "seed";
    public static final String EXTRA_DEMO_OPPONENTS = "opponents";
    public static final String EXTRA_DEMO_SPEED = "speed";
    public static final String EXTRA_DEMO_HIGHLIGHTS = "highlights";

    private Game game = Game.RED_ALERT;
    private ThorScreens screens;
    private boolean dualScreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Intent intent = getIntent();
        Game g = Game.fromId(intent.getStringExtra(EXTRA_GAME));
        if (g != null) {
            game = g;
        }
        boolean force = intent.getBooleanExtra(EXTRA_FORCE, false);
        game.ensureDirs(this);
        List<String> missing = game.missingRequired(this);
        boolean abort = !missing.isEmpty() && !force;

        if (abort) {
            // Spieldaten fehlen: Engine gar nicht erst starten (sie wuerde ohne MIX-Dateien
            // nur abstuerzen), sondern den Launcher mit Fehlermeldung zeigen.
            Log.e(TAG, game.id + ": Spieldaten fehlen in " + game.dataDir(this) + ": " + missing);
            Intent back = new Intent(this, MainActivity.class);
            back.putExtra(MainActivity.EXTRA_ERROR_GAME, game.id);
            back.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(back);
        }

        super.onCreate(savedInstanceState);
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        if (abort) {
            finish();
            return;
        }
        if (mBrokenLibraries) {
            return; // SDL zeigt selbst einen Fehlerdialog
        }

        File cfg = Game.configDir(this);
        // Zweitbildschirm: Bauleiste/Radar unten, oben nur die Karte. Extra "single"=true erzwingt
        // den Ein-Bildschirm-Modus (Original-Sidebar oben).
        dualScreen = !intent.getBooleanExtra(EXTRA_SINGLE, false) && ThorScreens.hasSecondScreen(this);
        int rc = NativeBridge.configure(game.nativeLib,
                game.dataDir(this).getAbsolutePath(),
                cfg.getAbsolutePath(),
                intent.getBooleanExtra(EXTRA_DOS, false),
                true, 60, "", dualScreen,
                intent.getIntExtra(EXTRA_POINTER_SPEED, 10),
                intent.getIntExtra(EXTRA_SCROLL, 100),
                intent.getBooleanExtra(EXTRA_SKIP_MOVIES, false) || intent.getBooleanExtra(EXTRA_DEMO, false),
                intent.getIntExtra(EXTRA_ZOOM, 2));
        if (intent.getBooleanExtra(EXTRA_DEMO, false)) {
            File log = new File(Game.baseDir(this), "demo-events.jsonl");
            NativeBridge.configureDemo(game.nativeLib, true,
                    intent.getIntExtra(EXTRA_DEMO_MAP, 0),
                    intent.getIntExtra(EXTRA_DEMO_SEED, 0),
                    intent.getIntExtra(EXTRA_DEMO_OPPONENTS, 1),
                    intent.getIntExtra(EXTRA_DEMO_SPEED, -1),
                    intent.getBooleanExtra(EXTRA_DEMO_HIGHLIGHTS, true),
                    log.getAbsolutePath(),
                    intent.getStringExtra(EXTRA_DEMO_MAP_NAME) != null ? intent.getStringExtra(EXTRA_DEMO_MAP_NAME) : "");
            Log.i(TAG, "Demo mode, event log: " + log);
        }
        Log.i(TAG, "Engine " + game.nativeLib + " konfiguriert (rc=" + rc + ", dual=" + dualScreen
                + "), Daten: " + game.dataDir(this) + ", Config: " + cfg);
        onEngineConfigured();
    }

    /** Unteren Bildschirm (Kommando-Deck) einrichten. */
    protected void onEngineConfigured() {
        if (dualScreen) {
            screens = new ThorScreens(this, getIntent().getBooleanExtra(EXTRA_DECK_COMPACT, false),
                    game == Game.TIBERIAN_DAWN);
            screens.start();
        }
    }

    /** Thor controller: L1/R1 move the focus ring on the deck, A builds the focused item. */
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        ThorDeck deck = screens != null ? screens.deck() : null;
        if (deck != null) {
            int code = event.getKeyCode();
            if (code == android.view.KeyEvent.KEYCODE_BUTTON_L1 || code == android.view.KeyEvent.KEYCODE_BUTTON_R1) {
                if (event.getAction() == android.view.KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                    deck.moveFocus(code == android.view.KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1);
                }
                return true;
            }
            if (code == android.view.KeyEvent.KEYCODE_BUTTON_A && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && event.getRepeatCount() == 0 && deck.activateFocus()) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (screens != null) {
            screens.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (screens != null) {
            screens.resume();
        }
    }

    @Override
    protected String[] getLibraries() {
        return new String[]{"c++_shared", "SDL2", "openal", game.nativeLib, "thorbridge"};
    }

    @Override
    protected String getMainSharedObject() {
        return getApplicationInfo().nativeLibraryDir + "/lib" + game.nativeLib + ".so";
    }

    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        // Die Engine erzeugt ein 0x0-Vollbildfenster; SDL wuerde daraus "Hochformat" ableiten.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override
    protected void onDestroy() {
        if (screens != null) {
            screens.stop();
            screens = null;
        }
        super.onDestroy();
        if (isFinishing()) {
            // Eigener Prozess ":game" - sauber beenden, damit ein Neustart frisch beginnt.
            System.exit(0);
        }
    }
}
