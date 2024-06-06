/*
 * Copyright (C) 2015 The Android Open Source Project
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
#ifndef COLOR_H
#define COLOR_H

#include <SkColor.h>
#include <SkColorSpace.h>
#include <SkImageInfo.h>
#include <cutils/compiler.h>
#include <math.h>
#include <system/graphics.h>

struct ANativeWindow_Buffer;
struct AHardwareBuffer_Desc;

namespace android {
namespace uirenderer {
namespace Color {
enum Color {
    Red_500 = 0xFFF44336,
    Pink_500 = 0xFFE91E63,
    Purple_500 = 0xFF9C27B0,
    DeepPurple_500 = 0xFF673AB7,
    Indigo_500 = 0xFF3F51B5,
    Blue_500 = 0xFF2196F3,
    LightBlue_300 = 0xFF4FC3F7,
    LightBlue_500 = 0xFF03A9F4,
    Cyan_500 = 0xFF00BCD4,
    Teal_500 = 0xFF008577,
    Teal_700 = 0xFF00796B,
    Green_500 = 0xFF4CAF50,
    Green_700 = 0xFF388E3C,
    LightGreen_500 = 0xFF8BC34A,
    LightGreen_700 = 0xFF689F38,
    Lime_500 = 0xFFCDDC39,
    Yellow_500 = 0xFFFFEB3B,
    Amber_500 = 0xFFFFC107,
    Orange_500 = 0xFFFF9800,
    DeepOrange_500 = 0xFFFF5722,
    Brown_500 = 0xFF795548,
    Grey_200 = 0xFFEEEEEE,
    Grey_500 = 0xFF9E9E9E,
    Grey_700 = 0xFF616161,
    BlueGrey_500 = 0xFF607D8B,
    Transparent = 0x00000000,
    Black = 0xFF000000,
    White = 0xFFFFFFFF,
};
}

static_assert(Color::White == SK_ColorWHITE, "color format has changed");
static_assert(Color::Black == SK_ColorBLACK, "color format has changed");

// Array of bright (500 intensity) colors for synthetic content
static const Color::Color BrightColors[] = {
        Color::Red_500,    Color::Pink_500,  Color::Purple_500,     Color::DeepPurple_500,
        Color::Indigo_500, Color::Blue_500,  Color::LightBlue_500,  Color::Cyan_500,
        Color::Teal_500,   Color::Green_500, Color::LightGreen_500, Color::Lime_500,
        Color::Yellow_500, Color::Amber_500, Color::Orange_500,     Color::DeepOrange_500,
        Color::Brown_500,  Color::Grey_500,  Color::BlueGrey_500,
};
static constexpr int BrightColorsCount = sizeof(BrightColors) / sizeof(Color::Color);

enum class TransferFunctionType : int8_t { None = 0, Full, Limited, Gamma };

// Opto-electronic conversion function for the sRGB color space
// Takes a linear sRGB value and converts it to a gamma-encoded sRGB value
static constexpr float OECF_sRGB(float linear) {
    // IEC 61966-2-1:1999
    return linear <= 0.0031308f ? linear * 12.92f : (powf(linear, 1.0f / 2.4f) * 1.055f) - 0.055f;
}

// Electro-optical conversion function for the sRGB color space
// Takes a gamma-encoded sRGB value and converts it to a linear sRGB value
static constexpr float EOCF_sRGB(float srgb) {
    // IEC 61966-2-1:1999
    return srgb <= 0.04045f ? srgb / 12.92f : powf((srgb + 0.055f) / 1.055f, 2.4f);
}

SkImageInfo ANativeWindowToImageInfo(const ANativeWindow_Buffer& buffer,
                                                 sk_sp<SkColorSpace> colorSpace);

SkImageInfo BufferDescriptionToImageInfo(const AHardwareBuffer_Desc& bufferDesc,
                                         sk_sp<SkColorSpace> colorSpace);

uint32_t ColorTypeToBufferFormat(SkColorType colorType);

ANDROID_API sk_sp<SkColorSpace> DataSpaceToColorSpace(android_dataspace dataspace);

/**
 * Return the android_dataspace corresponding to colorSpace.
 *
 * Note: This currently only returns android_dataspaces with corresponding
 * ADataSpaces. The NDK relies on this, so if you need to update it to return
 * an android_dataspace *without* an ADataSpace, the NDK methods need to be
 * updated.
 *
 * @param colorSpace May be null, in which case this will return
 *                   HAL_DATASPACE_UNKNOWN.
 * @param colorType Some SkColorSpaces are associated with more than one
 *                  android_dataspace. In that case, the SkColorType is used to
 *                  determine which one to return.
 */
ANDROID_API android_dataspace ColorSpaceToADataSpace(SkColorSpace*, SkColorType);

struct Lab {
    float L;
    float a;
    float b;
};

Lab sRGBToLab(SkColor color);
SkColor LabToSRGB(const Lab& lab, SkAlpha alpha);
skcms_TransferFunction GetPQSkTransferFunction(float sdr_white_level = 0.f);

} /* namespace uirenderer */
} /* namespace android */

#endif /* COLOR_H */
