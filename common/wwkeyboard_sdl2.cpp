//
// Copyright 2020 Electronic Arts Inc.
//
// TiberianDawn.DLL and RedAlert.dll and corresponding source code is free
// software: you can redistribute it and/or modify it under the terms of
// the GNU General Public License as published by the Free Software Foundation,
// either version 3 of the License, or (at your option) any later version.

// TiberianDawn.DLL and RedAlert.dll and corresponding source code is distributed
// in the hope that it will be useful, but with permitted additional restrictions
// under Section 7 of the GPL. See the GNU General Public License in LICENSE.TXT
// distributed with this program. You should have received a copy of the
// GNU General Public License along with permitted additional restrictions
// with this program. If not, see https://github.com/electronicarts/CnC_Remastered_Collection

#include "macros.h"
#include "wwkeyboard_sdl2.h"
#include "video.h"
#include "sdl_keymap.h"
#include "settings.h"
#include <cmath>
#include <SDL.h>

#if ANDROID
#include "thor.h"
static bool touchIsActive = false;

/*
** Thor: Touch-Gesten fuer den oberen Bildschirm.
**   Tippen                 = Linksklick
**   Ziehen (ein Finger)    = Linke Taste halten (Auswahlrahmen)
**   Langes Druecken        = Rechtsklick (abwaehlen/abbrechen)
**   Zwei Finger ziehen     = Karte scrollen
**   Zwei Finger tippen     = Rechtsklick
*/
namespace
{
    enum TouchMode
    {
        TOUCH_IDLE,
        TOUCH_PENDING,
        TOUCH_DRAG,
        TOUCH_LONG_DONE,
        TOUCH_MULTI
    };

    struct Finger
    {
        SDL_FingerID id;
        float x; // normiert 0..1
        float y;
    };

    const int TOUCH_MAX_FINGERS = 5;
    const uint32_t TOUCH_LONG_PRESS_MS = 450;
    const uint32_t TOUCH_TWO_TAP_MS = 350;
    const float TOUCH_DRAG_THRESHOLD = 0.012f; // Anteil der Fensterbreite

    struct TouchState
    {
        TouchMode Mode = TOUCH_IDLE;
        Finger Fingers[TOUCH_MAX_FINGERS];
        int Count = 0;
        float StartX = 0; // normiert
        float StartY = 0;
        uint32_t DownTicks = 0;
        float CentroidX = 0; // Spielpixel
        float CentroidY = 0;
        float MultiMoved = 0;
    } Touch;

    int Touch_Find(SDL_FingerID id)
    {
        for (int i = 0; i < Touch.Count; i++) {
            if (Touch.Fingers[i].id == id) {
                return i;
            }
        }
        return -1;
    }

    void Touch_Centroid(float& gx, float& gy)
    {
        float nx = 0, ny = 0;
        for (int i = 0; i < Touch.Count; i++) {
            nx += Touch.Fingers[i].x;
            ny += Touch.Fingers[i].y;
        }
        if (Touch.Count > 0) {
            nx /= Touch.Count;
            ny /= Touch.Count;
        }
        Video_Touch_To_Game(nx, ny, gx, gy);
    }
} // namespace

static void Touch_Click(WWKeyboardClassSDL2* kb, unsigned short key)
{
    int x, y;
    Get_Video_Mouse(x, y);
    kb->Thor_Mouse(key, x, y, false);
    kb->Thor_Mouse(key, x, y, true);
}

static void Touch_Event(WWKeyboardClassSDL2* kb, const SDL_TouchFingerEvent& f, Uint32 type)
{
    touchIsActive = true;
    float gx, gy;

    switch (type) {
    case SDL_FINGERDOWN: {
        if (Touch.Count < TOUCH_MAX_FINGERS && Touch_Find(f.fingerId) < 0) {
            Touch.Fingers[Touch.Count++] = Finger{f.fingerId, f.x, f.y};
        }
        if (Touch.Count == 1) {
            Touch.Mode = TOUCH_PENDING;
            Touch.StartX = f.x;
            Touch.StartY = f.y;
            Touch.DownTicks = SDL_GetTicks();
            Video_Touch_To_Game(f.x, f.y, gx, gy);
            Set_Video_Mouse(gx, gy);
        } else if (Touch.Mode == TOUCH_PENDING || Touch.Mode == TOUCH_DRAG) {
            if (Touch.Mode == TOUCH_DRAG) {
                int x, y;
                Get_Video_Mouse(x, y);
                kb->Thor_Mouse(VK_LBUTTON, x, y, true);
            }
            Touch.Mode = TOUCH_MULTI;
            Touch.MultiMoved = 0;
            Touch_Centroid(Touch.CentroidX, Touch.CentroidY);
        } else if (Touch.Mode == TOUCH_MULTI) {
            Touch_Centroid(Touch.CentroidX, Touch.CentroidY);
        }
    } break;

    case SDL_FINGERMOTION: {
        int i = Touch_Find(f.fingerId);
        if (i < 0) {
            break;
        }
        Touch.Fingers[i].x = f.x;
        Touch.Fingers[i].y = f.y;

        if (Touch.Mode == TOUCH_PENDING) {
            float dx = f.x - Touch.StartX;
            float dy = (f.y - Touch.StartY) * 9.0f / 16.0f;
            if (std::sqrt(dx * dx + dy * dy) > TOUCH_DRAG_THRESHOLD) {
                /* Ziehen beginnt: linke Taste am Startpunkt druecken. */
                Video_Touch_To_Game(Touch.StartX, Touch.StartY, gx, gy);
                Set_Video_Mouse(gx, gy);
                kb->Thor_Mouse(VK_LBUTTON, (int)gx, (int)gy, false);
                Touch.Mode = TOUCH_DRAG;
            }
        }
        if (Touch.Mode == TOUCH_DRAG) {
            Video_Touch_To_Game(f.x, f.y, gx, gy);
            Set_Video_Mouse(gx, gy);
        } else if (Touch.Mode == TOUCH_MULTI) {
            Touch_Centroid(gx, gy);
            float dx = gx - Touch.CentroidX;
            float dy = gy - Touch.CentroidY;
            Touch.CentroidX = gx;
            Touch.CentroidY = gy;
            Touch.MultiMoved += std::fabs(dx) + std::fabs(dy);
            Thor_Touch_Scroll(dx, dy);
        }
    } break;

    case SDL_FINGERUP: {
        int i = Touch_Find(f.fingerId);
        if (i >= 0) {
            Touch.Fingers[i] = Touch.Fingers[--Touch.Count];
        }
        if (Touch.Mode == TOUCH_PENDING) {
            Touch_Click(kb, VK_LBUTTON);
            Touch.Mode = TOUCH_IDLE;
        } else if (Touch.Mode == TOUCH_DRAG) {
            Video_Touch_To_Game(f.x, f.y, gx, gy);
            Set_Video_Mouse(gx, gy);
            kb->Thor_Mouse(VK_LBUTTON, (int)gx, (int)gy, true);
            Touch.Mode = TOUCH_IDLE;
        } else if (Touch.Mode == TOUCH_MULTI) {
            if (Touch.Count == 0) {
                if (Touch.MultiMoved < 6.0f && SDL_GetTicks() - Touch.DownTicks < TOUCH_TWO_TAP_MS + 300) {
                    Touch_Click(kb, VK_RBUTTON);
                }
                Touch.Mode = TOUCH_IDLE;
            } else {
                Touch_Centroid(Touch.CentroidX, Touch.CentroidY);
            }
        }
        if (Touch.Count == 0) {
            Touch.Mode = TOUCH_IDLE;
        }
    } break;
    }
}

static void Touch_Update(WWKeyboardClassSDL2* kb)
{
    if (Touch.Mode == TOUCH_PENDING && Touch.Count == 1 && SDL_GetTicks() - Touch.DownTicks >= TOUCH_LONG_PRESS_MS) {
        Touch_Click(kb, VK_RBUTTON);
        Touch.Mode = TOUCH_LONG_DONE;
    }
}
#endif

void Focus_Loss();
void Focus_Restore();
void Process_Network();

WWKeyboardClassSDL2::~WWKeyboardClassSDL2()
{
}

void WWKeyboardClassSDL2::Fill_Buffer_From_System(void)
{
#ifdef NETWORKING
    Process_Network();
#endif
    SDL_Event event;

    while (!Is_Buffer_Full() && SDL_PollEvent(&event)) {
        unsigned short key;
        switch (event.type) {
        case SDL_QUIT:
            exit(0);
            break;
        case SDL_KEYDOWN:
            Put_Key_Message(event.key.keysym.scancode, false);
            break;
        case SDL_KEYUP:
            if (event.key.keysym.scancode == SDL_SCANCODE_RETURN && Down(VK_MENU)) {
                Toggle_Video_Fullscreen();
            } else {
                Put_Key_Message(event.key.keysym.scancode, true);
            }
            break;
        case SDL_MOUSEMOTION:
#if ANDROID
            touchIsActive = event.motion.which == SDL_TOUCH_MOUSEID;
            if (Settings.Mouse.RawInput) {
                Move_Video_Mouse(static_cast<float>(event.motion.xrel), static_cast<float>(event.motion.yrel));
            } else {
                float gx, gy;
                Video_Window_To_Game((float)event.motion.x, (float)event.motion.y, gx, gy);
                Set_Video_Mouse(gx, gy);
            }
#else
            Move_Video_Mouse(static_cast<float>(event.motion.xrel), static_cast<float>(event.motion.yrel));
#endif
            break;
#if ANDROID
        case SDL_FINGERDOWN:
        case SDL_FINGERMOTION:
        case SDL_FINGERUP:
            Touch_Event(this, event.tfinger, event.type);
            break;
#endif
        case SDL_MOUSEBUTTONDOWN:
        case SDL_MOUSEBUTTONUP: {
            int x, y;
#if ANDROID
            touchIsActive = event.motion.which == SDL_TOUCH_MOUSEID;
#endif
            switch (event.button.button) {
            case SDL_BUTTON_LEFT:
            default:
                key = VK_LBUTTON;
                break;
            case SDL_BUTTON_RIGHT:
                key = VK_RBUTTON;
                break;
            case SDL_BUTTON_MIDDLE:
                key = VK_MBUTTON;
                break;
            }
#ifndef ANDROID
            if (Settings.Mouse.RawInput || Is_Gamepad_Active()) {
#else
            if (Settings.Mouse.RawInput) {
#endif
                Get_Video_Mouse(x, y);
            } else {
#if ANDROID
                float gx, gy;
                Video_Window_To_Game((float)event.button.x, (float)event.button.y, gx, gy);
                Set_Video_Mouse(gx, gy);
                x = (int)gx;
                y = (int)gy;
#else
                float scale_x = 1.0f, scale_y = 1.0f;
                Get_Video_Scale(scale_x, scale_y);
                x = event.button.x / scale_x;
                y = event.button.y / scale_y;
#endif
            }

            Put_Mouse_Message(key, x, y, event.type == SDL_MOUSEBUTTONDOWN ? false : true);
        } break;
#ifndef ANDROID
        case SDL_WINDOWEVENT:
            switch (event.window.event) {
            case SDL_WINDOWEVENT_EXPOSED:
            case SDL_WINDOWEVENT_RESTORED:
            case SDL_WINDOWEVENT_FOCUS_GAINED:
                Focus_Restore();
                break;
            case SDL_WINDOWEVENT_HIDDEN:
            case SDL_WINDOWEVENT_MINIMIZED:
            case SDL_WINDOWEVENT_FOCUS_LOST:
                Focus_Loss();
                break;
            }
            break;
#else
            case SDL_WINDOWEVENT:
                switch (event.window.event) {
                    case SDL_WINDOWEVENT_SIZE_CHANGED:
                        Update_HWCursor_Settings();
                        break;
                }
#endif
        case SDL_MOUSEWHEEL:
#if ANDROID
                touchIsActive = event.motion.which == SDL_TOUCH_MOUSEID;
#endif
                if (event.wheel.y > 0) { // scroll up
                Put_Key_Message(VK_MOUSEWHEEL_UP, false);
            } else if (event.wheel.y < 0) { // scroll down
                Put_Key_Message(VK_MOUSEWHEEL_DOWN, false);
            }
            break;
#ifndef ANDROID
        case SDL_CONTROLLERDEVICEREMOVED:
            if (GameController != nullptr) {
                const SDL_GameController* removedController = SDL_GameControllerFromInstanceID(event.jdevice.which);
                if (removedController == GameController) {
                    SDL_GameControllerClose(GameController);
                    GameController = nullptr;
                }
            }
            break;
        case SDL_CONTROLLERDEVICEADDED:
            if (GameController == nullptr) {
                GameController = SDL_GameControllerOpen(event.jdevice.which);
            }
            break;
#else
            case SDL_JOYDEVICEADDED:
            case SDL_JOYDEVICEREMOVED:
            case SDL_CONTROLLERDEVICEADDED:
            case SDL_CONTROLLERDEVICEREMOVED:
            case SDL_CONTROLLERDEVICEREMAPPED:
                Close_Controller();
                Open_Controller();
                break;
#endif
        case SDL_CONTROLLERAXISMOTION:
#if ANDROID
            touchIsActive = false;
#endif
            Handle_Controller_Axis_Event(event.caxis);
            break;
        case SDL_CONTROLLERBUTTONDOWN:
        case SDL_CONTROLLERBUTTONUP:
#if ANDROID
            touchIsActive = false;
#endif
            Handle_Controller_Button_Event(event.cbutton);
            break;
        }
    }
    if (Is_Gamepad_Active()) {
        Process_Controller_Axis_Motion();
    }
#if ANDROID
    Touch_Update(this);
    Thor_Input_Tick();
#endif
}

#if ANDROID
extern WWKeyboardClass* Keyboard;

void Thor_Inject_Click(int x, int y, bool right)
{
    if (!Keyboard) {
        return;
    }
    WWKeyboardClassSDL2* kb = (WWKeyboardClassSDL2*)Keyboard;
    Set_Video_Mouse((float)x, (float)y);
    unsigned short key = right ? VK_RBUTTON : VK_LBUTTON;
    kb->Thor_Mouse(key, x, y, false);
    kb->Thor_Mouse(key, x, y, true);
}
#endif

#ifdef ANDROID
extern void SetMute(bool mute);

int SDLCALL EventFilter(void*, SDL_Event* event)
{
    switch (event->type)
    {
        case SDL_APP_WILLENTERBACKGROUND:
            Focus_Loss();
            SetMute(true);
            break;
        case SDL_APP_DIDENTERFOREGROUND:
            Focus_Restore();
            SetMute(false);
            break;
    }

    return 1;
}
#endif

bool WWKeyboardClassSDL2::Is_Gamepad_Active()
{
#ifndef ANDROID
    return GameController != nullptr;
#else
    return GameController != nullptr && !touchIsActive;
#endif
}

void WWKeyboardClassSDL2::Open_Controller()
{
#ifndef ANDROID
    for (int i = 0; i < SDL_NumJoysticks(); ++i) {
        if (SDL_IsGameController(i)) {
            GameController = SDL_GameControllerOpen(i);
        }
    }
#else
    SDL_GameControllerUpdate();

    const int numJoysticks = SDL_NumJoysticks();
    int virtualControllerIndex = -1;

    for (int i = 0; i < numJoysticks; i++) {
        if(SDL_JoystickIsVirtual(i)){
            virtualControllerIndex = i;
            break;
        }
    }

    for (int joyIdx = 0; joyIdx < numJoysticks; ++joyIdx) {
        if (virtualControllerIndex!=-1 && joyIdx!=virtualControllerIndex){
            continue;
        }
        if (SDL_IsGameController(joyIdx)) {
            GameController = SDL_GameControllerOpen(joyIdx);
            if (GameController != nullptr) {
                break;
            }
        }
    }

#endif
}

void WWKeyboardClassSDL2::Close_Controller()
{
    if (SDL_GameControllerGetAttached(GameController)) {
        SDL_GameControllerClose(GameController);
        GameController = nullptr;
    }
}

void WWKeyboardClassSDL2::Process_Controller_Axis_Motion()
{
    const uint32_t currentTime = SDL_GetTicks();
    const float deltaTime = currentTime - LastControllerTime;
    LastControllerTime = currentTime;

    if (ControllerLeftXAxis != 0 || ControllerLeftYAxis != 0) {
        const int16_t xSign = (ControllerLeftXAxis > 0) - (ControllerLeftXAxis < 0);
        const int16_t ySign = (ControllerLeftYAxis > 0) - (ControllerLeftYAxis < 0);

        float movX = std::pow(std::abs(ControllerLeftXAxis), CONTROLLER_AXIS_SPEEDUP) * xSign * deltaTime
                     * Settings.Mouse.ControllerPointerSpeed / CONTROLLER_SPEED_MOD * ControllerSpeedBoost;
        float movY = std::pow(std::abs(ControllerLeftYAxis), CONTROLLER_AXIS_SPEEDUP) * ySign * deltaTime
                     * Settings.Mouse.ControllerPointerSpeed / CONTROLLER_SPEED_MOD * ControllerSpeedBoost;

        Move_Video_Mouse(movX, movY);
    }
}

void WWKeyboardClassSDL2::Handle_Controller_Axis_Event(const SDL_ControllerAxisEvent& motion)
{
    AnalogScrollActive = false;
    ScrollDirType directionX = SDIR_NONE;
    ScrollDirType directionY = SDIR_NONE;

    if (motion.axis == SDL_CONTROLLER_AXIS_LEFTX) {
        if (std::abs(motion.value) > CONTROLLER_L_DEADZONE)
            ControllerLeftXAxis = motion.value;
        else
            ControllerLeftXAxis = 0;
    } else if (motion.axis == SDL_CONTROLLER_AXIS_LEFTY) {
        if (std::abs(motion.value) > CONTROLLER_L_DEADZONE)
            ControllerLeftYAxis = motion.value;
        else
            ControllerLeftYAxis = 0;
    } else if (motion.axis == SDL_CONTROLLER_AXIS_RIGHTX) {
        if (std::abs(motion.value) > CONTROLLER_R_DEADZONE)
            ControllerRightXAxis = motion.value;
        else
            ControllerRightXAxis = 0;
    } else if (motion.axis == SDL_CONTROLLER_AXIS_RIGHTY) {
        if (std::abs(motion.value) > CONTROLLER_R_DEADZONE)
            ControllerRightYAxis = motion.value;
        else
            ControllerRightYAxis = 0;
    } else if (motion.axis == SDL_CONTROLLER_AXIS_TRIGGERRIGHT) {
        if (std::abs(motion.value) > CONTROLLER_TRIGGER_R_DEADZONE)
            ControllerSpeedBoost = 1 + (static_cast<float>(motion.value) / 32767) * CONTROLLER_TRIGGER_SPEEDUP;
        else
            ControllerSpeedBoost = 1;
    }

    if (ControllerRightXAxis != 0) {
        AnalogScrollActive = true;
        directionX = ControllerRightXAxis > 0 ? SDIR_E : SDIR_W;
    }
    if (ControllerRightYAxis != 0) {
        AnalogScrollActive = true;
        directionY = ControllerRightYAxis > 0 ? SDIR_S : SDIR_N;
    }

    if (directionX == SDIR_E && directionY == SDIR_N) {
        ScrollDirection = SDIR_NE;
    } else if (directionX == SDIR_E && directionY == SDIR_S) {
        ScrollDirection = SDIR_SE;
    } else if (directionX == SDIR_W && directionY == SDIR_N) {
        ScrollDirection = SDIR_NW;
    } else if (directionX == SDIR_W && directionY == SDIR_S) {
        ScrollDirection = SDIR_SW;
    } else if (directionX == SDIR_E) {
        ScrollDirection = SDIR_E;
    } else if (directionX == SDIR_W) {
        ScrollDirection = SDIR_W;
    } else if (directionY == SDIR_S) {
        ScrollDirection = SDIR_S;
    } else if (directionY == SDIR_N) {
        ScrollDirection = SDIR_N;
    }
}

void WWKeyboardClassSDL2::Handle_Controller_Button_Event(const SDL_ControllerButtonEvent& button)
{
    bool keyboardPress = false;
    bool mousePress = false;
    unsigned short key;
    SDL_Scancode scancode;

    switch (button.button) {
    case SDL_CONTROLLER_BUTTON_A:
        mousePress = true;
        key = VK_LBUTTON;
        break;
    case SDL_CONTROLLER_BUTTON_B:
        mousePress = true;
        key = VK_RBUTTON;
        break;
    case SDL_CONTROLLER_BUTTON_X:
        keyboardPress = true;
        scancode = SDL_SCANCODE_G;
        break;
    case SDL_CONTROLLER_BUTTON_Y:
        keyboardPress = true;
        scancode = SDL_SCANCODE_F;
        break;
    case SDL_CONTROLLER_BUTTON_BACK:
        keyboardPress = true;
        scancode = SDL_SCANCODE_ESCAPE;
        break;
    case SDL_CONTROLLER_BUTTON_START:
        keyboardPress = true;
        scancode = SDL_SCANCODE_RETURN;
        break;
    case SDL_CONTROLLER_BUTTON_LEFTSHOULDER:
        keyboardPress = true;
        scancode = SDL_SCANCODE_LCTRL;
        break;
    case SDL_CONTROLLER_BUTTON_RIGHTSHOULDER:
        keyboardPress = true;
        scancode = SDL_SCANCODE_LALT;
        break;
    case SDL_CONTROLLER_BUTTON_DPAD_UP:
        keyboardPress = true;
        scancode = SDL_SCANCODE_1;
        break;
    case SDL_CONTROLLER_BUTTON_DPAD_RIGHT:
        keyboardPress = true;
        scancode = SDL_SCANCODE_2;
        break;
    case SDL_CONTROLLER_BUTTON_DPAD_DOWN:
        keyboardPress = true;
        scancode = SDL_SCANCODE_3;
        break;
    case SDL_CONTROLLER_BUTTON_DPAD_LEFT:
        keyboardPress = true;
        scancode = SDL_SCANCODE_4;
        break;
    default:
        break;
    }

    if (keyboardPress) {
        Put_Key_Message(scancode, button.state == SDL_RELEASED);
    } else if (mousePress) {
        int x, y;
        Get_Video_Mouse(x, y);
        Put_Mouse_Message(key, x, y, button.state == SDL_RELEASED);
    }
}

bool WWKeyboardClassSDL2::Is_Analog_Scroll_Active()
{
    return AnalogScrollActive;
}

unsigned char WWKeyboardClassSDL2::Get_Scroll_Direction()
{
    return ScrollDirection;
}

KeyASCIIType WWKeyboardClassSDL2::To_ASCII(unsigned short key)
{
    if (key & WWKEY_RLS_BIT) {
        return KA_NONE;
    }

    key &= 0xFF; // drop all mods

    if (key > ARRAY_SIZE(sdl_keymap) / 2 - 1) {
        return KA_NONE;
    }

    if (SDL_GetModState() & KMOD_SHIFT) {
        return sdl_keymap[key + ARRAY_SIZE(sdl_keymap) / 2];
    } else {
        return sdl_keymap[key];
    }
}

WWKeyboardClass* CreateWWKeyboardClass(void)
{
    return new WWKeyboardClassSDL2;
}
