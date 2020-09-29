/*
 * Copyright (C) 2020 The Android Open Source Project
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
#include "Bitmap.h"
#include "GraphicsJNI.h"
#include "SkImageFilter.h"
#include "SkImageFilters.h"
#include "graphics_jni_helpers.h"
#include "utils/Blur.h"
#include <utils/Log.h>

using namespace android::uirenderer;

static jlong createOffsetEffect(
    JNIEnv* env,
    jobject,
    jfloat offsetX,
    jfloat offsetY,
    jlong inputFilterHandle
) {
    auto* inputFilter = reinterpret_cast<const SkImageFilter*>(inputFilterHandle);
    sk_sp<SkImageFilter> offset = SkImageFilters::Offset(offsetX, offsetY, sk_ref_sp(inputFilter));
    return reinterpret_cast<jlong>(offset.release());
}

static jlong createBlurEffect(JNIEnv* env , jobject, jfloat radiusX,
        jfloat radiusY, jlong inputFilterHandle, jint edgeTreatment) {
    auto* inputImageFilter = reinterpret_cast<SkImageFilter*>(inputFilterHandle);
    sk_sp<SkImageFilter> blurFilter =
            SkImageFilters::Blur(
                    Blur::convertRadiusToSigma(radiusX),
                    Blur::convertRadiusToSigma(radiusY),
                    static_cast<SkTileMode>(edgeTreatment),
                    sk_ref_sp(inputImageFilter),
                    nullptr);
    return reinterpret_cast<jlong>(blurFilter.release());
}

static void RenderEffect_safeUnref(SkImageFilter* filter) {
    SkSafeUnref(filter);
}

static jlong getRenderEffectFinalizer(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&RenderEffect_safeUnref));
}

static const JNINativeMethod gRenderEffectMethods[] = {
    {"nativeGetFinalizer", "()J", (void*)getRenderEffectFinalizer},
    {"nativeCreateOffsetEffect", "(FFJ)J", (void*)createOffsetEffect},
    {"nativeCreateBlurEffect", "(FFJI)J", (void*)createBlurEffect}
};

int register_android_graphics_RenderEffect(JNIEnv* env) {
    android::RegisterMethodsOrDie(env, "android/graphics/RenderEffect",
            gRenderEffectMethods, NELEM(gRenderEffectMethods));
    return 0;
}