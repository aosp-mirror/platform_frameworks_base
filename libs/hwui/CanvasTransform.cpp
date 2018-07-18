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
#include "utils/Color.h"
#include "Properties.h"

#include <ui/ColorSpace.h>
#include <SkColorFilter.h>
#include <SkPaint.h>

#include <algorithm>
#include <cmath>

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

class ColorFilterCanvas : public SkPaintFilterCanvas {
public:
    ColorFilterCanvas(ColorTransform transform, SkCanvas* canvas)
            : SkPaintFilterCanvas(canvas), mTransform(transform) {}

    bool onFilter(SkTCopyOnFirstWrite<SkPaint>* paint, Type type) const override {
        if (*paint) {
            applyColorTransform(mTransform, *(paint->writable()));
        }
        return true;
    }

private:
    ColorTransform mTransform;
};

std::unique_ptr<SkCanvas> makeTransformCanvas(SkCanvas* inCanvas, ColorTransform transform) {
    switch (transform) {
        case ColorTransform::Light:
            return std::make_unique<ColorFilterCanvas>(ColorTransform::Light, inCanvas);
        case ColorTransform::Dark:
            return std::make_unique<ColorFilterCanvas>(ColorTransform::Dark, inCanvas);
        default:
            return nullptr;
    }
}

std::unique_ptr<SkCanvas> makeTransformCanvas(SkCanvas* inCanvas, UsageHint usageHint) {
    if (Properties::forceDarkMode) {
        switch (usageHint) {
            case UsageHint::Unknown:
                return makeTransformCanvas(inCanvas, ColorTransform::Light);
            case UsageHint::Background:
                return makeTransformCanvas(inCanvas, ColorTransform::Dark);
        }
    }
    return nullptr;
}

};  // namespace android::uirenderer