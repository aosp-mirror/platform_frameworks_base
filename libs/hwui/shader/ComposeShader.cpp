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

#include "ComposeShader.h"

#include "SkImageFilters.h"
#include "SkShader.h"

namespace android::uirenderer {

ComposeShader::ComposeShader(Shader& shaderA, Shader& shaderB, const SkBlendMode blendMode,
                             const SkMatrix* matrix)
        : Shader(matrix) {
    // If both Shaders can be represented as SkShaders then use those, if not
    // create an SkImageFilter from both Shaders and create the equivalent SkImageFilter
    sk_sp<SkShader> skShaderA = shaderA.asSkShader();
    sk_sp<SkShader> skShaderB = shaderB.asSkShader();
    if (skShaderA.get() && skShaderB.get()) {
        skShader = SkShaders::Blend(blendMode, skShaderA, skShaderB);
        skImageFilter = nullptr;
    } else {
        sk_sp<SkImageFilter> skImageFilterA = shaderA.asSkImageFilter();
        sk_sp<SkImageFilter> skImageFilterB = shaderB.asSkImageFilter();
        skShader = nullptr;
        skImageFilter = SkImageFilters::Xfermode(blendMode, skImageFilterA, skImageFilterB);
    }
}

sk_sp<SkShader> ComposeShader::makeSkShader() {
    return skShader;
}

sk_sp<SkImageFilter> ComposeShader::makeSkImageFilter() {
    return skImageFilter;
}

ComposeShader::~ComposeShader() {}
}  // namespace android::uirenderer
