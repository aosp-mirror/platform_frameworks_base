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
 * Shader implementation that can composite 2 Shaders together with the specified blend mode.
 * This implementation can appropriately convert the composed result to either an SkShader or
 * SkImageFilter depending on the inputs
 */
class ComposeShader : public Shader {
public:
    ComposeShader(Shader& shaderA, Shader& shaderB, const SkBlendMode blendMode,
                  const SkMatrix* matrix);
    ~ComposeShader() override;

protected:
    sk_sp<SkShader> makeSkShader() override;
    sk_sp<SkImageFilter> makeSkImageFilter() override;

private:
    sk_sp<SkShader> skShader;
    sk_sp<SkImageFilter> skImageFilter;
};
}  // namespace android::uirenderer
