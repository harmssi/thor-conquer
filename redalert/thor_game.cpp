/*
** Thor Conquer - Red-Alert-Teil der Zweitbildschirm-Schnittstelle (siehe common/thor.h).
**
** Alles hier laeuft im Spiel-Thread (aus Main_Loop ueber Thor_Frame).
** Bau-Klicks laufen durch SidebarClass::StripClass::SelectClass::Action - denselben Code wie
** ein Mausklick auf ein Cameo der Original-Sidebar. Es wird kein Spielzustand direkt veraendert.
*/
#include "function.h"
#include "common/thor.h"

#ifdef THOR_ENABLED

#include <SDL.h>
#include <time.h>
#define THOR_MIN(a, b) ((a) < (b) ? (a) : (b))
#define THOR_MAX(a, b) max(a, b)

#include "common/keyframe.h"
#include "common/shape.h"
#include "common/wwkeyboard_sdl2.h"

void* Get_Shape_Header_Data(void* ptr);

/* common/thor_deck.inc (included at the end of this file) */
static bool Thor_Deck_Command(const ThorCommand& cmd);
static void Thor_Deck_Snapshot(std::vector<int>& out);
static bool Thor_Deck_Art(int key, std::vector<unsigned char>& pixels, int& w, int& h);

static inline int Thor_Key_Of(RTTIType type, int id)
{
    return ((int)type << 16) | (id & 0xFFFF);
}

struct ThorAccess
{
    static void Build_Click(int column, int index, int key, bool right)
    {
        if (column < 0 || column > 1) {
            return;
        }
        SidebarClass::StripClass& strip = Map.Column[column];
        if (index < 0 || index >= strip.BuildableCount) {
            return;
        }
        /* Liste koennte sich seit dem Schnappschuss verschoben haben. */
        if (Thor_Key_Of(strip.Buildables[index].BuildableType, strip.Buildables[index].BuildableID) != key) {
            return;
        }
        SidebarClass::StripClass::SelectClass& button = SidebarClass::StripClass::SelectButton[column][0];
        if (button.Strip == nullptr) {
            return;
        }
        KeyNumType kn = KN_NONE;
        SidebarClass::StripClass::SelectClass::ThorIndexOverride = index;
        button.Action(right ? GadgetClass::RIGHTPRESS : GadgetClass::LEFTPRESS, kn);
        SidebarClass::StripClass::SelectClass::ThorIndexOverride = -1;
    }

    static void const* Special_Cameo(int spc)
    {
        return Map.Column[0].Get_Special_Cameo(SpecialWeaponType(spc));
    }
};

/* Last spot where the player's base was attacked (Frame resets per mission, so -1/newer = none). */
static COORDINATE ThorAttackCoord = 0;
static int ThorAttackFrame = -1;

void Thor_Game_Note_Attack(uint32_t coord)
{
    if (ThorAttackFrame < 0 || ThorAttackFrame > Frame || Frame - ThorAttackFrame >= TICKS_PER_SECOND * 30) {
        Thor_Demo_Log("base_attacked", false, "base under attack at cell %d,%d", Coord_XCell(coord), Coord_YCell(coord));
    }
    ThorAttackCoord = (COORDINATE)coord;
    ThorAttackFrame = Frame;
}

static bool Thor_Attack_Known()
{
    return ThorAttackFrame >= 0 && ThorAttackFrame <= Frame;
}

int Thor_Pixel_To_Lepton(int pixels)
{
    return pixels < 0 ? -Pixel_To_Lepton(-pixels) : Pixel_To_Lepton(pixels);
}

void Thor_Game_Scroll(int dx, int dy)
{
    if (!GameActive) {
        return;
    }
    if (dx != 0) {
        int d = abs(dx);
        Map.Scroll_Map(dx > 0 ? DIR_E : DIR_W, d, true);
    }
    if (dy != 0) {
        int d = abs(dy);
        Map.Scroll_Map(dy > 0 ? DIR_S : DIR_N, d, true);
    }
}

static void Thor_Radar_Scroll(int cellx, int celly)
{
    /*
    ** Gleiche Rechnung wie RadarClass::RTacticalClass::Action bei einem Linksklick ohne
    ** ausgewaehlte Einheiten: Ansicht auf die angeklickte Zelle zentrieren.
    */
    if (!Map.Is_Radar_Active()) {
        return;
    }
    if (cellx < Map.MapCellX || celly < Map.MapCellY || cellx >= Map.MapCellX + Map.MapCellWidth
        || celly >= Map.MapCellY + Map.MapCellHeight) {
        return;
    }
    cellx -= Lepton_To_Cell(Map.TacLeptonWidth) / 2;
    cellx = max(cellx, Map.MapCellX);
    celly -= Lepton_To_Cell(Map.TacLeptonHeight) / 2;
    celly = max(celly, Map.MapCellY);
    Map.Set_Tactical_Position(Cell_Coord(XY_Cell(cellx, celly)));
    Map.DisplayClass::IsToRedraw = true;
    Map.Flag_To_Redraw(true);
}

void Thor_Game_Command(const ThorCommand& cmd)
{
    if (!GameActive || PlayerPtr == nullptr) {
        return;
    }
    if (Thor_Deck_Command(cmd)) {
        return;
    }
    switch (cmd.type) {
    case THOR_CMD_BUILD_CLICK:
        ThorAccess::Build_Click(cmd.a, cmd.b, cmd.c >> 1, (cmd.c & 1) != 0);
        break;
    case THOR_CMD_RADAR:
        Thor_Radar_Scroll(cmd.a, cmd.b);
        break;
    case THOR_CMD_REPAIR:
        /* Wie der Reparatur-Knopf der Sidebar (BUTTON_REPAIR). */
        if (PlayerPtr->BScan) {
            Map.Repair_Mode_Control(-1);
        }
        break;
    case THOR_CMD_SELL:
        /* Wie der Verkaufen-Knopf der Sidebar (BUTTON_UPGRADE). */
        Map.Sell_Mode_Control(-1);
        break;
    case THOR_CMD_JUMP:
        Thor_Game_Jump(cmd.a);
        break;
    case THOR_CMD_TAKE_CONTROL:
        /* Undo the AI takeover: the human plays from here (same fields the takeover set). */
        if (Thor_Demo_Autopilot()) {
            PlayerPtr->IsHuman = true;
            PlayerPtr->IQ = 0;
            PlayerPtr->IsBaseBuilding = false;
            /* Fill the build lists for buildings the AI placed (not "discovered" by the player yet). */
            for (int i = 0; i < Buildings.Count(); i++) {
                BuildingClass* b = Buildings.Ptr(i);
                if (b && !b->IsInLimbo && b->House == PlayerPtr && b->Strength > 0) {
                    b->IsDiscoveredByPlayer = true;
                    b->Update_Buildables();
                }
            }
            Thor_Demo_Take_Control();
        }
        break;
    case THOR_CMD_KEY:
        if (Keyboard) {
            ((WWKeyboardClassSDL2*)Keyboard)->Thor_Key((unsigned short)cmd.a, false);
            ((WWKeyboardClassSDL2*)Keyboard)->Thor_Key((unsigned short)cmd.a, true);
        }
        break;
    default:
        break;
    }
}

void Thor_Game_Snapshot(std::vector<int>& out, std::vector<unsigned char>* radar, int& radar_w, int& radar_h)
{
    out.assign(THOR_HEADER_INTS, 0);
    out[0] = THOR_SNAPSHOT_VERSION;
    if (!GameActive || PlayerPtr == nullptr) {
        return;
    }
    out[1] = 1;
    out[2] = PlayerPtr->Available_Money();
    out[3] = PlayerPtr->Power;
    out[4] = PlayerPtr->Drain;

    int flags = 0;
    if (Map.Is_Radar_Active()) {
        flags |= THOR_FLAG_RADAR;
    }
    if (Map.IsRepairMode) {
        flags |= THOR_FLAG_REPAIR_MODE;
    }
    if (Map.IsSellMode) {
        flags |= THOR_FLAG_SELL_MODE;
    }
    if (Map.PendingObjectPtr != nullptr) {
        flags |= THOR_FLAG_PLACING;
    }
    if (Map.IsTargettingMode != SPC_NONE) {
        flags |= THOR_FLAG_TARGETING;
    }
    if (PlayerPtr->BScan) {
        flags |= THOR_FLAG_REPAIR_AVAILABLE;
    }
    if (Thor_Demo_Autopilot()) {
        flags |= THOR_FLAG_AUTOPILOT;
    }
    if (Thor_Attack_Known()) {
        flags |= THOR_FLAG_ALERT_KNOWN;
        if (Frame - ThorAttackFrame < TICKS_PER_SECOND * 30) {
            flags |= THOR_FLAG_ALERT;
        }
    }
    out[5] = flags;
    out[6] = Map.MapCellX;
    out[7] = Map.MapCellY;
    out[8] = Map.MapCellWidth;
    out[9] = Map.MapCellHeight;
    out[10] = Coord_X(Map.TacticalCoord) / 16;
    out[11] = Coord_Y(Map.TacticalCoord) / 16;
    out[12] = Map.TacLeptonWidth / 16;
    out[13] = Map.TacLeptonHeight / 16;
    out[16] = (PlayerPtr->ActLike == HOUSE_USSR || PlayerPtr->ActLike == HOUSE_UKRAINE || PlayerPtr->ActLike == HOUSE_BAD) ? 1 : 0;

    Thor_Deck_Snapshot(out);

    for (int col = 0; col < 2; col++) {
        SidebarClass::StripClass& strip = Map.Column[col];
        int count = 0;
        for (int i = 0; i < strip.BuildableCount; i++) {
            RTTIType type = strip.Buildables[i].BuildableType;
            int id = strip.Buildables[i].BuildableID;
            int state = THOR_STATE_AVAILABLE;
            int progress = 0;
            int cost = 0;

            if (type == RTTI_SPECIAL) {
                SpecialWeaponType spc = SpecialWeaponType(id);
                if ((unsigned)spc >= SPC_COUNT) {
                    continue;
                }
                bool ready = PlayerPtr->SuperWeapon[spc].Is_Ready();
                progress = PlayerPtr->SuperWeapon[spc].Anim_Stage() * 1000 / SuperClass::ANIMATION_STAGES;
                state = ready ? THOR_STATE_READY : THOR_STATE_CHARGING;
            } else {
                ObjectTypeClass const* obj = Fetch_Techno_Type(type, id);
                if (obj == nullptr) {
                    continue;
                }
                cost = obj->Cost_Of() * PlayerPtr->CostBias;
                if (strip.Buildables[i].Factory != -1) {
                    FactoryClass* factory = Factories.Raw_Ptr(strip.Buildables[i].Factory);
                    bool done = factory->Has_Completed();
                    progress = factory->Completion() * 1000 / FactoryClass::STEP_COUNT;
                    state = done ? THOR_STATE_READY : (factory->Is_Building() ? THOR_STATE_BUILDING : THOR_STATE_ON_HOLD);
                } else if (PlayerPtr->Fetch_Factory(type) != nullptr && PlayerPtr->Fetch_Factory(type)->Get_Object() != nullptr
                           && (void const*)PlayerPtr->Fetch_Factory(type)->Get_Object()->Techno_Type_Class() == (void const*)obj) {
                    /* Production started outside the sidebar (e.g. by the AI in demo mode): mirror it. */
                    FactoryClass* hf = PlayerPtr->Fetch_Factory(type);
                    progress = hf->Completion() * 1000 / FactoryClass::STEP_COUNT;
                    state = hf->Has_Completed() ? THOR_STATE_READY : (hf->Is_Building() ? THOR_STATE_BUILDING : THOR_STATE_ON_HOLD);
                } else {
                    bool busy = PlayerPtr->Fetch_Factory(type) != nullptr || PlayerPtr->Is_Hack_Prevented(type, id);
                    state = busy ? THOR_STATE_DISABLED : THOR_STATE_AVAILABLE;
                }
            }
            out.push_back(col);
            out.push_back(i);
            out.push_back(Thor_Key_Of(type, id));
            out.push_back(cost);
            out.push_back(state);
            out.push_back(progress);
            count++;
        }
        out[14 + col] = count;
    }

    if (radar != nullptr) {
        radar_w = Map.MapCellWidth;
        radar_h = Map.MapCellHeight;
        radar->assign(radar_w * radar_h, 0);
        for (int y = 0; y < radar_h; y++) {
            for (int x = 0; x < radar_w; x++) {
                CELL cell = XY_Cell(Map.MapCellX + x, Map.MapCellY + y);
                CellClass& c = Map[cell];
                unsigned char color = 0;
                if (c.IsMapped || Debug_Unshroud) {
                    int cc = c.Cell_Color(true);
                    if (cc == TBLACK) {
                        cc = c.Cell_Color(false);
                    }
                    color = (unsigned char)cc;
                }
                (*radar)[y * radar_w + x] = color;
            }
        }
    }
}

bool Thor_Game_Cameo(int key, std::vector<unsigned char>& pixels, int& w, int& h)
{
    if ((key >> 16) >= THOR_KEY_FONT) {
        return Thor_Deck_Art(key, pixels, w, h);
    }
    RTTIType type = RTTIType(key >> 16);
    int id = key & 0xFFFF;
    void const* shape = nullptr;
    if (type == RTTI_SPECIAL) {
        shape = ThorAccess::Special_Cameo(id);
    } else {
        ObjectTypeClass const* obj = Fetch_Techno_Type(type, id);
        if (obj != nullptr) {
            shape = obj->Get_Cameo_Data();
        }
    }
    if (shape == nullptr) {
        return false;
    }
    w = Get_Build_Frame_Width(shape);
    h = Get_Build_Frame_Height(shape);
    if (w <= 0 || h <= 0 || w * h > 256 * 256) {
        return false;
    }
    uintptr_t frame = Build_Frame(shape, 0, _ShapeBuffer);
    if (!frame) {
        return false;
    }
    unsigned char const* data = (unsigned char const*)Get_Shape_Header_Data((void*)frame);
    pixels.assign(data, data + w * h);
    return true;
}

std::string Thor_Game_Item_Name(int key)
{
    if ((key >> 16) >= THOR_KEY_FONT) {
        return "";
    }
    RTTIType type = RTTIType(key >> 16);
    int id = key & 0xFFFF;
    if (type == RTTI_SPECIAL) {
        if ((unsigned)id < SPC_COUNT) {
            return Text_String(SpecialWeaponHelp[id]);
        }
        return "";
    }
    ObjectTypeClass const* obj = Fetch_Techno_Type(type, id);
    return obj ? Text_String(obj->Full_Name()) : "";
}


/* ======================================================================================
** Demo / autopilot mode
** ====================================================================================*/

namespace
{
    bool DemoTakenOver = false;
    uint32_t DemoNextCamera = 0;
    uint32_t DemoNextJump = 0;
    uint32_t DemoNextScript = 0;
    uint32_t DemoNextBuildables = 0;
    uint32_t DemoNextMcv = 0;
    int DemoBuildingCount = -1;
    COORDINATE DemoTarget = 0;
    unsigned DemoRng = 1;

    unsigned Demo_Rand(unsigned range)
    {
        DemoRng = DemoRng * 1103515245u + 12345u;
        return (DemoRng >> 16) % (range ? range : 1);
    }

    /* Center the view on a coordinate (same math as a radar click). */
    COORDINATE Demo_View_For(COORDINATE coord)
    {
        int cellx = Coord_XCell(coord) - Lepton_To_Cell(Map.TacLeptonWidth) / 2;
        int celly = Coord_YCell(coord) - Lepton_To_Cell(Map.TacLeptonHeight) / 2;
        cellx = THOR_MAX(cellx, Map.MapCellX);
        celly = THOR_MAX(celly, Map.MapCellY);
        return Cell_Coord(XY_Cell(cellx, celly));
    }

    /* A visible fight: an object that has a target, on a mapped cell. Prefers the player's units. */
    bool Demo_Find_Combat(COORDINATE& where, char* what, int len)
    {
        TechnoClass* best = nullptr;
        int best_score = -1;
        for (int i = 0; i < Units.Count(); i++) {
            TechnoClass* t = Units.Ptr(i);
            if (t && !t->IsInLimbo && t->TarCom != TARGET_NONE && Map[Coord_Cell(t->Center_Coord())].IsMapped) {
                int score = (t->House == PlayerPtr ? 10 : 5) + (int)Demo_Rand(5);
                if (score > best_score) {
                    best = t;
                    best_score = score;
                }
            }
        }
        for (int i = 0; i < Infantry.Count(); i++) {
            TechnoClass* t = Infantry.Ptr(i);
            if (t && !t->IsInLimbo && t->TarCom != TARGET_NONE && Map[Coord_Cell(t->Center_Coord())].IsMapped) {
                int score = (t->House == PlayerPtr ? 8 : 4) + (int)Demo_Rand(5);
                if (score > best_score) {
                    best = t;
                    best_score = score;
                }
            }
        }
        if (!best) {
            return false;
        }
        where = best->Center_Coord();
        snprintf(what, len, "%s", Text_String(best->Techno_Type_Class()->Full_Name()));
        return true;
    }

    BuildingClass* Demo_Newest_Building()
    {
        BuildingClass* last = nullptr;
        for (int i = 0; i < Buildings.Count(); i++) {
            BuildingClass* b = Buildings.Ptr(i);
            if (b && !b->IsInLimbo && b->House == PlayerPtr) {
                last = b;
            }
        }
        return last;
    }

    int Demo_Player_Building_Count()
    {
        int n = 0;
        for (int i = 0; i < Buildings.Count(); i++) {
            BuildingClass* b = Buildings.Ptr(i);
            if (b && !b->IsInLimbo && b->House == PlayerPtr) {
                n++;
            }
        }
        return n;
    }


    /* Script rotation categories: refinery, barracks, war factory, defense, infantry, vehicle. */
    bool Demo_In_Category(RTTIType type, int id, int cat)
    {
        if (type == RTTI_BUILDINGTYPE) {
            switch (cat) {
            case 0: return id == STRUCT_REFINERY;
            case 1: return id == STRUCT_BARRACKS || id == STRUCT_TENT;
            case 2: return id == STRUCT_WEAP;
            case 3: return id == STRUCT_PILLBOX || id == STRUCT_CAMOPILLBOX || id == STRUCT_TURRET || id == STRUCT_TESLA
                           || id == STRUCT_FLAME_TURRET || id == STRUCT_AAGUN || id == STRUCT_SAM;
            default: return false;
            }
        }
        if (cat == 4) {
            return type == RTTI_INFANTRYTYPE;
        }
        if (cat == 5) {
            return type == RTTI_UNITTYPE && id != UNIT_MCV && id != UNIT_HARVESTER;
        }
        return false;
    }
    int DemoCategory = 0;
    const char* const DemoCategoryName[6] = {"refinery", "barracks", "war factory", "defense", "infantry", "vehicle"};

    /* Scripted deck tap on one buildable through the normal sidebar path. */
    bool Demo_Tap(int column, int i, const char* why)
    {
        SidebarClass::StripClass& strip = Map.Column[column];
        RTTIType type = strip.Buildables[i].BuildableType;
        int id = strip.Buildables[i].BuildableID;
        TechnoTypeClass const* tt = Fetch_Techno_Type(type, id);
        int key = Thor_Key_Of(type, id);
        Thor_Demo_Highlight(key);
        ThorAccess::Build_Click(column, i, key, false);
        Thor_Demo_Log("deck_tap", true, "build %s (%s)", tt ? Text_String(tt->Full_Name()) : "?", why);
        return true;
    }

    bool Demo_Can_Start(int column, int i)
    {
        SidebarClass::StripClass& strip = Map.Column[column];
        RTTIType type = strip.Buildables[i].BuildableType;
        int id = strip.Buildables[i].BuildableID;
        if (type == RTTI_SPECIAL) {
            return false;
        }
        TechnoTypeClass const* tt = Fetch_Techno_Type(type, id);
        return tt && PlayerPtr->Fetch_Factory(type) == nullptr && strip.Buildables[i].Factory == -1
               && tt->Cost_Of() <= PlayerPtr->Available_Money();
    }

    /* Power when low, otherwise rotate refinery -> barracks -> war factory -> defense -> infantry -> vehicle. */
    bool Demo_Scripted_Build(int, bool want_power)
    {
        if (want_power) {
            SidebarClass::StripClass& strip = Map.Column[0];
            for (int i = 0; i < strip.BuildableCount; i++) {
                if (strip.Buildables[i].BuildableType == RTTI_BUILDINGTYPE && Demo_Can_Start(0, i)) {
                    TechnoTypeClass const* tt = Fetch_Techno_Type(RTTI_BUILDINGTYPE, strip.Buildables[i].BuildableID);
                    if (tt && ((BuildingTypeClass const*)tt)->Power > 0) {
                        return Demo_Tap(0, i, "power low");
                    }
                }
            }
            return false;
        }
        for (int step = 0; step < 6; step++) {
            int cat = (DemoCategory + step) % 6;
            for (int col = 0; col < 2; col++) {
                SidebarClass::StripClass& strip = Map.Column[col];
                for (int i = 0; i < strip.BuildableCount; i++) {
                    if (Demo_In_Category(strip.Buildables[i].BuildableType, strip.Buildables[i].BuildableID, cat)
                        && Demo_Can_Start(col, i)) {
                        DemoCategory = (cat + 1) % 6;
                        return Demo_Tap(col, i, DemoCategoryName[cat]);
                    }
                }
            }
        }
        return false;
    }
} // namespace

bool Thor_Game_In_Mission()
{
    return GameActive && PlayerPtr != nullptr && Map.MapCellWidth > 0;
}

void Thor_Game_Demo_Tick()
{
    if (!Thor_Game_In_Mission()) {
        return;
    }
    const ThorDemoConfig& cfg = Thor_Demo();
    uint32_t now = Thor_Demo_Ms();

    if (!DemoTakenOver) {
        /*
        ** Hand the player house to the engine's own AI - the same steps the engine uses when a
        ** multiplayer human disconnects (see dllinterface.cpp, player disconnect).
        */
        DemoTakenOver = true;
        DemoRng = cfg.Seed ? (unsigned)cfg.Seed : (unsigned)SDL_GetTicks();
        PlayerPtr->IsHuman = false;
        PlayerPtr->IsStarted = true;
        PlayerPtr->IQ = Rule.MaxIQ;
        PlayerPtr->IsBaseBuilding = true;
        for (int i = 0; i < Units.Count(); i++) {
            UnitClass* obj = Units.Ptr(i);
            if (obj && !obj->IsInLimbo && obj->House == PlayerPtr && *obj == UNIT_MCV) {
                obj->Assign_Mission(MISSION_GUARD);
                obj->Assign_Target(TARGET_NONE);
                obj->Assign_Destination(TARGET_NONE);
                obj->Assign_Mission(MISSION_UNLOAD);
                obj->Commence();
            }
        }
        if (cfg.Speed >= 0) {
            Options.GameSpeed = cfg.Speed;
        }
        Theme.Stop(); // music off, sound effects stay
        Set_Score_Vol(0);
        DemoNextCamera = now + 4000;
        DemoNextJump = now + 20000 + Demo_Rand(15000);
        DemoNextScript = now + 30000;
        Thor_Demo_Log("ai_takeover", false, "player house handed to the engine AI (IQ %d)", (int)Rule.MaxIQ);
    }


    /*
    ** Starting MCV that cannot deploy where it stands (units next to it): issue the same orders a
    ** player would - drive to a free spot, then deploy. Only until the first construction yard exists.
    */
    if (now >= DemoNextMcv && Demo_Player_Building_Count() == 0) {
        DemoNextMcv = now + 5000;
        for (int i = 0; i < Units.Count(); i++) {
            UnitClass* mcv = Units.Ptr(i);
            if (!mcv || mcv->IsInLimbo || !(mcv->House == PlayerPtr) || !(*mcv == UNIT_MCV)) {
                continue;
            }
            if (Target_Legal(mcv->NavCom) || mcv->IsDriving) {
                break; // still on its way
            }
            CELL here = Coord_Cell(mcv->Center_Coord());
            if (BuildingTypeClass::As_Reference(STRUCT_CONST).Legal_Placement(Adjacent_Cell(here, FACING_NW))) {
                mcv->Assign_Mission(MISSION_UNLOAD);
                mcv->Commence();
                Thor_Demo_Log("mcv_deploy", false, "deploy MCV");
            } else {
                for (int tries = 0; tries < 40; tries++) {
                    int dx = (int)Demo_Rand(9) - 4, dy = (int)Demo_Rand(9) - 4;
                    CELL c = XY_Cell(Cell_X(here) + dx, Cell_Y(here) + dy);
                    if (Map.In_Radar(c) && BuildingTypeClass::As_Reference(STRUCT_CONST).Legal_Placement(Adjacent_Cell(c, FACING_NW))) {
                        mcv->Assign_Destination(::As_Target(c));
                        mcv->Assign_Mission(MISSION_MOVE);
                        mcv->Commence();
                        Thor_Demo_Log("mcv_move", false, "move MCV to a free spot");
                        break;
                    }
                }
            }
            break;
        }
    }

    /*
    ** Keep the build lists (= deck) filled like for a human player: the engine only refreshes them
    ** for buildings the player has "discovered", which AI-placed buildings may not be.
    */
    if (now >= DemoNextBuildables) {
        DemoNextBuildables = now + 2000;
        for (int i = 0; i < Buildings.Count(); i++) {
            BuildingClass* b = Buildings.Ptr(i);
            if (b && !b->IsInLimbo && b->House == PlayerPtr && b->Strength > 0) {
                b->IsDiscoveredByPlayer = true;
                b->Update_Buildables();
            }
        }
    }

    /*
    ** Camera: pick a target every few seconds, glide there slowly.
    */
    if (now >= DemoNextCamera) {
        DemoNextCamera = now + 7000 + Demo_Rand(4000);
        char what[96] = {0};
        COORDINATE where = 0;
        int count = Demo_Player_Building_Count();
        BuildingClass* newest = Demo_Newest_Building();
        if (DemoBuildingCount >= 0 && count > DemoBuildingCount && newest) {
            DemoTarget = Demo_View_For(newest->Center_Coord());
            Thor_Demo_Log("new_building", false, "%s placed", Text_String(newest->Class->Full_Name()));
        } else if (Demo_Find_Combat(where, what, sizeof(what))) {
            DemoTarget = Demo_View_For(where);
            Thor_Demo_Log("camera_combat", false, "follow combat: %s", what);
        } else if (newest) {
            DemoTarget = Demo_View_For(newest->Center_Coord());
        }
        DemoBuildingCount = count;
    }

    /* Radar jump every 20-40 s: cut straight to another hotspot, pulse the deck radar. */
    if (now >= DemoNextJump) {
        DemoNextJump = now + 20000 + Demo_Rand(20000);
        char what[96] = {0};
        COORDINATE where = 0;
        if (Demo_Find_Combat(where, what, sizeof(what))) {
            DemoTarget = Demo_View_For(where);
            Map.Set_Tactical_Position(DemoTarget);
            Map.DisplayClass::IsToRedraw = true;
            Map.Flag_To_Redraw(true);
            Thor_Demo_Radar_Pulse();
            Thor_Demo_Log("radar_jump", false, "radar jump to %s", what);
        }
    }

    if (DemoTarget) {
        int dx = Coord_X(DemoTarget) - Coord_X(Map.TacticalCoord);
        int dy = Coord_Y(DemoTarget) - Coord_Y(Map.TacticalCoord);
        const int step = 24; // leptons per frame: slow, calm glide
        int sx = dx > step ? step : (dx < -step ? -step : dx);
        int sy = dy > step ? step : (dy < -step ? -step : dy);
        if (sx != 0 || sy != 0) {
            Thor_Game_Scroll(sx, sy);
        }
    }

    /* Scripted deck interactions (through the normal sidebar path). */
    if (now >= DemoNextScript) {
        DemoNextScript = now + 35000 + Demo_Rand(15000);
        bool low_power = PlayerPtr->Drain > PlayerPtr->Power;
        Demo_Scripted_Build(0, low_power);
    }
}

/* Main menu -> Multiplayer -> Skirmish -> map -> OK (game coordinates, 640x400). */
bool Thor_Game_Demo_Menu_Step(int step, int& x, int& y, uint32_t& delay_ms)
{
    switch (step) {
    case 0: x = 320; y = 278; delay_ms = 2500; return true; // Multiplayer Game
    case 1: x = 320; y = 230; delay_ms = 3000; return true; // Skirmish
    case 2: x = 105; y = 368; delay_ms = 0; return true;    // OK (map is preselected by name/index)
    default: return false;
    }
}


/* ======================================================================================
** 960x540 view: menus/films/score as a centred 640x400 frame, missions full width.
** ====================================================================================*/
void Thor_Game_Set_View(bool mission)
{
    if (!Thor_Big_View() || Thor_View_State() == (mission ? 1 : 0)) {
        return;
    }
    const int bw = 960, bh = 540;
    int x = mission ? 0 : (bw - 640) / 2;
    int y = mission ? 0 : (bh - 400) / 2;
    int w = mission ? bw : 640;
    int h = mission ? bh : 400;
    Thor_Note_View_State(mission ? 1 : 0);

    SeenBuff.Attach(&VisiblePage, x, y, w, h);
    HidPage.Attach(&HiddenPage, x, y, w, h);
    ScreenWidth = w;
    ScreenHeight = h;
    WindowList[0][WINDOWWIDTH] = w;
    WindowList[0][WINDOWHEIGHT] = h;
    WindowList[WINDOW_EDITOR][WINDOWWIDTH] = w;
    WindowList[WINDOW_EDITOR][WINDOWHEIGHT] = h;
    Video_Set_View(x, y, w, h, true);

    if (!mission && GameActive && Map.MapCellWidth > 0) {
        /*
        ** Dialog during a mission (options, message box, briefing): the map behind it is redrawn
        ** too, so the tactical window must fit the 640x400 frame or it draws outside the buffer.
        */
        Map.Set_View_Dimensions(0, 0);
        Map.Flag_To_Redraw(true);
    }

    if (mission && GameActive && Map.MapCellWidth > 0) {
        /* Black borders, then the tactical view. No tab bar in dual-screen mode. */
        SeenBuff.Clear();
        HidPage.Clear();
        int top = 0;
        int cells_w = THOR_MIN(Map.MapCellWidth, (w + CELL_PIXEL_W - 1) / CELL_PIXEL_W);
        int cells_h = THOR_MIN(Map.MapCellHeight, (h - top + CELL_PIXEL_H - 1) / CELL_PIXEL_H);
        int view_w = THOR_MIN(w, cells_w * CELL_PIXEL_W);
        int view_h = THOR_MIN(h - top, cells_h * CELL_PIXEL_H);
        int vx = (w - view_w) / 2;
        int vy = top + (h - top - view_h) / 2;
        if (view_w >= w && view_h >= h - top) {
            Map.Set_View_Dimensions(0, top);
        } else {
            /* Map smaller than the screen: centre it, the rest stays black. */
            Map.Set_View_Dimensions(vx, vy, view_w / CELL_PIXEL_W, view_h / CELL_PIXEL_H);
        }
        Map.Flag_To_Redraw(true);
    }
}


/* ======================================================================================
** Deck: jump to own units / base, select own units on screen.
** ====================================================================================*/
static void Thor_Center_On(COORDINATE coord)
{
    int cellx = Coord_XCell(coord) - Lepton_To_Cell(Map.TacLeptonWidth) / 2;
    int celly = Coord_YCell(coord) - Lepton_To_Cell(Map.TacLeptonHeight) / 2;
    cellx = THOR_MAX(cellx, Map.MapCellX);
    celly = THOR_MAX(celly, Map.MapCellY);
    Map.Set_Tactical_Position(Cell_Coord(XY_Cell(cellx, celly)));
    Map.DisplayClass::IsToRedraw = true;
    Map.Flag_To_Redraw(true);
}

static void Thor_Collect_Units(TechnoClass** list, int& n, int cap)
{
    n = 0;
    for (int i = 0; i < Units.Count() && n < cap; i++) {
        TechnoClass* t = Units.Ptr(i);
        if (t && !t->IsInLimbo && t->Strength > 0 && t->House == PlayerPtr) {
            list[n++] = t;
        }
    }
    for (int i = 0; i < Infantry.Count() && n < cap; i++) {
        TechnoClass* t = Infantry.Ptr(i);
        if (t && !t->IsInLimbo && t->Strength > 0 && t->House == PlayerPtr) {
            list[n++] = t;
        }
    }
}

/* Bit per group number 0..9 that has living own members. */
static int Thor_Groups_Used()
{
    int used = 0;
    for (int i = 0; i < Units.Count(); i++) {
        FootClass* f = Units.Ptr(i);
        if (f && !f->IsInLimbo && f->Strength > 0 && f->House == PlayerPtr && f->Group < 10) {
            used |= 1 << f->Group;
        }
    }
    for (int i = 0; i < Infantry.Count(); i++) {
        FootClass* f = Infantry.Ptr(i);
        if (f && !f->IsInLimbo && f->Strength > 0 && f->House == PlayerPtr && f->Group < 10) {
            used |= 1 << f->Group;
        }
    }
    for (int i = 0; i < Aircraft.Count(); i++) {
        FootClass* f = Aircraft.Ptr(i);
        if (f && !f->IsInLimbo && f->Strength > 0 && f->House == PlayerPtr && f->Group < 10) {
            used |= 1 << f->Group;
        }
    }
    return used;
}

void Thor_Game_Jump(int what)
{
    static int next = 0;
    TechnoClass* list[512];
    int n = 0;
    if (what == 0) {
        Thor_Collect_Units(list, n, 512);
        if (n > 0) {
            next %= n;
            Thor_Center_On(list[next]->Center_Coord());
            next++;
        }
    } else if (what == 1) {
        /* Like "H" in the original, then repeated taps cycle through the factories. */
        static int base_next = 0;
        static uint32_t base_at = 0;
        BuildingClass* blist[64];
        int bn = 0;
        /* pass 0: construction yards, 1: factories, 2 (only if nothing yet): any building */
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < Buildings.Count() && bn < 64 && (pass < 2 || bn == 0); i++) {
                BuildingClass* b = Buildings.Ptr(i);
                if (!b || b->IsInLimbo || !(b->House == PlayerPtr) || b->Strength <= 0) {
                    continue;
                }
                bool yard = *b == STRUCT_CONST;
                if (pass == 2 || (pass == 0 && yard) || (pass == 1 && !yard && b->Class->ToBuild != RTTI_NONE)) {
                    blist[bn++] = b;
                }
            }
        }
        uint32_t now = SDL_GetTicks();
        if (now - base_at > 4000) {
            base_next = 0; // a fresh tap always starts at the construction yard
        }
        base_at = now;
        if (bn > 0) {
            base_next %= bn;
            Thor_Center_On(blist[base_next]->Center_Coord());
            base_next++;
        }
    } else if (what == 2) {
        Thor_Collect_Units(list, n, 512);
        bool any = false;
        for (int i = 0; i < n; i++) {
            if (Map.In_View(Coord_Cell(list[i]->Center_Coord()))) {
                if (!any) {
                    Unselect_All();
                    any = true;
                }
                list[i]->Select(true);
            }
        }
    } else if (what == 3) {
        /* Selection off screen: jump there. Otherwise select + centre the next group (like Alt+1..9). */
        static int group_next = 0;
        if (CurrentObject.Count() > 0) {
            int x = 0, y = 0;
            for (int i = 0; i < CurrentObject.Count(); i++) {
                x += Coord_X(CurrentObject[i]->Center_Coord()) / CurrentObject.Count();
                y += Coord_Y(CurrentObject[i]->Center_Coord()) / CurrentObject.Count();
            }
            COORDINATE center = XY_Coord(x, y);
            if (!Map.In_View(Coord_Cell(center))) {
                Thor_Center_On(center);
                return;
            }
        }
        int used = Thor_Groups_Used();
        for (int k = 0; k < 10; k++) {
            int g = (group_next + k) % 10;
            if (used & (1 << g)) {
                Handle_Team(g, 3);
                group_next = g + 1;
                break;
            }
        }
    } else if (what == 4) {
        if (Thor_Attack_Known()) {
            Thor_Center_On(ThorAttackCoord);
        }
    } else if (what == 5) {
        /* Make the selection a group (like Ctrl+1..9), first free number. */
        bool feet = false;
        for (int i = 0; i < CurrentObject.Count(); i++) {
            RTTIType r = CurrentObject[i]->What_Am_I();
            feet = feet || r == RTTI_UNIT || r == RTTI_INFANTRY || r == RTTI_AIRCRAFT;
        }
        int used = Thor_Groups_Used();
        for (int g = 0; feet && g < 10; g++) {
            if (!(used & (1 << g))) {
                Handle_Team(g, 2);
                break;
            }
        }
    }
}

/* ======================================================================================
** Deck: orders, control groups, quick save, interface art (shared source, see the .inc).
** ====================================================================================*/
#define THOR_HAS_VESSELS 1
#define THOR_TARGET(obj) TargetClass(obj)
#define THOR_SINGLE_PLAYER (Session.Type == GAME_NORMAL)
#define THOR_AFTER_LOAD()                                                                                              \
    do {                                                                                                               \
        Hide_Mouse();                                                                                                  \
        SeenPage.Clear();                                                                                              \
        GamePalette.Set();                                                                                             \
        Show_Mouse();                                                                                                  \
    } while (0)

/* The same files the sidebar, radar, power bar and tab bar load (side 0 = Allies, 1 = Soviets). */
static void const* Thor_Art_Shape(int art, int side)
{
    static const char* const names[THOR_ART_COUNT][2] = {
        {"SIDE1NA.SHP", "SIDE1US.SHP"},   {"SIDE2NA.SHP", "SIDE2US.SHP"},   {"SIDE3NA.SHP", "SIDE3US.SHP"},
        {"STRIPNA.SHP", "STRIPUS.SHP"},   {"NATORADR.SHP", "USSRRADR.SHP"}, {"NRADRFRM.SHP", "URADRFRM.SHP"},
        {"REPAIR.SHP", "REPAIR.SHP"},     {"SELL.SHP", "SELL.SHP"},         {"MAP.SHP", "MAP.SHP"},
        {"POWERBAR.SHP", "POWERBAR.SHP"}, {"POWER.SHP", "POWER.SHP"},       {"TABS.SHP", "TABS.SHP"},
        {"STRIPUP.SHP", "STRIPUP.SHP"},   {"STRIPDN.SHP", "STRIPDN.SHP"},
    };
    return (art >= 0 && art < THOR_ART_COUNT) ? MFCD::Retrieve(names[art][side]) : nullptr;
}

/* Font 1: the tab bar's metal font. Font 0: the small grey font of the sidebar. */
static void Thor_Print(int font, const char* text, int x, int y)
{
    if (font == 1) {
        Fancy_Text_Print("%s", x, y, &MetalScheme, TBLACK, TPF_METAL12 | TPF_USE_GRAD_PAL, text);
    } else {
        Fancy_Text_Print("%s", x, y, &ColorRemaps[PCOLOR_GREY], TBLACK,
                         TPF_6PT_GRAD | TPF_USE_GRAD_PAL | TPF_NOSHADOW, text);
    }
}

#include "common/thor_deck.inc"

#endif
