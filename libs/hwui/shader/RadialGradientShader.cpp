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
#include "RadialGradientShader.h"

#include <vector>

#include "SkGradientShader.h"

namespace android::uirenderer {

RadialGradientShader::RadialGradientShader(const SkPoint& center, const float radius,
                                           const std::vector<SkColor4f>& colors,
                                           sk_sp<SkColorSpace> colorspace, const SkScalar pos[],
                                           const SkTileMode tileMode, const uint32_t shaderFlags,
                                           const SkMatrix* matrix)
        : Shader(matrix)
        , skShader(SkGradientShader::MakeRadial(center, radius, colors.data(), colorspace, pos,
                                                colors.size(), tileMode, shaderFlags, nullptr)) {}

sk_sp<SkShader> RadialGradientShader::makeSkShader() {
    return skShader;
}

RadialGradientShader::~RadialGradientShader() {}
}  // namespace android::uirenderer