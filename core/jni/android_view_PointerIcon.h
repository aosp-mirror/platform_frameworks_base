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

#include <android/graphics/bitmap.h>
#include <input/Input.h>
#include <utils/Errors.h>

#include <vector>

#include "jni.h"

namespace android {

/*
 * Describes a pointer icon.
 */
struct PointerIcon {
    inline PointerIcon() { reset(); }

    PointerIconStyle style;
    graphics::Bitmap bitmap;
    float hotSpotX;
    float hotSpotY;
    std::vector<graphics::Bitmap> bitmapFrames;
    int32_t durationPerFrame;

    inline bool isNullIcon() { return style == PointerIconStyle::TYPE_NULL; }

    inline void reset() {
        style = PointerIconStyle::TYPE_NULL;
        bitmap.reset();
        hotSpotX = 0;
        hotSpotY = 0;
        bitmapFrames.clear();
        durationPerFrame = 0;
    }
};

/*
 * Obtain the data of the Java pointerIconObj into a native PointerIcon.
 *
 * The pointerIconObj must not be null.
 */
PointerIcon android_view_PointerIcon_toNative(JNIEnv* env, jobject pointerIconObj);

} // namespace android

#endif // _ANDROID_OS_POINTER_ICON_H
