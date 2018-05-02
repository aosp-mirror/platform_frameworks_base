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

#include "Canvas.h"

#include "MinikinUtils.h"
#include "Paint.h"
#include "Properties.h"
#include "RecordingCanvas.h"
#include "RenderNode.h"
#include "Typeface.h"
#include "pipeline/skia/SkiaRecordingCanvas.h"

#include <SkDrawFilter.h>

namespace android {

Canvas* Canvas::create_recording_canvas(int width, int height, uirenderer::RenderNode* renderNode) {
    return new uirenderer::skiapipeline::SkiaRecordingCanvas(renderNode, width, height);
}

static inline void drawStroke(SkScalar left, SkScalar right, SkScalar top, SkScalar thickness,
                              const SkPaint& paint, Canvas* canvas) {
    const SkScalar strokeWidth = fmax(thickness, 1.0f);
    const SkScalar bottom = top + strokeWidth;
    canvas->drawRect(left, top, right, bottom, paint);
}

void Canvas::drawTextDecorations(float x, float y, float length, const SkPaint& paint) {
    uint32_t flags;
    SkDrawFilter* drawFilter = getDrawFilter();
    if (drawFilter) {
        SkPaint paintCopy(paint);
        drawFilter->filter(&paintCopy, SkDrawFilter::kText_Type);
        flags = paintCopy.getFlags();
    } else {
        flags = paint.getFlags();
    }
    if (flags & (SkPaint::kUnderlineText_ReserveFlag | SkPaint::kStrikeThruText_ReserveFlag)) {
        const SkScalar left = x;
        const SkScalar right = x + length;
        if (flags & SkPaint::kUnderlineText_ReserveFlag) {
            Paint::FontMetrics metrics;
            paint.getFontMetrics(&metrics);
            SkScalar position;
            if (!metrics.hasUnderlinePosition(&position)) {
                position = paint.getTextSize() * Paint::kStdUnderline_Top;
            }
            SkScalar thickness;
            if (!metrics.hasUnderlineThickness(&thickness)) {
                thickness = paint.getTextSize() * Paint::kStdUnderline_Thickness;
            }
            const SkScalar top = y + position;
            drawStroke(left, right, top, thickness, paint, this);
        }
        if (flags & SkPaint::kStrikeThruText_ReserveFlag) {
            const float textSize = paint.getTextSize();
            const float position = textSize * Paint::kStdStrikeThru_Top;
            const SkScalar thickness = textSize * Paint::kStdStrikeThru_Thickness;
            const SkScalar top = y + position;
            drawStroke(left, right, top, thickness, paint, this);
        }
    }
}

static void simplifyPaint(int color, SkPaint* paint) {
    paint->setColor(color);
    paint->setShader(nullptr);
    paint->setColorFilter(nullptr);
    paint->setLooper(nullptr);
    paint->setStrokeWidth(4 + 0.04 * paint->getTextSize());
    paint->setStrokeJoin(SkPaint::kRound_Join);
    paint->setLooper(nullptr);
}

class DrawTextFunctor {
public:
    DrawTextFunctor(const minikin::Layout& layout, Canvas* canvas, const SkPaint& paint, float x,
                    float y, minikin::MinikinRect& bounds, float totalAdvance)
            : layout(layout)
            , canvas(canvas)
            , paint(paint)
            , x(x)
            , y(y)
            , bounds(bounds)
            , totalAdvance(totalAdvance) {}

    void operator()(size_t start, size_t end) {
        auto glyphFunc = [&](uint16_t* text, float* positions) {
            if (canvas->drawTextAbsolutePos()) {
                for (size_t i = start, textIndex = 0, posIndex = 0; i < end; i++) {
                    text[textIndex++] = layout.getGlyphId(i);
                    positions[posIndex++] = x + layout.getX(i);
                    positions[posIndex++] = y + layout.getY(i);
                }
            } else {
                for (size_t i = start, textIndex = 0, posIndex = 0; i < end; i++) {
                    text[textIndex++] = layout.getGlyphId(i);
                    positions[posIndex++] = layout.getX(i);
                    positions[posIndex++] = layout.getY(i);
                }
            }
        };

        size_t glyphCount = end - start;

        if (CC_UNLIKELY(canvas->isHighContrastText() && paint.getAlpha() != 0)) {
            // high contrast draw path
            int color = paint.getColor();
            int channelSum = SkColorGetR(color) + SkColorGetG(color) + SkColorGetB(color);
            bool darken = channelSum < (128 * 3);

            // outline
            SkPaint outlinePaint(paint);
            simplifyPaint(darken ? SK_ColorWHITE : SK_ColorBLACK, &outlinePaint);
            outlinePaint.setStyle(SkPaint::kStrokeAndFill_Style);
            canvas->drawGlyphs(glyphFunc, glyphCount, outlinePaint, x, y, bounds.mLeft, bounds.mTop,
                               bounds.mRight, bounds.mBottom, totalAdvance);

            // inner
            SkPaint innerPaint(paint);
            simplifyPaint(darken ? SK_ColorBLACK : SK_ColorWHITE, &innerPaint);
            innerPaint.setStyle(SkPaint::kFill_Style);
            canvas->drawGlyphs(glyphFunc, glyphCount, innerPaint, x, y, bounds.mLeft, bounds.mTop,
                               bounds.mRight, bounds.mBottom, totalAdvance);
        } else {
            // standard draw path
            canvas->drawGlyphs(glyphFunc, glyphCount, paint, x, y, bounds.mLeft, bounds.mTop,
                               bounds.mRight, bounds.mBottom, totalAdvance);
        }
    }

private:
    const minikin::Layout& layout;
    Canvas* canvas;
    const SkPaint& paint;
    float x;
    float y;
    minikin::MinikinRect& bounds;
    float totalAdvance;
};

void Canvas::drawText(const uint16_t* text, int textSize, int start, int count, int contextStart,
                      int contextCount, float x, float y, minikin::Bidi bidiFlags,
                      const Paint& origPaint, const Typeface* typeface, minikin::MeasuredText* mt) {
    // minikin may modify the original paint
    Paint paint(origPaint);

    minikin::Layout layout =
            MinikinUtils::doLayout(&paint, bidiFlags, typeface, text, textSize, start, count,
                                   contextStart, contextCount, mt);

    x += MinikinUtils::xOffsetForTextAlign(&paint, layout);

    minikin::MinikinRect bounds;
    layout.getBounds(&bounds);
    if (!drawTextAbsolutePos()) {
        bounds.offset(x, y);
    }

    // Set align to left for drawing, as we don't want individual
    // glyphs centered or right-aligned; the offset above takes
    // care of all alignment.
    paint.setTextAlign(Paint::kLeft_Align);

    DrawTextFunctor f(layout, this, paint, x, y, bounds, layout.getAdvance());
    MinikinUtils::forFontRun(layout, &paint, f);
}

class DrawTextOnPathFunctor {
public:
    DrawTextOnPathFunctor(const minikin::Layout& layout, Canvas* canvas, float hOffset,
                          float vOffset, const Paint& paint, const SkPath& path)
            : layout(layout)
            , canvas(canvas)
            , hOffset(hOffset)
            , vOffset(vOffset)
            , paint(paint)
            , path(path) {}

    void operator()(size_t start, size_t end) {
        canvas->drawLayoutOnPath(layout, hOffset, vOffset, paint, path, start, end);
    }

private:
    const minikin::Layout& layout;
    Canvas* canvas;
    float hOffset;
    float vOffset;
    const Paint& paint;
    const SkPath& path;
};

void Canvas::drawTextOnPath(const uint16_t* text, int count, minikin::Bidi bidiFlags,
                            const SkPath& path, float hOffset, float vOffset, const Paint& paint,
                            const Typeface* typeface) {
    Paint paintCopy(paint);
    minikin::Layout layout = MinikinUtils::doLayout(&paintCopy, bidiFlags, typeface,
            text, count,  // text buffer
            0, count,  // draw range
            0, count,  // context range
            nullptr);
    hOffset += MinikinUtils::hOffsetForTextAlign(&paintCopy, layout, path);

    // Set align to left for drawing, as we don't want individual
    // glyphs centered or right-aligned; the offset above takes
    // care of all alignment.
    paintCopy.setTextAlign(Paint::kLeft_Align);

    DrawTextOnPathFunctor f(layout, this, hOffset, vOffset, paintCopy, path);
    MinikinUtils::forFontRun(layout, &paintCopy, f);
}

int Canvas::sApiLevel = 1;

void Canvas::setCompatibilityVersion(int apiLevel) {
    sApiLevel = apiLevel;
}

}  // namespace android
