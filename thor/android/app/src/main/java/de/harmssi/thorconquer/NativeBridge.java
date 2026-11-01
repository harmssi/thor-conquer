package de.harmssi.thorconquer;

/**
 * JNI-Bruecke zu libthorbridge.so (thor/native/thorbridge.c).
 *
 * Die Engine exportiert ihre Android-Konfiguration als einfache C-Funktionen
 * (setPathToResources, setPathToConfigsDirectory, setUseDoseModeState, ...).
 * thorbridge sucht sie per dlsym in der bereits geladenen Spielbibliothek.
 *
 * Milestone 2: Hier kommen spaeter die Aufrufe fuer Sidebar/Radar auf dem
 * zweiten Bildschirm hinzu (vgl. ThorBridge in generals-thor).
 */
public final class NativeBridge {
    private NativeBridge() {}

    /** @return 0 bei Erfolg, sonst Anzahl nicht gefundener Pflicht-Symbole. */
    public static native int configure(String nativeLib, String resourcePath, String configPath,
                                       boolean dosMode, boolean vsync, int frameLimit,
                                       String controllerDbPath, boolean dualScreen,
                                       int pointerSpeed, int scrollPercent, boolean skipMovies, int zoom);

    /* ---- Zweitbildschirm (common/thor.h) ---- */
    public static final int CMD_BUILD_CLICK = 1;
    public static final int CMD_RADAR = 2;
    public static final int CMD_REPAIR = 3;
    public static final int CMD_SELL = 4;
    public static final int CMD_KEY = 5;

    /** Demo / autopilot mode (see common/thor.h ThorDemoConfig). */
    public static native void configureDemo(String nativeLib, boolean enabled, int map, int seed, int opponents,
                                            int speed, boolean highlights, String logPath, String mapName);

    public static native void pushCommand(int type, int a, int b, int c);
    /** [0]=Alter ms, [1]=Nummer, danach Layout aus common/thor.h. */
    public static native int snapshot(int[] out);
    public static native int radar(byte[] out, int[] wh);
    public static native void palette(int[] out256);
    public static native int cameo(int key, int[] out, int[] wh);
    public static native String itemName(int key);
}
