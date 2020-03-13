#ifndef ANDROIDTVREMOTE_SERVICE_JNI_GAMEPAD_KEYS_H_
#define ANDROIDTVREMOTE_SERVICE_JNI_GAMEPAD_KEYS_H_

#include <linux/input.h>

namespace android {

// Follows the W3 spec for gamepad buttons and their corresponding mapping into
// Linux keycodes. Note that gamepads are generally not very well standardized
// and various controllers will result in different buttons. This mapping tries
// to be reasonable.
//
// W3 Button spec: https://www.w3.org/TR/gamepad/#remapping
//
// Standard gamepad keycodes are added plus 2 additional buttons (e.g. Stadia
// has "Assistant" and "Share", PS4 has the touchpad button).
//
// To generate this list, PS4, XBox, Stadia and Nintendo Switch Pro were tested.
static const int GAMEPAD_KEY_CODES[19] = {
        // Right-side buttons. A/B/X/Y or circle/triangle/square/X or similar
        BTN_A, // "South", A, GAMEPAD and SOUTH have the same constant
        BTN_B, // "East", BTN_B, BTN_EAST have the same constant
        BTN_X, // "West", Note that this maps to X and NORTH in constants
        BTN_Y, // "North", Note that this maps to Y and WEST in constants

        BTN_TL, // "Left Bumper" / "L1" - Nintendo sends BTN_WEST instead
        BTN_TR, // "Right Bumper" / "R1" - Nintendo sends BTN_Z instead

        // For triggers, gamepads vary:
        //   - Stadia sends analog values over ABS_GAS/ABS_BRAKE and sends
        //     TriggerHappy3/4 as digital presses
        //   - PS4 and Xbox send analog values as ABS_Z/ABS_RZ
        //   - Nintendo Pro sends BTN_TL/BTN_TR (since bumpers behave differently)
        // As placeholders we chose the stadia trigger-happy values since TL/TR are
        // sent for bumper button presses
        BTN_TRIGGER_HAPPY4, // "Left Trigger" / "L2"
        BTN_TRIGGER_HAPPY3, // "Right Trigger" / "R2"

        BTN_SELECT, // "Select/Back". Often "options" or similar
        BTN_START,  // "Start/forward". Often "hamburger" icon

        BTN_THUMBL, // "Left Joystick Pressed"
        BTN_THUMBR, // "Right Joystick Pressed"

        // For DPads, gamepads generally only send axis changes
        // on ABS_HAT0X and ABS_HAT0Y.
        KEY_UP,    // "Digital Pad up"
        KEY_DOWN,  // "Digital Pad down"
        KEY_LEFT,  // "Digital Pad left"
        KEY_RIGHT, // "Digital Pad right"

        BTN_MODE, // "Main button" (Stadia/PS/XBOX/Home)

        BTN_TRIGGER_HAPPY1, // Extra button: "Assistant" for Stadia
        BTN_TRIGGER_HAPPY2, // Extra button: "Share" for Stadia
};

// Defines information for an axis.
struct Axis {
    int number;
    int rangeMin;
    int rangeMax;
};

// List of all axes supported by a gamepad
static const Axis GAMEPAD_AXES[] = {
        {ABS_X, 0, 254},    // Left joystick X
        {ABS_Y, 0, 254},    // Left joystick Y
        {ABS_RX, 0, 254},   // Right joystick X
        {ABS_RY, 0, 254},   // Right joystick Y
        {ABS_Z, 0, 254},    // Left trigger
        {ABS_RZ, 0, 254},   // Right trigger
        {ABS_HAT0X, -1, 1}, // DPad X
        {ABS_HAT0Y, -1, 1}, // DPad Y
};

} // namespace android

#endif // ANDROIDTVREMOTE_SERVICE_JNI_GAMEPAD_KEYS_H_
