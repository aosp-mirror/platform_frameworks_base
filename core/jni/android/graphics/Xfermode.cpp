/*
 * Copyright (C) 2007 The Android Open Source Project
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

#include "SkAvoidXfermode.h"
#include "SkPixelXorXfermode.h"

namespace android {

class SkXfermodeGlue {
public:

    static void finalizer(JNIEnv* env, jobject, SkXfermode* obj)
    {
        obj->safeUnref();
    }
    
    static SkXfermode* avoid_create(JNIEnv* env, jobject, SkColor opColor,
                                U8CPU tolerance, SkAvoidXfermode::Mode mode)
    {
        return new SkAvoidXfermode(opColor, tolerance, mode);
    }
    
    static SkXfermode* pixelxor_create(JNIEnv* env, jobject, SkColor opColor)
    {
        return new SkPixelXorXfermode(opColor);
    }
};

///////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gXfermodeMethods[] = {
    {"finalizer", "(I)V", (void*) SkXfermodeGlue::finalizer}
};

static JNINativeMethod gAvoidMethods[] = {
    {"nativeCreate", "(III)I", (void*) SkXfermodeGlue::avoid_create}
};

static JNINativeMethod gPixelXorMethods[] = {
    {"nativeCreate", "(I)I", (void*) SkXfermodeGlue::pixelxor_create}
};

#include <android_runtime/AndroidRuntime.h>

#define REG(env, name, array)                                              \
    result = android::AndroidRuntime::registerNativeMethods(env, name, array, \
                                                  SK_ARRAY_COUNT(array));  \
    if (result < 0) return result

int register_android_graphics_Xfermode(JNIEnv* env) {
    int result;
    
    REG(env, "android/graphics/Xfermode", gXfermodeMethods);
    REG(env, "android/graphics/AvoidXfermode", gAvoidMethods);
    REG(env, "android/graphics/PixelXorXfermode", gPixelXorMethods);

    return 0;
}

}
