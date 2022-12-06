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

#include "GraphicsJNI.h"

#include "SkColorFilter.h"
#include "SkColorMatrixFilter.h"

namespace android {

using namespace uirenderer;

class SkColorFilterGlue {
public:
    static void SafeUnref(SkColorFilter* filter) {
        SkSafeUnref(filter);
    }

    static jlong GetNativeFinalizer(JNIEnv*, jobject) {
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(&SafeUnref));
    }

    static jlong CreateBlendModeFilter(JNIEnv* env, jobject, jint srcColor, jint modeHandle) {
        SkBlendMode mode = static_cast<SkBlendMode>(modeHandle);
        return reinterpret_cast<jlong>(SkColorFilters::Blend(srcColor, mode).release());
    }

    static jlong CreateLightingFilter(JNIEnv* env, jobject, jint mul, jint add) {
        return reinterpret_cast<jlong>(SkColorMatrixFilter::MakeLightingFilter(mul, add).release());
    }

    static jlong CreateColorMatrixFilter(JNIEnv* env, jobject, jfloatArray jarray) {
        float matrix[20];
        env->GetFloatArrayRegion(jarray, 0, 20, matrix);
        // java biases the translates by 255, so undo that before calling skia
        matrix[ 4] *= (1.0f/255);
        matrix[ 9] *= (1.0f/255);
        matrix[14] *= (1.0f/255);
        matrix[19] *= (1.0f/255);
        return reinterpret_cast<jlong>(SkColorFilters::Matrix(matrix).release());
    }
};

static const JNINativeMethod colorfilter_methods[] = {
    {"nativeGetFinalizer", "()J", (void*) SkColorFilterGlue::GetNativeFinalizer }
};

static const JNINativeMethod blendmode_methods[] = {
    { "native_CreateBlendModeFilter", "(II)J", (void*) SkColorFilterGlue::CreateBlendModeFilter },
};

static const JNINativeMethod lighting_methods[] = {
    { "native_CreateLightingFilter", "(II)J", (void*) SkColorFilterGlue::CreateLightingFilter },
};

static const JNINativeMethod colormatrix_methods[] = {
    { "nativeColorMatrixFilter", "([F)J", (void*) SkColorFilterGlue::CreateColorMatrixFilter },
};

int register_android_graphics_ColorFilter(JNIEnv* env) {
    android::RegisterMethodsOrDie(env, "android/graphics/ColorFilter", colorfilter_methods,
                                  NELEM(colorfilter_methods));
    android::RegisterMethodsOrDie(env, "android/graphics/PorterDuffColorFilter", blendmode_methods,
                                  NELEM(blendmode_methods));
    android::RegisterMethodsOrDie(env, "android/graphics/BlendModeColorFilter", blendmode_methods,
                                  NELEM(blendmode_methods));
    android::RegisterMethodsOrDie(env, "android/graphics/LightingColorFilter", lighting_methods,
                                  NELEM(lighting_methods));
    android::RegisterMethodsOrDie(env, "android/graphics/ColorMatrixColorFilter",
                                  colormatrix_methods, NELEM(colormatrix_methods));
    
    return 0;
}

}
