/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "GraphicsJNI.h"

#include "SkColor.h"

using namespace android;

static void Color_RGBToHSV(JNIEnv* env, jobject, jint red, jint green, jint blue,
                           jfloatArray hsvArray)
{
    SkScalar hsv[3];
    SkRGBToHSV(red, green, blue, hsv);

    AutoJavaFloatArray  autoHSV(env, hsvArray, 3);
    float* values = autoHSV.ptr();
    for (int i = 0; i < 3; i++) {
        values[i] = SkScalarToFloat(hsv[i]);
    }
}

static jint Color_HSVToColor(JNIEnv* env, jobject, jint alpha, jfloatArray hsvArray)
{
    AutoJavaFloatArray  autoHSV(env, hsvArray, 3);
    SkScalar* hsv = autoHSV.ptr();
    return static_cast<jint>(SkHSVToColor(alpha, hsv));
}

static const JNINativeMethod gColorMethods[] = {
    { "nativeRGBToHSV",    "(III[F)V", (void*)Color_RGBToHSV   },
    { "nativeHSVToColor",  "(I[F)I",   (void*)Color_HSVToColor }
};

namespace android {

int register_android_graphics_Color(JNIEnv* env) {
    return android::RegisterMethodsOrDie(env, "android/graphics/Color", gColorMethods,
                                         NELEM(gColorMethods));
}

}; // namespace android
