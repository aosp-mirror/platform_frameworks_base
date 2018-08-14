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


#include <utils/Log.h>
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
        return almostEqual(sRGBParamA, transferFunction.fA) &&
               almostEqual(sRGBParamB, transferFunction.fB) &&
               almostEqual(sRGBParamC, transferFunction.fC) &&
               almostEqual(sRGBParamD, transferFunction.fD) &&
               almostEqual(sRGBParamE, transferFunction.fE) &&
               almostEqual(sRGBParamF, transferFunction.fF) &&
               almostEqual(sRGBParamG, transferFunction.fG);
    }

    return false;
}

sk_sp<SkColorSpace> DataSpaceToColorSpace(android_dataspace dataspace) {

    SkColorSpace::Gamut gamut;
    switch (dataspace & HAL_DATASPACE_STANDARD_MASK) {
        case HAL_DATASPACE_STANDARD_BT709:
            gamut = SkColorSpace::kSRGB_Gamut;
            break;
        case HAL_DATASPACE_STANDARD_BT2020:
            gamut = SkColorSpace::kRec2020_Gamut;
            break;
        case HAL_DATASPACE_STANDARD_DCI_P3:
            gamut = SkColorSpace::kDCIP3_D65_Gamut;
            break;
        case HAL_DATASPACE_STANDARD_ADOBE_RGB:
            gamut = SkColorSpace::kAdobeRGB_Gamut;
            break;
        case HAL_DATASPACE_STANDARD_UNSPECIFIED:
            return nullptr;
        case HAL_DATASPACE_STANDARD_BT601_625:
        case HAL_DATASPACE_STANDARD_BT601_625_UNADJUSTED:
        case HAL_DATASPACE_STANDARD_BT601_525:
        case HAL_DATASPACE_STANDARD_BT601_525_UNADJUSTED:
        case HAL_DATASPACE_STANDARD_BT2020_CONSTANT_LUMINANCE:
        case HAL_DATASPACE_STANDARD_BT470M:
        case HAL_DATASPACE_STANDARD_FILM:
        default:
            ALOGW("Unsupported Gamut: %d", dataspace);
            return nullptr;
    }

    switch (dataspace & HAL_DATASPACE_TRANSFER_MASK) {
        case HAL_DATASPACE_TRANSFER_LINEAR:
            return SkColorSpace::MakeRGB(SkColorSpace::kLinear_RenderTargetGamma, gamut);
        case HAL_DATASPACE_TRANSFER_SRGB:
            return SkColorSpace::MakeRGB(SkColorSpace::kSRGB_RenderTargetGamma, gamut);
        case HAL_DATASPACE_TRANSFER_GAMMA2_2:
            return SkColorSpace::MakeRGB({2.2f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f}, gamut);
        case HAL_DATASPACE_TRANSFER_GAMMA2_6:
            return SkColorSpace::MakeRGB({2.6f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f}, gamut);
        case HAL_DATASPACE_TRANSFER_GAMMA2_8:
            return SkColorSpace::MakeRGB({2.8f, 1.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f}, gamut);
        case HAL_DATASPACE_TRANSFER_UNSPECIFIED:
            return nullptr;
        case HAL_DATASPACE_TRANSFER_SMPTE_170M:
        case HAL_DATASPACE_TRANSFER_ST2084:
        case HAL_DATASPACE_TRANSFER_HLG:
        default:
            ALOGW("Unsupported Gamma: %d", dataspace);
            return nullptr;
    }
}

};  // namespace uirenderer
};  // namespace android
