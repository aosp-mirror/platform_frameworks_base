/* libs/android_runtime/android/graphics/ColorFilter.cpp
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/

#include "jni.h"
#include "GraphicsJNI.h"
#include <android_runtime/AndroidRuntime.h>

#include "SkColorFilter.h"
#include "SkColorMatrixFilter.h"
#include "SkPorterDuff.h"

namespace android {

class SkColorFilterGlue {
public:

    static void finalizer(JNIEnv* env, jobject clazz, SkColorFilter* obj) {
        obj->safeUnref();
    }

    static SkColorFilter* CreatePorterDuffFilter(JNIEnv* env, jobject,
                            jint srcColor, SkPorterDuff::Mode mode) {
        return SkColorFilter::CreateModeFilter(srcColor,
                                           SkPorterDuff::ToXfermodeMode(mode));
    }
 
    static SkColorFilter* CreateLightingFilter(JNIEnv* env, jobject,
                                               jint mul, jint add) {
        return SkColorFilter::CreateLightingFilter(mul, add);
    }
    
    static SkColorFilter* CreateColorMatrixFilter(JNIEnv* env, jobject,
                                                  jfloatArray jarray) {
        AutoJavaFloatArray autoArray(env, jarray, 20);
        const float* src = autoArray.ptr();

#ifdef SK_SCALAR_IS_FIXED
        SkFixed array[20];
        for (int i = 0; i < 20; i++) {
            array[i] = SkFloatToScalar(src[i]);
        }
        return new SkColorMatrixFilter(array);
#else
        return new SkColorMatrixFilter(src);
#endif
    }
 
};

static JNINativeMethod colorfilter_methods[] = {
    {"finalizer", "(I)V", (void*) SkColorFilterGlue::finalizer}
};

static JNINativeMethod porterduff_methods[] = {
    {"native_CreatePorterDuffFilter","(II)I",
        (void*) SkColorFilterGlue::CreatePorterDuffFilter}
};

static JNINativeMethod lighting_methods[] = {
    {"native_CreateLightingFilter","(II)I",
        (void*) SkColorFilterGlue::CreateLightingFilter}
};

static JNINativeMethod colormatrix_methods[] = {
    {"nativeColorMatrixFilter","([F)I",
        (void*) SkColorFilterGlue::CreateColorMatrixFilter}
};

#define REG(env, name, array) \
    result = android::AndroidRuntime::registerNativeMethods(env, name, array, \
                                                    SK_ARRAY_COUNT(array));  \
    if (result < 0) return result


int register_android_graphics_ColorFilter(JNIEnv* env) {
    int result;
    
    REG(env, "android/graphics/ColorFilter", colorfilter_methods);
    REG(env, "android/graphics/PorterDuffColorFilter", porterduff_methods);
    REG(env, "android/graphics/LightingColorFilter", lighting_methods);
    REG(env, "android/graphics/ColorMatrixColorFilter", colormatrix_methods);
    
    return 0;
}

}
