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

#include "ColorFilter.h"

#include "GraphicsJNI.h"
#include "RuntimeEffectUtils.h"
#include "SkBlendMode.h"
#include "include/effects/SkRuntimeEffect.h"

namespace android {

using namespace uirenderer;

class ColorFilterGlue {
public:
    static void SafeUnref(ColorFilter* filter) {
        if (filter) {
            filter->decStrong(nullptr);
        }
    }

    static jlong GetNativeFinalizer(JNIEnv*, jobject) {
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(&SafeUnref));
    }

    static jlong CreateBlendModeFilter(JNIEnv* env, jobject, jint srcColor, jint modeHandle) {
        auto mode = static_cast<SkBlendMode>(modeHandle);
        auto* blendModeFilter = new BlendModeColorFilter(srcColor, mode);
        blendModeFilter->incStrong(nullptr);
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(blendModeFilter));
    }

    static jlong CreateLightingFilter(JNIEnv* env, jobject, jint mul, jint add) {
        auto* lightingFilter = new LightingFilter(mul, add);
        lightingFilter->incStrong(nullptr);
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(lightingFilter));
    }

    static void SetLightingFilterMul(JNIEnv* env, jobject, jlong lightingFilterPtr, jint mul) {
        auto* filter = reinterpret_cast<LightingFilter*>(lightingFilterPtr);
        if (filter) {
            filter->setMul(mul);
        }
    }

    static void SetLightingFilterAdd(JNIEnv* env, jobject, jlong lightingFilterPtr, jint add) {
        auto* filter = reinterpret_cast<LightingFilter*>(lightingFilterPtr);
        if (filter) {
            filter->setAdd(add);
        }
    }

    static std::vector<float> getMatrixFromJFloatArray(JNIEnv* env, jfloatArray jarray) {
        std::vector<float> matrix(20);
        // float matrix[20];
        env->GetFloatArrayRegion(jarray, 0, 20, matrix.data());
        // java biases the translates by 255, so undo that before calling skia
        matrix[ 4] *= (1.0f/255);
        matrix[ 9] *= (1.0f/255);
        matrix[14] *= (1.0f/255);
        matrix[19] *= (1.0f/255);
        return matrix;
    }

    static jlong CreateColorMatrixFilter(JNIEnv* env, jobject, jfloatArray jarray) {
        std::vector<float> matrix = getMatrixFromJFloatArray(env, jarray);
        auto* colorMatrixColorFilter = new ColorMatrixColorFilter(std::move(matrix));
        colorMatrixColorFilter->incStrong(nullptr);
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(colorMatrixColorFilter));
    }

    static void SetColorMatrix(JNIEnv* env, jobject, jlong colorMatrixColorFilterPtr,
                               jfloatArray jarray) {
        auto* filter = reinterpret_cast<ColorMatrixColorFilter*>(colorMatrixColorFilterPtr);
        if (filter) {
            filter->setMatrix(getMatrixFromJFloatArray(env, jarray));
        }
    }

    static jlong RuntimeColorFilter_createColorFilter(JNIEnv* env, jobject, jstring agsl) {
        ScopedUtfChars strSksl(env, agsl);
        auto result = SkRuntimeEffect::MakeForColorFilter(SkString(strSksl.c_str()),
                                                          SkRuntimeEffect::Options{});
        if (result.effect.get() == nullptr) {
            doThrowIAE(env, result.errorText.c_str());
            return 0;
        }
        auto builder = new SkRuntimeEffectBuilder(std::move(result.effect));
        auto* runtimeColorFilter = new RuntimeColorFilter(builder);
        runtimeColorFilter->incStrong(nullptr);
        return static_cast<jlong>(reinterpret_cast<uintptr_t>(runtimeColorFilter));
    }

    static void RuntimeColorFilter_updateUniformsFloatArray(JNIEnv* env, jobject,
                                                            jlong colorFilterPtr,
                                                            jstring uniformName,
                                                            jfloatArray uniforms,
                                                            jboolean isColor) {
        auto* filter = reinterpret_cast<RuntimeColorFilter*>(colorFilterPtr);
        ScopedUtfChars name(env, uniformName);
        AutoJavaFloatArray autoValues(env, uniforms, 0, kRO_JNIAccess);
        if (filter) {
            filter->updateUniforms(env, name.c_str(), autoValues.ptr(), autoValues.length(),
                                   isColor);
        }
    }

    static void RuntimeColorFilter_updateUniformsFloats(JNIEnv* env, jobject, jlong colorFilterPtr,
                                                        jstring uniformName, jfloat value1,
                                                        jfloat value2, jfloat value3, jfloat value4,
                                                        jint count) {
        auto* filter = reinterpret_cast<RuntimeColorFilter*>(colorFilterPtr);
        ScopedUtfChars name(env, uniformName);
        const float values[4] = {value1, value2, value3, value4};
        if (filter) {
            filter->updateUniforms(env, name.c_str(), values, count, false);
        }
    }

    static void RuntimeColorFilter_updateUniformsIntArray(JNIEnv* env, jobject,
                                                          jlong colorFilterPtr, jstring uniformName,
                                                          jintArray uniforms) {
        auto* filter = reinterpret_cast<RuntimeColorFilter*>(colorFilterPtr);
        ScopedUtfChars name(env, uniformName);
        AutoJavaIntArray autoValues(env, uniforms, 0);
        if (filter) {
            filter->updateUniforms(env, name.c_str(), autoValues.ptr(), autoValues.length());
        }
    }

    static void RuntimeColorFilter_updateUniformsInts(JNIEnv* env, jobject, jlong colorFilterPtr,
                                                      jstring uniformName, jint value1, jint value2,
                                                      jint value3, jint value4, jint count) {
        auto* filter = reinterpret_cast<RuntimeColorFilter*>(colorFilterPtr);
        ScopedUtfChars name(env, uniformName);
        const int values[4] = {value1, value2, value3, value4};
        if (filter) {
            filter->updateUniforms(env, name.c_str(), values, count);
        }
    }

    static void RuntimeColorFilter_updateChild(JNIEnv* env, jobject, jlong colorFilterPtr,
                                               jstring childName, jlong childPtr) {
        auto* filter = reinterpret_cast<RuntimeColorFilter*>(colorFilterPtr);
        ScopedUtfChars name(env, childName);
        auto* child = reinterpret_cast<SkFlattenable*>(childPtr);
        if (filter && child) {
            filter->updateChild(env, name.c_str(), child);
        }
    }

    static void RuntimeColorFilter_updateInputColorFilter(JNIEnv* env, jobject,
                                                          jlong colorFilterPtr, jstring childName,
                                                          jlong childFilterPtr) {
        auto* filter = reinterpret_cast<RuntimeColorFilter*>(colorFilterPtr);
        ScopedUtfChars name(env, childName);
        auto* child = reinterpret_cast<ColorFilter*>(childFilterPtr);
        if (filter && child) {
            auto childInput = child->getInstance();
            if (childInput) {
                filter->updateChild(env, name.c_str(), childInput.release());
            }
        }
    }
};

static const JNINativeMethod colorfilter_methods[] = {
        {"nativeGetFinalizer", "()J", (void*)ColorFilterGlue::GetNativeFinalizer}};

static const JNINativeMethod blendmode_methods[] = {
        {"native_CreateBlendModeFilter", "(II)J", (void*)ColorFilterGlue::CreateBlendModeFilter},
};

static const JNINativeMethod lighting_methods[] = {
        {"native_CreateLightingFilter", "(II)J", (void*)ColorFilterGlue::CreateLightingFilter},
        {"native_SetLightingFilterAdd", "(JI)V", (void*)ColorFilterGlue::SetLightingFilterAdd},
        {"native_SetLightingFilterMul", "(JI)V", (void*)ColorFilterGlue::SetLightingFilterMul}};

static const JNINativeMethod colormatrix_methods[] = {
        {"nativeColorMatrixFilter", "([F)J", (void*)ColorFilterGlue::CreateColorMatrixFilter},
        {"nativeSetColorMatrix", "(J[F)V", (void*)ColorFilterGlue::SetColorMatrix}};

static const JNINativeMethod runtime_color_filter_methods[] = {
        {"nativeCreateRuntimeColorFilter", "(Ljava/lang/String;)J",
         (void*)ColorFilterGlue::RuntimeColorFilter_createColorFilter},
        {"nativeUpdateUniforms", "(JLjava/lang/String;[FZ)V",
         (void*)ColorFilterGlue::RuntimeColorFilter_updateUniformsFloatArray},
        {"nativeUpdateUniforms", "(JLjava/lang/String;FFFFI)V",
         (void*)ColorFilterGlue::RuntimeColorFilter_updateUniformsFloats},
        {"nativeUpdateUniforms", "(JLjava/lang/String;[I)V",
         (void*)ColorFilterGlue::RuntimeColorFilter_updateUniformsIntArray},
        {"nativeUpdateUniforms", "(JLjava/lang/String;IIIII)V",
         (void*)ColorFilterGlue::RuntimeColorFilter_updateUniformsInts},
        {"nativeUpdateChild", "(JLjava/lang/String;J)V",
         (void*)ColorFilterGlue::RuntimeColorFilter_updateChild},
        {"nativeUpdateInputColorFilter", "(JLjava/lang/String;J)V",
         (void*)ColorFilterGlue::RuntimeColorFilter_updateInputColorFilter}};

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
    android::RegisterMethodsOrDie(env, "android/graphics/RuntimeColorFilter",
                                  runtime_color_filter_methods,
                                  NELEM(runtime_color_filter_methods));

    return 0;
}

}
