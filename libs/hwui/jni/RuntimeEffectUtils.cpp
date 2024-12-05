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

#include "RuntimeEffectUtils.h"

#include "include/effects/SkRuntimeEffect.h"

namespace android {
namespace uirenderer {

static inline int ThrowIAEFmt(JNIEnv* env, const char* fmt, ...) {
    va_list args;
    va_start(args, fmt);
    int ret = jniThrowExceptionFmt(env, "java/lang/IllegalArgumentException", fmt, args);
    va_end(args);
    return ret;
}

bool isIntUniformType(const SkRuntimeEffect::Uniform::Type& type) {
    switch (type) {
        case SkRuntimeEffect::Uniform::Type::kFloat:
        case SkRuntimeEffect::Uniform::Type::kFloat2:
        case SkRuntimeEffect::Uniform::Type::kFloat3:
        case SkRuntimeEffect::Uniform::Type::kFloat4:
        case SkRuntimeEffect::Uniform::Type::kFloat2x2:
        case SkRuntimeEffect::Uniform::Type::kFloat3x3:
        case SkRuntimeEffect::Uniform::Type::kFloat4x4:
            return false;
        case SkRuntimeEffect::Uniform::Type::kInt:
        case SkRuntimeEffect::Uniform::Type::kInt2:
        case SkRuntimeEffect::Uniform::Type::kInt3:
        case SkRuntimeEffect::Uniform::Type::kInt4:
            return true;
    }
}

void UpdateFloatUniforms(JNIEnv* env, SkRuntimeEffectBuilder* builder, const char* uniformName,
                         const float values[], int count, bool isColor) {
    SkRuntimeEffectBuilder::BuilderUniform uniform = builder->uniform(uniformName);
    if (uniform.fVar == nullptr) {
        ThrowIAEFmt(env, "unable to find uniform named %s", uniformName);
    } else if (isColor != ((uniform.fVar->flags & SkRuntimeEffect::Uniform::kColor_Flag) != 0)) {
        if (isColor) {
            jniThrowExceptionFmt(
                    env, "java/lang/IllegalArgumentException",
                    "attempting to set a color uniform using the non-color specific APIs: %s %x",
                    uniformName, uniform.fVar->flags);
        } else {
            ThrowIAEFmt(env,
                        "attempting to set a non-color uniform using the setColorUniform APIs: %s",
                        uniformName);
        }
    } else if (isIntUniformType(uniform.fVar->type)) {
        ThrowIAEFmt(env, "attempting to set a int uniform using the setUniform APIs: %s",
                    uniformName);
    } else if (!uniform.set<float>(values, count)) {
        ThrowIAEFmt(env, "mismatch in byte size for uniform [expected: %zu actual: %zu]",
                    uniform.fVar->sizeInBytes(), sizeof(float) * count);
    }
}

void UpdateIntUniforms(JNIEnv* env, SkRuntimeEffectBuilder* builder, const char* uniformName,
                       const int values[], int count) {
    SkRuntimeEffectBuilder::BuilderUniform uniform = builder->uniform(uniformName);
    if (uniform.fVar == nullptr) {
        ThrowIAEFmt(env, "unable to find uniform named %s", uniformName);
    } else if (!isIntUniformType(uniform.fVar->type)) {
        ThrowIAEFmt(env, "attempting to set a non-int uniform using the setIntUniform APIs: %s",
                    uniformName);
    } else if (!uniform.set<int>(values, count)) {
        ThrowIAEFmt(env, "mismatch in byte size for uniform [expected: %zu actual: %zu]",
                    uniform.fVar->sizeInBytes(), sizeof(float) * count);
    }
}

void UpdateChild(JNIEnv* env, SkRuntimeEffectBuilder* builder, const char* childName,
                 SkFlattenable* childEffect) {
    SkRuntimeShaderBuilder::BuilderChild builderChild = builder->child(childName);
    if (builderChild.fChild == nullptr) {
        ThrowIAEFmt(env, "unable to find child named %s", childName);
        return;
    }

    builderChild = sk_ref_sp(childEffect);
}

}  // namespace uirenderer
}  // namespace android