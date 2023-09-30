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

#include "Canvas.h"
#include "FeatureFlags.h"
#include "MinikinUtils.h"
#include "Paint.h"
#include "Properties.h"
#include "RenderNode.h"
#include "Typeface.h"
#include "hwui/PaintFilter.h"
#include "pipeline/skia/SkiaRecordingCanvas.h"

namespace android {

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
    paint->setStrokeWidth(4 + 0.04 * paint->getSkFont().getSize());
    paint->setStrokeJoin(SkPaint::kRound_Join);
    paint->setLooper(nullptr);
}

class DrawTextFunctor {
public:
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
            int channelSum = SkColorGetR(color) + SkColorGetG(color) + SkColorGetB(color);
            bool darken = channelSum < (128 * 3);

            // outline
            gDrawTextBlobMode = DrawTextBlobMode::HctOutline;
            Paint outlinePaint(paint);
            simplifyPaint(darken ? SK_ColorWHITE : SK_ColorBLACK, &outlinePaint);
            outlinePaint.setStyle(SkPaint::kStrokeAndFill_Style);
            canvas->drawGlyphs(glyphFunc, glyphCount, outlinePaint, x, y, totalAdvance);

            // inner
            gDrawTextBlobMode = DrawTextBlobMode::HctInner;
            Paint innerPaint(paint);
            simplifyPaint(darken ? SK_ColorBLACK : SK_ColorWHITE, &innerPaint);
            innerPaint.setStyle(SkPaint::kFill_Style);
            canvas->drawGlyphs(glyphFunc, glyphCount, innerPaint, x, y, totalAdvance);
            gDrawTextBlobMode = DrawTextBlobMode::Normal;
        } else {
            // standard draw path
            canvas->drawGlyphs(glyphFunc, glyphCount, paint, x, y, totalAdvance);
        }

        if (text_feature::fix_double_underline()) {
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
