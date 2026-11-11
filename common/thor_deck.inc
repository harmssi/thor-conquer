/*
** Thor Conquer - deck parts that are the same source for Red Alert and Tiberian Dawn.
** Included by redalert/thor_game.cpp and tiberiandawn/thor_game.cpp, which define before:
**   THOR_TARGET(obj)             target of an object for an EventClass
**   THOR_SINGLE_PLAYER           true in a campaign mission (saving allowed)
**   THOR_AFTER_LOAD()            what the load dialog does after a successful Load_Game
**   Thor_Art_Shape(art, side)    the game's own interface shape (from the player's MIX files) or nullptr
**   Thor_Print(font, text, x, y) print with the game's own text code into the logic page
**
** Orders go the same way as the keyboard commands in Keyboard_Process (conquer.cpp): events in
** OutList, Handle_Team, Save_Game / Load_Game. Nothing here changes the game state directly.
*/

enum
{
    THOR_QUICK_SLOT = 99 // SAVEGAME.099, also listed in the game's own load dialog
};

static bool Thor_Is_Foot(ObjectClass const* obj)
{
    RTTIType r = obj->What_Am_I();
    return r == RTTI_UNIT || r == RTTI_INFANTRY || r == RTTI_AIRCRAFT
#ifdef THOR_HAS_VESSELS
           || r == RTTI_VESSEL
#endif
        ;
}

/* One own factory selected: the building that a "primary" order would go to. */
static BuildingClass* Thor_Selected_Factory()
{
    if (CurrentObject.Count() != 1 || CurrentObject[0]->What_Am_I() != RTTI_BUILDING) {
        return nullptr;
    }
    BuildingClass* b = (BuildingClass*)CurrentObject[0];
    return (b->House == PlayerPtr && b->Class->ToBuild != RTTI_NONE) ? b : nullptr;
}

static bool Thor_Quick_Save_Exists()
{
    static uint32_t checked = 0;
    static bool exists = false;
    uint32_t now = SDL_GetTicks();
    if (checked == 0 || now - checked > 2000) {
        checked = now ? now : 1;
        char name[32];
        snprintf(name, sizeof(name), "SAVEGAME.%03d", THOR_QUICK_SLOT);
        exists = CDFileClass(name).Is_Available();
    }
    return exists;
}

template <class T> static void Thor_Count_Groups(T& heap, int* members)
{
    for (int i = 0; i < heap.Count(); i++) {
        FootClass* f = heap.Ptr(i);
        if (f && !f->IsInLimbo && f->Strength > 0 && f->House == PlayerPtr && f->Group < 4) {
            members[f->Group]++;
        }
    }
}

/* Snapshot values [20..27] and the flags that belong to the orders below. */
static void Thor_Deck_Snapshot(std::vector<int>& out)
{
    int members[4] = {0, 0, 0, 0};
    Thor_Count_Groups(Units, members);
    Thor_Count_Groups(Infantry, members);
    Thor_Count_Groups(Aircraft, members);
#ifdef THOR_HAS_VESSELS
    Thor_Count_Groups(Vessels, members);
#endif
    for (int g = 0; g < 4; g++) {
        out[21 + g] = members[g];
        if (members[g] > 0) {
            out[20] |= 1 << g;
        }
    }
    out[25] = Options.GameSpeed;
    out[26] = CurrentObject.Count();

    /* The group that is selected: everything selected belongs to it and all of it is selected. */
    int group = -1;
    for (int i = 0; i < CurrentObject.Count(); i++) {
        int g = Thor_Is_Foot(CurrentObject[i]) ? ((FootClass*)CurrentObject[i])->Group : 0xFF;
        if (g >= 4 || (group != -1 && g != group)) {
            group = -1;
            break;
        }
        group = g;
    }
    out[27] = (group >= 0 && members[group] == CurrentObject.Count()) ? group : -1;

    if (Thor_Selected_Factory() != nullptr) {
        out[5] |= THOR_FLAG_PRIMARY_OK;
    }
    if (THOR_SINGLE_PLAYER) {
        out[5] |= THOR_FLAG_SAVE_OK;
        if (Thor_Quick_Save_Exists()) {
            out[5] |= THOR_FLAG_QUICKSAVE;
        }
    }
}

static void Thor_Deck_Order(int what)
{
    switch (what) {
    case 0: // stop (Options.KeyStop)
        for (int i = 0; i < CurrentObject.Count(); i++) {
            ObjectClass* tech = CurrentObject[i];
            if (tech->Can_Player_Move() || (tech->Can_Player_Fire() && tech->What_Am_I() != RTTI_BUILDING)) {
                OutList.Add(EventClass(EventClass::IDLE, THOR_TARGET(tech)));
            }
        }
        break;
    case 1: // guard area (Options.KeyGuard)
        for (int i = 0; i < CurrentObject.Count(); i++) {
            ObjectClass* tech = CurrentObject[i];
            if (tech->Can_Player_Move() && tech->Can_Player_Fire()) {
                OutList.Add(EventClass(THOR_TARGET(tech), MISSION_GUARD_AREA));
            }
        }
        break;
    case 2: // scatter (Options.KeyScatter)
        for (int i = 0; i < CurrentObject.Count(); i++) {
            ObjectClass* tech = CurrentObject[i];
            if (tech->Can_Player_Move()) {
                OutList.Add(EventClass(EventClass::SCATTER, THOR_TARGET(tech)));
            }
        }
        break;
    case 3: { // the whole army: every own unit that can move and shoot, wherever it is
        TechnoClass* list[512];
        int n = 0;
        Thor_Collect_Units(list, n, 512);
        bool any = false;
        for (int i = 0; i < n; i++) {
            if (list[i]->Can_Player_Move() && list[i]->Can_Player_Fire()) {
                if (!any) {
                    Unselect_All();
                    any = true;
                }
                list[i]->Select(true);
            }
        }
        break;
    }
    case 4: // deselect, leave repair and sell mode
        Unselect_All();
        Map.Repair_Mode_Control(0);
        Map.Sell_Mode_Control(0);
        break;
    case 5: // primary factory (same event as a click on the selected factory)
        if (BuildingClass* b = Thor_Selected_Factory()) {
            OutList.Add(EventClass(EventClass::PRIMARY, THOR_TARGET(b)));
        }
        break;
    default:
        break;
    }
}

static void Thor_Deck_Quick(int what)
{
    if (!THOR_SINGLE_PLAYER) {
        return;
    }
    if (what == 0) {
        char descr[40];
        time_t now = time(nullptr);
        strftime(descr, sizeof(descr), "Thor quick save %d.%m. %H:%M", localtime(&now));
        Save_Game(THOR_QUICK_SLOT, descr);
    } else if (Thor_Quick_Save_Exists()) {
        /* Thor_Frame runs at the top of Main_Loop, so this is between two game frames like the load dialog. */
        if (Load_Game(THOR_QUICK_SLOT)) {
            if (PlayerPtr) {
                CurrentObject.Set_Active_Context(PlayerPtr->Class->House);
            }
            THOR_AFTER_LOAD();
            Map.Flag_To_Redraw(true);
            Thor_Note_View_State(-1); // the 960x540 view is set again by Thor_Frame
        }
    }
}

static bool Thor_Deck_Command(const ThorCommand& cmd)
{
    switch (cmd.type) {
    case THOR_CMD_GROUP:
        if (cmd.a >= 0 && cmd.a < 4) {
            /* Handle_Team: 0 = select (plain number key), 2 = store (Ctrl), 3 = select and centre (Alt). */
            Handle_Team(cmd.a, cmd.b == 1 ? 2 : cmd.b == 2 ? 3 : 0);
        }
        return true;
    case THOR_CMD_ORDER:
        Thor_Deck_Order(cmd.a);
        return true;
    case THOR_CMD_SPEED:
        if (cmd.a >= 1 && cmd.a <= 6) {
            OutList.Add(EventClass(EventClass::GAMESPEED, cmd.a)); // like the slider in the game controls dialog
        }
        return true;
    case THOR_CMD_QUICK:
        Thor_Deck_Quick(cmd.a);
        return true;
    default:
        return false;
    }
}

/*
** Interface art for the deck: one frame of one of the game's own shapes, or a font atlas printed
** with the game's own text code. Palette indices; the deck colours them with the game palette.
*/
static bool Thor_Deck_Art(int key, std::vector<unsigned char>& pixels, int& w, int& h)
{
    int type = key >> 16;
    if (type == THOR_KEY_FONT) {
        /* ASCII 32..127, 16 x 6 cells; the deck measures the glyph widths itself. */
        w = 16 * THOR_FONT_CELL;
        h = 6 * THOR_FONT_CELL;
        pixels.assign(w * h, 0);
        GraphicBufferClass page(w, h, pixels.data());
        GraphicViewPortClass* old = Set_Logic_Page(page);
        for (int c = 33; c < 127; c++) {
            char text[2] = {(char)c, 0};
            Thor_Print(key & 0xFF, text, ((c - 32) % 16) * THOR_FONT_CELL + 1, ((c - 32) / 16) * THOR_FONT_CELL + 1);
        }
        Set_Logic_Page(old);
        return true;
    }
    int art = (key >> 8) & 0x7F;
    int frame = key & 0xFF;
    void const* shape = Thor_Art_Shape(art, (key >> 15) & 1);
    if (shape == nullptr || frame >= Get_Build_Frame_Count(shape)) {
        return false;
    }
    w = Get_Build_Frame_Width(shape);
    h = Get_Build_Frame_Height(shape);
    if (w <= 0 || h <= 0 || w * h > 256 * 256) {
        return false;
    }
    uintptr_t built = Build_Frame(shape, frame, _ShapeBuffer);
    if (!built) {
        return false;
    }
    unsigned char const* data = (unsigned char const*)Get_Shape_Header_Data((void*)built);
    pixels.assign(data, data + w * h);
    return true;
}
