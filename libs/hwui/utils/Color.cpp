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
#include <ui/ColorSpace.h>

#include <algorithm>
#include <cmath>

namespace android {
namespace uirenderer {

android::PixelFormat ColorTypeToPixelFormat(SkColorType colorType) {
    switch (colorType) {
        case kRGBA_8888_SkColorType:
            return PIXEL_FORMAT_RGBA_8888;
        case kRGBA_F16_SkColorType:
            return PIXEL_FORMAT_RGBA_FP16;
        case kRGB_565_SkColorType:
            return PIXEL_FORMAT_RGB_565;
        case kRGB_888x_SkColorType:
            return PIXEL_FORMAT_RGBX_8888;
        case kRGBA_1010102_SkColorType:
            return PIXEL_FORMAT_RGBA_1010102;
        case kARGB_4444_SkColorType:
            return PIXEL_FORMAT_RGBA_4444;
        default:
            ALOGV("Unsupported colorType: %d, return RGBA_8888 by default", (int)colorType);
            return PIXEL_FORMAT_RGBA_8888;
    }
}

SkColorType PixelFormatToColorType(android::PixelFormat format) {
    switch (format) {
        case PIXEL_FORMAT_RGBX_8888:    return kRGB_888x_SkColorType;
        case PIXEL_FORMAT_RGBA_8888:    return kRGBA_8888_SkColorType;
        case PIXEL_FORMAT_RGBA_FP16:    return kRGBA_F16_SkColorType;
        case PIXEL_FORMAT_RGB_565:      return kRGB_565_SkColorType;
        case PIXEL_FORMAT_RGBA_1010102: return kRGBA_1010102_SkColorType;
        case PIXEL_FORMAT_RGBA_4444:    return kARGB_4444_SkColorType;
        default:
            ALOGV("Unsupported PixelFormat: %d, return kUnknown_SkColorType by default", format);
            return kUnknown_SkColorType;
    }
}

sk_sp<SkColorSpace> DataSpaceToColorSpace(android_dataspace dataspace) {
    if (dataspace == HAL_DATASPACE_UNKNOWN) {
        return SkColorSpace::MakeSRGB();
    }

    skcms_Matrix3x3 gamut;
    switch (dataspace & HAL_DATASPACE_STANDARD_MASK) {
        case HAL_DATASPACE_STANDARD_BT709:
            gamut = SkNamedGamut::kSRGB;
            break;
        case HAL_DATASPACE_STANDARD_BT2020:
            gamut = SkNamedGamut::kRec2020;
            break;
        case HAL_DATASPACE_STANDARD_DCI_P3:
            gamut = SkNamedGamut::kDCIP3;
            break;
        case HAL_DATASPACE_STANDARD_ADOBE_RGB:
            gamut = SkNamedGamut::kAdobeRGB;
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
            ALOGV("Unsupported Gamut: %d", dataspace);
            return nullptr;
    }

    switch (dataspace & HAL_DATASPACE_TRANSFER_MASK) {
        case HAL_DATASPACE_TRANSFER_LINEAR:
            return SkColorSpace::MakeRGB(SkNamedTransferFn::kLinear, gamut);
        case HAL_DATASPACE_TRANSFER_SRGB:
            return SkColorSpace::MakeRGB(SkNamedTransferFn::kSRGB, gamut);
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
            ALOGV("Unsupported Gamma: %d", dataspace);
            return nullptr;
    }
}

template<typename T>
static constexpr T clamp(T x, T min, T max) {
    return x < min ? min : x > max ? max : x;
}

//static const float2 ILLUMINANT_D50_XY = {0.34567f, 0.35850f};
static const float3 ILLUMINANT_D50_XYZ = {0.964212f, 1.0f, 0.825188f};
static const mat3 BRADFORD = mat3{
        float3{ 0.8951f, -0.7502f,  0.0389f},
        float3{ 0.2664f,  1.7135f, -0.0685f},
        float3{-0.1614f,  0.0367f,  1.0296f}
};

static mat3 adaptation(const mat3& matrix, const float3& srcWhitePoint, const float3& dstWhitePoint) {
    float3 srcLMS = matrix * srcWhitePoint;
    float3 dstLMS = matrix * dstWhitePoint;
    return inverse(matrix) * mat3{dstLMS / srcLMS} * matrix;
}

namespace LabColorSpace {

static constexpr float A = 216.0f / 24389.0f;
static constexpr float B = 841.0f / 108.0f;
static constexpr float C = 4.0f / 29.0f;
static constexpr float D = 6.0f / 29.0f;

float3 toXyz(const Lab& lab) {
    float3 v { lab.L, lab.a, lab.b };
    v[0] = clamp(v[0], 0.0f, 100.0f);
    v[1] = clamp(v[1], -128.0f, 128.0f);
    v[2] = clamp(v[2], -128.0f, 128.0f);

    float fy = (v[0] + 16.0f) / 116.0f;
    float fx = fy + (v[1] * 0.002f);
    float fz = fy - (v[2] * 0.005f);
    float X = fx > D ? fx * fx * fx : (1.0f / B) * (fx - C);
    float Y = fy > D ? fy * fy * fy : (1.0f / B) * (fy - C);
    float Z = fz > D ? fz * fz * fz : (1.0f / B) * (fz - C);

    v[0] = X * ILLUMINANT_D50_XYZ[0];
    v[1] = Y * ILLUMINANT_D50_XYZ[1];
    v[2] = Z * ILLUMINANT_D50_XYZ[2];

    return v;
}

Lab fromXyz(const float3& v) {
    float X = v[0] / ILLUMINANT_D50_XYZ[0];
    float Y = v[1] / ILLUMINANT_D50_XYZ[1];
    float Z = v[2] / ILLUMINANT_D50_XYZ[2];

    float fx = X > A ? pow(X, 1.0f / 3.0f) : B * X + C;
    float fy = Y > A ? pow(Y, 1.0f / 3.0f) : B * Y + C;
    float fz = Z > A ? pow(Z, 1.0f / 3.0f) : B * Z + C;

    float L = 116.0f * fy - 16.0f;
    float a = 500.0f * (fx - fy);
    float b = 200.0f * (fy - fz);

    return Lab {
            clamp(L, 0.0f, 100.0f),
            clamp(a, -128.0f, 128.0f),
            clamp(b, -128.0f, 128.0f)
    };
}

};

Lab sRGBToLab(SkColor color) {
    auto colorSpace = ColorSpace::sRGB();
    float3 rgb;
    rgb.r = SkColorGetR(color) / 255.0f;
    rgb.g = SkColorGetG(color) / 255.0f;
    rgb.b = SkColorGetB(color) / 255.0f;
    float3 xyz = colorSpace.rgbToXYZ(rgb);
    float3 srcXYZ = ColorSpace::XYZ(float3{colorSpace.getWhitePoint(), 1});
    xyz = adaptation(BRADFORD, srcXYZ, ILLUMINANT_D50_XYZ) * xyz;
    return LabColorSpace::fromXyz(xyz);
}

SkColor LabToSRGB(const Lab& lab, SkAlpha alpha) {
    auto colorSpace = ColorSpace::sRGB();
    float3 xyz = LabColorSpace::toXyz(lab);
    float3 dstXYZ = ColorSpace::XYZ(float3{colorSpace.getWhitePoint(), 1});
    xyz = adaptation(BRADFORD, ILLUMINANT_D50_XYZ, dstXYZ) * xyz;
    float3 rgb = colorSpace.xyzToRGB(xyz);
    return SkColorSetARGB(alpha,
            static_cast<uint8_t>(rgb.r * 255),
            static_cast<uint8_t>(rgb.g * 255),
            static_cast<uint8_t>(rgb.b * 255));
}

}  // namespace uirenderer
}  // namespace android
