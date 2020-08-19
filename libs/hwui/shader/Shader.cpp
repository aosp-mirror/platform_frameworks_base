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

#include "Shader.h"

#include "SkImageFilters.h"
#include "SkPaint.h"
#include "SkRefCnt.h"

namespace android::uirenderer {

Shader::Shader(const SkMatrix* matrix)
        : localMatrix(matrix ? *matrix : SkMatrix::I())
        , skShader(nullptr)
        , skImageFilter(nullptr) {}

Shader::~Shader() {}

sk_sp<SkShader> Shader::asSkShader() {
    // If we already have created a shader with these parameters just return the existing
    // shader we have already created
    if (!this->skShader.get()) {
        this->skShader = makeSkShader();
        if (this->skShader.get()) {
            if (!localMatrix.isIdentity()) {
                this->skShader = this->skShader->makeWithLocalMatrix(localMatrix);
            }
        }
    }
    return this->skShader;
}

/**
 * By default return null as we cannot convert all visual effects to SkShader instances
 */
sk_sp<SkShader> Shader::makeSkShader() {
    return nullptr;
}

sk_sp<SkImageFilter> Shader::asSkImageFilter() {
    // If we already have created an ImageFilter with these parameters just return the existing
    // ImageFilter we have already created
    if (!this->skImageFilter.get()) {
        // Attempt to create an SkImageFilter from the current Shader implementation
        this->skImageFilter = makeSkImageFilter();
        if (this->skImageFilter) {
            if (!localMatrix.isIdentity()) {
                // If we have created an SkImageFilter and we have a transformation, wrap
                // the created SkImageFilter to apply the given matrix
                this->skImageFilter = SkImageFilters::MatrixTransform(
                    localMatrix, kMedium_SkFilterQuality, this->skImageFilter);
            }
        } else {
            // Otherwise if no SkImageFilter implementation is provided, create one from
            // the result of asSkShader. Note the matrix is already applied to the shader in
            // this case so just convert it to an SkImageFilter using SkImageFilters::Paint
            SkPaint paint;
            paint.setShader(asSkShader());
            sk_sp<SkImageFilter> paintFilter = SkImageFilters::Paint(paint);
            this->skImageFilter = SkImageFilters::Xfermode(SkBlendMode::kDstIn,
                    std::move(paintFilter));
        }
    }
    return this->skImageFilter;
}

/**
 * By default return null for subclasses to implement. If there is not a direct SkImageFilter
 * conversion
 */
sk_sp<SkImageFilter> Shader::makeSkImageFilter() {
    return nullptr;
}
}  // namespace android::uirenderer