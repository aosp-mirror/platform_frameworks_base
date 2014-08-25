/*
 * Copyright (C) 2013 The Android Open Source Project
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
#include <android_runtime/AndroidRuntime.h>

#include "GraphicsJNI.h"
#include <ScopedPrimitiveArray.h>
#include "SkStream.h"
#include "SkTypeface.h"
#include "TypefaceImpl.h"
#include <android_runtime/android_util_AssetManager.h>
#include <androidfw/AssetManager.h>

using namespace android;

static jlong Typeface_createFromTypeface(JNIEnv* env, jobject, jlong familyHandle, jint style) {
    TypefaceImpl* family = reinterpret_cast<TypefaceImpl*>(familyHandle);
    TypefaceImpl* face = TypefaceImpl_createFromTypeface(family, (SkTypeface::Style)style);
    // TODO: the following logic shouldn't be necessary, the above should always succeed.
    // Try to find the closest matching font, using the standard heuristic
    if (NULL == face) {
        face = TypefaceImpl_createFromTypeface(family, (SkTypeface::Style)(style ^ SkTypeface::kItalic));
    }
    for (int i = 0; NULL == face && i < 4; i++) {
        face = TypefaceImpl_createFromTypeface(family, (SkTypeface::Style)i);
    }
    return reinterpret_cast<jlong>(face);
}

static jlong Typeface_createWeightAlias(JNIEnv* env, jobject, jlong familyHandle, jint weight) {
    TypefaceImpl* family = reinterpret_cast<TypefaceImpl*>(familyHandle);
    TypefaceImpl* face = TypefaceImpl_createWeightAlias(family, weight);
    return reinterpret_cast<jlong>(face);
}

static void Typeface_unref(JNIEnv* env, jobject obj, jlong faceHandle) {
    TypefaceImpl* face = reinterpret_cast<TypefaceImpl*>(faceHandle);
    TypefaceImpl_unref(face);
}

static jint Typeface_getStyle(JNIEnv* env, jobject obj, jlong faceHandle) {
    TypefaceImpl* face = reinterpret_cast<TypefaceImpl*>(faceHandle);
    return TypefaceImpl_getStyle(face);
}

static jlong Typeface_createFromArray(JNIEnv *env, jobject, jlongArray familyArray) {
    ScopedLongArrayRO families(env, familyArray);
    return reinterpret_cast<jlong>(TypefaceImpl_createFromFamilies(families.get(), families.size()));
}

static void Typeface_setDefault(JNIEnv *env, jobject, jlong faceHandle) {
    TypefaceImpl* face = reinterpret_cast<TypefaceImpl*>(faceHandle);
    return TypefaceImpl_setDefault(face);
}

///////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gTypefaceMethods[] = {
    { "nativeCreateFromTypeface", "(JI)J", (void*)Typeface_createFromTypeface },
    { "nativeCreateWeightAlias",  "(JI)J", (void*)Typeface_createWeightAlias },
    { "nativeUnref",              "(J)V",  (void*)Typeface_unref },
    { "nativeGetStyle",           "(J)I",  (void*)Typeface_getStyle },
    { "nativeCreateFromArray",    "([J)J",
                                           (void*)Typeface_createFromArray },
    { "nativeSetDefault",         "(J)V",   (void*)Typeface_setDefault },
};

int register_android_graphics_Typeface(JNIEnv* env)
{
    return android::AndroidRuntime::registerNativeMethods(env,
                                                       "android/graphics/Typeface",
                                                       gTypefaceMethods,
                                                       SK_ARRAY_COUNT(gTypefaceMethods));
}
