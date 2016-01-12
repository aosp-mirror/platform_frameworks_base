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
#include "core_jni_helpers.h"

#include "SkColorFilter.h"
#include "SkColorMatrixFilter.h"
#include "SkXfermode.h"

#include <Caches.h>

namespace android {

using namespace uirenderer;

class SkColorFilterGlue {
public:
    static void finalizer(JNIEnv* env, jobject clazz, jlong skFilterHandle) {
        SkColorFilter* filter = reinterpret_cast<SkColorFilter *>(skFilterHandle);
        if (filter) SkSafeUnref(filter);
    }

    static jlong CreatePorterDuffFilter(JNIEnv* env, jobject, jint srcColor, jint modeHandle) {
        SkXfermode::Mode mode = static_cast<SkXfermode::Mode>(modeHandle);
        return reinterpret_cast<jlong>(SkColorFilter::CreateModeFilter(srcColor, mode));
    }

    static jlong CreateLightingFilter(JNIEnv* env, jobject, jint mul, jint add) {
        return reinterpret_cast<jlong>(SkColorMatrixFilter::CreateLightingFilter(mul, add));
    }

    static jlong CreateColorMatrixFilter(JNIEnv* env, jobject, jfloatArray jarray) {
        AutoJavaFloatArray autoArray(env, jarray, 20);
        const float* src = autoArray.ptr();

#ifdef SK_SCALAR_IS_FLOAT
        return reinterpret_cast<jlong>(SkColorMatrixFilter::Create(src));
#else
        SkASSERT(false);
#endif
    }
};

static const JNINativeMethod colorfilter_methods[] = {
    {"destroyFilter", "(J)V", (void*) SkColorFilterGlue::finalizer}
};

static const JNINativeMethod porterduff_methods[] = {
    { "native_CreatePorterDuffFilter", "(II)J", (void*) SkColorFilterGlue::CreatePorterDuffFilter   },
};

static const JNINativeMethod lighting_methods[] = {
    { "native_CreateLightingFilter", "(II)J", (void*) SkColorFilterGlue::CreateLightingFilter   },
};

static const JNINativeMethod colormatrix_methods[] = {
    { "nativeColorMatrixFilter", "([F)J", (void*) SkColorFilterGlue::CreateColorMatrixFilter   },
};

int register_android_graphics_ColorFilter(JNIEnv* env) {
    android::RegisterMethodsOrDie(env, "android/graphics/ColorFilter", colorfilter_methods,
                                  NELEM(colorfilter_methods));
    android::RegisterMethodsOrDie(env, "android/graphics/PorterDuffColorFilter", porterduff_methods,
                                  NELEM(porterduff_methods));
    android::RegisterMethodsOrDie(env, "android/graphics/LightingColorFilter", lighting_methods,
                                  NELEM(lighting_methods));
    android::RegisterMethodsOrDie(env, "android/graphics/ColorMatrixColorFilter",
                                  colormatrix_methods, NELEM(colormatrix_methods));
    
    return 0;
}

}
