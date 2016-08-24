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

#include "DisplayListCanvas.h"
#include "RecordingCanvas.h"
#include "MinikinUtils.h"
#include "Paint.h"
#include "Typeface.h"

#include <SkDrawFilter.h>

namespace android {

Canvas* Canvas::create_recording_canvas(int width, int height) {
#if HWUI_NEW_OPS
    return new uirenderer::RecordingCanvas(width, height);
#else
    return new uirenderer::DisplayListCanvas(width, height);
#endif
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
    if (flags & (SkPaint::kUnderlineText_Flag | SkPaint::kStrikeThruText_Flag)) {
        // Same values used by Skia
        const float kStdStrikeThru_Offset   = (-6.0f / 21.0f);
        const float kStdUnderline_Offset    = (1.0f / 9.0f);
        const float kStdUnderline_Thickness = (1.0f / 18.0f);

        SkScalar left = x;
        SkScalar right = x + length;
        float textSize = paint.getTextSize();
        float strokeWidth = fmax(textSize * kStdUnderline_Thickness, 1.0f);
        if (flags & SkPaint::kUnderlineText_Flag) {
            SkScalar top = y + textSize * kStdUnderline_Offset - 0.5f * strokeWidth;
            SkScalar bottom = y + textSize * kStdUnderline_Offset + 0.5f * strokeWidth;
            drawRect(left, top, right, bottom, paint);
        }
        if (flags & SkPaint::kStrikeThruText_Flag) {
            SkScalar top = y + textSize * kStdStrikeThru_Offset - 0.5f * strokeWidth;
            SkScalar bottom = y + textSize * kStdStrikeThru_Offset + 0.5f * strokeWidth;
            drawRect(left, top, right, bottom, paint);
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
    DrawTextFunctor(const Layout& layout, Canvas* canvas, uint16_t* glyphs, float* pos,
            const SkPaint& paint, float x, float y, MinikinRect& bounds, float totalAdvance)
        : layout(layout)
        , canvas(canvas)
        , glyphs(glyphs)
        , pos(pos)
        , paint(paint)
        , x(x)
        , y(y)
        , bounds(bounds)
        , totalAdvance(totalAdvance) {
    }

    void operator()(size_t start, size_t end) {
        if (canvas->drawTextAbsolutePos()) {
            for (size_t i = start; i < end; i++) {
                glyphs[i] = layout.getGlyphId(i);
                pos[2 * i] = x + layout.getX(i);
                pos[2 * i + 1] = y + layout.getY(i);
            }
        } else {
            for (size_t i = start; i < end; i++) {
                glyphs[i] = layout.getGlyphId(i);
                pos[2 * i] = layout.getX(i);
                pos[2 * i + 1] = layout.getY(i);
            }
        }

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
            canvas->drawGlyphs(glyphs + start, pos + (2 * start), glyphCount, outlinePaint, x, y,
                    bounds.mLeft, bounds.mTop, bounds.mRight, bounds.mBottom, totalAdvance);

            // inner
            SkPaint innerPaint(paint);
            simplifyPaint(darken ? SK_ColorBLACK : SK_ColorWHITE, &innerPaint);
            innerPaint.setStyle(SkPaint::kFill_Style);
            canvas->drawGlyphs(glyphs + start, pos + (2 * start), glyphCount, innerPaint, x, y,
                    bounds.mLeft, bounds.mTop, bounds.mRight, bounds.mBottom, totalAdvance);
        } else {
            // standard draw path
            canvas->drawGlyphs(glyphs + start, pos + (2 * start), glyphCount, paint, x, y,
                    bounds.mLeft, bounds.mTop, bounds.mRight, bounds.mBottom, totalAdvance);
        }
    }
private:
    const Layout& layout;
    Canvas* canvas;
    uint16_t* glyphs;
    float* pos;
    const SkPaint& paint;
    float x;
    float y;
    MinikinRect& bounds;
    float totalAdvance;
};

void Canvas::drawText(const uint16_t* text, int start, int count, int contextCount,
        float x, float y, int bidiFlags, const Paint& origPaint, Typeface* typeface) {
    // minikin may modify the original paint
    Paint paint(origPaint);

    Layout layout;
    MinikinUtils::doLayout(&layout, &paint, bidiFlags, typeface, text, start, count, contextCount);

    size_t nGlyphs = layout.nGlyphs();
    std::unique_ptr<uint16_t[]> glyphs(new uint16_t[nGlyphs]);
    std::unique_ptr<float[]> pos(new float[nGlyphs * 2]);

    x += MinikinUtils::xOffsetForTextAlign(&paint, layout);

    MinikinRect bounds;
    layout.getBounds(&bounds);
    if (!drawTextAbsolutePos()) {
        bounds.offset(x, y);
    }

    // Set align to left for drawing, as we don't want individual
    // glyphs centered or right-aligned; the offset above takes
    // care of all alignment.
    paint.setTextAlign(Paint::kLeft_Align);

    DrawTextFunctor f(layout, this, glyphs.get(), pos.get(),
            paint, x, y, bounds, layout.getAdvance());
    MinikinUtils::forFontRun(layout, &paint, f);
}

class DrawTextOnPathFunctor {
public:
    DrawTextOnPathFunctor(const Layout& layout, Canvas* canvas, float hOffset,
            float vOffset, const Paint& paint, const SkPath& path)
        : layout(layout)
        , canvas(canvas)
        , hOffset(hOffset)
        , vOffset(vOffset)
        , paint(paint)
        , path(path) {
    }

    void operator()(size_t start, size_t end) {
        uint16_t glyphs[1];
        for (size_t i = start; i < end; i++) {
            glyphs[0] = layout.getGlyphId(i);
            float x = hOffset + layout.getX(i);
            float y = vOffset + layout.getY(i);
            canvas->drawGlyphsOnPath(glyphs, 1, path, x, y, paint);
        }
    }
private:
    const Layout& layout;
    Canvas* canvas;
    float hOffset;
    float vOffset;
    const Paint& paint;
    const SkPath& path;
};

void Canvas::drawTextOnPath(const uint16_t* text, int count, int bidiFlags, const SkPath& path,
        float hOffset, float vOffset, const Paint& paint, Typeface* typeface) {
    Paint paintCopy(paint);
    Layout layout;
    MinikinUtils::doLayout(&layout, &paintCopy, bidiFlags, typeface, text, 0, count, count);
    hOffset += MinikinUtils::hOffsetForTextAlign(&paintCopy, layout, path);

    // Set align to left for drawing, as we don't want individual
    // glyphs centered or right-aligned; the offset above takes
    // care of all alignment.
    paintCopy.setTextAlign(Paint::kLeft_Align);

    DrawTextOnPathFunctor f(layout, this, hOffset, vOffset, paintCopy, path);
    MinikinUtils::forFontRun(layout, &paintCopy, f);
}

} // namespace android
