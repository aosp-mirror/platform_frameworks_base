/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef _ANDROID_VIEW_KEY_CHARACTER_MAP_H
#define _ANDROID_VIEW_KEY_CHARACTER_MAP_H

#include "jni.h"

#include <androidfw/KeyCharacterMap.h>

namespace android {

/* Creates a KeyCharacterMap object from the given information. */
extern jobject android_view_KeyCharacterMap_create(JNIEnv* env, int32_t deviceId,
        const sp<KeyCharacterMap>& map);

} // namespace android

#endif // _ANDROID_VIEW_KEY_CHARACTER_MAP_H
