/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "Color.h"

#include <cmath>

namespace android {
namespace uirenderer {

static inline bool almostEqual(float a, float b) {
    return std::abs(a - b) < 1e-2f;
}

bool transferFunctionCloseToSRGB(const SkColorSpace* colorSpace) {
    if (colorSpace == nullptr) return true;
    if (colorSpace->isSRGB()) return true;

    SkColorSpaceTransferFn transferFunction;
    if (colorSpace->isNumericalTransferFn(&transferFunction)) {
        // sRGB transfer function params:
        const float sRGBParamA = 1 / 1.055f;
        const float sRGBParamB = 0.055f / 1.055f;
        const float sRGBParamC = 1 / 12.92f;
        const float sRGBParamD = 0.04045f;
        const float sRGBParamE = 0.0f;
        const float sRGBParamF = 0.0f;
        const float sRGBParamG = 2.4f;

        // This comparison will catch Display P3
        return
                almostEqual(sRGBParamA, transferFunction.fA)
             && almostEqual(sRGBParamB, transferFunction.fB)
             && almostEqual(sRGBParamC, transferFunction.fC)
             && almostEqual(sRGBParamD, transferFunction.fD)
             && almostEqual(sRGBParamE, transferFunction.fE)
             && almostEqual(sRGBParamF, transferFunction.fF)
             && almostEqual(sRGBParamG, transferFunction.fG);
    }

    return false;
}

}; // namespace uirenderer
}; // namespace android
