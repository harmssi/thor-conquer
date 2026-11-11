/*
** Thor Conquer - gemeinsamer Teil der Zweitbildschirm-Schnittstelle (siehe thor.h).
*/
#include "thor.h"

#ifdef THOR_ENABLED

#include <SDL.h>
#include <map>
#include <mutex>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

namespace
{
    struct CameoData
    {
        int w = 0;
        int h = 0;
        std::vector<unsigned char> pixels; // Palettenindizes
    };

    std::mutex g_lock;
    bool g_dual = false;
    bool g_skip_movies = false;
    int g_zoom = 2; // 2 = 960x540 (2x), 0 = classic 640x400
    ThorDemoConfig g_demo = {false, 0, 0, 1, -1, true, {0}};
    std::string g_demo_log;
    uint32_t g_demo_start = 0;
    int g_radar_pulse = 0;
    int g_highlight_key = 0;
    int g_highlight_serial = 0;
    int g_menu_step = 0;
    uint32_t g_menu_next = 0;
    struct QueuedCommand
    {
        ThorCommand cmd;
        uint32_t ticks;
    };
    std::vector<QueuedCommand> g_commands;

    std::vector<int> g_snapshot;
    std::vector<unsigned char> g_radar;
    int g_radar_w = 0;
    int g_radar_h = 0;
    uint32_t g_snapshot_ticks = 0;
    uint32_t g_snapshot_serial = 0;

    std::map<int, CameoData> g_cameos;
    std::map<int, std::string> g_names;
    std::vector<int> g_wanted; // interface art the deck asked for (decoded in the game thread)

    /* Nur Spiel-Thread. */
    float g_scroll_x = 0;
    float g_scroll_y = 0;
    float g_scroll_scale = 1.0f; // Launcher setting 'scroll speed'
    uint32_t g_last_snapshot = 0;
    uint32_t g_last_radar = 0;
    uint32_t g_last_frame = 0;
} // namespace

bool Thor_Dual_Screen()
{
    return g_dual;
}

bool Thor_Big_View()
{
    return g_dual && g_zoom == 2;
}

static int g_view_state = -1;

int Thor_View_State()
{
    return g_view_state;
}

void Thor_Note_View_State(int state)
{
    g_view_state = state;
}

ThorMenuView::ThorMenuView()
    : Previous(g_view_state)
{
    if (Thor_Big_View()) {
        Thor_Game_Set_View(false);
    }
}

ThorMenuView::~ThorMenuView()
{
    if (Thor_Big_View() && Previous == 1) {
        Thor_Game_Set_View(true);
    }
}

bool Thor_Skip_Movies()
{
    return g_skip_movies || g_demo.Enabled;
}

const ThorDemoConfig& Thor_Demo()
{
    return g_demo;
}

uint32_t Thor_Demo_Ms()
{
    return g_demo_start ? SDL_GetTicks() - g_demo_start : 0;
}

void Thor_Demo_Log(const char* type, bool scripted, const char* fmt, ...)
{
    if (!g_demo.Enabled || g_demo_log.empty()) {
        return;
    }
    char desc[256];
    va_list args;
    va_start(args, fmt);
    vsnprintf(desc, sizeof(desc), fmt, args);
    va_end(args);
    for (char* p = desc; *p; p++) {
        if (*p == '"' || *p == '\\' || (unsigned char)*p < 0x20) {
            *p = '\'';
        }
    }
    FILE* f = fopen(g_demo_log.c_str(), "a");
    if (f) {
        fprintf(f, "{\"t_ms\":%u,\"type\":\"%s\",\"mode\":\"%s\",\"desc\":\"%s\"}\n",
                Thor_Demo_Ms(), type, scripted ? "scripted" : "automatic", desc);
        fclose(f);
    }
}

static bool g_taken_over = false;

bool Thor_Demo_Autopilot()
{
    return g_demo.Enabled && !g_taken_over;
}

void Thor_Demo_Take_Control()
{
    if (g_demo.Enabled && !g_taken_over) {
        g_taken_over = true;
        Thor_Demo_Log("take_control", true, "player took over from the autopilot");
    }
}

int Thor_Demo_Pick_Map(const char* const* names, int count)
{
    if (!g_demo.Enabled || count <= 0) {
        return -1;
    }
    if (g_demo.MapName[0]) {
        for (int i = 0; i < count; i++) {
            if (names[i] && strcasestr(names[i], g_demo.MapName)) {
                Thor_Demo_Log("map", false, "map by name: %s", names[i]);
                return i;
            }
        }
    }
    int idx = g_demo.Map >= 0 && g_demo.Map < count ? g_demo.Map : 0;
    Thor_Demo_Log("map", false, "map by index %d: %s", idx, names[idx] ? names[idx] : "?");
    return idx;
}

void Thor_Demo_Radar_Pulse()
{
    std::lock_guard<std::mutex> guard(g_lock);
    g_radar_pulse++;
}

void Thor_Demo_Highlight(int key)
{
    std::lock_guard<std::mutex> guard(g_lock);
    g_highlight_key = key;
    g_highlight_serial++;
}

/*
** Called from the SDL input pump, so it also runs while the engine sits in a menu.
** In demo mode it walks the main menu into a skirmish game with scripted clicks.
*/
void Thor_Input_Tick()
{
    if (!g_demo.Enabled) {
        return;
    }
    uint32_t now = SDL_GetTicks();
    if (g_demo_start == 0) {
        g_demo_start = now;
        g_menu_next = now + 6500; // main menu needs a few seconds after start
        Thor_Demo_Log("start", false, "demo mode map=%d seed=%d opponents=%d speed=%d", g_demo.Map, g_demo.Seed,
                      g_demo.Opponents, g_demo.Speed);
    }
    if (Thor_Game_In_Mission() || g_menu_step < 0 || now < g_menu_next) {
        return;
    }
    int x, y;
    uint32_t delay = 0;
    if (!Thor_Game_Demo_Menu_Step(g_menu_step, x, y, delay)) {
        g_menu_step = -1; // script finished
        return;
    }
    Thor_Inject_Click(x, y, false);
    Thor_Demo_Log("menu", true, "menu step %d click %d,%d", g_menu_step, x, y);
    g_menu_step++;
    g_menu_next = now + delay;
}

void Thor_Touch_Scroll(float dx, float dy)
{
    /* Finger nach rechts = Karte folgt dem Finger = Sichtfenster nach links. */
    g_scroll_x -= dx * g_scroll_scale;
    g_scroll_y -= dy * g_scroll_scale;
}

void Thor_Frame()
{
    /*
    ** 1) Befehle vom unteren Bildschirm ausfuehren.
    */
    if (Thor_Big_View()) {
        Thor_Game_Set_View(true);
    }
    uint32_t now = SDL_GetTicks();
    bool resumed = (now - g_last_frame) > 500; // z. B. nach Menue/Video: alte Eingaben verwerfen
    g_last_frame = now;

    std::vector<QueuedCommand> cmds;
    {
        std::lock_guard<std::mutex> guard(g_lock);
        cmds.swap(g_commands);
    }
    for (const QueuedCommand& q : cmds) {
        if (now - q.ticks < 1000) {
            Thor_Game_Command(q.cmd);
        }
    }
    if (resumed) {
        g_scroll_x = g_scroll_y = 0;
    }

    if (Thor_Demo_Autopilot()) {
        Thor_Game_Demo_Tick();
    }

    /*
    ** 2) Touch-Scrollen anwenden (ganze Pixel).
    */
    int px = (int)g_scroll_x;
    int py = (int)g_scroll_y;
    if (px != 0 || py != 0) {
        g_scroll_x -= px;
        g_scroll_y -= py;
        Thor_Game_Scroll(Thor_Pixel_To_Lepton(px), Thor_Pixel_To_Lepton(py));
    }

    /*
    ** 3) Schnappschuss fuer die Java-Seite (max. ~12 Hz, Radar ~4 Hz).
    */
    if (!g_dual) {
        return;
    }
    if (now - g_last_snapshot < 80) {
        return;
    }
    g_last_snapshot = now;

    bool want_radar = (now - g_last_radar) >= 250;
    std::vector<int> snap;
    std::vector<unsigned char> radar;
    int rw = 0, rh = 0;
    Thor_Game_Snapshot(snap, want_radar ? &radar : nullptr, rw, rh);
    if (want_radar) {
        g_last_radar = now;
    }

    /*
    ** Cameos und Namen fuer neue Eintraege im Spiel-Thread vorbereiten
    ** (Shape-Dekodierung ist nicht threadsicher).
    */
    std::vector<int> new_keys;
    if (snap.size() >= THOR_HEADER_INTS) {
        for (size_t i = THOR_HEADER_INTS; i + THOR_ITEM_INTS <= snap.size(); i += THOR_ITEM_INTS) {
            int key = snap[i + 2];
            std::lock_guard<std::mutex> guard(g_lock);
            if (g_cameos.find(key) == g_cameos.end()) {
                new_keys.push_back(key);
            }
        }
    }
    {
        std::lock_guard<std::mutex> guard(g_lock);
        new_keys.insert(new_keys.end(), g_wanted.begin(), g_wanted.end());
        g_wanted.clear();
    }
    for (int key : new_keys) {
        CameoData cd;
        if (!Thor_Game_Cameo(key, cd.pixels, cd.w, cd.h)) {
            cd.w = cd.h = 0;
        }
        std::string name = Thor_Game_Item_Name(key);
        std::lock_guard<std::mutex> guard(g_lock);
        g_cameos[key] = cd;
        g_names[key] = name;
    }

    std::lock_guard<std::mutex> guard(g_lock);
    g_snapshot.swap(snap);
    if (want_radar) {
        g_radar.swap(radar);
        g_radar_w = rw;
        g_radar_h = rh;
    }
    g_snapshot_ticks = now;
    g_snapshot_serial++;
}

/*
** Exporte fuer libthorbridge.so (per dlsym gesucht).
*/
extern "C" {

__attribute__((used)) __attribute__((visibility("default"))) void thorSetDualScreen(bool dual)
{
    g_dual = dual;
}

__attribute__((used)) __attribute__((visibility("default"))) void
thorSetDemo(bool enabled, int map, int seed, int opponents, int speed, bool highlights, const char* log_path,
            const char* map_name)
{
    snprintf(g_demo.MapName, sizeof(g_demo.MapName), "%s", map_name ? map_name : "");
    g_demo.Enabled = enabled;
    g_demo.Map = map;
    g_demo.Seed = seed;
    g_demo.Opponents = opponents;
    g_demo.Speed = speed;
    g_demo.Highlights = highlights;
    g_demo_log = log_path ? log_path : "";
    if (enabled && !g_demo_log.empty()) {
        FILE* f = fopen(g_demo_log.c_str(), "w"); // new log per take
        if (f) {
            fclose(f);
        }
    }
}

__attribute__((used)) __attribute__((visibility("default"))) void thorSetZoom(int zoom)
{
    g_zoom = zoom;
}

__attribute__((used)) __attribute__((visibility("default"))) void thorSetSkipMovies(bool skip)
{
    g_skip_movies = skip;
}

__attribute__((used)) __attribute__((visibility("default"))) void thorSetScrollScale(int percent)
{
    g_scroll_scale = percent / 100.0f;
}

__attribute__((used)) __attribute__((visibility("default"))) void thorPushCommand(int type, int a, int b, int c)
{
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_commands.size() < 64) {
        g_commands.push_back(QueuedCommand{ThorCommand{type, a, b, c}, SDL_GetTicks()});
    }
}

/*
** out[0] = Alter des Schnappschusses in ms, out[1] = laufende Nummer, danach das Layout aus thor.h.
** Rueckgabe: Anzahl geschriebener Werte (0 = noch kein Schnappschuss).
*/
__attribute__((used)) __attribute__((visibility("default"))) int thorSnapshot(int* out, int cap)
{
    std::lock_guard<std::mutex> guard(g_lock);
    if (g_snapshot.empty() || cap < 2) {
        return 0;
    }
    if (g_snapshot.size() >= THOR_HEADER_INTS) {
        g_snapshot[17] = g_radar_pulse;
        g_snapshot[18] = g_highlight_key;
        g_snapshot[19] = g_demo.Highlights ? g_highlight_serial : 0;
    }
    out[0] = (int)(SDL_GetTicks() - g_snapshot_ticks);
    out[1] = (int)g_snapshot_serial;
    int n = (int)g_snapshot.size();
    if (n > cap - 2) {
        n = cap - 2;
    }
    memcpy(out + 2, g_snapshot.data(), n * sizeof(int));
    return n + 2;
}

__attribute__((used)) __attribute__((visibility("default"))) int
thorRadar(unsigned char* out, int cap, int* w, int* h)
{
    std::lock_guard<std::mutex> guard(g_lock);
    int n = (int)g_radar.size();
    if (n == 0 || n > cap) {
        return 0;
    }
    memcpy(out, g_radar.data(), n);
    *w = g_radar_w;
    *h = g_radar_h;
    return n;
}

__attribute__((used)) __attribute__((visibility("default"))) void thorPalette(uint32_t* argb256)
{
    Thor_Get_Palette(argb256);
}

/* Cameo als ARGB mit der aktuellen Palette; Index 0 ist transparent. */
__attribute__((used)) __attribute__((visibility("default"))) int
thorCameo(int key, uint32_t* out, int cap, int* w, int* h)
{
    uint32_t pal[256];
    Thor_Get_Palette(pal);
    std::lock_guard<std::mutex> guard(g_lock);
    auto it = g_cameos.find(key);
    if (it == g_cameos.end()) {
        /* Interface art is not part of the snapshot: remember the request for the game thread. */
        if ((key >> 16) >= THOR_KEY_FONT && g_wanted.size() < 64) {
            g_wanted.push_back(key);
        }
        return 0;
    }
    if (it->second.w <= 0) {
        return 0;
    }
    int n = it->second.w * it->second.h;
    if (n > cap) {
        return 0;
    }
    for (int i = 0; i < n; i++) {
        unsigned char idx = it->second.pixels[i];
        out[i] = idx == 0 ? 0 : (pal[idx] | 0xFF000000u);
    }
    *w = it->second.w;
    *h = it->second.h;
    return n;
}

__attribute__((used)) __attribute__((visibility("default"))) int thorItemName(int key, char* out, int cap)
{
    std::lock_guard<std::mutex> guard(g_lock);
    auto it = g_names.find(key);
    if (it == g_names.end() || cap <= 0) {
        return 0;
    }
    snprintf(out, cap, "%s", it->second.c_str());
    return (int)strlen(out);
}
}

#endif
