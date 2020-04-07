#ifndef ANDROIDTVREMOTE_SERVICE_JNI_GAMEPAD_KEYS_H_
#define ANDROIDTVREMOTE_SERVICE_JNI_GAMEPAD_KEYS_H_

#include <android/input.h>
#include <android/keycodes.h>
#include <linux/input.h>

namespace android {

// The constant array below defines a mapping between "Android" IDs (key code
// within events) and what is being sent through /dev/uinput.
//
// The translation back from uinput key codes into android key codes is done through
// the corresponding key layout files. This file and
//
//    data/keyboards/Vendor_18d1_Product_0200.kl
//
// MUST be kept in sync.
//
// see https://source.android.com/devices/input/key-layout-files for documentation.

// Defines axis mapping information between android and
// uinput axis.
struct GamepadKey {
    int32_t androidKeyCode;
    int linuxUinputKeyCode;
};

static const GamepadKey GAMEPAD_KEYS[] = {
        // Right-side buttons. A/B/X/Y or circle/triangle/square/X or similar
        {AKEYCODE_BUTTON_A, BTN_A},
        {AKEYCODE_BUTTON_B, BTN_B},
        {AKEYCODE_BUTTON_X, BTN_X},
        {AKEYCODE_BUTTON_Y, BTN_Y},

        // Bumper buttons and digital triggers. Triggers generally have
        // both analog versions (GAS and BRAKE output) and digital ones
        {AKEYCODE_BUTTON_L1, BTN_TL2},
        {AKEYCODE_BUTTON_L2, BTN_TL},
        {AKEYCODE_BUTTON_R1, BTN_TR2},
        {AKEYCODE_BUTTON_R2, BTN_TR},

        // general actions for controllers
        {AKEYCODE_BUTTON_SELECT, BTN_SELECT}, // Options or "..."
        {AKEYCODE_BUTTON_START, BTN_START},   // Menu/Hamburger menu
        {AKEYCODE_BUTTON_MODE, BTN_MODE},     // "main" button

        // Pressing on the joyticks themselves
        {AKEYCODE_BUTTON_THUMBL, BTN_THUMBL},
        {AKEYCODE_BUTTON_THUMBR, BTN_THUMBR},

        // DPAD digital keys. HAT axis events are generally also sent.
        {AKEYCODE_DPAD_UP, KEY_UP},
        {AKEYCODE_DPAD_DOWN, KEY_DOWN},
        {AKEYCODE_DPAD_LEFT, KEY_LEFT},
        {AKEYCODE_DPAD_RIGHT, KEY_RIGHT},

        // "Extra" controller buttons: some devices have "share" and "assistant"
        {AKEYCODE_BUTTON_1, BTN_TRIGGER_HAPPY1},
        {AKEYCODE_BUTTON_2, BTN_TRIGGER_HAPPY2},
        {AKEYCODE_BUTTON_3, BTN_TRIGGER_HAPPY3},
        {AKEYCODE_BUTTON_4, BTN_TRIGGER_HAPPY4},
        {AKEYCODE_BUTTON_5, BTN_TRIGGER_HAPPY5},
        {AKEYCODE_BUTTON_6, BTN_TRIGGER_HAPPY6},
        {AKEYCODE_BUTTON_7, BTN_TRIGGER_HAPPY7},
        {AKEYCODE_BUTTON_8, BTN_TRIGGER_HAPPY8},
        {AKEYCODE_BUTTON_9, BTN_TRIGGER_HAPPY9},
        {AKEYCODE_BUTTON_10, BTN_TRIGGER_HAPPY10},
        {AKEYCODE_BUTTON_11, BTN_TRIGGER_HAPPY11},
        {AKEYCODE_BUTTON_12, BTN_TRIGGER_HAPPY12},
        {AKEYCODE_BUTTON_13, BTN_TRIGGER_HAPPY13},
        {AKEYCODE_BUTTON_14, BTN_TRIGGER_HAPPY14},
        {AKEYCODE_BUTTON_15, BTN_TRIGGER_HAPPY15},
        {AKEYCODE_BUTTON_16, BTN_TRIGGER_HAPPY16},

        // Assignment to support global assistant for devices that support it.
        {AKEYCODE_ASSIST, KEY_ASSISTANT},
        {AKEYCODE_VOICE_ASSIST, KEY_VOICECOMMAND},
};

// Defines axis mapping information between android and
// uinput axis.
struct GamepadAxis {
    int32_t androidAxis;
    float androidRangeMin;
    float androidRangeMax;
    int linuxUinputAxis;
    int linuxUinputRangeMin;
    int linuxUinputRangeMax;
};

// List of all axes supported by a gamepad
static const GamepadAxis GAMEPAD_AXES[] = {
        {AMOTION_EVENT_AXIS_X, -1, 1, ABS_X, 0, 254},           // Left joystick X
        {AMOTION_EVENT_AXIS_Y, -1, 1, ABS_Y, 0, 254},           // Left joystick Y
        {AMOTION_EVENT_AXIS_Z, -1, 1, ABS_Z, 0, 254},           // Right joystick X
        {AMOTION_EVENT_AXIS_RZ, -1, 1, ABS_RZ, 0, 254},         // Right joystick Y
        {AMOTION_EVENT_AXIS_LTRIGGER, 0, 1, ABS_GAS, 0, 254},   // Left trigger
        {AMOTION_EVENT_AXIS_RTRIGGER, 0, 1, ABS_BRAKE, 0, 254}, // Right trigger
        {AMOTION_EVENT_AXIS_HAT_X, -1, 1, ABS_HAT0X, -1, 1},    // DPad X
        {AMOTION_EVENT_AXIS_HAT_Y, -1, 1, ABS_HAT0Y, -1, 1},    // DPad Y
};

} // namespace android

#endif // ANDROIDTVREMOTE_SERVICE_JNI_GAMEPAD_KEYS_H_
