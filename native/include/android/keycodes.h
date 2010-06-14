/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef _ANDROID_KEYCODES_H
#define _ANDROID_KEYCODES_H

/******************************************************************
 *
 * IMPORTANT NOTICE:
 *
 *   This file is part of Android's set of stable system headers
 *   exposed by the Android NDK (Native Development Kit).
 *
 *   Third-party source AND binary code relies on the definitions
 *   here to be FROZEN ON ALL UPCOMING PLATFORM RELEASES.
 *
 *   - DO NOT MODIFY ENUMS (EXCEPT IF YOU ADD NEW 32-BIT VALUES)
 *   - DO NOT MODIFY CONSTANTS OR FUNCTIONAL MACROS
 *   - DO NOT CHANGE THE SIGNATURE OF FUNCTIONS IN ANY WAY
 *   - DO NOT CHANGE THE LAYOUT OR SIZE OF STRUCTURES
 */

#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Key codes.
 *
 * XXX: The declarations in <ui/KeycodeLabel.h> should be updated to use these instead.
 *      We should probably move this into android/keycodes.h and add some new API for
 *      getting labels so that we can remove the other tables also in KeycodeLabel.h.
 */
enum {
    KEYCODE_UNKNOWN         = 0,
    KEYCODE_SOFT_LEFT       = 1,
    KEYCODE_SOFT_RIGHT      = 2,
    KEYCODE_HOME            = 3,
    KEYCODE_BACK            = 4,
    KEYCODE_CALL            = 5,
    KEYCODE_ENDCALL         = 6,
    KEYCODE_0               = 7,
    KEYCODE_1               = 8,
    KEYCODE_2               = 9,
    KEYCODE_3               = 10,
    KEYCODE_4               = 11,
    KEYCODE_5               = 12,
    KEYCODE_6               = 13,
    KEYCODE_7               = 14,
    KEYCODE_8               = 15,
    KEYCODE_9               = 16,
    KEYCODE_STAR            = 17,
    KEYCODE_POUND           = 18,
    KEYCODE_DPAD_UP         = 19,
    KEYCODE_DPAD_DOWN       = 20,
    KEYCODE_DPAD_LEFT       = 21,
    KEYCODE_DPAD_RIGHT      = 22,
    KEYCODE_DPAD_CENTER     = 23,
    KEYCODE_VOLUME_UP       = 24,
    KEYCODE_VOLUME_DOWN     = 25,
    KEYCODE_POWER           = 26,
    KEYCODE_CAMERA          = 27,
    KEYCODE_CLEAR           = 28,
    KEYCODE_A               = 29,
    KEYCODE_B               = 30,
    KEYCODE_C               = 31,
    KEYCODE_D               = 32,
    KEYCODE_E               = 33,
    KEYCODE_F               = 34,
    KEYCODE_G               = 35,
    KEYCODE_H               = 36,
    KEYCODE_I               = 37,
    KEYCODE_J               = 38,
    KEYCODE_K               = 39,
    KEYCODE_L               = 40,
    KEYCODE_M               = 41,
    KEYCODE_N               = 42,
    KEYCODE_O               = 43,
    KEYCODE_P               = 44,
    KEYCODE_Q               = 45,
    KEYCODE_R               = 46,
    KEYCODE_S               = 47,
    KEYCODE_T               = 48,
    KEYCODE_U               = 49,
    KEYCODE_V               = 50,
    KEYCODE_W               = 51,
    KEYCODE_X               = 52,
    KEYCODE_Y               = 53,
    KEYCODE_Z               = 54,
    KEYCODE_COMMA           = 55,
    KEYCODE_PERIOD          = 56,
    KEYCODE_ALT_LEFT        = 57,
    KEYCODE_ALT_RIGHT       = 58,
    KEYCODE_SHIFT_LEFT      = 59,
    KEYCODE_SHIFT_RIGHT     = 60,
    KEYCODE_TAB             = 61,
    KEYCODE_SPACE           = 62,
    KEYCODE_SYM             = 63,
    KEYCODE_EXPLORER        = 64,
    KEYCODE_ENVELOPE        = 65,
    KEYCODE_ENTER           = 66,
    KEYCODE_DEL             = 67,
    KEYCODE_GRAVE           = 68,
    KEYCODE_MINUS           = 69,
    KEYCODE_EQUALS          = 70,
    KEYCODE_LEFT_BRACKET    = 71,
    KEYCODE_RIGHT_BRACKET   = 72,
    KEYCODE_BACKSLASH       = 73,
    KEYCODE_SEMICOLON       = 74,
    KEYCODE_APOSTROPHE      = 75,
    KEYCODE_SLASH           = 76,
    KEYCODE_AT              = 77,
    KEYCODE_NUM             = 78,
    KEYCODE_HEADSETHOOK     = 79,
    KEYCODE_FOCUS           = 80,   // *Camera* focus
    KEYCODE_PLUS            = 81,
    KEYCODE_MENU            = 82,
    KEYCODE_NOTIFICATION    = 83,
    KEYCODE_SEARCH          = 84,
    KEYCODE_MEDIA_PLAY_PAUSE= 85,
    KEYCODE_MEDIA_STOP      = 86,
    KEYCODE_MEDIA_NEXT      = 87,
    KEYCODE_MEDIA_PREVIOUS  = 88,
    KEYCODE_MEDIA_REWIND    = 89,
    KEYCODE_MEDIA_FAST_FORWARD = 90,
    KEYCODE_MUTE            = 91,
    KEYCODE_PAGE_UP         = 92,
    KEYCODE_PAGE_DOWN       = 93

    /* NOTE: If you add a new keycode here you must also add it to:
     *  native/include/android/keycodes.h
     *  frameworks/base/include/ui/KeycodeLabels.h
     *   frameworks/base/core/java/android/view/KeyEvent.java
     *   tools/puppet_master/PuppetMaster.nav_keys.py
     *   frameworks/base/core/res/res/values/attrs.xml
     */
};

#ifdef __cplusplus
}
#endif

#endif // _ANDROID_KEYCODES_H
