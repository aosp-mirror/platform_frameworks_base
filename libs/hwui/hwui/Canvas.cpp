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
#include "RenderNode.h"
#include "Typeface.h"
#include "pipeline/skia/SkiaRecordingCanvas.h"

#include "hwui/PaintFilter.h"

#include <SkFontMetrics.h>

namespace android {

Canvas* Canvas::create_recording_canvas(int width, int height, uirenderer::RenderNode* renderNode) {
    return new uirenderer::skiapipeline::SkiaRecordingCanvas(renderNode, width, height);
}

static inline void drawStroke(SkScalar left, SkScalar right, SkScalar top, SkScalar thickness,
                              const Paint& paint, Canvas* canvas) {
    const SkScalar strokeWidth = fmax(thickness, 1.0f);
    const SkScalar bottom = top + strokeWidth;
    canvas->drawRect(left, top, right, bottom, paint);
}

void Canvas::drawTextDecorations(float x, float y, float length, const Paint& paint) {
    // paint has already been filtered by our caller, so we can ignore any filter
    const bool strikeThru = paint.isStrikeThru();
    const bool underline = paint.isUnderline();
    if (strikeThru || underline) {
        const SkScalar left = x;
        const SkScalar right = x + length;
        const float textSize = paint.getSkFont().getSize();
        if (underline) {
            SkFontMetrics metrics;
            paint.getSkFont().getMetrics(&metrics);
            SkScalar position;
            if (!metrics.hasUnderlinePosition(&position)) {
                position = textSize * Paint::kStdUnderline_Top;
            }
            SkScalar thickness;
            if (!metrics.hasUnderlineThickness(&thickness)) {
                thickness = textSize * Paint::kStdUnderline_Thickness;
            }
            const SkScalar top = y + position;
            drawStroke(left, right, top, thickness, paint, this);
        }
        if (strikeThru) {
            const float position = textSize * Paint::kStdStrikeThru_Top;
            const SkScalar thickness = textSize * Paint::kStdStrikeThru_Thickness;
            const SkScalar top = y + position;
            drawStroke(left, right, top, thickness, paint, this);
        }
    }
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
            , totalAdvance(totalAdvance) {}

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
    }

private:
    const minikin::Layout& layout;
    Canvas* canvas;
    const Paint& paint;
    float x;
    float y;
    float totalAdvance;
};

void Canvas::drawGlyphs(const minikin::Font& font, const int* glyphIds, const float* positions,
                        int glyphCount, const Paint& paint) {
    // Minikin modify skFont for auto-fakebold/auto-fakeitalic.
    Paint copied(paint);

    auto glyphFunc = [&](uint16_t* outGlyphIds, float* outPositions) {
        for (uint32_t i = 0; i < glyphCount; ++i) {
            outGlyphIds[i] = static_cast<uint16_t>(glyphIds[i]);
        }
        memcpy(outPositions, positions, sizeof(float) * 2 * glyphCount);
    };

    const minikin::MinikinFont* minikinFont = font.typeface().get();
    SkFont* skfont = &copied.getSkFont();
    MinikinFontSkia::populateSkFont(skfont, minikinFont, minikin::FontFakery());

    // total advance is used for drawing underline. We do not support underlyine by glyph drawing.
    drawGlyphs(glyphFunc, glyphCount, copied, 0 /* x */, 0 /* y */, 0 /* total Advance */);
}

void Canvas::drawText(const uint16_t* text, int textSize, int start, int count, int contextStart,
                      int contextCount, float x, float y, minikin::Bidi bidiFlags,
                      const Paint& origPaint, const Typeface* typeface, minikin::MeasuredText* mt) {
    // minikin may modify the original paint
    Paint paint(origPaint);

    // interpret 'linear metrics' flag as 'linear', forcing no-hinting when drawing
    if (paint.getSkFont().isLinearMetrics()) {
        paint.getSkFont().setHinting(SkFontHinting::kNone);
    }

    minikin::Layout layout = MinikinUtils::doLayout(&paint, bidiFlags, typeface, text, textSize,
                                                    start, count, contextStart, contextCount, mt);

    x += MinikinUtils::xOffsetForTextAlign(&paint, layout);

    // Set align to left for drawing, as we don't want individual
    // glyphs centered or right-aligned; the offset above takes
    // care of all alignment.
    paint.setTextAlign(Paint::kLeft_Align);

    DrawTextFunctor f(layout, this, paint, x, y, layout.getAdvance());
    MinikinUtils::forFontRun(layout, &paint, f);
}

void Canvas::drawDoubleRoundRectXY(float outerLeft, float outerTop, float outerRight,
                            float outerBottom, float outerRx, float outerRy, float innerLeft,
                            float innerTop, float innerRight, float innerBottom, float innerRx,
                            float innerRy, const Paint& paint) {
    if (CC_UNLIKELY(paint.nothingToDraw())) return;
    SkRect outer = SkRect::MakeLTRB(outerLeft, outerTop, outerRight, outerBottom);
    SkRect inner = SkRect::MakeLTRB(innerLeft, innerTop, innerRight, innerBottom);

    SkRRect outerRRect;
    outerRRect.setRectXY(outer, outerRx, outerRy);

    SkRRect innerRRect;
    innerRRect.setRectXY(inner, innerRx, innerRy);
    drawDoubleRoundRect(outerRRect, innerRRect, paint);
}

void Canvas::drawDoubleRoundRectRadii(float outerLeft, float outerTop, float outerRight,
                            float outerBottom, const float* outerRadii, float innerLeft,
                            float innerTop, float innerRight, float innerBottom,
                            const float* innerRadii, const Paint& paint) {
    static_assert(sizeof(SkVector) == sizeof(float) * 2);
    if (CC_UNLIKELY(paint.nothingToDraw())) return;
    SkRect outer = SkRect::MakeLTRB(outerLeft, outerTop, outerRight, outerBottom);
    SkRect inner = SkRect::MakeLTRB(innerLeft, innerTop, innerRight, innerBottom);

    SkRRect outerRRect;
    const SkVector* outerSkVector = reinterpret_cast<const SkVector*>(outerRadii);
    outerRRect.setRectRadii(outer, outerSkVector);

    SkRRect innerRRect;
    const SkVector* innerSkVector = reinterpret_cast<const SkVector*>(innerRadii);
    innerRRect.setRectRadii(inner, innerSkVector);
    drawDoubleRoundRect(outerRRect, innerRRect, paint);
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
                            const SkPath& path, float hOffset, float vOffset,
                            const Paint& origPaint, const Typeface* typeface) {
    // minikin may modify the original paint
    Paint paint(origPaint);

    // interpret 'linear metrics' flag as 'linear', forcing no-hinting when drawing
    if (paint.getSkFont().isLinearMetrics()) {
        paint.getSkFont().setHinting(SkFontHinting::kNone);
    }

    minikin::Layout layout =
            MinikinUtils::doLayout(&paint, bidiFlags, typeface, text, count,  // text buffer
                                   0, count,                                  // draw range
                                   0, count,                                  // context range
                                   nullptr);
    hOffset += MinikinUtils::hOffsetForTextAlign(&paint, layout, path);

    // Set align to left for drawing, as we don't want individual
    // glyphs centered or right-aligned; the offset above takes
    // care of all alignment.
    paint.setTextAlign(Paint::kLeft_Align);

    DrawTextOnPathFunctor f(layout, this, hOffset, vOffset, paint, path);
    MinikinUtils::forFontRun(layout, &paint, f);
}

int Canvas::sApiLevel = 1;

void Canvas::setCompatibilityVersion(int apiLevel) {
    sApiLevel = apiLevel;
}

}  // namespace android
