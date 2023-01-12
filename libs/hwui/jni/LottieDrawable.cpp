/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include <SkRect.h>
#include <Skottie.h>
#include <hwui/Canvas.h>
#include <hwui/LottieDrawable.h>

#include "GraphicsJNI.h"
#include "Utils.h"

using namespace android;

static jclass gLottieDrawableClass;

static jlong LottieDrawable_nCreate(JNIEnv* env, jobject, jstring jjson) {
    const ScopedUtfChars cstr(env, jjson);
    // TODO(b/259267150) provide more accurate byteSize
    size_t bytes = strlen(cstr.c_str());
    auto animation = skottie::Animation::Builder().make(cstr.c_str(), bytes);
    sk_sp<LottieDrawable> drawable(LottieDrawable::Make(std::move(animation), bytes));
    if (!drawable) {
        return 0;
    }
    return reinterpret_cast<jlong>(drawable.release());
}

static void LottieDrawable_destruct(LottieDrawable* drawable) {
    SkSafeUnref(drawable);
}

static jlong LottieDrawable_nGetNativeFinalizer(JNIEnv* /*env*/, jobject /*clazz*/) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&LottieDrawable_destruct));
}

static void LottieDrawable_nDraw(JNIEnv* env, jobject /*clazz*/, jlong nativePtr, jlong canvasPtr) {
    auto* drawable = reinterpret_cast<LottieDrawable*>(nativePtr);
    auto* canvas = reinterpret_cast<Canvas*>(canvasPtr);
    canvas->drawLottie(drawable);
}

static jboolean LottieDrawable_nIsRunning(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<LottieDrawable*>(nativePtr);
    return drawable->isRunning();
}

static jboolean LottieDrawable_nStart(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<LottieDrawable*>(nativePtr);
    return drawable->start();
}

static jboolean LottieDrawable_nStop(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<LottieDrawable*>(nativePtr);
    return drawable->stop();
}

static jlong LottieDrawable_nNativeByteSize(JNIEnv* env, jobject /*clazz*/, jlong nativePtr) {
    auto* drawable = reinterpret_cast<LottieDrawable*>(nativePtr);
    return drawable->byteSize();
}

static const JNINativeMethod gLottieDrawableMethods[] = {
        {"nCreate", "(Ljava/lang/String;)J", (void*)LottieDrawable_nCreate},
        {"nNativeByteSize", "(J)J", (void*)LottieDrawable_nNativeByteSize},
        {"nGetNativeFinalizer", "()J", (void*)LottieDrawable_nGetNativeFinalizer},
        {"nDraw", "(JJ)V", (void*)LottieDrawable_nDraw},
        {"nIsRunning", "(J)Z", (void*)LottieDrawable_nIsRunning},
        {"nStart", "(J)Z", (void*)LottieDrawable_nStart},
        {"nStop", "(J)Z", (void*)LottieDrawable_nStop},
};

int register_android_graphics_drawable_LottieDrawable(JNIEnv* env) {
    gLottieDrawableClass = reinterpret_cast<jclass>(
            env->NewGlobalRef(FindClassOrDie(env, "android/graphics/drawable/LottieDrawable")));

    return android::RegisterMethodsOrDie(env, "android/graphics/drawable/LottieDrawable",
                                         gLottieDrawableMethods, NELEM(gLottieDrawableMethods));
}
