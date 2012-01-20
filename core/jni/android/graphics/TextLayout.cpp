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

/**
 * Character-based Arabic shaping.
 *
 * We'll use harfbuzz and glyph-based shaping instead once we're set up for it.
 *
 * @context the text context
 * @start the start of the text to render
 * @count the length of the text to render, start + count  must be <= contextCount
 * @contextCount the length of the context
 * @shaped where to put the shaped text, must have capacity for count uchars
 * @return the length of the shaped text, or -1 if error
 */
int TextLayout::shapeRtlText(const jchar* context, jsize start, jsize count, jsize contextCount,
                        jchar* shaped, UErrorCode& status) {
    SkAutoSTMalloc<CHAR_BUFFER_SIZE, jchar> tempBuffer(contextCount);
    jchar* buffer = tempBuffer.get();

    // Use fixed length since we need to keep start and count valid
    u_shapeArabic(context, contextCount, buffer, contextCount,
                   U_SHAPE_LENGTH_FIXED_SPACES_NEAR |
                   U_SHAPE_TEXT_DIRECTION_LOGICAL | U_SHAPE_LETTERS_SHAPE |
                   U_SHAPE_X_LAMALEF_SUB_ALTERNATE, &status);

    if (U_SUCCESS(status)) {
        // trim out UNICODE_NOT_A_CHAR following ligatures, if any
        int end = 0;
        for (int i = start, e = start + count; i < e; ++i) {
            if (buffer[i] != UNICODE_NOT_A_CHAR) {
                buffer[end++] = buffer[i];
            }
        }
        count = end;
        // ALOG(LOG_INFO, "CSRTL", "start %d count %d ccount %d\n", start, count, contextCount);
        ubidi_writeReverse(buffer, count, shaped, count, UBIDI_DO_MIRRORING | UBIDI_OUTPUT_REVERSE
                           | UBIDI_KEEP_BASE_COMBINING, &status);
        if (U_SUCCESS(status)) {
            return count;
        }
    }
    return -1;
}

/**
 * Basic character-based layout supporting rtl and arabic shaping.
 * Runs bidi on the text and generates a reordered, shaped line in buffer, returning
 * the length.
 * @text the text
 * @len the length of the text in uchars
 * @dir receives the resolved paragraph direction
 * @buffer the buffer to receive the reordered, shaped line.  Must have capacity of
 * at least len jchars.
 * @flags line bidi flags
 * @return the length of the reordered, shaped line, or -1 if error
 */
jint TextLayout::layoutLine(const jchar* text, jint len, jint flags, int& dir, jchar* buffer,
        UErrorCode& status) {
    static const int RTL_OPTS = UBIDI_DO_MIRRORING | UBIDI_KEEP_BASE_COMBINING |
            UBIDI_REMOVE_BIDI_CONTROLS | UBIDI_OUTPUT_REVERSE;

    UBiDiLevel bidiReq = 0;
    switch (flags) {
    case kBidi_LTR: bidiReq = 0; break; // no ICU constant, canonical LTR level
    case kBidi_RTL: bidiReq = 1; break; // no ICU constant, canonical RTL level
    case kBidi_Default_LTR: bidiReq = UBIDI_DEFAULT_LTR; break;
    case kBidi_Default_RTL: bidiReq = UBIDI_DEFAULT_RTL; break;
    case kBidi_Force_LTR: memcpy(buffer, text, len * sizeof(jchar)); return len;
    case kBidi_Force_RTL: return shapeRtlText(text, 0, len, len, buffer, status);
    }

    int32_t result = -1;

    UBiDi* bidi = ubidi_open();
    if (bidi) {
        ubidi_setPara(bidi, text, len, bidiReq, NULL, &status);
        if (U_SUCCESS(status)) {
            dir = ubidi_getParaLevel(bidi) & 0x1; // 0 if ltr, 1 if rtl

            int rc = ubidi_countRuns(bidi, &status);
            if (U_SUCCESS(status)) {
                // ALOG(LOG_INFO, "LAYOUT", "para bidiReq=%d dir=%d rc=%d\n", bidiReq, dir, rc);

                int32_t slen = 0;
                for (int i = 0; i < rc; ++i) {
                    int32_t start;
                    int32_t length;
                    UBiDiDirection runDir = ubidi_getVisualRun(bidi, i, &start, &length);

                    if (runDir == UBIDI_RTL) {
                        slen += shapeRtlText(text + start, 0, length, length, buffer + slen, status);
                    } else {
                        memcpy(buffer + slen, text + start, length * sizeof(jchar));
                        slen += length;
                    }
                }
                if (U_SUCCESS(status)) {
                    result = slen;
                }
            }
        }
        ubidi_close(bidi);
    }

    return result;
}

bool TextLayout::prepareText(SkPaint* paint, const jchar* text, jsize len, jint bidiFlags,
        const jchar** outText, int32_t* outBytes, jchar** outBuffer) {
    const jchar *workText = text;
    jchar *buffer = NULL;
    int dir = kDirection_LTR;
    if (needsLayout(text, len, bidiFlags)) {
        buffer =(jchar *) malloc(len * sizeof(jchar));
        if (!buffer) {
            return false;
        }
        UErrorCode status = U_ZERO_ERROR;
        len = layoutLine(text, len, bidiFlags, dir, buffer, status); // might change len, dir
        if (!U_SUCCESS(status)) {
            ALOG(LOG_WARN, "LAYOUT", "drawText error %d\n", status);
            free(buffer);
            return false; // can't render
        }
        workText = buffer; // use the shaped text
    }

    bool trimLeft = false;
    bool trimRight = false;

    SkPaint::Align horiz = paint->getTextAlign();
    switch (horiz) {
        case SkPaint::kLeft_Align: trimLeft = dir & kDirection_Mask; break;
        case SkPaint::kCenter_Align: trimLeft = trimRight = true; break;
        case SkPaint::kRight_Align: trimRight = !(dir & kDirection_Mask);
        default: break;
    }
    const jchar* workLimit = workText + len;

    if (trimLeft) {
        while (workText < workLimit && *workText == ' ') {
            ++workText;
        }
    }
    if (trimRight) {
        while (workLimit > workText && *(workLimit - 1) == ' ') {
            --workLimit;
        }
    }

    *outBytes = (workLimit - workText) << 1;
    *outText = workText;
    *outBuffer = buffer;

    return true;
}

// Draws or gets the path of a paragraph of text on a single line, running bidi and shaping.
// This will draw if canvas is not null, otherwise path must be non-null and it will create
// a path representing the text that would have been drawn.
void TextLayout::handleText(SkPaint *paint, const jchar* text, jsize len,
                            jint bidiFlags, jfloat x, jfloat y,SkCanvas *canvas, SkPath *path) {
    const jchar *workText;
    jchar *buffer = NULL;
    int32_t workBytes;
    if (prepareText(paint, text, len, bidiFlags, &workText, &workBytes, &buffer)) {
        SkScalar x_ = SkFloatToScalar(x);
        SkScalar y_ = SkFloatToScalar(y);
        if (canvas) {
            canvas->drawText(workText, workBytes, x_, y_, *paint);
        } else {
            paint->getTextPath(workText, workBytes, x_, y_, path);
        }
        free(buffer);
    }
}

bool TextLayout::prepareRtlTextRun(const jchar* context, jsize start, jsize& count,
        jsize contextCount, jchar* shaped) {
    UErrorCode status = U_ZERO_ERROR;
    count = shapeRtlText(context, start, count, contextCount, shaped, status);
    if (U_SUCCESS(status)) {
        return true;
    } else {
        ALOGW("drawTextRun error %d\n", status);
    }
    return false;
}

void TextLayout::drawTextRun(SkPaint* paint, const jchar* chars,
                             jint start, jint count, jint contextCount,
                             int dirFlags, jfloat x, jfloat y, SkCanvas* canvas) {

     SkScalar x_ = SkFloatToScalar(x);
     SkScalar y_ = SkFloatToScalar(y);

     uint8_t rtl = dirFlags & 0x1;
     if (rtl) {
         SkAutoSTMalloc<CHAR_BUFFER_SIZE, jchar> buffer(contextCount);
         if (prepareRtlTextRun(chars, start, count, contextCount, buffer.get())) {
             canvas->drawText(buffer.get(), count << 1, x_, y_, *paint);
         }
     } else {
         canvas->drawText(chars + start, count << 1, x_, y_, *paint);
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
    value = new TextLayoutCacheValue();
    value->computeValues(paint, chars, start, count, contextCount, dirFlags);
#endif
    if (value != NULL) {
        if (resultAdvances) {
            memcpy(resultAdvances, value->getAdvances(), value->getAdvancesCount() * sizeof(jfloat));
        }
        if (resultTotalAdvance) {
            *resultTotalAdvance = value->getTotalAdvance();
        }
    }
}

void TextLayout::getTextRunAdvancesICU(SkPaint* paint, const jchar* chars, jint start,
                                    jint count, jint contextCount, jint dirFlags,
                                    jfloat* resultAdvances, jfloat& resultTotalAdvance) {
    // Compute advances and return them
    computeAdvancesWithICU(paint, chars, start, count, contextCount, dirFlags,
            resultAdvances, &resultTotalAdvance);
}

// Draws a paragraph of text on a single line, running bidi and shaping
void TextLayout::drawText(SkPaint* paint, const jchar* text, jsize len,
                          int bidiFlags, jfloat x, jfloat y, SkCanvas* canvas) {
    handleText(paint, text, len, bidiFlags, x, y, canvas, NULL);
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

    SkAutoSTMalloc<CHAR_BUFFER_SIZE, jchar> buffer(count);

    int dir = kDirection_LTR;
    UErrorCode status = U_ZERO_ERROR;
    count = layoutLine(text, count, bidiFlags, dir, buffer.get(), status);
    if (U_SUCCESS(status)) {
        canvas->drawTextOnPathHV(buffer.get(), count << 1, *path, h_, v_, *paint);
    }
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
    ALOGD("ICU -- count=%d", widths);
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
            ALOGD("icu-adv = %f - total = %f", outAdvances[i], totalAdvance);
#endif
        }
    } else {
#if DEBUG_ADVANCES
    ALOGD("ICU -- count=%d", count);
#endif
        for (size_t i = 0; i < count; i++) {
            totalAdvance += outAdvances[i] = SkScalarToFloat(scalarArray[i]);
#if DEBUG_ADVANCES
            ALOGD("icu-adv = %f - total = %f", outAdvances[i], totalAdvance);
#endif
        }
    }
    *outTotalAdvance = totalAdvance;
}

}
