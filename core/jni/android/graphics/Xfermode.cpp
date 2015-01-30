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
#include "core_jni_helpers.h"

#include "AvoidXfermode.h"
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
        AvoidXfermode::Mode mode = static_cast<AvoidXfermode::Mode>(modeHandle);
        return reinterpret_cast<jlong>(AvoidXfermode::Create(opColor, tolerance, mode));
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

int register_android_graphics_Xfermode(JNIEnv* env) {
    android::RegisterMethodsOrDie(env, "android/graphics/Xfermode", gXfermodeMethods,
                                  NELEM(gXfermodeMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/Xfermode", gXfermodeMethods,
                                  NELEM(gXfermodeMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/AvoidXfermode", gAvoidMethods,
                                  NELEM(gAvoidMethods));
    android::RegisterMethodsOrDie(env, "android/graphics/PixelXorXfermode", gPixelXorMethods,
                                  NELEM(gPixelXorMethods));

    return 0;
}

}
