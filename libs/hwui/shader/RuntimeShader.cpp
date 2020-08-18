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

#include "RuntimeShader.h"

#include "SkShader.h"
#include "include/effects/SkRuntimeEffect.h"

namespace android::uirenderer {

RuntimeShader::RuntimeShader(SkRuntimeEffect& effect, sk_sp<SkData> data, bool isOpaque,
                             const SkMatrix* matrix)
        : Shader(nullptr)
        ,  // Explicitly passing null as RuntimeShader is created with the
           // matrix directly
        skShader(effect.makeShader(std::move(data), nullptr, 0, matrix, isOpaque)) {}

sk_sp<SkShader> RuntimeShader::makeSkShader() {
    return skShader;
}

RuntimeShader::~RuntimeShader() {}
}  // namespace android::uirenderer