/*
 * Copyright (C) 2010 The Android Open Source Project
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

#include "GammaFontRenderer.h"
#include "Debug.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

GammaFontRenderer::GammaFontRenderer() {
    INIT_LOGD("Creating lookup gamma font renderer");

#ifndef ANDROID_ENABLE_LINEAR_BLENDING
    // Compute the gamma tables
    const float gamma = 1.0f / Properties::textGamma;
    for (uint32_t i = 0; i <= 255; i++) {
        mGammaTable[i] = uint8_t((float)::floor(pow(i / 255.0f, gamma) * 255.0f + 0.5f));
    }
#endif
}

void GammaFontRenderer::endPrecaching() {
    if (mRenderer) {
        mRenderer->endPrecaching();
    }
}

};  // namespace uirenderer
};  // namespace android
