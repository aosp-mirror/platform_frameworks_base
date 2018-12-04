/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include "CanvasTransform.h"
#include "Properties.h"
#include "utils/Color.h"

#include <SkColorFilter.h>
#include <SkGradientShader.h>
#include <SkPaint.h>
#include <SkShader.h>
#include <ui/ColorSpace.h>

#include <algorithm>
#include <cmath>

#include <log/log.h>
#include <SkHighContrastFilter.h>

namespace android::uirenderer {

static SkColor makeLight(SkColor color) {
    Lab lab = sRGBToLab(color);
    float invertedL = std::min(110 - lab.L, 100.0f);
    if (invertedL > lab.L) {
        lab.L = invertedL;
        return LabToSRGB(lab, SkColorGetA(color));
    } else {
        return color;
    }
}

static SkColor makeDark(SkColor color) {
    Lab lab = sRGBToLab(color);
    float invertedL = std::min(110 - lab.L, 100.0f);
    if (invertedL < lab.L) {
        lab.L = invertedL;
        return LabToSRGB(lab, SkColorGetA(color));
    } else {
        return color;
    }
}

static SkColor transformColor(ColorTransform transform, SkColor color) {
    switch (transform) {
        case ColorTransform::Light:
            return makeLight(color);
        case ColorTransform::Dark:
            return makeDark(color);
        default:
            return color;
    }
}

static void applyColorTransform(ColorTransform transform, SkPaint& paint) {
    if (transform == ColorTransform::None) return;

    SkColor newColor = transformColor(transform, paint.getColor());
    paint.setColor(newColor);

    if (paint.getShader()) {
        SkShader::GradientInfo info;
        std::array<SkColor, 10> _colorStorage;
        std::array<SkScalar, _colorStorage.size()> _offsetStorage;
        info.fColorCount = _colorStorage.size();
        info.fColors = _colorStorage.data();
        info.fColorOffsets = _offsetStorage.data();
        SkShader::GradientType type = paint.getShader()->asAGradient(&info);

        if (info.fColorCount <= 10) {
            switch (type) {
                case SkShader::kLinear_GradientType:
                    for (int i = 0; i < info.fColorCount; i++) {
                        info.fColors[i] = transformColor(transform, info.fColors[i]);
                    }
                    paint.setShader(SkGradientShader::MakeLinear(info.fPoint, info.fColors,
                                                                 info.fColorOffsets, info.fColorCount,
                                                                 info.fTileMode, info.fGradientFlags, nullptr));
                    break;
                default:break;
            }

        }
    }

    if (paint.getColorFilter()) {
        SkBlendMode mode;
        SkColor color;
        // TODO: LRU this or something to avoid spamming new color mode filters
        if (paint.getColorFilter()->asColorMode(&color, &mode)) {
            color = transformColor(transform, color);
            paint.setColorFilter(SkColorFilter::MakeModeFilter(color, mode));
        }
    }
}

static BitmapPalette paletteForColorHSV(SkColor color) {
    float hsv[3];
    SkColorToHSV(color, hsv);
    return hsv[2] >= .5f ? BitmapPalette::Light : BitmapPalette::Dark;
}

static BitmapPalette filterPalette(const SkPaint* paint, BitmapPalette palette) {
    if (palette == BitmapPalette::Unknown || !paint || !paint->getColorFilter()) {
        return palette;
    }

    SkColor color = palette == BitmapPalette::Light ? SK_ColorWHITE : SK_ColorBLACK;
    color = paint->getColorFilter()->filterColor(color);
    return paletteForColorHSV(color);
}

bool transformPaint(ColorTransform transform, SkPaint* paint) {
    // TODO
    applyColorTransform(transform, *paint);
    return true;
}

bool transformPaint(ColorTransform transform, SkPaint* paint, BitmapPalette palette) {
    palette = filterPalette(paint, palette);
    bool shouldInvert = false;
    if (palette == BitmapPalette::Light && transform == ColorTransform::Dark) {
        shouldInvert = true;
    }
    if (palette == BitmapPalette::Dark && transform == ColorTransform::Light) {
        shouldInvert = true;
    }
    if (shouldInvert) {
        SkHighContrastConfig config;
        config.fInvertStyle = SkHighContrastConfig::InvertStyle::kInvertLightness;
        paint->setColorFilter(SkHighContrastFilter::Make(config)->makeComposed(paint->refColorFilter()));
    }
    return shouldInvert;
}

}  // namespace android::uirenderer
