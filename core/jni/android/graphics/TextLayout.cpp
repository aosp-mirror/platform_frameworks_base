/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "TextLayout"

#include "TextLayout.h"
#include "TextLayoutCache.h"

#include <android_runtime/AndroidRuntime.h>

#include "SkTemplates.h"
#include "unicode/ubidi.h"
#include "unicode/ushape.h"
#include <utils/Log.h>

namespace android {

// Draws or gets the path of a paragraph of text on a single line, running bidi and shaping.
// This will draw if canvas is not null, otherwise path must be non-null and it will create
// a path representing the text that would have been drawn.
void TextLayout::handleText(SkPaint *paint, const jchar* text, jsize len,
                            jfloat x, jfloat y, SkPath *path) {
    sp<TextLayoutValue> value = TextLayoutEngine::getInstance().getValue(paint,
            text, 0, len, len);
    if (value == NULL) {
        return ;
    }
    SkScalar x_ = SkFloatToScalar(x);
    SkScalar y_ = SkFloatToScalar(y);
    // Beware: this needs Glyph encoding (already done on the Paint constructor)
    paint->getTextPath(value->getGlyphs(), value->getGlyphsCount() * 2, x_, y_, path);
}

void TextLayout::getTextRunAdvances(SkPaint* paint, const jchar* chars, jint start,
                                    jint count, jint contextCount,
                                    jfloat* resultAdvances, jfloat* resultTotalAdvance) {
    sp<TextLayoutValue> value = TextLayoutEngine::getInstance().getValue(paint,
            chars, start, count, contextCount);
    if (value == NULL) {
        return ;
    }
    if (resultAdvances) {
        memcpy(resultAdvances, value->getAdvances(), value->getAdvancesCount() * sizeof(jfloat));
    }
    if (resultTotalAdvance) {
        *resultTotalAdvance = value->getTotalAdvance();
    }
}

void TextLayout::getTextPath(SkPaint *paint, const jchar *text, jsize len,
                             jfloat x, jfloat y, SkPath *path) {
    handleText(paint, text, len, x, y, path);
}

void TextLayout::drawTextOnPath(SkPaint* paint, const jchar* text, int count,
                                jfloat hOffset, jfloat vOffset,
                                SkPath* path, SkCanvas* canvas) {

    SkScalar h_ = SkFloatToScalar(hOffset);
    SkScalar v_ = SkFloatToScalar(vOffset);

    sp<TextLayoutValue> value = TextLayoutEngine::getInstance().getValue(paint,
            text, 0, count, count);
    if (value == NULL) {
        return;
    }

    // Beware: this needs Glyph encoding (already done on the Paint constructor)
    canvas->drawTextOnPathHV(value->getGlyphs(), value->getGlyphsCount() * 2, *path, h_, v_, *paint);
}

}
