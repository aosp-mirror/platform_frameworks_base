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


#ifndef ANDROID_WINDOW_H
#define ANDROID_WINDOW_H

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Window flags, as per the Java API at android.view.WindowManager.LayoutParams.
 */
enum {
    AWINDOW_FLAG_ALLOW_LOCK_WHILE_SCREEN_ON = 0x00000001,
    AWINDOW_FLAG_DIM_BEHIND                 = 0x00000002,
    AWINDOW_FLAG_BLUR_BEHIND                = 0x00000004,
    AWINDOW_FLAG_NOT_FOCUSABLE              = 0x00000008,
    AWINDOW_FLAG_NOT_TOUCHABLE              = 0x00000010,
    AWINDOW_FLAG_NOT_TOUCH_MODAL            = 0x00000020,
    AWINDOW_FLAG_TOUCHABLE_WHEN_WAKING      = 0x00000040,
    AWINDOW_FLAG_KEEP_SCREEN_ON             = 0x00000080,
    AWINDOW_FLAG_LAYOUT_IN_SCREEN           = 0x00000100,
    AWINDOW_FLAG_LAYOUT_NO_LIMITS           = 0x00000200,
    AWINDOW_FLAG_FULLSCREEN                 = 0x00000400,
    AWINDOW_FLAG_FORCE_NOT_FULLSCREEN       = 0x00000800,
    AWINDOW_FLAG_DITHER                     = 0x00001000,
    AWINDOW_FLAG_SECURE                     = 0x00002000,
    AWINDOW_FLAG_SCALED                     = 0x00004000,
    AWINDOW_FLAG_IGNORE_CHEEK_PRESSES       = 0x00008000,
    AWINDOW_FLAG_LAYOUT_INSET_DECOR         = 0x00010000,
    AWINDOW_FLAG_ALT_FOCUSABLE_IM           = 0x00020000,
    AWINDOW_FLAG_WATCH_OUTSIDE_TOUCH        = 0x00040000,
    AWINDOW_FLAG_SHOW_WHEN_LOCKED           = 0x00080000,
    AWINDOW_FLAG_SHOW_WALLPAPER             = 0x00100000,
    AWINDOW_FLAG_TURN_SCREEN_ON             = 0x00200000,
    AWINDOW_FLAG_DISMISS_KEYGUARD           = 0x00400000,
};

#ifdef __cplusplus
};
#endif

#endif // ANDROID_WINDOW_H
