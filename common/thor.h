/*
** Thor Conquer - Zweitbildschirm-Schnittstelle (nur Android).
**
** Der untere Bildschirm des AYN Thor zeigt Bauleiste, Radar und Kraftwerksanzeige als native
** Android-Ansicht. Dafuer liefert die Engine regelmaessig einen Schnappschuss (Liste der baubaren
** Objekte mit Zustand/Fortschritt, Radar-Zellen, Geld/Strom) und nimmt Befehle entgegen.
**
** Alle Befehle werden im Spiel-Thread (aus Main_Loop) ausgefuehrt und laufen ueber denselben
** Code wie ein Klick in die Original-Sidebar bzw. das Radar.
*/
#ifndef COMMON_THOR_H
#define COMMON_THOR_H

#if defined(ANDROID) && ANDROID

#include <stdint.h>
#include <string>
#include <vector>

#define THOR_ENABLED 1

enum ThorCommandType
{
    THOR_CMD_BUILD_CLICK = 1, // a = Spalte, b = Index, c = (Schluessel << 1) | rechte Taste
    THOR_CMD_RADAR = 2,       // a = Zelle X, b = Zelle Y  -> Karte dorthin scrollen
    THOR_CMD_REPAIR = 3,      // Reparaturmodus umschalten
    THOR_CMD_SELL = 4,        // Verkaufsmodus umschalten
    THOR_CMD_KEY = 5,         // a = SDL-Scancode (druecken + loslassen)
    THOR_CMD_SCROLL_STRIP = 6, // a = Spalte, b = Richtung (fuer Controller, optional)
    THOR_CMD_TAKE_CONTROL = 7, // demo: hand the player house back to the human
    THOR_CMD_JUMP = 8,         // a: 0 = next own unit, 1 = base (cycles yard/factories), 2 = select own units on
                               // screen, 3 = selection / next group, 4 = last attack, 5 = make group of selection
    THOR_CMD_GROUP = 9,        // a = group 0..3, b: 0 = select, 1 = store the selection, 2 = select and centre
    THOR_CMD_ORDER = 10,       // a: 0 = stop, 1 = guard area, 2 = scatter, 3 = select the whole army, 4 = deselect and
                               // leave repair/sell mode, 5 = make the selected factory the primary one
    THOR_CMD_SPEED = 11,       // a = engine game speed (1 fastest .. 6 slowest)
    THOR_CMD_QUICK = 12        // a: 0 = quick save, 1 = quick load (single player only)
};

/* Interface art for the deck travels through the cameo channel under these key types. */
enum
{
    THOR_KEY_FONT = 0x7E, // key = THOR_KEY_FONT << 16 | font (0 = small, 1 = tab font): atlas of ASCII 32..127
    THOR_KEY_ART = 0x7F,  // key = THOR_KEY_ART << 16 | (side << 7 | THOR_ART_*) << 8 | frame
    THOR_FONT_CELL = 20,  // atlas: 16 x 6 cells of this size
};

enum
{
    THOR_ART_SIDE_TOP = 0,
    THOR_ART_SIDE_MID = 1,
    THOR_ART_SIDE_BOTTOM = 2,
    THOR_ART_STRIP = 3,
    THOR_ART_RADAR = 4,
    THOR_ART_RADAR_FRAME = 5,
    THOR_ART_REPAIR = 6,
    THOR_ART_SELL = 7,
    THOR_ART_MAP = 8,
    THOR_ART_POWERBAR = 9,
    THOR_ART_POWER = 10,
    THOR_ART_TABS = 11,
    THOR_ART_STRIPUP = 12,
    THOR_ART_STRIPDN = 13,
    THOR_ART_COUNT
};

struct ThorCommand
{
    int type;
    int a;
    int b;
    int c;
};

/*
** Snapshot-Layout (int-Array), Version 1:
**  [0] Version  [1] im Spiel (0/1)  [2] Credits  [3] Strom erzeugt  [4] Strom verbraucht
**  [5] Flags (THOR_FLAG_*)  [6..9] Karte in Zellen (x, y, w, h)
**  [10..13] Sichtfenster in 1/16 Zellen (x, y, w, h)  [14] Anzahl Spalte 0  [15] Anzahl Spalte 1
**  [16] Seite (0 = Alliierte/GDI, 1 = Sowjets/NOD)  [17] Radar-Puls-Zaehler (Demo-Radarsprung)
**  [18] Highlight-Schluessel  [19] Highlight-Zaehler (Demo-Tipp auf dem Deck)
**  [20] bit per control group 0..3 that has members  [21..24] members of group 0..3
**  [25] game speed (engine value)  [26] number of selected objects  [27] group that is selected (-1 = none)
**  ab [28]: je Eintrag THOR_ITEM_INTS Werte:
**     Spalte, Index, Schluessel (RTTI << 16 | ID), Kosten, Zustand (THOR_STATE_*), Fortschritt 0..1000
*/
enum
{
    THOR_SNAPSHOT_VERSION = 4,
    THOR_HEADER_INTS = 28,
    THOR_ITEM_INTS = 6,
};

enum
{
    THOR_FLAG_RADAR = 1 << 0,
    THOR_FLAG_REPAIR_MODE = 1 << 1,
    THOR_FLAG_SELL_MODE = 1 << 2,
    THOR_FLAG_PLACING = 1 << 3,
    THOR_FLAG_TARGETING = 1 << 4,
    THOR_FLAG_REPAIR_AVAILABLE = 1 << 5,
    THOR_FLAG_AUTOPILOT = 1 << 6,
    THOR_FLAG_ALERT_KNOWN = 1 << 7, // base was attacked in this mission (jump target exists)
    THOR_FLAG_ALERT = 1 << 8,       // ... within the last 30 s
    THOR_FLAG_PRIMARY_OK = 1 << 9,  // the selection is one own factory (can be made primary)
    THOR_FLAG_SAVE_OK = 1 << 10,    // single player mission: quick save and load are allowed
    THOR_FLAG_QUICKSAVE = 1 << 11,  // a quick save exists
};

enum
{
    THOR_STATE_AVAILABLE = 0,
    THOR_STATE_BUILDING = 1,
    THOR_STATE_ON_HOLD = 2,
    THOR_STATE_READY = 3,
    THOR_STATE_DISABLED = 4,
    THOR_STATE_CHARGING = 5,
};

bool Thor_Dual_Screen();
/* 960x540 game buffer (map full width, menus as centred 640x400 frame at 2x). */
bool Thor_Big_View();
/* Game specific (thor_game.cpp): switch between menu frame and full mission view. */
void Thor_Game_Set_View(bool mission);
/* RAII: show a centred 640x400 frame (films, score, briefing) and restore afterwards. */
struct ThorMenuView
{
    ThorMenuView();
    ~ThorMenuView();
    int Previous;
};
int Thor_View_State(); // -1 unknown, 0 menu, 1 mission
void Thor_Note_View_State(int state);
bool Thor_Skip_Movies();

/*
** Demo / autopilot mode (recording without a human player).
*/
struct ThorDemoConfig
{
    bool Enabled;
    int Map;        // index in the skirmish map list
    int Seed;       // 0 = random
    int Opponents;  // 1..3 (RA), TD uses the dialog default
    int Speed;      // engine game speed (0 = fastest .. 6), -1 = unchanged
    bool Highlights;
    char MapName[64]; // optional: pick the skirmish map by (part of) its name
};
const ThorDemoConfig& Thor_Demo();
/* Demo: index of the skirmish map to select (by name if set, else by index), or -1. */
int Thor_Demo_Pick_Map(const char* const* names, int count);
uint32_t Thor_Demo_Ms();                                       // ms since demo start
void Thor_Demo_Log(const char* type, bool scripted, const char* fmt, ...);
void Thor_Demo_Radar_Pulse();                                  // deck: visible radar pulse
void Thor_Demo_Highlight(int key);                             // deck: tap ripple on a cameo
void Thor_Input_Tick();                                        // from the SDL input pump (menus too)
void Thor_Inject_Click(int x, int y, bool right);              // wwkeyboard_sdl2.cpp

/* Game specific demo parts (thor_game.cpp). */
bool Thor_Game_In_Mission();
bool Thor_Game_Demo_Menu_Step(int step, int& x, int& y, uint32_t& delay_ms);
void Thor_Game_Demo_Tick();
bool Thor_Demo_Autopilot(); // demo active and not taken over
void Thor_Demo_Take_Control();

/* Einmal pro Main_Loop im Spiel-Thread aufrufen. */
void Thor_Frame();

/* Touch-Scrollen mit zwei Fingern (Spielpixel, Bewegung der Finger). Spiel-Thread. */
void Thor_Touch_Scroll(float dx, float dy);

/* Spielspezifisch (redalert/thor_game.cpp bzw. tiberiandawn/thor_game.cpp). */
void Thor_Game_Command(const ThorCommand& cmd);
/* radar == nullptr: Radar diesmal nicht berechnen. */
void Thor_Game_Snapshot(std::vector<int>& out, std::vector<unsigned char>* radar, int& radar_w, int& radar_h);
bool Thor_Game_Cameo(int key, std::vector<unsigned char>& pixels, int& w, int& h);
std::string Thor_Game_Item_Name(int key);
void Thor_Game_Scroll(int dx_leptons, int dy_leptons);
void Thor_Game_Jump(int what);
/* house.cpp, HouseClass::Attacked: the player's base is under attack at this coordinate. */
void Thor_Game_Note_Attack(uint32_t coord);
int Thor_Pixel_To_Lepton(int pixels);

/* video_sdl2.cpp */
void Thor_Get_Palette(uint32_t* argb256);

#endif
#endif
