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

#define LOG_TAG "HardwareRenderer"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <EGL/egl_cache.h>

EGLAPI void EGLAPIENTRY eglBeginFrame(EGLDisplay dpy, EGLSurface surface);

namespace android {

// ----------------------------------------------------------------------------
// Misc
// ----------------------------------------------------------------------------

static void android_view_HardwareRenderer_setupShadersDiskCache(JNIEnv* env, jobject clazz,
        jstring diskCachePath) {

    const char* cacheArray = env->GetStringUTFChars(diskCachePath, NULL);
    egl_cache_t::get()->setCacheFilename(cacheArray);
    env->ReleaseStringUTFChars(diskCachePath, cacheArray);
}

static void android_view_HardwareRenderer_beginFrame(JNIEnv* env, jobject clazz) {
    EGLDisplay dpy = eglGetCurrentDisplay();
    EGLSurface surf = eglGetCurrentSurface(EGL_DRAW);
    eglBeginFrame(dpy, surf);
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/HardwareRenderer";

static JNINativeMethod gMethods[] = {
    { "nSetupShadersDiskCache", "(Ljava/lang/String;)V",
            (void*) android_view_HardwareRenderer_setupShadersDiskCache },
    { "nBeginFrame", "()V",
            (void*) android_view_HardwareRenderer_beginFrame },
};

int register_android_view_HardwareRenderer(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

};
