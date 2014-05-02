/*
 * Copyright (C) 20014 The Android Open Source Project
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

#include "jni.h"
#include "GraphicsJNI.h"
#include <android_runtime/AndroidRuntime.h>

#include <utils/VirtualLightRefBase.h>
#include <CanvasProperty.h>

namespace android {

using namespace uirenderer;

#ifdef USE_OPENGL_RENDERER

static jlong incRef(VirtualLightRefBase* ptr) {
    ptr->incStrong(0);
    return reinterpret_cast<jlong>(ptr);
}

static jlong createFloat(JNIEnv* env, jobject clazz, jfloat initialValue) {
    return incRef(new CanvasPropertyPrimitive(initialValue));
}

static jlong createPaint(JNIEnv* env, jobject clazz, jlong paintPtr) {
    const SkPaint* paint = reinterpret_cast<const SkPaint*>(paintPtr);
    return incRef(new CanvasPropertyPaint(*paint));
}

static void unref(JNIEnv* env, jobject clazz, jlong containerPtr) {
    reinterpret_cast<VirtualLightRefBase*>(containerPtr)->decStrong(0);
}

#endif

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/graphics/CanvasProperty";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "nCreateFloat", "(F)J", (void*) createFloat },
    { "nCreatePaint", "(J)J", (void*) createPaint },
    { "nUnref", "(J)V", (void*) unref },
#endif
};

int register_android_graphics_CanvasProperty(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

}; // namespace android
