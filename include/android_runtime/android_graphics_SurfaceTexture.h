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

#ifndef _ANDROID_GRAPHICS_SURFACETEXTURE_H
#define _ANDROID_GRAPHICS_SURFACETEXTURE_H

#include <android/native_window.h>

#include "jni.h"

namespace android {

class SurfaceTexture;

extern sp<ANativeWindow> android_SurfaceTexture_getNativeWindow(
        JNIEnv* env, jobject thiz);
extern bool android_SurfaceTexture_isInstanceOf(JNIEnv* env, jobject thiz);

/* Gets the underlying SurfaceTexture from a SurfaceTexture Java object. */
extern sp<SurfaceTexture> SurfaceTexture_getSurfaceTexture(JNIEnv* env, jobject thiz);

} // namespace android

#endif // _ANDROID_GRAPHICS_SURFACETEXTURE_H
