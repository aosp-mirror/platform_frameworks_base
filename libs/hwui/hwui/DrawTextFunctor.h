/*
 * Copyright (C) 2023 The Android Open Source Project
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

#include <SkFontMetrics.h>
#include <SkRRect.h>
#include <SkTextBlob.h>

#include "../utils/Color.h"
#include "Canvas.h"
#include "FeatureFlags.h"
#include "MinikinUtils.h"
#include "Paint.h"
#include "Properties.h"
#include "RenderNode.h"
#include "Typeface.h"
#include "hwui/PaintFilter.h"
#include "pipeline/skia/SkiaRecordingCanvas.h"

#ifdef __ANDROID__
#include <com_android_graphics_hwui_flags.h>
namespace flags = com::android::graphics::hwui::flags;
#else
namespace flags {
constexpr bool high_contrast_text_small_text_rect() {
    return false;
}
constexpr bool high_contrast_text_inner_text_color() {
    return false;
}
}  // namespace flags
#endif

namespace android {

// These should match the constants in framework/base/core/java/android/text/Layout.java
inline constexpr float kHighContrastTextBorderWidth = 4.0f;
inline constexpr float kHighContrastTextBorderWidthFactor = 0.2f;

static inline void drawStroke(SkScalar left, SkScalar right, SkScalar top, SkScalar thickness,
                              const Paint& paint, Canvas* canvas) {
    const SkScalar strokeWidth = fmax(thickness, 1.0f);
    const SkScalar bottom = top + strokeWidth;
    canvas->drawRect(left, top, right, bottom, paint);
}

static void simplifyPaint(int color, Paint* paint) {
    paint->setColor(color);
    paint->setShader(nullptr);
    paint->setColorFilter(nullptr);
    paint->setLooper(nullptr);

    if (flags::high_contrast_text_small_text_rect()) {
        paint->setStrokeWidth(
                std::max(kHighContrastTextBorderWidth,
                         kHighContrastTextBorderWidthFactor * paint->getSkFont().getSize()));
    } else {
        auto borderWidthFactor = 0.04f;
        paint->setStrokeWidth(kHighContrastTextBorderWidth +
                              borderWidthFactor * paint->getSkFont().getSize());
    }
    paint->setStrokeJoin(SkPaint::kRound_Join);
    paint->setLooper(nullptr);
    paint->setBlendMode(SkBlendMode::kSrcOver);
}

namespace {

static bool shouldDarkenTextForHighContrast(const uirenderer::Lab& lab) {
    // LINT.IfChange(hct_darken)
    return lab.L <= 50;
    // LINT.ThenChange(/core/java/android/text/Layout.java:hct_darken)
}

}  // namespace

static void adjustHighContrastInnerTextColor(uirenderer::Lab* lab) {
    bool darken = shouldDarkenTextForHighContrast(*lab);
    bool isGrayscale = abs(lab->a) < 10 && abs(lab->b) < 10;
    if (isGrayscale) {
        // For near-grayscale text we first remove all color.
        lab->a = lab->b = 0;
        if (lab->L > 40 && lab->L < 60) {
            // Text near "middle gray" is pushed to a more contrasty gray.
            lab->L = darken ? 20 : 80;
        } else {
            // Other grayscale text is pushed completely white or black.
            lab->L = darken ? 0 : 100;
        }
    } else {
        // For color text we ensure the text is bright enough (for light text)
        // or dark enough (for dark text) to stand out against the background,
        // without touching the A and B components so we retain color.
        if (darken && lab->L > 20.f) {
            lab->L = 20.0f;
        } else if (!darken && lab->L < 90.f) {
            lab->L = 90.0f;
        }
    }
}

class DrawTextFunctor {
public:
    /**
     * Creates a Functor to draw the given text layout.
     *
     * @param layout
     * @param canvas
     * @param paint
     * @param x
     * @param y
     * @param totalAdvance
     * @param bounds bounds of the text. Only required if high contrast text mode is enabled.
     */
    DrawTextFunctor(const minikin::Layout& layout, Canvas* canvas, const Paint& paint, float x,
                    float y, float totalAdvance)
            : layout(layout)
            , canvas(canvas)
            , paint(paint)
            , x(x)
            , y(y)
            , totalAdvance(totalAdvance)
            , underlinePosition(0)
            , underlineThickness(0) {}

    void operator()(size_t start, size_t end) {
        auto glyphFunc = [&](uint16_t* text, float* positions) {
            for (size_t i = start, textIndex = 0, posIndex = 0; i < end; i++) {
                text[textIndex++] = layout.getGlyphId(i);
                positions[posIndex++] = x + layout.getX(i);
                positions[posIndex++] = y + layout.getY(i);
            }
        };

        size_t glyphCount = end - start;

        if (CC_UNLIKELY(canvas->isHighContrastText() && paint.getAlpha() != 0)) {
            // high contrast draw path
            int color = paint.getColor();
            uirenderer::Lab lab = uirenderer::sRGBToLab(color);
            bool darken = shouldDarkenTextForHighContrast(lab);

            // outline
            gDrawTextBlobMode = DrawTextBlobMode::HctOutline;
            Paint outlinePaint(paint);
            simplifyPaint(darken ? SK_ColorWHITE : SK_ColorBLACK, &outlinePaint);
            outlinePaint.setStyle(SkPaint::kStrokeAndFill_Style);
            canvas->drawGlyphs(glyphFunc, glyphCount, outlinePaint, x, y, totalAdvance);

            // inner
            gDrawTextBlobMode = DrawTextBlobMode::HctInner;
            Paint innerPaint(paint);
            if (flags::high_contrast_text_inner_text_color()) {
                adjustHighContrastInnerTextColor(&lab);
                simplifyPaint(uirenderer::LabToSRGB(lab, SK_AlphaOPAQUE), &innerPaint);
            } else {
                simplifyPaint(darken ? SK_ColorBLACK : SK_ColorWHITE, &innerPaint);
            }
            innerPaint.setStyle(SkPaint::kFill_Style);
            canvas->drawGlyphs(glyphFunc, glyphCount, innerPaint, x, y, totalAdvance);
            gDrawTextBlobMode = DrawTextBlobMode::Normal;
        } else {
            // standard draw path
            canvas->drawGlyphs(glyphFunc, glyphCount, paint, x, y, totalAdvance);
        }

        // Extract underline position and thickness.
        if (paint.isUnderline()) {
            SkFontMetrics metrics;
            paint.getSkFont().getMetrics(&metrics);
            const float textSize = paint.getSkFont().getSize();
            SkScalar position;
            if (!metrics.hasUnderlinePosition(&position)) {
                position = textSize * Paint::kStdUnderline_Top;
            }
            SkScalar thickness;
            if (!metrics.hasUnderlineThickness(&thickness)) {
                thickness = textSize * Paint::kStdUnderline_Thickness;
            }

            // If multiple fonts are used, use the most bottom position and most thick stroke
            // width as the underline position. This follows the CSS standard:
            // https://www.w3.org/TR/css-text-decor-3/#text-underline-position-property
            // <quote>
            // The exact position and thickness of line decorations is UA-defined in this level.
            // However, for underlines and overlines the UA must use a single thickness and
            // position on each line for the decorations deriving from a single decorating box.
            // </quote>
            underlinePosition = std::max(underlinePosition, position);
            underlineThickness = std::max(underlineThickness, thickness);
        }
    }

    float getUnderlinePosition() const { return underlinePosition; }
    float getUnderlineThickness() const { return underlineThickness; }

private:
    const minikin::Layout& layout;
    Canvas* canvas;
    const Paint& paint;
    float x;
    float y;
    float totalAdvance;
    float underlinePosition;
    float underlineThickness;
};

}  // namespace android
