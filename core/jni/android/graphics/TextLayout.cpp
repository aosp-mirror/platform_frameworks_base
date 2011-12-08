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

// Returns true if we might need layout.  If bidiFlags force LTR, assume no layout, if
// bidiFlags indicate there probably is RTL, assume we do, otherwise scan the text
// looking for a character >= the first RTL character in unicode and assume we do if
// we find one.
bool TextLayout::needsLayout(const jchar* text, jint len, jint bidiFlags) {
    if (bidiFlags == kBidi_Force_LTR) {
        return false;
    }
    if ((bidiFlags == kBidi_RTL) || (bidiFlags == kBidi_Default_RTL) ||
            bidiFlags == kBidi_Force_RTL) {
        return true;
    }
    for (int i = 0; i < len; ++i) {
        if (text[i] >= UNICODE_FIRST_RTL_CHAR) {
            return true;
        }
    }
    return false;
}

// Draws or gets the path of a paragraph of text on a single line, running bidi and shaping.
// This will draw if canvas is not null, otherwise path must be non-null and it will create
// a path representing the text that would have been drawn.
void TextLayout::handleText(SkPaint *paint, const jchar* text, jsize len,
                            jint bidiFlags, jfloat x, jfloat y,SkCanvas *canvas, SkPath *path) {
    sp<TextLayoutCacheValue> value;
#if USE_TEXT_LAYOUT_CACHE
    // Return advances from the cache. Compute them if needed
    value = TextLayoutCache::getInstance().getValue(paint, text, 0, len,
            len, bidiFlags);
#else
    value = new TextLayoutCacheValue(len);
    TextLayoutEngine::getInstance().computeValues(value.get(), paint,
            reinterpret_cast<const UChar*>(text), 0, len, len, bidiFlags);
#endif
    if (value == NULL) {
        LOGE("Cannot get TextLayoutCache value for text = '%s'",
                String8(text, len).string());
        return ;
    }
    SkScalar x_ = SkFloatToScalar(x);
    SkScalar y_ = SkFloatToScalar(y);
    if (canvas) {
        canvas->drawText(value->getGlyphs(), value->getGlyphsCount() * 2, x_, y_, *paint);
    } else {
        paint->getTextPath(value->getGlyphs(), value->getGlyphsCount() * 2, x_, y_, path);
    }
}

void TextLayout::getTextRunAdvances(SkPaint* paint, const jchar* chars, jint start,
                                    jint count, jint contextCount, jint dirFlags,
                                    jfloat* resultAdvances, jfloat* resultTotalAdvance) {
    sp<TextLayoutCacheValue> value;
#if USE_TEXT_LAYOUT_CACHE
    // Return advances from the cache. Compute them if needed
    value = TextLayoutCache::getInstance().getValue(paint, chars, start, count,
            contextCount, dirFlags);
#else
    value = new TextLayoutCacheValue(contextCount);
    TextLayoutEngine::getInstance().computeValues(value.get(), paint,
            reinterpret_cast<const UChar*>(chars), start, count, contextCount, dirFlags);
#endif
    if (value == NULL) {
        LOGE("Cannot get TextLayoutCache value for text = '%s'",
                String8(chars + start, count).string());
        return ;
    }
    if (resultAdvances) {
        memcpy(resultAdvances, value->getAdvances(), value->getAdvancesCount() * sizeof(jfloat));
    }
    if (resultTotalAdvance) {
        *resultTotalAdvance = value->getTotalAdvance();
    }
}

void TextLayout::getTextRunAdvancesICU(SkPaint* paint, const jchar* chars, jint start,
                                    jint count, jint contextCount, jint dirFlags,
                                    jfloat* resultAdvances, jfloat& resultTotalAdvance) {
    // Compute advances and return them
    computeAdvancesWithICU(paint, chars, start, count, contextCount, dirFlags,
            resultAdvances, &resultTotalAdvance);
}

void TextLayout::getTextPath(SkPaint *paint, const jchar *text, jsize len,
                             jint bidiFlags, jfloat x, jfloat y, SkPath *path) {
    handleText(paint, text, len, bidiFlags, x, y, NULL, path);
}


void TextLayout::drawTextOnPath(SkPaint* paint, const jchar* text, int count,
                                int bidiFlags, jfloat hOffset, jfloat vOffset,
                                SkPath* path, SkCanvas* canvas) {

    SkScalar h_ = SkFloatToScalar(hOffset);
    SkScalar v_ = SkFloatToScalar(vOffset);

    if (!needsLayout(text, count, bidiFlags)) {
        canvas->drawTextOnPathHV(text, count << 1, *path, h_, v_, *paint);
        return;
    }

    sp<TextLayoutCacheValue> value;
#if USE_TEXT_LAYOUT_CACHE
    value = TextLayoutCache::getInstance().getValue(paint, text, 0, count,
            count, bidiFlags);
#else
    value = new TextLayoutCacheValue(count);
    TextLayoutEngine::getInstance().computeValues(value.get(), paint,
            reinterpret_cast<const UChar*>(text), 0, count, count, bidiFlags);
#endif
    if (value == NULL) {
        LOGE("Cannot get TextLayoutCache value for text = '%s'",
                String8(text, count).string());
        return ;
    }

    // Save old text encoding
    SkPaint::TextEncoding oldEncoding = paint->getTextEncoding();
    // Define Glyph encoding
    paint->setTextEncoding(SkPaint::kGlyphID_TextEncoding);

    canvas->drawTextOnPathHV(value->getGlyphs(), value->getGlyphsCount() * 2, *path, h_, v_, *paint);

    // Get back old encoding
    paint->setTextEncoding(oldEncoding);
}

void TextLayout::computeAdvancesWithICU(SkPaint* paint, const UChar* chars,
        size_t start, size_t count, size_t contextCount, int dirFlags,
        jfloat* outAdvances, jfloat* outTotalAdvance) {
    SkAutoSTMalloc<CHAR_BUFFER_SIZE, jchar> tempBuffer(contextCount);
    jchar* buffer = tempBuffer.get();
    SkScalar* scalarArray = (SkScalar*)outAdvances;

    // this is where we'd call harfbuzz
    // for now we just use ushape.c
    size_t widths;
    const jchar* text;
    if (dirFlags & 0x1) { // rtl, call arabic shaping in case
        UErrorCode status = U_ZERO_ERROR;
        // Use fixed length since we need to keep start and count valid
        u_shapeArabic(chars, contextCount, buffer, contextCount,
                U_SHAPE_LENGTH_FIXED_SPACES_NEAR |
                U_SHAPE_TEXT_DIRECTION_LOGICAL | U_SHAPE_LETTERS_SHAPE |
                U_SHAPE_X_LAMALEF_SUB_ALTERNATE, &status);
        // we shouldn't fail unless there's an out of memory condition,
        // in which case we're hosed anyway
        for (int i = start, e = i + count; i < e; ++i) {
            if (buffer[i] == UNICODE_NOT_A_CHAR) {
                buffer[i] = UNICODE_ZWSP; // zero-width-space for skia
            }
        }
        text = buffer + start;
        widths = paint->getTextWidths(text, count << 1, scalarArray);
    } else {
        text = chars + start;
        widths = paint->getTextWidths(text, count << 1, scalarArray);
    }

    jfloat totalAdvance = 0;
    if (widths < count) {
#if DEBUG_ADVANCES
    LOGD("ICU -- count=%d", widths);
#endif
        // Skia operates on code points, not code units, so surrogate pairs return only
        // one value. Expand the result so we have one value per UTF-16 code unit.

        // Note, skia's getTextWidth gets confused if it encounters a surrogate pair,
        // leaving the remaining widths zero.  Not nice.
        for (size_t i = 0, p = 0; i < widths; ++i) {
            totalAdvance += outAdvances[p++] = SkScalarToFloat(scalarArray[i]);
            if (p < count &&
                    text[p] >= UNICODE_FIRST_LOW_SURROGATE &&
                    text[p] < UNICODE_FIRST_PRIVATE_USE &&
                    text[p-1] >= UNICODE_FIRST_HIGH_SURROGATE &&
                    text[p-1] < UNICODE_FIRST_LOW_SURROGATE) {
                outAdvances[p++] = 0;
            }
#if DEBUG_ADVANCES
            LOGD("icu-adv = %f - total = %f", outAdvances[i], totalAdvance);
#endif
        }
    } else {
#if DEBUG_ADVANCES
    LOGD("ICU -- count=%d", count);
#endif
        for (size_t i = 0; i < count; i++) {
            totalAdvance += outAdvances[i] = SkScalarToFloat(scalarArray[i]);
#if DEBUG_ADVANCES
            LOGD("icu-adv = %f - total = %f", outAdvances[i], totalAdvance);
#endif
        }
    }
    *outTotalAdvance = totalAdvance;
}

}
