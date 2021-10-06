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

#include "BlurShader.h"
#include "SkImageFilters.h"
#include "SkRefCnt.h"
#include "utils/Blur.h"

namespace android::uirenderer {
BlurShader::BlurShader(float radiusX, float radiusY, Shader* inputShader, SkTileMode edgeTreatment,
        const SkMatrix* matrix)
    : Shader(matrix)
    , skImageFilter(
            SkImageFilters::Blur(
                    Blur::convertRadiusToSigma(radiusX),
                    Blur::convertRadiusToSigma(radiusY),
                    edgeTreatment,
                    inputShader ? inputShader->asSkImageFilter() : nullptr,
                    nullptr)
            ) { }

sk_sp<SkImageFilter> BlurShader::makeSkImageFilter() {
    return skImageFilter;
}

BlurShader::~BlurShader() {}

} // namespace android::uirenderer