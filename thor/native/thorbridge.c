/*
 * thorbridge - JNI-Bruecke zwischen der Thor-Conquer-Java-Huelle und der Engine.
 *
 * Die Engine (Fork sandstranger/Vanilla-Conquer, Branch android) exportiert ihre
 * Android-Einstellungen als normale C-Funktionen. Diese Bibliothek wird NACH der
 * Spielbibliothek (libvanillara.so / libvanillatd.so) geladen und ruft die
 * Funktionen per dlsym auf, bevor SDL den Engine-Thread (SDL_main) startet.
 *
 * Milestone 2: hier kommen die JNI-Einstiege fuer die Sidebar/Radar-Ausgabe auf
 * dem zweiten Bildschirm hinzu.
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <android/log.h>

#define TAG "ThorBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef void (*fn_str)(const char*);
typedef void (*fn_bool)(bool);
typedef void (*fn_int)(int);

/* Zweitbildschirm-Schnittstelle der Engine (common/thor.cpp). */
typedef void (*fn_push)(int, int, int, int);
typedef int (*fn_snapshot)(int*, int);
typedef int (*fn_radar)(unsigned char*, int, int*, int*);
typedef void (*fn_palette)(unsigned int*);
typedef int (*fn_cameo)(int, unsigned int*, int, int*, int*);
typedef int (*fn_name)(int, char*, int);

static fn_push g_push;
static fn_snapshot g_snapshot;
static fn_radar g_radar;
static fn_palette g_palette;
static fn_cameo g_cameo;
static fn_name g_name;

static void* sym(void* h, const char* name, int* missing, int required)
{
    void* p = dlsym(h, name);
    if (!p) {
        if (required) {
            LOGE("Symbol fehlt: %s", name);
            (*missing)++;
        } else {
            LOGI("Symbol (optional) fehlt: %s", name);
        }
    }
    return p;
}

JNIEXPORT jint JNICALL Java_de_harmssi_thorconquer_NativeBridge_configure(JNIEnv* env,
                                                                          jclass cls,
                                                                          jstring jlib,
                                                                          jstring jres,
                                                                          jstring jcfg,
                                                                          jboolean dosMode,
                                                                          jboolean vsync,
                                                                          jint frameLimit,
                                                                          jstring jctrlDb,
                                                                          jboolean dualScreen,
                                                                          jint pointerSpeed,
                                                                          jint scrollPercent,
                                                                          jboolean skipMovies,
                                                                          jint zoom)
{
    (void)cls;
    int missing = 0;
    const char* lib = (*env)->GetStringUTFChars(env, jlib, NULL);
    const char* res = (*env)->GetStringUTFChars(env, jres, NULL);
    const char* cfg = (*env)->GetStringUTFChars(env, jcfg, NULL);
    const char* ctrlDb = (*env)->GetStringUTFChars(env, jctrlDb, NULL);

    char soname[256];
    snprintf(soname, sizeof(soname), "lib%s.so", lib);
    void* h = dlopen(soname, RTLD_NOW | RTLD_NOLOAD);
    if (!h) {
        LOGE("dlopen(%s) fehlgeschlagen: %s", soname, dlerror());
        missing = 100;
        goto out;
    }

    /* SDL-Hints ueber Umgebungsvariablen (SDL_GetHint faellt auf getenv zurueck). */
    setenv("SDL_ANDROID_TRAP_BACK_BUTTON", "1", 1); /* Zurueck beendet nicht das Spiel */
    setenv("SDL_IOS_ORIENTATIONS", "LandscapeLeft LandscapeRight", 1);
    setenv("SDL_TOUCH_MOUSE_EVENTS", "0", 1); /* Touch-Gesten wertet die Engine selbst aus */

    fn_str setRes = (fn_str)sym(h, "setPathToResources", &missing, 1);
    fn_str setCfg = (fn_str)sym(h, "setPathToConfigsDirectory", &missing, 1);
    fn_bool setDos = (fn_bool)sym(h, "setUseDoseModeState", &missing, 0);
    fn_bool setVsync = (fn_bool)sym(h, "setEnableVsyncState", &missing, 0);
    fn_int setFps = (fn_int)sym(h, "setFrameRateLimit", &missing, 0);
    fn_bool setOsc = (fn_bool)sym(h, "setOnScreenControlsState", &missing, 0);
    fn_str setDb = (fn_str)sym(h, "setPathToSDLControllerDB", &missing, 0);

    if (setRes)
        setRes(res);
    if (setCfg)
        setCfg(cfg);
    if (setDos)
        setDos(dosMode ? true : false);
    if (setVsync)
        setVsync(vsync ? true : false);
    if (setFps)
        setFps(frameLimit);
    /* "On-Screen-Controls aktiv" bedeutet in der Engine: absolute Maus (kein RawInput),
       das ist fuer Touch richtig. */
    if (setOsc)
        setOsc(true);
    if (setDb)
        setDb(ctrlDb);

    fn_int setPtr = (fn_int)sym(h, "setControllerPointerSpeed", &missing, 0);
    if (setPtr && pointerSpeed > 0)
        setPtr(pointerSpeed);
    fn_int setScroll = (fn_int)sym(h, "thorSetScrollScale", &missing, 0);
    if (setScroll && scrollPercent > 0)
        setScroll(scrollPercent);

    fn_int setZoom = (fn_int)sym(h, "thorSetZoom", &missing, 0);
    if (setZoom)
        setZoom(zoom);
    fn_bool setSkip = (fn_bool)sym(h, "thorSetSkipMovies", &missing, 0);
    if (setSkip)
        setSkip(skipMovies ? true : false);

    fn_bool setDual = (fn_bool)sym(h, "thorSetDualScreen", &missing, 0);
    if (setDual)
        setDual(dualScreen ? true : false);
    g_push = (fn_push)sym(h, "thorPushCommand", &missing, 0);
    g_snapshot = (fn_snapshot)sym(h, "thorSnapshot", &missing, 0);
    g_radar = (fn_radar)sym(h, "thorRadar", &missing, 0);
    g_palette = (fn_palette)sym(h, "thorPalette", &missing, 0);
    g_cameo = (fn_cameo)sym(h, "thorCameo", &missing, 0);
    g_name = (fn_name)sym(h, "thorItemName", &missing, 0);

    LOGI("%s: res=%s cfg=%s dos=%d vsync=%d fps=%d dual=%d",
         soname, res, cfg, (int)dosMode, (int)vsync, (int)frameLimit, (int)dualScreen);
    dlclose(h); /* NOLOAD-Referenz wieder freigeben */

out:
    (*env)->ReleaseStringUTFChars(env, jlib, lib);
    (*env)->ReleaseStringUTFChars(env, jres, res);
    (*env)->ReleaseStringUTFChars(env, jcfg, cfg);
    (*env)->ReleaseStringUTFChars(env, jctrlDb, ctrlDb);
    return missing;
}

/* ---- Zweitbildschirm ---- */

JNIEXPORT void JNICALL Java_de_harmssi_thorconquer_NativeBridge_pushCommand(JNIEnv* env, jclass cls, jint type, jint a, jint b, jint c)
{
    (void)env;
    (void)cls;
    if (g_push)
        g_push(type, a, b, c);
}

JNIEXPORT jint JNICALL Java_de_harmssi_thorconquer_NativeBridge_snapshot(JNIEnv* env, jclass cls, jintArray out)
{
    (void)cls;
    if (!g_snapshot)
        return 0;
    jint cap = (*env)->GetArrayLength(env, out);
    jint* buf = (*env)->GetIntArrayElements(env, out, NULL);
    int n = g_snapshot((int*)buf, cap);
    (*env)->ReleaseIntArrayElements(env, out, buf, 0);
    return n;
}

JNIEXPORT jint JNICALL Java_de_harmssi_thorconquer_NativeBridge_radar(JNIEnv* env, jclass cls, jbyteArray out, jintArray wh)
{
    (void)cls;
    if (!g_radar)
        return 0;
    int w = 0, h = 0;
    jint cap = (*env)->GetArrayLength(env, out);
    jbyte* buf = (*env)->GetByteArrayElements(env, out, NULL);
    int n = g_radar((unsigned char*)buf, cap, &w, &h);
    (*env)->ReleaseByteArrayElements(env, out, buf, 0);
    jint dims[2] = {w, h};
    (*env)->SetIntArrayRegion(env, wh, 0, 2, dims);
    return n;
}

JNIEXPORT void JNICALL Java_de_harmssi_thorconquer_NativeBridge_palette(JNIEnv* env, jclass cls, jintArray out)
{
    (void)cls;
    if (!g_palette)
        return;
    unsigned int pal[256];
    g_palette(pal);
    (*env)->SetIntArrayRegion(env, out, 0, 256, (const jint*)pal);
}

JNIEXPORT jint JNICALL Java_de_harmssi_thorconquer_NativeBridge_cameo(JNIEnv* env, jclass cls, jint key, jintArray out, jintArray wh)
{
    (void)cls;
    if (!g_cameo)
        return 0;
    int w = 0, h = 0;
    jint cap = (*env)->GetArrayLength(env, out);
    jint* buf = (*env)->GetIntArrayElements(env, out, NULL);
    int n = g_cameo(key, (unsigned int*)buf, cap, &w, &h);
    (*env)->ReleaseIntArrayElements(env, out, buf, 0);
    jint dims[2] = {w, h};
    (*env)->SetIntArrayRegion(env, wh, 0, 2, dims);
    return n;
}

JNIEXPORT jstring JNICALL Java_de_harmssi_thorconquer_NativeBridge_itemName(JNIEnv* env, jclass cls, jint key)
{
    (void)cls;
    char buf[128];
    buf[0] = 0;
    if (g_name)
        g_name(key, buf, sizeof(buf));
    /* Spieltexte sind Codepage 437/Latin-1, NewStringUTF erwartet (modifiziertes) UTF-8. */
    for (char* p = buf; *p; p++) {
        if ((unsigned char)*p >= 0x80)
            *p = '?';
    }
    return (*env)->NewStringUTF(env, buf);
}

JNIEXPORT void JNICALL Java_de_harmssi_thorconquer_NativeBridge_configureDemo(JNIEnv* env, jclass cls, jstring jlib,
                                                                              jboolean enabled, jint map, jint seed,
                                                                              jint opponents, jint speed,
                                                                              jboolean highlights, jstring jlog,
                                                                              jstring jmapName)
{
    (void)cls;
    const char* lib = (*env)->GetStringUTFChars(env, jlib, NULL);
    const char* logp = (*env)->GetStringUTFChars(env, jlog, NULL);
    const char* mapName = (*env)->GetStringUTFChars(env, jmapName, NULL);
    char soname[256];
    snprintf(soname, sizeof(soname), "lib%s.so", lib);
    void* h = dlopen(soname, RTLD_NOW | RTLD_NOLOAD);
    if (h) {
        typedef void (*fn_demo)(bool, int, int, int, int, bool, const char*, const char*);
        fn_demo f = (fn_demo)dlsym(h, "thorSetDemo");
        if (f) {
            f(enabled ? true : false, map, seed, opponents, speed, highlights ? true : false, logp, mapName);
            LOGI("demo: map=%d seed=%d opponents=%d speed=%d highlights=%d log=%s", (int)map, (int)seed,
                 (int)opponents, (int)speed, (int)highlights, logp);
        }
        dlclose(h);
    }
    (*env)->ReleaseStringUTFChars(env, jlib, lib);
    (*env)->ReleaseStringUTFChars(env, jlog, logp);
    (*env)->ReleaseStringUTFChars(env, jmapName, mapName);
}
