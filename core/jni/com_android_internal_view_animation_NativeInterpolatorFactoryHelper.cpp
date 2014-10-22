/*
 * Copyright (C) 2014 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <Interpolator.h>

namespace android {

using namespace uirenderer;

#ifdef USE_OPENGL_RENDERER

static jlong createAccelerateDecelerateInterpolator(JNIEnv* env, jobject clazz) {
    return reinterpret_cast<jlong>(new AccelerateDecelerateInterpolator());
}

static jlong createAccelerateInterpolator(JNIEnv* env, jobject clazz, jfloat factor) {
    return reinterpret_cast<jlong>(new AccelerateInterpolator(factor));
}

static jlong createAnticipateInterpolator(JNIEnv* env, jobject clazz, jfloat tension) {
    return reinterpret_cast<jlong>(new AnticipateInterpolator(tension));
}

static jlong createAnticipateOvershootInterpolator(JNIEnv* env, jobject clazz, jfloat tension) {
    return reinterpret_cast<jlong>(new AnticipateOvershootInterpolator(tension));
}

static jlong createBounceInterpolator(JNIEnv* env, jobject clazz) {
    return reinterpret_cast<jlong>(new BounceInterpolator());
}

static jlong createCycleInterpolator(JNIEnv* env, jobject clazz, jfloat cycles) {
    return reinterpret_cast<jlong>(new CycleInterpolator(cycles));
}

static jlong createDecelerateInterpolator(JNIEnv* env, jobject clazz, jfloat factor) {
    return reinterpret_cast<jlong>(new DecelerateInterpolator(factor));
}

static jlong createLinearInterpolator(JNIEnv* env, jobject clazz) {
    return reinterpret_cast<jlong>(new LinearInterpolator());
}

static jlong createOvershootInterpolator(JNIEnv* env, jobject clazz, jfloat tension) {
    return reinterpret_cast<jlong>(new OvershootInterpolator(tension));
}

static jlong createLutInterpolator(JNIEnv* env, jobject clazz, jfloatArray jlut) {
    jsize len = env->GetArrayLength(jlut);
    if (len <= 0) {
        return 0;
    }
    float* lut = new float[len];
    env->GetFloatArrayRegion(jlut, 0, len, lut);
    return reinterpret_cast<jlong>(new LUTInterpolator(lut, len));
}

#endif

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "com/android/internal/view/animation/NativeInterpolatorFactoryHelper";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "createAccelerateDecelerateInterpolator", "()J", (void*) createAccelerateDecelerateInterpolator },
    { "createAccelerateInterpolator", "(F)J", (void*) createAccelerateInterpolator },
    { "createAnticipateInterpolator", "(F)J", (void*) createAnticipateInterpolator },
    { "createAnticipateOvershootInterpolator", "(F)J", (void*) createAnticipateOvershootInterpolator },
    { "createBounceInterpolator", "()J", (void*) createBounceInterpolator },
    { "createCycleInterpolator", "(F)J", (void*) createCycleInterpolator },
    { "createDecelerateInterpolator", "(F)J", (void*) createDecelerateInterpolator },
    { "createLinearInterpolator", "()J", (void*) createLinearInterpolator },
    { "createOvershootInterpolator", "(F)J", (void*) createOvershootInterpolator },
    { "createLutInterpolator", "([F)J", (void*) createLutInterpolator },
#endif
};

int register_com_android_internal_view_animation_NativeInterpolatorFactoryHelper(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}


} // namespace android
