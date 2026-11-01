package de.harmssi.thorconquer;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/**
 * Verwaltet den unteren Bildschirm: zeigt {@link ThorDeck} als Presentation auf dem ersten
 * Display, das nicht das Spiel-Display ist (beim Thor Display 4). Das Fenster ist
 * FLAG_NOT_FOCUSABLE, damit Controller-Eingaben beim Spiel bleiben.
 *
 * Ohne zweiten Bildschirm bleibt die Engine im Ein-Bildschirm-Modus (Original-Sidebar oben).
 */
final class ThorScreens implements DisplayManager.DisplayListener {
    private static final String TAG = "ThorConquer";
    private final Activity activity;
    private final DisplayManager displays;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Presentation presentation;
    private ThorDeck deck;
    private final boolean compactDeck;
    private final boolean td;

    ThorScreens(Activity activity, boolean compactDeck, boolean td) {
        this.activity = activity;
        this.compactDeck = compactDeck;
        this.td = td;
        this.displays = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
    }

    /** Gibt es einen zweiten Bildschirm? (vor dem Engine-Start abfragen) */
    static boolean hasSecondScreen(Activity activity) {
        return findSecondary(activity, (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE)) != null;
    }

    private static Display findSecondary(Activity activity, DisplayManager dm) {
        int own = Display.DEFAULT_DISPLAY;
        try {
            own = activity.getDisplay().getDisplayId();
        } catch (Throwable ignored) {
        }
        // Bevorzugt Presentation-Displays (Thor: Display 4)
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (d.getDisplayId() != own) {
                return d;
            }
        }
        for (Display d : dm.getDisplays()) {
            if (d.getDisplayId() != own) {
                return d;
            }
        }
        return null;
    }

    void start() {
        displays.registerDisplayListener(this, handler);
        attach();
    }

    void stop() {
        displays.unregisterDisplayListener(this);
        if (deck != null) {
            deck.stop();
        }
        if (presentation != null) {
            presentation.dismiss();
        }
        presentation = null;
        deck = null;
    }

    ThorDeck deck() {
        return deck;
    }

    void pause() {
        if (deck != null) {
            deck.stop();
        }
    }

    void resume() {
        if (deck != null) {
            deck.start();
        }
    }

    private void attach() {
        Display target = findSecondary(activity, displays);
        if (presentation != null && (target == null || presentation.getDisplay().getDisplayId() != target.getDisplayId())) {
            deck.stop();
            presentation.dismiss();
            presentation = null;
            deck = null;
        }
        if (target == null || presentation != null) {
            return;
        }
        presentation = new Presentation(activity, target);
        // Presentation contexts do not inherit the per-app language; apply the activity's locales.
        android.content.res.Configuration cfg = new android.content.res.Configuration(
                presentation.getContext().getResources().getConfiguration());
        cfg.setLocales(activity.getResources().getConfiguration().getLocales());
        android.content.Context deckCtx = presentation.getContext().createConfigurationContext(cfg);
        deck = new ThorDeck(deckCtx, compactDeck, td);
        presentation.setContentView(deck);
        presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        presentation.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        try {
            presentation.show();
            deck.start();
            Log.i(TAG, "Kommando-Deck auf Display " + target.getDisplayId());
        } catch (WindowManager.InvalidDisplayException e) {
            Log.w(TAG, "Presentation fehlgeschlagen", e);
            presentation = null;
            deck = null;
        }
    }

    @Override
    public void onDisplayAdded(int displayId) {
        attach();
    }

    @Override
    public void onDisplayRemoved(int displayId) {
        attach();
    }

    @Override
    public void onDisplayChanged(int displayId) {
    }
}
