/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef _ANDROID_VIEW_POINTER_ICON_H
#define _ANDROID_VIEW_POINTER_ICON_H

#include "jni.h"

#include <vector>

#include <utils/Errors.h>
#include <SkBitmap.h>

namespace android {

/* Pointer icon styles.
 * Must match the definition in android.view.PointerIcon.
 */
enum {
    POINTER_ICON_STYLE_CUSTOM = -1,
    POINTER_ICON_STYLE_NULL = 0,
    POINTER_ICON_STYLE_ARROW = 1000,
    POINTER_ICON_STYLE_CONTEXT_MENU = 1001,
    POINTER_ICON_STYLE_HAND = 1002,
    POINTER_ICON_STYLE_HELP = 1003,
    POINTER_ICON_STYLE_WAIT = 1004,
    POINTER_ICON_STYLE_CELL = 1006,
    POINTER_ICON_STYLE_CROSSHAIR = 1007,
    POINTER_ICON_STYLE_TEXT = 1008,
    POINTER_ICON_STYLE_VERTICAL_TEXT = 1009,
    POINTER_ICON_STYLE_ALIAS = 1010,
    POINTER_ICON_STYLE_COPY = 1011,
    POINTER_ICON_STYLE_NO_DROP = 1012,
    POINTER_ICON_STYLE_ALL_SCROLL = 1013,
    POINTER_ICON_STYLE_HORIZONTAL_DOUBLE_ARROW = 1014,
    POINTER_ICON_STYLE_VERTICAL_DOUBLE_ARROW = 1015,
    POINTER_ICON_STYLE_TOP_RIGHT_DOUBLE_ARROW = 1016,
    POINTER_ICON_STYLE_TOP_LEFT_DOUBLE_ARROW = 1017,
    POINTER_ICON_STYLE_ZOOM_IN = 1018,
    POINTER_ICON_STYLE_ZOOM_OUT = 1019,
    POINTER_ICON_STYLE_GRAB = 1020,
    POINTER_ICON_STYLE_GRABBING = 1021,

    POINTER_ICON_STYLE_SPOT_HOVER = 2000,
    POINTER_ICON_STYLE_SPOT_TOUCH = 2001,
    POINTER_ICON_STYLE_SPOT_ANCHOR = 2002,
};

/*
 * Describes a pointer icon.
 */
struct PointerIcon {
    inline PointerIcon() {
        reset();
    }

    int32_t style;
    SkBitmap bitmap;
    float hotSpotX;
    float hotSpotY;
    std::vector<SkBitmap> bitmapFrames;
    int32_t durationPerFrame;

    inline bool isNullIcon() {
        return style == POINTER_ICON_STYLE_NULL;
    }

    inline void reset() {
        style = POINTER_ICON_STYLE_NULL;
        bitmap.reset();
        hotSpotX = 0;
        hotSpotY = 0;
        bitmapFrames.clear();
        durationPerFrame = 0;
    }
};

/* Gets a system pointer icon with the specified style. */
extern jobject android_view_PointerIcon_getSystemIcon(JNIEnv* env,
        jobject contextObj, int32_t style);

/* Loads the bitmap associated with a pointer icon.
 * If pointerIconObj is NULL, returns OK and a pointer icon with POINTER_ICON_STYLE_NULL. */
extern status_t android_view_PointerIcon_load(JNIEnv* env,
        jobject pointerIconObj, jobject contextObj, PointerIcon* outPointerIcon);

/* Obtain the data of pointerIconObj and put to outPointerIcon. */
extern status_t android_view_PointerIcon_getLoadedIcon(JNIEnv* env, jobject pointerIconObj,
        PointerIcon* outPointerIcon);


/* Loads the bitmap associated with a pointer icon by style.
 * If pointerIconObj is NULL, returns OK and a pointer icon with POINTER_ICON_STYLE_NULL. */
extern status_t android_view_PointerIcon_loadSystemIcon(JNIEnv* env,
        jobject contextObj, int32_t style, PointerIcon* outPointerIcon);

} // namespace android

#endif // _ANDROID_OS_POINTER_ICON_H
