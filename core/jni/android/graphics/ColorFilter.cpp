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

#include <SkiaColorFilter.h>

namespace android {

using namespace uirenderer;

class SkColorFilterGlue {
public:
    static void finalizer(JNIEnv* env, jobject clazz, SkColorFilter* obj, SkiaColorFilter* f) {
        delete f;
        obj->safeUnref();
    }

    static SkiaColorFilter* glCreatePorterDuffFilter(JNIEnv* env, jobject, jint srcColor,
            SkPorterDuff::Mode mode) {
#ifdef USE_OPENGL_RENDERER
        return new SkiaBlendFilter(srcColor, SkPorterDuff::ToXfermodeMode(mode));
#else
        return NULL;
#endif
    }

    static SkiaColorFilter* glCreateLightingFilter(JNIEnv* env, jobject, jint mul, jint add) {
#ifdef USE_OPENGL_RENDERER
        return new SkiaLightingFilter(mul, add);
#else
        return NULL;
#endif
    }

    static SkiaColorFilter* glCreateColorMatrixFilter(JNIEnv* env, jobject, jfloatArray jarray) {
#ifdef USE_OPENGL_RENDERER
        AutoJavaFloatArray autoArray(env, jarray, 20);
        const float* src = autoArray.ptr();

        float* colorMatrix = new float[16];
        memcpy(colorMatrix, src, 4 * sizeof(float));
        memcpy(&colorMatrix[4], &src[5], 4 * sizeof(float));
        memcpy(&colorMatrix[8], &src[10], 4 * sizeof(float));
        memcpy(&colorMatrix[12], &src[15], 4 * sizeof(float));

        float* colorVector = new float[4];
        colorVector[0] = src[4];
        colorVector[1] = src[9];
        colorVector[2] = src[14];
        colorVector[3] = src[19];

        return new SkiaColorMatrixFilter(colorMatrix, colorVector);
#else
        return NULL;
#endif
    }

    static SkColorFilter* CreatePorterDuffFilter(JNIEnv* env, jobject, jint srcColor,
            SkPorterDuff::Mode mode) {
        return SkColorFilter::CreateModeFilter(srcColor, SkPorterDuff::ToXfermodeMode(mode));
    }

    static SkColorFilter* CreateLightingFilter(JNIEnv* env, jobject, jint mul, jint add) {
        return SkColorFilter::CreateLightingFilter(mul, add);
    }

    static SkColorFilter* CreateColorMatrixFilter(JNIEnv* env, jobject, jfloatArray jarray) {
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
    {"finalizer", "(II)V", (void*) SkColorFilterGlue::finalizer}
};

static JNINativeMethod porterduff_methods[] = {
    { "native_CreatePorterDuffFilter", "(II)I", (void*) SkColorFilterGlue::CreatePorterDuffFilter   },
    { "nCreatePorterDuffFilter",       "(II)I", (void*) SkColorFilterGlue::glCreatePorterDuffFilter }
};

static JNINativeMethod lighting_methods[] = {
    { "native_CreateLightingFilter", "(II)I", (void*) SkColorFilterGlue::CreateLightingFilter   },
    { "nCreateLightingFilter",       "(II)I", (void*) SkColorFilterGlue::glCreateLightingFilter },
};

static JNINativeMethod colormatrix_methods[] = {
    { "nativeColorMatrixFilter", "([F)I", (void*) SkColorFilterGlue::CreateColorMatrixFilter   },
    { "nColorMatrixFilter",      "([F)I", (void*) SkColorFilterGlue::glCreateColorMatrixFilter }
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
