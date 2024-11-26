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

#include "ColorFilter.h"
#include "GraphicsJNI.h"
#include "RuntimeEffectUtils.h"
#include "SkBlender.h"

using namespace android::uirenderer;

static void SkRuntimeEffectBuilder_delete(SkRuntimeEffectBuilder* builder) {
    delete builder;
}

static jlong RuntimeXfermode_getNativeFinalizer(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&SkRuntimeEffectBuilder_delete));
}

static jlong RuntimeXfermode_createBuilder(JNIEnv* env, jobject, jstring sksl) {
    ScopedUtfChars strSksl(env, sksl);
    auto result =
            SkRuntimeEffect::MakeForBlender(SkString(strSksl.c_str()), SkRuntimeEffect::Options{});
    if (result.effect.get() == nullptr) {
        doThrowIAE(env, result.errorText.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(new SkRuntimeEffectBuilder(std::move(result.effect)));
}

static jlong RuntimeXfermode_create(JNIEnv* env, jobject, jlong builderPtr) {
    auto* builder = reinterpret_cast<SkRuntimeEffectBuilder*>(builderPtr);
    sk_sp<SkBlender> blender = builder->makeBlender();
    if (!blender) {
        doThrowIAE(env);
    }
    return reinterpret_cast<jlong>(blender.release());
}

static void RuntimeXfermode_updateFloatArrayUniforms(JNIEnv* env, jobject, jlong builderPtr,
                                                     jstring uniformName, jfloatArray uniforms,
                                                     jboolean isColor) {
    auto* builder = reinterpret_cast<SkRuntimeEffectBuilder*>(builderPtr);
    ScopedUtfChars name(env, uniformName);
    AutoJavaFloatArray autoValues(env, uniforms, 0, kRO_JNIAccess);
    UpdateFloatUniforms(env, builder, name.c_str(), autoValues.ptr(), autoValues.length(), isColor);
}

static void RuntimeXfermode_updateFloatUniforms(JNIEnv* env, jobject, jlong builderPtr,
                                                jstring uniformName, jfloat value1, jfloat value2,
                                                jfloat value3, jfloat value4, jint count) {
    auto* builder = reinterpret_cast<SkRuntimeEffectBuilder*>(builderPtr);
    ScopedUtfChars name(env, uniformName);
    const float values[4] = {value1, value2, value3, value4};
    UpdateFloatUniforms(env, builder, name.c_str(), values, count, false);
}

static void RuntimeXfermode_updateIntArrayUniforms(JNIEnv* env, jobject, jlong builderPtr,
                                                   jstring uniformName, jintArray uniforms) {
    auto* builder = reinterpret_cast<SkRuntimeEffectBuilder*>(builderPtr);
    ScopedUtfChars name(env, uniformName);
    AutoJavaIntArray autoValues(env, uniforms, 0);
    UpdateIntUniforms(env, builder, name.c_str(), autoValues.ptr(), autoValues.length());
}

static void RuntimeXfermode_updateIntUniforms(JNIEnv* env, jobject, jlong builderPtr,
                                              jstring uniformName, jint value1, jint value2,
                                              jint value3, jint value4, jint count) {
    auto* builder = reinterpret_cast<SkRuntimeEffectBuilder*>(builderPtr);
    ScopedUtfChars name(env, uniformName);
    const int values[4] = {value1, value2, value3, value4};
    UpdateIntUniforms(env, builder, name.c_str(), values, count);
}

static void RuntimeXfermode_updateChild(JNIEnv* env, jobject, jlong builderPtr, jstring childName,
                                        jlong childPtr) {
    auto* builder = reinterpret_cast<SkRuntimeEffectBuilder*>(builderPtr);
    ScopedUtfChars name(env, childName);
    auto* child = reinterpret_cast<SkFlattenable*>(childPtr);
    if (child) {
        UpdateChild(env, builder, name.c_str(), child);
    }
}

static void RuntimeXfermode_updateColorFilter(JNIEnv* env, jobject, jlong builderPtr,
                                              jstring childName, jlong colorFilterPtr) {
    auto* builder = reinterpret_cast<SkRuntimeEffectBuilder*>(builderPtr);
    ScopedUtfChars name(env, childName);
    auto* child = reinterpret_cast<ColorFilter*>(colorFilterPtr);
    if (child) {
        auto childInput = child->getInstance();
        if (childInput) {
            UpdateChild(env, builder, name.c_str(), childInput.release());
        }
    }
}

static const JNINativeMethod gRuntimeXfermodeMethods[] = {
        {"nativeGetFinalizer", "()J", (void*)RuntimeXfermode_getNativeFinalizer},
        {"nativeCreateBlenderBuilder", "(Ljava/lang/String;)J",
         (void*)RuntimeXfermode_createBuilder},
        {"nativeCreateNativeInstance", "(J)J", (void*)RuntimeXfermode_create},
        {"nativeUpdateUniforms", "(JLjava/lang/String;[FZ)V",
         (void*)RuntimeXfermode_updateFloatArrayUniforms},
        {"nativeUpdateUniforms", "(JLjava/lang/String;FFFFI)V",
         (void*)RuntimeXfermode_updateFloatUniforms},
        {"nativeUpdateUniforms", "(JLjava/lang/String;[I)V",
         (void*)RuntimeXfermode_updateIntArrayUniforms},
        {"nativeUpdateUniforms", "(JLjava/lang/String;IIIII)V",
         (void*)RuntimeXfermode_updateIntUniforms},
        {"nativeUpdateChild", "(JLjava/lang/String;J)V", (void*)RuntimeXfermode_updateChild},
        {"nativeUpdateColorFilter", "(JLjava/lang/String;J)V",
         (void*)RuntimeXfermode_updateColorFilter},
};

int register_android_graphics_RuntimeXfermode(JNIEnv* env) {
    android::RegisterMethodsOrDie(env, "android/graphics/RuntimeXfermode", gRuntimeXfermodeMethods,
                                  NELEM(gRuntimeXfermodeMethods));

    return 0;
}
