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
#include <Caches.h>

namespace android {

using namespace uirenderer;

class SkColorFilterGlue {
public:
    static void finalizer(JNIEnv* env, jobject clazz, jlong objHandle, jlong fHandle) {
        SkColorFilter* obj = reinterpret_cast<SkColorFilter *>(objHandle);
        SkiaColorFilter* f = reinterpret_cast<SkiaColorFilter *>(fHandle);
        if (obj) SkSafeUnref(obj);
        // f == NULL when not !USE_OPENGL_RENDERER, so no need to delete outside the ifdef
#ifdef USE_OPENGL_RENDERER
        if (f && android::uirenderer::Caches::hasInstance()) {
            android::uirenderer::Caches::getInstance().resourceCache.destructor(f);
        } else {
            delete f;
        }
#endif
    }

    static jlong glCreatePorterDuffFilter(JNIEnv* env, jobject, jlong skFilterHandle,
            jint srcColor, jint modeHandle) {
        SkColorFilter *skFilter = reinterpret_cast<SkColorFilter *>(skFilterHandle);
        SkPorterDuff::Mode mode = static_cast<SkPorterDuff::Mode>(modeHandle);
#ifdef USE_OPENGL_RENDERER
        return reinterpret_cast<jlong>(new SkiaBlendFilter(skFilter, srcColor, SkPorterDuff::ToXfermodeMode(mode)));
#else
        return NULL;
#endif
    }

    static jlong glCreateLightingFilter(JNIEnv* env, jobject, jlong skFilterHandle,
            jint mul, jint add) {
        SkColorFilter *skFilter = reinterpret_cast<SkColorFilter *>(skFilterHandle);
#ifdef USE_OPENGL_RENDERER
        return reinterpret_cast<jlong>(new SkiaLightingFilter(skFilter, mul, add));
#else
        return NULL;
#endif
    }

    static jlong glCreateColorMatrixFilter(JNIEnv* env, jobject, jlong skFilterHandle,
            jfloatArray jarray) {
        SkColorFilter *skFilter = reinterpret_cast<SkColorFilter *>(skFilterHandle);
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

        return reinterpret_cast<jlong>(new SkiaColorMatrixFilter(skFilter, colorMatrix, colorVector));
#else
        return NULL;
#endif
    }

    static jlong CreatePorterDuffFilter(JNIEnv* env, jobject, jint srcColor,
            jint modeHandle) {
        SkPorterDuff::Mode mode = (SkPorterDuff::Mode) modeHandle;
        return reinterpret_cast<jlong>(SkColorFilter::CreateModeFilter(srcColor, SkPorterDuff::ToXfermodeMode(mode)));
    }

    static jlong CreateLightingFilter(JNIEnv* env, jobject, jint mul, jint add) {
        return reinterpret_cast<jlong>(SkColorFilter::CreateLightingFilter(mul, add));
    }

    static jlong CreateColorMatrixFilter(JNIEnv* env, jobject, jfloatArray jarray) {
        AutoJavaFloatArray autoArray(env, jarray, 20);
        const float* src = autoArray.ptr();

#ifdef SK_SCALAR_IS_FIXED
        SkFixed array[20];
        for (int i = 0; i < 20; i++) {
            array[i] = SkFloatToScalar(src[i]);
        }
        return reinterpret_cast<jlong>(new SkColorMatrixFilter(array));
#else
        return reinterpret_cast<jlong>(new SkColorMatrixFilter(src));
#endif
    }
};

static JNINativeMethod colorfilter_methods[] = {
    {"destroyFilter", "(JJ)V", (void*) SkColorFilterGlue::finalizer}
};

static JNINativeMethod porterduff_methods[] = {
    { "native_CreatePorterDuffFilter", "(II)J", (void*) SkColorFilterGlue::CreatePorterDuffFilter   },
    { "nCreatePorterDuffFilter",       "(JII)J", (void*) SkColorFilterGlue::glCreatePorterDuffFilter }
};

static JNINativeMethod lighting_methods[] = {
    { "native_CreateLightingFilter", "(II)J", (void*) SkColorFilterGlue::CreateLightingFilter   },
    { "nCreateLightingFilter",       "(JII)J", (void*) SkColorFilterGlue::glCreateLightingFilter },
};

static JNINativeMethod colormatrix_methods[] = {
    { "nativeColorMatrixFilter", "([F)J", (void*) SkColorFilterGlue::CreateColorMatrixFilter   },
    { "nColorMatrixFilter",      "(J[F)J", (void*) SkColorFilterGlue::glCreateColorMatrixFilter }
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
