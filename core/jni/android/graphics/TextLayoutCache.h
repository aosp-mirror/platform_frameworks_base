/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef ANDROID_TEXT_LAYOUT_CACHE_H
#define ANDROID_TEXT_LAYOUT_CACHE_H

#include "RtlProperties.h"

#include "stddef.h"
#include <utils/threads.h>
#include <utils/String16.h>
#include "utils/GenerationCache.h"
#include "utils/Compare.h"

#include "SkPaint.h"
#include "SkTemplates.h"

#include "unicode/ubidi.h"
#include "unicode/ushape.h"

#include <android_runtime/AndroidRuntime.h>

#define UNICODE_NOT_A_CHAR              0xffff
#define UNICODE_ZWSP                    0x200b
#define UNICODE_FIRST_LOW_SURROGATE     0xdc00
#define UNICODE_FIRST_HIGH_SURROGATE    0xd800
#define UNICODE_FIRST_PRIVATE_USE       0xe000
#define UNICODE_FIRST_RTL_CHAR          0x0590

// Temporary buffer size
#define CHAR_BUFFER_SIZE 80

// Converts a number of mega-bytes into bytes
#define MB(s) s * 1024 * 1024

// Define the default cache size in Mb
#define DEFAULT_TEXT_LAYOUT_CACHE_SIZE_IN_MB 0.125f

// Define the interval in number of cache hits between two statistics dump
#define DEFAULT_DUMP_STATS_CACHE_HIT_INTERVAL 100

namespace android {

/**
 * TextLayoutCacheKey is the Cache key
 */
class TextLayoutCacheKey {
public:
    TextLayoutCacheKey() : text(NULL), start(0), count(0), contextCount(0),
            dirFlags(0), textSize(0), typeface(NULL), textSkewX(0), fakeBoldText(false)  {
    }

    TextLayoutCacheKey(const SkPaint* paint,
            const UChar* text, size_t start, size_t count,
            size_t contextCount, int dirFlags) :
                text(text), start(start), count(count), contextCount(contextCount),
                dirFlags(dirFlags) {
        textSize = paint->getTextSize();
        typeface = paint->getTypeface();
        textSkewX = paint->getTextSkewX();
        fakeBoldText = paint->isFakeBoldText();
    }

    bool operator<(const TextLayoutCacheKey& rhs) const {
        LTE_INT(count) {
            LTE_INT(contextCount) {
                LTE_INT(start) {
                    LTE_FLOAT(textSize) {
                        LTE_INT(typeface) {
                            LTE_INT(textSkewX) {
                                LTE_INT(fakeBoldText) {
                                    LTE_INT(dirFlags) {
                                        return strncmp16(text, rhs.text, contextCount) < 0;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    // We need to copy the text when we insert the key into the cache itself.
    // We don't need to copy the text when we are only comparing keys.
    void internalTextCopy() {
        textCopy.setTo(text, contextCount);
        text = textCopy.string();
    }

    /**
     * Get the size of the Cache key.
     */
    size_t getSize() {
        return sizeof(TextLayoutCacheKey) + sizeof(UChar) * contextCount;
    }

private:
    const UChar* text;
    String16 textCopy;
    size_t start;
    size_t count;
    size_t contextCount;
    int dirFlags;
    float textSize;
    SkTypeface* typeface;
    float textSkewX;
    bool fakeBoldText;
}; // TextLayoutCacheKey

/*
 * RunAdvanceDescription is the Cache entry
 */
class RunAdvanceDescription {
public:
    RunAdvanceDescription() {
        advances = NULL;
        totalAdvance = 0;
    }

    ~RunAdvanceDescription() {
        delete[] advances;
    }

    void setElapsedTime(uint32_t time) {
        elapsedTime = time;
    }

    uint32_t getElapsedTime() {
        return elapsedTime;
    }

    void computeAdvances(SkPaint* paint, const UChar* chars, size_t start, size_t count,
            size_t contextCount, int dirFlags) {
        advances = new float[count];
        this->count = count;

        computeAdvances(paint, chars, start, count, contextCount, dirFlags,
                advances, &totalAdvance);
    }

    void copyResult(jfloat* outAdvances, jfloat* outTotalAdvance) {
        memcpy(outAdvances, advances, count * sizeof(jfloat));
        *outTotalAdvance = totalAdvance;
    }

    /**
     * Get the size of the Cache entry
     */
    size_t getSize() {
        return sizeof(RunAdvanceDescription) + sizeof(jfloat) * count;
    }

    static void computeAdvances(SkPaint* paint, const UChar* chars, size_t start, size_t count,
            size_t contextCount, int dirFlags, jfloat* outAdvances, jfloat* outTotalAdvance) {
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
            }
        } else {
            for (size_t i = 0; i < count; i++) {
                totalAdvance += outAdvances[i] = SkScalarToFloat(scalarArray[i]);
            }
        }
        *outTotalAdvance = totalAdvance;
    }

private:
    jfloat* advances;
    jfloat totalAdvance;
    size_t count;

    uint32_t elapsedTime;
}; // RunAdvanceDescription


class TextLayoutCache: public OnEntryRemoved<TextLayoutCacheKey, RunAdvanceDescription*>
{
public:
    TextLayoutCache();
    TextLayoutCache(uint32_t maxByteSize);

    virtual ~TextLayoutCache();

    bool isInitialized() {
        return mInitialized;
    }

    /**
     * Used as a callback when an entry is removed from the cache.
     * Do not invoke directly.
     */
    void operator()(TextLayoutCacheKey& text, RunAdvanceDescription*& desc);

    /**
     * Get cache entries
     */
    void getRunAdvances(SkPaint* paint, const jchar* text,
            jint start, jint count, jint contextCount, jint dirFlags,
            jfloat* outAdvances, jfloat* outTotalAdvance);

    /**
     * Clear the cache
     */
    void clear();

    /**
     * Sets the maximum size of the cache in bytes.
     */
    void setMaxSize(uint32_t maxSize);

    /**
     * Returns the maximum size of the cache in bytes.
     */
    uint32_t getMaxSize();

    /**
     * Returns the current size of the cache in bytes.
     */
    uint32_t getSize();

private:
    Mutex mLock;
    bool mInitialized;

    GenerationCache<TextLayoutCacheKey, RunAdvanceDescription*> mCache;

    uint32_t mSize;
    uint32_t mMaxSize;

    uint32_t mCacheHitCount;
    uint64_t mNanosecondsSaved;

    uint64_t mCacheStartTime;

    RtlDebugLevel mDebugLevel;
    bool mDebugEnabled;

    /*
     * Class initialization
     */
    void init();

    /**
     * Remove oldest entries until we are having enough space
     */
    void removeOldests();

    /**
     * Dump Cache statistics
     */
    void dumpCacheStats();
}; // TextLayoutCache

} // namespace android
#endif /* ANDROID_TEXT_LAYOUT_CACHE_H */

