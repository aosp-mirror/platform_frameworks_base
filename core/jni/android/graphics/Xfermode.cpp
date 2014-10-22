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

    static void finalizer(JNIEnv* env, jobject, jlong objHandle)
    {
        SkXfermode* obj = reinterpret_cast<SkXfermode *>(objHandle);
        SkSafeUnref(obj);
    }
    
    static jlong avoid_create(JNIEnv* env, jobject, jint opColor,
                                jint tolerance, jint modeHandle)
    {
        SkAvoidXfermode::Mode mode = static_cast<SkAvoidXfermode::Mode>(modeHandle);
        return reinterpret_cast<jlong>(SkAvoidXfermode::Create(opColor, tolerance, mode));
    }

    static jlong pixelxor_create(JNIEnv* env, jobject, jint opColor)
    {
        return reinterpret_cast<jlong>(SkPixelXorXfermode::Create(opColor));
    }
};

///////////////////////////////////////////////////////////////////////////////

static JNINativeMethod gXfermodeMethods[] = {
    {"finalizer", "(J)V", (void*) SkXfermodeGlue::finalizer}
};

static JNINativeMethod gAvoidMethods[] = {
    {"nativeCreate", "(III)J", (void*) SkXfermodeGlue::avoid_create}
};

static JNINativeMethod gPixelXorMethods[] = {
    {"nativeCreate", "(I)J", (void*) SkXfermodeGlue::pixelxor_create}
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
