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

#include "TextLayoutCache.h"
#include "TextLayout.h"

extern "C" {
  #include "harfbuzz-unicode.h"
}

namespace android {

TextLayoutCache::TextLayoutCache() :
        mCache(GenerationCache<TextLayoutCacheKey, sp<TextLayoutCacheValue> >::kUnlimitedCapacity),
        mSize(0), mMaxSize(MB(DEFAULT_TEXT_LAYOUT_CACHE_SIZE_IN_MB)),
        mCacheHitCount(0), mNanosecondsSaved(0) {
    init();
}

TextLayoutCache::TextLayoutCache(uint32_t max):
        mCache(GenerationCache<TextLayoutCacheKey, sp<TextLayoutCacheValue> >::kUnlimitedCapacity),
        mSize(0), mMaxSize(max),
        mCacheHitCount(0), mNanosecondsSaved(0) {
    init();
}

TextLayoutCache::~TextLayoutCache() {
    mCache.clear();
}

void TextLayoutCache::init() {
    mCache.setOnEntryRemovedListener(this);

    mDebugLevel = readRtlDebugLevel();
    mDebugEnabled = mDebugLevel & kRtlDebugCaches;
    LOGD("Using TextLayoutCache debug level: %d - Debug Enabled: %d", mDebugLevel, mDebugEnabled);

    mCacheStartTime = systemTime(SYSTEM_TIME_MONOTONIC);
    if (mDebugEnabled) {
        LOGD("TextLayoutCache start time: %lld", mCacheStartTime);
    }
    mInitialized = true;

    if (mDebugEnabled) {
#if RTL_USE_HARFBUZZ
        LOGD("TextLayoutCache is using HARFBUZZ");
#else
        LOGD("TextLayoutCache is using ICU");
#endif
    }

    if (mDebugEnabled) {
        LOGD("TextLayoutCache initialization is done");
    }
}

/*
 * Size management
 */

uint32_t TextLayoutCache::getSize() {
    return mSize;
}

uint32_t TextLayoutCache::getMaxSize() {
    return mMaxSize;
}

void TextLayoutCache::setMaxSize(uint32_t maxSize) {
    mMaxSize = maxSize;
    removeOldests();
}

void TextLayoutCache::removeOldests() {
    while (mSize > mMaxSize) {
        mCache.removeOldest();
    }
}

/**
 *  Callbacks
 */
void TextLayoutCache::operator()(TextLayoutCacheKey& text, sp<TextLayoutCacheValue>& desc) {
    if (desc != NULL) {
        size_t totalSizeToDelete = text.getSize() + desc->getSize();
        mSize -= totalSizeToDelete;
        if (mDebugEnabled) {
            LOGD("Cache value deleted, size = %d", totalSizeToDelete);
        }
        desc.clear();
    }
}

/*
 * Cache clearing
 */
void TextLayoutCache::clear() {
    mCache.clear();
}

/*
 * Caching
 */
sp<TextLayoutCacheValue> TextLayoutCache::getValue(SkPaint* paint,
            const jchar* text, jint start, jint count, jint contextCount, jint dirFlags) {
    AutoMutex _l(mLock);
    nsecs_t startTime = 0;
    if (mDebugEnabled) {
        startTime = systemTime(SYSTEM_TIME_MONOTONIC);
    }

    // Create the key
    TextLayoutCacheKey key(paint, text, start, count, contextCount, dirFlags);

    // Get value from cache if possible
    sp<TextLayoutCacheValue> value = mCache.get(key);

    // Value not found for the key, we need to add a new value in the cache
    if (value == NULL) {
        if (mDebugEnabled) {
            startTime = systemTime(SYSTEM_TIME_MONOTONIC);
        }

        value = new TextLayoutCacheValue();

        // Compute advances and store them
        value->computeValues(paint, text, start, count, contextCount, dirFlags);

        nsecs_t endTime = systemTime(SYSTEM_TIME_MONOTONIC);

        // Don't bother to add in the cache if the entry is too big
        size_t size = key.getSize() + value->getSize();
        if (size <= mMaxSize) {
            // Cleanup to make some room if needed
            if (mSize + size > mMaxSize) {
                if (mDebugEnabled) {
                    LOGD("TextLayoutCache: need to clean some entries "
                            "for making some room for a new entry");
                }
                while (mSize + size > mMaxSize) {
                    // This will call the callback
                    mCache.removeOldest();
                }
            }

            // Update current cache size
            mSize += size;

            // Copy the text when we insert the new entry
            key.internalTextCopy();
            mCache.put(key, value);

            if (mDebugEnabled) {
                // Update timing information for statistics
                value->setElapsedTime(endTime - startTime);

                LOGD("CACHE MISS: Added entry for text='%s' with start=%d, count=%d, "
                        "contextCount=%d, entry size %d bytes, remaining space %d bytes"
                        " - Compute time in nanos: %d",
                        String8(text, contextCount).string(), start, count, contextCount,
                        size, mMaxSize - mSize, value->getElapsedTime());
            }
        } else {
            if (mDebugEnabled) {
                LOGD("CACHE MISS: Calculated but not storing entry because it is too big "
                        "for text='%s' with start=%d, count=%d, contextCount=%d, "
                        "entry size %d bytes, remaining space %d bytes"
                        " - Compute time in nanos: %lld",
                        String8(text, contextCount).string(), start, count, contextCount,
                        size, mMaxSize - mSize, endTime);
            }
            value.clear();
        }
    } else {
        // This is a cache hit, just log timestamp and user infos
        if (mDebugEnabled) {
            nsecs_t elapsedTimeThruCacheGet = systemTime(SYSTEM_TIME_MONOTONIC) - startTime;
            mNanosecondsSaved += (value->getElapsedTime() - elapsedTimeThruCacheGet);
            ++mCacheHitCount;

            if (value->getElapsedTime() > 0) {
                float deltaPercent = 100 * ((value->getElapsedTime() - elapsedTimeThruCacheGet)
                        / ((float)value->getElapsedTime()));
                LOGD("CACHE HIT #%d for text='%s' with start=%d, count=%d, contextCount=%d "
                        "- Compute time in nanos: %d - "
                        "Cache get time in nanos: %lld - Gain in percent: %2.2f",
                        mCacheHitCount, String8(text, contextCount).string(), start, count,
                        contextCount,
                        value->getElapsedTime(), elapsedTimeThruCacheGet, deltaPercent);
            }
            if (mCacheHitCount % DEFAULT_DUMP_STATS_CACHE_HIT_INTERVAL == 0) {
                dumpCacheStats();
            }
        }
    }
    return value;
}

void TextLayoutCache::dumpCacheStats() {
    float remainingPercent = 100 * ((mMaxSize - mSize) / ((float)mMaxSize));
    float timeRunningInSec = (systemTime(SYSTEM_TIME_MONOTONIC) - mCacheStartTime) / 1000000000;
    LOGD("------------------------------------------------");
    LOGD("TextLayoutCache stats");
    LOGD("------------------------------------------------");
    LOGD("pid       : %d", getpid());
    LOGD("running   : %.0f seconds", timeRunningInSec);
    LOGD("entries   : %d", mCache.size());
    LOGD("size      : %d bytes", mMaxSize);
    LOGD("remaining : %d bytes or %2.2f percent", mMaxSize - mSize, remainingPercent);
    LOGD("hits      : %d", mCacheHitCount);
    LOGD("saved     : %lld milliseconds", mNanosecondsSaved / 1000000);
    LOGD("------------------------------------------------");
}

/**
 * TextLayoutCacheKey
 */
TextLayoutCacheKey::TextLayoutCacheKey(): text(NULL), start(0), count(0), contextCount(0),
        dirFlags(0), typeface(NULL), textSize(0), textSkewX(0), textScaleX(0), flags(0),
        hinting(SkPaint::kNo_Hinting)  {
}

TextLayoutCacheKey::TextLayoutCacheKey(const SkPaint* paint,
        const UChar* text, size_t start, size_t count,
        size_t contextCount, int dirFlags) :
            text(text), start(start), count(count), contextCount(contextCount),
            dirFlags(dirFlags) {
    typeface = paint->getTypeface();
    textSize = paint->getTextSize();
    textSkewX = paint->getTextSkewX();
    textScaleX = paint->getTextScaleX();
    flags = paint->getFlags();
    hinting = paint->getHinting();
}

bool TextLayoutCacheKey::operator<(const TextLayoutCacheKey& rhs) const {
    LTE_INT(count) {
        LTE_INT(contextCount) {
            LTE_INT(start) {
                LTE_INT(typeface) {
                    LTE_FLOAT(textSize) {
                        LTE_FLOAT(textSkewX) {
                            LTE_FLOAT(textScaleX) {
                                LTE_INT(flags) {
                                    LTE_INT(hinting) {
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
        }
    }
    return false;
}

void TextLayoutCacheKey::internalTextCopy() {
    textCopy.setTo(text, contextCount);
    text = textCopy.string();
}

size_t TextLayoutCacheKey::getSize() {
    return sizeof(TextLayoutCacheKey) + sizeof(UChar) * contextCount;
}

/**
 * TextLayoutCacheValue
 */
TextLayoutCacheValue::TextLayoutCacheValue() :
        mAdvances(NULL), mTotalAdvance(0), mAdvancesCount(0),
        mGlyphs(NULL), mGlyphsCount(0), mElapsedTime(0) {
}

TextLayoutCacheValue::~TextLayoutCacheValue() {
    delete[] mAdvances;
    delete[] mGlyphs;
}

void TextLayoutCacheValue::setElapsedTime(uint32_t time) {
    mElapsedTime = time;
}

uint32_t TextLayoutCacheValue::getElapsedTime() {
    return mElapsedTime;
}

void TextLayoutCacheValue::computeValues(SkPaint* paint, const UChar* chars, size_t start,
        size_t count, size_t contextCount, int dirFlags) {
    mAdvancesCount = count;
    mAdvances = new float[count];

#if RTL_USE_HARFBUZZ
    computeValuesWithHarfbuzz(paint, chars, start, count, contextCount, dirFlags,
            mAdvances, &mTotalAdvance, &mGlyphs, &mGlyphsCount);
#else
    computeAdvancesWithICU(paint, chars, start, count, contextCount, dirFlags,
            mAdvances, &mTotalAdvance);
#endif
#if DEBUG_ADVANCES
    LOGD("Advances - count=%d - countextCount=%d - totalAdvance=%f - "
            "adv[0]=%f adv[1]=%f adv[2]=%f adv[3]=%f", count, contextCount, mTotalAdvance,
            mAdvances[0], mAdvances[1], mAdvances[2], mAdvances[3]);
#endif
}

size_t TextLayoutCacheValue::getSize() {
    return sizeof(TextLayoutCacheValue) + sizeof(jfloat) * mAdvancesCount +
            sizeof(jchar) * mGlyphsCount;
}

void TextLayoutCacheValue::setupShaperItem(HB_ShaperItem* shaperItem, HB_FontRec* font,
        FontData* fontData, SkPaint* paint, const UChar* chars, size_t start, size_t count,
        size_t contextCount, bool isRTL) {
    font->klass = &harfbuzzSkiaClass;
    font->userData = 0;
    // The values which harfbuzzSkiaClass returns are already scaled to
    // pixel units, so we just set all these to one to disable further
    // scaling.
    font->x_ppem = 1;
    font->y_ppem = 1;
    font->x_scale = 1;
    font->y_scale = 1;

    memset(shaperItem, 0, sizeof(*shaperItem));
    shaperItem->font = font;
    shaperItem->face = HB_NewFace(shaperItem->font, harfbuzzSkiaGetTable);

    shaperItem->kerning_applied = false;

    // We cannot know, ahead of time, how many glyphs a given script run
    // will produce. We take a guess that script runs will not produce more
    // than twice as many glyphs as there are code points plus a bit of
    // padding and fallback if we find that we are wrong.
    createGlyphArrays(shaperItem, (contextCount + 2) * 2);

    // Free memory for clusters if needed and recreate the clusters array
    if (shaperItem->log_clusters) {
        delete shaperItem->log_clusters;
    }
    shaperItem->log_clusters = new unsigned short[contextCount];

    shaperItem->item.pos = start;
    shaperItem->item.length = count;
    shaperItem->item.bidiLevel = isRTL;

    shaperItem->item.script = isRTL ? HB_Script_Arabic : HB_Script_Common;

    shaperItem->string = chars;
    shaperItem->stringLength = contextCount;

    fontData->typeFace = paint->getTypeface();
    fontData->textSize = paint->getTextSize();
    fontData->textSkewX = paint->getTextSkewX();
    fontData->textScaleX = paint->getTextScaleX();
    fontData->flags = paint->getFlags();
    fontData->hinting = paint->getHinting();

    shaperItem->font->userData = fontData;
}

void TextLayoutCacheValue::shapeWithHarfbuzz(HB_ShaperItem* shaperItem, HB_FontRec* font,
        FontData* fontData, SkPaint* paint, const UChar* chars, size_t start, size_t count,
        size_t contextCount, bool isRTL) {
    // Setup Harfbuzz Shaper
    setupShaperItem(shaperItem, font, fontData, paint, chars, start, count,
            contextCount, isRTL);

    // Shape
    resetGlyphArrays(shaperItem);
    while (!HB_ShapeItem(shaperItem)) {
        // We overflowed our arrays. Resize and retry.
        // HB_ShapeItem fills in shaperItem.num_glyphs with the needed size.
        deleteGlyphArrays(shaperItem);
        createGlyphArrays(shaperItem, shaperItem->num_glyphs << 1);
        resetGlyphArrays(shaperItem);
    }
}

struct GlyphRun {
    inline GlyphRun() {}
    inline GlyphRun(jchar* glyphs, size_t glyphsCount, bool isRTL) :
            glyphs(glyphs), glyphsCount(glyphsCount), isRTL(isRTL) { }
    jchar* glyphs;
    size_t glyphsCount;
    int isRTL;
};

void static reverseGlyphArray(jchar* glyphs, size_t count) {
    for (size_t i = 0; i < count / 2; i++) {
        jchar temp = glyphs[i];
        glyphs[i] = glyphs[count - 1 - i];
        glyphs[count - 1 - i] = temp;
    }
}

void TextLayoutCacheValue::computeValuesWithHarfbuzz(SkPaint* paint, const UChar* chars,
        size_t start, size_t count, size_t contextCount, int dirFlags,
        jfloat* outAdvances, jfloat* outTotalAdvance,
        jchar** outGlyphs, size_t* outGlyphsCount) {

        UBiDiLevel bidiReq = 0;
        bool forceLTR = false;
        bool forceRTL = false;

        switch (dirFlags) {
            case kBidi_LTR: bidiReq = 0; break; // no ICU constant, canonical LTR level
            case kBidi_RTL: bidiReq = 1; break; // no ICU constant, canonical RTL level
            case kBidi_Default_LTR: bidiReq = UBIDI_DEFAULT_LTR; break;
            case kBidi_Default_RTL: bidiReq = UBIDI_DEFAULT_RTL; break;
            case kBidi_Force_LTR: forceLTR = true; break; // every char is LTR
            case kBidi_Force_RTL: forceRTL = true; break; // every char is RTL
        }

        if (forceLTR || forceRTL) {
#if DEBUG_GLYPHS
                    LOGD("computeValuesWithHarfbuzz -- forcing run with LTR=%d RTL=%d",
                            forceLTR, forceRTL);
#endif
            computeRunValuesWithHarfbuzz(paint, chars, start, count, contextCount, forceRTL,
                    outAdvances, outTotalAdvance, outGlyphs, outGlyphsCount);

            if (forceRTL && *outGlyphsCount > 1) {
                reverseGlyphArray(*outGlyphs, *outGlyphsCount);
            }
        } else {
            UBiDi* bidi = ubidi_open();
            if (bidi) {
                UErrorCode status = U_ZERO_ERROR;
#if DEBUG_GLYPHS
                LOGD("computeValuesWithHarfbuzz -- bidiReq=%d", bidiReq);
#endif
                ubidi_setPara(bidi, chars, contextCount, bidiReq, NULL, &status);
                if (U_SUCCESS(status)) {
                    int paraDir = ubidi_getParaLevel(bidi) & kDirection_Mask; // 0 if ltr, 1 if rtl
                    size_t rc = ubidi_countRuns(bidi, &status);
#if DEBUG_GLYPHS
                    LOGD("computeValuesWithHarfbuzz -- dirFlags=%d run-count=%d paraDir=%d", dirFlags, rc, paraDir);
#endif
                    if (rc == 1 || !U_SUCCESS(status)) {
                        bool isRTL = (paraDir == 1);
#if DEBUG_GLYPHS
                        LOGD("computeValuesWithHarfbuzz -- processing SINGLE run "
                                "-- run-start=%d run-len=%d isRTL=%d", start, count, isRTL);
#endif
                        computeRunValuesWithHarfbuzz(paint, chars, start, count, contextCount,
                                isRTL, outAdvances, outTotalAdvance, outGlyphs, outGlyphsCount);

                        if (isRTL && *outGlyphsCount > 1) {
                            reverseGlyphArray(*outGlyphs, *outGlyphsCount);
                        }
                    } else {
                        Vector<GlyphRun> glyphRuns;
                        jchar* runGlyphs;
                        size_t runGlyphsCount = 0;
                        int32_t end = start + count;
                        for (size_t i = 0; i < rc; ++i) {
                            int32_t startRun;
                            int32_t lengthRun;
                            UBiDiDirection runDir = ubidi_getVisualRun(bidi, i, &startRun, &lengthRun);

                            if (startRun >= end) {
                              break;
                            }

                            int32_t endRun = startRun + lengthRun;
                            if (endRun <= start) {
                              continue;
                            }

                            if (startRun < start) {
                              startRun = start;
                            }
                            if (endRun > end) {
                              endRun = end;
                            }

                            lengthRun = endRun - startRun;

                            bool isRTL = (runDir == UBIDI_RTL);
                            jfloat runTotalAdvance = 0;
#if DEBUG_GLYPHS
                            LOGD("computeValuesWithHarfbuzz -- run-start=%d run-len=%d isRTL=%d",
                                    startRun, lengthRun, isRTL);
#endif
                            computeRunValuesWithHarfbuzz(paint, chars, startRun,
                                    lengthRun, contextCount, isRTL,
                                    outAdvances, &runTotalAdvance,
                                    &runGlyphs, &runGlyphsCount);

                            outAdvances += lengthRun;
                            *outTotalAdvance += runTotalAdvance;
                            *outGlyphsCount += runGlyphsCount;
#if DEBUG_GLYPHS
                            LOGD("computeValuesWithHarfbuzz -- run=%d run-glyphs-count=%d",
                                    i, runGlyphsCount);
                            for (size_t j = 0; j < runGlyphsCount; j++) {
                                LOGD("                          -- glyphs[%d]=%d", j, runGlyphs[j]);
                            }
#endif
                            glyphRuns.push(GlyphRun(runGlyphs, runGlyphsCount, isRTL));
                        }
                        *outGlyphs = new jchar[*outGlyphsCount];

                        jchar* glyphs = *outGlyphs;
                        for (size_t i = 0; i < glyphRuns.size(); i++) {
                            const GlyphRun& glyphRun = glyphRuns.itemAt(i);
                            if (glyphRun.isRTL) {
                                for (size_t n = 0; n < glyphRun.glyphsCount; n++) {
                                    glyphs[glyphRun.glyphsCount - n - 1] = glyphRun.glyphs[n];
                                }
                            } else {
                                memcpy(glyphs, glyphRun.glyphs, glyphRun.glyphsCount * sizeof(jchar));
                            }
                            glyphs += glyphRun.glyphsCount;
                            delete[] glyphRun.glyphs;
                        }
                    }
                }
                ubidi_close(bidi);
            } else {
                // Cannot run BiDi, just consider one Run
                bool isRTL = (bidiReq = 1) || (bidiReq = UBIDI_DEFAULT_RTL);
#if DEBUG_GLYPHS
                LOGD("computeValuesWithHarfbuzz -- cannot run BiDi, considering a SINGLE Run "
                        "-- run-start=%d run-len=%d isRTL=%d", start, count, isRTL);
#endif
                computeRunValuesWithHarfbuzz(paint, chars, start, count, contextCount, isRTL,
                        outAdvances, outTotalAdvance, outGlyphs, outGlyphsCount);

                if (isRTL && *outGlyphsCount > 1) {
                    reverseGlyphArray(*outGlyphs, *outGlyphsCount);
                }
            }
        }
#if DEBUG_GLYPHS
        LOGD("computeValuesWithHarfbuzz -- total-glyphs-count=%d", *outGlyphsCount);
#endif
}

static void logGlyphs(HB_ShaperItem shaperItem) {
    LOGD("Got glyphs - count=%d", shaperItem.num_glyphs);
    for (size_t i = 0; i < shaperItem.num_glyphs; i++) {
        LOGD("      glyphs[%d]=%d - offset.x=%f offset.y=%f", i, shaperItem.glyphs[i],
                HBFixedToFloat(shaperItem.offsets[i].x),
                HBFixedToFloat(shaperItem.offsets[i].y));
    }
}

void TextLayoutCacheValue::computeRunValuesWithHarfbuzz(SkPaint* paint, const UChar* chars,
        size_t start, size_t count, size_t contextCount, bool isRTL,
        jfloat* outAdvances, jfloat* outTotalAdvance,
        jchar** outGlyphs, size_t* outGlyphsCount) {

    HB_ShaperItem shaperItem;
    HB_FontRec font;
    FontData fontData;
    shapeWithHarfbuzz(&shaperItem, &font, &fontData, paint, chars, start, count,
            contextCount, isRTL);

#if DEBUG_GLYPHS
    LOGD("HARFBUZZ -- num_glypth=%d - kerning_applied=%d", shaperItem.num_glyphs,
            shaperItem.kerning_applied);
    LOGD("         -- string= '%s'", String8(chars + start, count).string());
    LOGD("         -- isDevKernText=%d", paint->isDevKernText());

    logGlyphs(shaperItem);
#endif

    if (shaperItem.advances == NULL || shaperItem.num_glyphs == 0) {
#if DEBUG_GLYPHS
    LOGD("HARFBUZZ -- advances array is empty or num_glypth = 0");
#endif
        for (size_t i = 0; i < count; i++) {
            outAdvances[i] = 0;
        }
        *outTotalAdvance = 0;

        if (outGlyphs) {
            *outGlyphsCount = 0;
            *outGlyphs = new jchar[0];
        }

        // Cleaning
        deleteGlyphArrays(&shaperItem);
        HB_FreeFace(shaperItem.face);
        return;
    }
    // Get Advances and their total
    jfloat totalAdvance = outAdvances[0] = HBFixedToFloat(shaperItem.advances[shaperItem.log_clusters[0]]);
    for (size_t i = 1; i < count; i++) {
        size_t clusterPrevious = shaperItem.log_clusters[i - 1];
        size_t cluster = shaperItem.log_clusters[i];
        if (cluster == clusterPrevious) {
            outAdvances[i] = 0;
        } else {
            totalAdvance += outAdvances[i] = HBFixedToFloat(shaperItem.advances[shaperItem.log_clusters[i]]);
        }
    }
    *outTotalAdvance = totalAdvance;

#if DEBUG_ADVANCES
    for (size_t i = 0; i < count; i++) {
        LOGD("hb-adv[%d] = %f - log_clusters = %d - total = %f", i,
                outAdvances[i], shaperItem.log_clusters[i], totalAdvance);
    }
#endif

    // Get Glyphs
    if (outGlyphs) {
        *outGlyphsCount = shaperItem.num_glyphs;
        *outGlyphs = new jchar[shaperItem.num_glyphs];
        for (size_t i = 0; i < shaperItem.num_glyphs; i++) {
            (*outGlyphs)[i] = (jchar) shaperItem.glyphs[i];
        }
    }

    // Cleaning
    deleteGlyphArrays(&shaperItem);
    HB_FreeFace(shaperItem.face);
}

void TextLayoutCacheValue::computeAdvancesWithICU(SkPaint* paint, const UChar* chars,
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

void TextLayoutCacheValue::deleteGlyphArrays(HB_ShaperItem* shaperItem) {
    delete[] shaperItem->glyphs;
    delete[] shaperItem->attributes;
    delete[] shaperItem->advances;
    delete[] shaperItem->offsets;
}

void TextLayoutCacheValue::createGlyphArrays(HB_ShaperItem* shaperItem, int size) {
    shaperItem->glyphs = new HB_Glyph[size];
    shaperItem->attributes = new HB_GlyphAttributes[size];
    shaperItem->advances = new HB_Fixed[size];
    shaperItem->offsets = new HB_FixedPoint[size];
    shaperItem->num_glyphs = size;
}

void TextLayoutCacheValue::resetGlyphArrays(HB_ShaperItem* shaperItem) {
    int size = shaperItem->num_glyphs;
    // All the types here don't have pointers. It is safe to reset to
    // zero unless Harfbuzz breaks the compatibility in the future.
    memset(shaperItem->glyphs, 0, size * sizeof(shaperItem->glyphs[0]));
    memset(shaperItem->attributes, 0, size * sizeof(shaperItem->attributes[0]));
    memset(shaperItem->advances, 0, size * sizeof(shaperItem->advances[0]));
    memset(shaperItem->offsets, 0, size * sizeof(shaperItem->offsets[0]));
}


} // namespace android
