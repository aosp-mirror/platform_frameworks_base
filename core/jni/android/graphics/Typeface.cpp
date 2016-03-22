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
#include "core_jni_helpers.h"

#include "GraphicsJNI.h"
#include "ScopedPrimitiveArray.h"
#include "SkTypeface.h"
#include <android_runtime/android_util_AssetManager.h>
#include <androidfw/AssetManager.h>
#include <hwui/Typeface.h>

using namespace android;

static jlong Typeface_createFromTypeface(JNIEnv* env, jobject, jlong familyHandle, jint style) {
    Typeface* family = reinterpret_cast<Typeface*>(familyHandle);
    Typeface* face = Typeface::createFromTypeface(family, (SkTypeface::Style)style);
    // TODO: the following logic shouldn't be necessary, the above should always succeed.
    // Try to find the closest matching font, using the standard heuristic
    if (NULL == face) {
        face = Typeface::createFromTypeface(family, (SkTypeface::Style)(style ^ SkTypeface::kItalic));
    }
    for (int i = 0; NULL == face && i < 4; i++) {
        face = Typeface::createFromTypeface(family, (SkTypeface::Style)i);
    }
    return reinterpret_cast<jlong>(face);
}

static jlong Typeface_createWeightAlias(JNIEnv* env, jobject, jlong familyHandle, jint weight) {
    Typeface* family = reinterpret_cast<Typeface*>(familyHandle);
    Typeface* face = Typeface::createWeightAlias(family, weight);
    return reinterpret_cast<jlong>(face);
}

static void Typeface_unref(JNIEnv* env, jobject obj, jlong faceHandle) {
    Typeface* face = reinterpret_cast<Typeface*>(faceHandle);
    if (face != NULL) {
        face->unref();
    }
}

static jint Typeface_getStyle(JNIEnv* env, jobject obj, jlong faceHandle) {
    Typeface* face = reinterpret_cast<Typeface*>(faceHandle);
    return face->fSkiaStyle;
}

static jlong Typeface_createFromArray(JNIEnv *env, jobject, jlongArray familyArray) {
    ScopedLongArrayRO families(env, familyArray);
    std::vector<FontFamily*> familyVec;
    for (size_t i = 0; i < families.size(); i++) {
        FontFamily* family = reinterpret_cast<FontFamily*>(families[i]);
        familyVec.push_back(family);
    }
    return reinterpret_cast<jlong>(Typeface::createFromFamilies(familyVec));
}

static void Typeface_setDefault(JNIEnv *env, jobject, jlong faceHandle) {
    Typeface* face = reinterpret_cast<Typeface*>(faceHandle);
    return Typeface::setDefault(face);
}

///////////////////////////////////////////////////////////////////////////////

static const JNINativeMethod gTypefaceMethods[] = {
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
    return RegisterMethodsOrDie(env, "android/graphics/Typeface", gTypefaceMethods,
                                NELEM(gTypefaceMethods));
}
