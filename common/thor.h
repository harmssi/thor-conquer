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
    THOR_CMD_JUMP = 8          // a: 0 = next own unit, 1 = base, 2 = select own units on screen
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
**  ab [20]: je Eintrag THOR_ITEM_INTS Werte:
**     Spalte, Index, Schluessel (RTTI << 16 | ID), Kosten, Zustand (THOR_STATE_*), Fortschritt 0..1000
*/
enum
{
    THOR_SNAPSHOT_VERSION = 3,
    THOR_HEADER_INTS = 20,
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
int Thor_Pixel_To_Lepton(int pixels);

/* video_sdl2.cpp */
void Thor_Get_Palette(uint32_t* argb256);

#endif
#endif
