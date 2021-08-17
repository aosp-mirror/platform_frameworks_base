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

namespace android::uirenderer {

/**
 * Shader implementation that blurs another Shader instance or the source bitmap
 */
class BlurShader : public Shader {
public:
    /**
     * Creates a BlurShader instance with the provided radius values to blur along the x and y
     * axis accordingly.
     *
     * This will blur the contents of the provided input shader if it is non-null, otherwise
     * the source bitmap will be blurred instead.
     *
     * The edge treatment parameter determines how content near the edges of the source is to
     * participate in the blur
     */
    BlurShader(float radiusX, float radiusY, Shader* inputShader, SkTileMode edgeTreatment,
            const SkMatrix* matrix);
    ~BlurShader() override;
protected:
    sk_sp<SkImageFilter> makeSkImageFilter() override;
private:
    sk_sp<SkImageFilter> skImageFilter;
};

} // namespace android::uirenderer