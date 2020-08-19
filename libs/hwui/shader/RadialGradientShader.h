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

#pragma once

#include "Shader.h"
#include "SkShader.h"

namespace android::uirenderer {

/**
 * Shader implementation that renders a color ramp from the center outward to either as either
 * a SkShader or SkImageFilter
 */
class RadialGradientShader : public Shader {
public:
    RadialGradientShader(const SkPoint& center, const float radius,
                         const std::vector<SkColor4f>& colors, sk_sp<SkColorSpace> colorSpace,
                         const SkScalar pos[], const SkTileMode tileMode, const uint32_t shaderFlags,
                         const SkMatrix* matrix);
    ~RadialGradientShader() override;

protected:
    sk_sp<SkShader> makeSkShader() override;

private:
    sk_sp<SkShader> skShader;
};
}  // namespace android::uirenderer