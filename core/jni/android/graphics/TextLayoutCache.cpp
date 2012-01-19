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

#define LOG_TAG "TextLayoutCache"

#include "TextLayoutCache.h"
#include "TextLayout.h"

extern "C" {
  #include "harfbuzz-unicode.h"
}

namespace android {

//--------------------------------------------------------------------------------------------------
#if USE_TEXT_LAYOUT_CACHE
    ANDROID_SINGLETON_STATIC_INSTANCE(TextLayoutCache);
#endif
//--------------------------------------------------------------------------------------------------

TextLayoutCache::TextLayoutCache() :
        mCache(GenerationCache<TextLayoutCacheKey, sp<TextLayoutCacheValue> >::kUnlimitedCapacity),
        mSize(0), mMaxSize(MB(DEFAULT_TEXT_LAYOUT_CACHE_SIZE_IN_MB)),
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
    ALOGD("Using debug level: %d - Debug Enabled: %d", mDebugLevel, mDebugEnabled);

    mCacheStartTime = systemTime(SYSTEM_TIME_MONOTONIC);

    if (mDebugEnabled) {
        ALOGD("Initialization is done - Start time: %lld", mCacheStartTime);
    }

    mInitialized = true;
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
            ALOGD("Cache value deleted, size = %d", totalSizeToDelete);
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
                    ALOGD("Need to clean some entries for making some room for a new entry");
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

                ALOGD("CACHE MISS: Added entry with "
                        "count=%d, entry size %d bytes, remaining space %d bytes"
                        " - Compute time in nanos: %d - Text='%s' ",
                        count, size, mMaxSize - mSize, value->getElapsedTime(),
                        String8(text, count).string());
            }
        } else {
            if (mDebugEnabled) {
                ALOGD("CACHE MISS: Calculated but not storing entry because it is too big "
                        "with start=%d count=%d contextCount=%d, "
                        "entry size %d bytes, remaining space %d bytes"
                        " - Compute time in nanos: %lld - Text='%s'",
                        start, count, contextCount, size, mMaxSize - mSize, endTime,
                        String8(text, count).string());
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
                ALOGD("CACHE HIT #%d with start=%d count=%d contextCount=%d"
                        "- Compute time in nanos: %d - "
                        "Cache get time in nanos: %lld - Gain in percent: %2.2f - Text='%s' ",
                        mCacheHitCount, start, count, contextCount,
                        value->getElapsedTime(), elapsedTimeThruCacheGet, deltaPercent,
                        String8(text, count).string());
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
    ALOGD("------------------------------------------------");
    ALOGD("Cache stats");
    ALOGD("------------------------------------------------");
    ALOGD("pid       : %d", getpid());
    ALOGD("running   : %.0f seconds", timeRunningInSec);
    ALOGD("entries   : %d", mCache.size());
    ALOGD("size      : %d bytes", mMaxSize);
    ALOGD("remaining : %d bytes or %2.2f percent", mMaxSize - mSize, remainingPercent);
    ALOGD("hits      : %d", mCacheHitCount);
    ALOGD("saved     : %lld milliseconds", mNanosecondsSaved / 1000000);
    ALOGD("------------------------------------------------");
}

/**
 * TextLayoutCacheKey
 */
TextLayoutCacheKey::TextLayoutCacheKey(): text(NULL), start(0), count(0), contextCount(0),
        dirFlags(0), typeface(NULL), textSize(0), textSkewX(0), textScaleX(0), flags(0),
        hinting(SkPaint::kNo_Hinting)  {
}

TextLayoutCacheKey::TextLayoutCacheKey(const SkPaint* paint, const UChar* text,
        size_t start, size_t count, size_t contextCount, int dirFlags) :
            text(text), start(start), count(count), contextCount(contextCount),
            dirFlags(dirFlags) {
    typeface = paint->getTypeface();
    textSize = paint->getTextSize();
    textSkewX = paint->getTextSkewX();
    textScaleX = paint->getTextScaleX();
    flags = paint->getFlags();
    hinting = paint->getHinting();
}

TextLayoutCacheKey::TextLayoutCacheKey(const TextLayoutCacheKey& other) :
        text(NULL),
        textCopy(other.textCopy),
        start(other.start),
        count(other.count),
        contextCount(other.contextCount),
        dirFlags(other.dirFlags),
        typeface(other.typeface),
        textSize(other.textSize),
        textSkewX(other.textSkewX),
        textScaleX(other.textScaleX),
        flags(other.flags),
        hinting(other.hinting) {
    if (other.text) {
        textCopy.setTo(other.text, other.contextCount);
    }
}

int TextLayoutCacheKey::compare(const TextLayoutCacheKey& lhs, const TextLayoutCacheKey& rhs) {
    int deltaInt = lhs.start - rhs.start;
    if (deltaInt != 0) return (deltaInt);

    deltaInt = lhs.count - rhs.count;
    if (deltaInt != 0) return (deltaInt);

    deltaInt = lhs.contextCount - rhs.contextCount;
    if (deltaInt != 0) return (deltaInt);

    if (lhs.typeface < rhs.typeface) return -1;
    if (lhs.typeface > rhs.typeface) return +1;

    if (lhs.textSize < rhs.textSize) return -1;
    if (lhs.textSize > rhs.textSize) return +1;

    if (lhs.textSkewX < rhs.textSkewX) return -1;
    if (lhs.textSkewX > rhs.textSkewX) return +1;

    if (lhs.textScaleX < rhs.textScaleX) return -1;
    if (lhs.textScaleX > rhs.textScaleX) return +1;

    deltaInt = lhs.flags - rhs.flags;
    if (deltaInt != 0) return (deltaInt);

    deltaInt = lhs.hinting - rhs.hinting;
    if (deltaInt != 0) return (deltaInt);

    deltaInt = lhs.dirFlags - rhs.dirFlags;
    if (deltaInt) return (deltaInt);

    return memcmp(lhs.getText(), rhs.getText(), lhs.contextCount * sizeof(UChar));
}

void TextLayoutCacheKey::internalTextCopy() {
    textCopy.setTo(text, contextCount);
    text = NULL;
}

size_t TextLayoutCacheKey::getSize() {
    return sizeof(TextLayoutCacheKey) + sizeof(UChar) * contextCount;
}

/**
 * TextLayoutCacheValue
 */
TextLayoutCacheValue::TextLayoutCacheValue() :
        mTotalAdvance(0), mElapsedTime(0) {
}

void TextLayoutCacheValue::setElapsedTime(uint32_t time) {
    mElapsedTime = time;
}

uint32_t TextLayoutCacheValue::getElapsedTime() {
    return mElapsedTime;
}

void TextLayoutCacheValue::computeValues(SkPaint* paint, const UChar* chars,
        size_t start, size_t count, size_t contextCount, int dirFlags) {
    // Give a hint for advances, glyphs and log clusters vectors size
    mAdvances.setCapacity(contextCount);
    mGlyphs.setCapacity(contextCount);

    computeValuesWithHarfbuzz(paint, chars, start, count, contextCount, dirFlags,
            &mAdvances, &mTotalAdvance, &mGlyphs);
#if DEBUG_ADVANCES
    ALOGD("Advances - start=%d, count=%d, countextCount=%d, totalAdvance=%f", start, count,
            contextCount, mTotalAdvance);
#endif
}

size_t TextLayoutCacheValue::getSize() {
    return sizeof(TextLayoutCacheValue) + sizeof(jfloat) * mAdvances.capacity() +
            sizeof(jchar) * mGlyphs.capacity();
}

void TextLayoutCacheValue::initShaperItem(HB_ShaperItem& shaperItem, HB_FontRec* font,
        FontData* fontData, SkPaint* paint, const UChar* chars, size_t contextCount) {
    // Zero the Shaper struct
    memset(&shaperItem, 0, sizeof(shaperItem));

    font->klass = &harfbuzzSkiaClass;
    font->userData = 0;

    // The values which harfbuzzSkiaClass returns are already scaled to
    // pixel units, so we just set all these to one to disable further
    // scaling.
    font->x_ppem = 1;
    font->y_ppem = 1;
    font->x_scale = 1;
    font->y_scale = 1;

    // Reset kerning
    shaperItem.kerning_applied = false;

    // Define font data
    fontData->typeFace = paint->getTypeface();
    fontData->textSize = paint->getTextSize();
    fontData->textSkewX = paint->getTextSkewX();
    fontData->textScaleX = paint->getTextScaleX();
    fontData->flags = paint->getFlags();
    fontData->hinting = paint->getHinting();

    shaperItem.font = font;
    shaperItem.font->userData = fontData;

    shaperItem.face = HB_NewFace(NULL, harfbuzzSkiaGetTable);

    // We cannot know, ahead of time, how many glyphs a given script run
    // will produce. We take a guess that script runs will not produce more
    // than twice as many glyphs as there are code points plus a bit of
    // padding and fallback if we find that we are wrong.
    createGlyphArrays(shaperItem, (contextCount + 2) * 2);

    // Set the string properties
    shaperItem.string = chars;
    shaperItem.stringLength = contextCount;
}

void TextLayoutCacheValue::freeShaperItem(HB_ShaperItem& shaperItem) {
    deleteGlyphArrays(shaperItem);
    HB_FreeFace(shaperItem.face);
}

void TextLayoutCacheValue::shapeRun(HB_ShaperItem& shaperItem, size_t start, size_t count,
        bool isRTL) {
    // Update Harfbuzz Shaper
    shaperItem.item.pos = start;
    shaperItem.item.length = count;
    shaperItem.item.bidiLevel = isRTL;

    shaperItem.item.script = isRTL ? HB_Script_Arabic : HB_Script_Common;

    // Shape
    assert(shaperItem.item.length > 0); // Harfbuzz will overwrite other memory if length is 0.
    while (!HB_ShapeItem(&shaperItem)) {
        // We overflowed our arrays. Resize and retry.
        // HB_ShapeItem fills in shaperItem.num_glyphs with the needed size.
        deleteGlyphArrays(shaperItem);
        createGlyphArrays(shaperItem, shaperItem.num_glyphs << 1);
    }
}

void TextLayoutCacheValue::computeValuesWithHarfbuzz(SkPaint* paint, const UChar* chars,
        size_t start, size_t count, size_t contextCount, int dirFlags,
        Vector<jfloat>* const outAdvances, jfloat* outTotalAdvance,
        Vector<jchar>* const outGlyphs) {
        if (!count) {
            *outTotalAdvance = 0;
            return;
        }

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

        HB_ShaperItem shaperItem;
        HB_FontRec font;
        FontData fontData;

        // Initialize Harfbuzz Shaper
        initShaperItem(shaperItem, &font, &fontData, paint, chars, contextCount);

        bool useSingleRun = false;
        bool isRTL = forceRTL;
        if (forceLTR || forceRTL) {
            useSingleRun = true;
        } else {
            UBiDi* bidi = ubidi_open();
            if (bidi) {
                UErrorCode status = U_ZERO_ERROR;
#if DEBUG_GLYPHS
                ALOGD("computeValuesWithHarfbuzz -- bidiReq=%d", bidiReq);
#endif
                ubidi_setPara(bidi, chars, contextCount, bidiReq, NULL, &status);
                if (U_SUCCESS(status)) {
                    int paraDir = ubidi_getParaLevel(bidi) & kDirection_Mask; // 0 if ltr, 1 if rtl
                    ssize_t rc = ubidi_countRuns(bidi, &status);
#if DEBUG_GLYPHS
                    ALOGD("computeValuesWithHarfbuzz -- dirFlags=%d run-count=%d paraDir=%d",
                            dirFlags, rc, paraDir);
#endif
                    if (U_SUCCESS(status) && rc == 1) {
                        // Normal case: one run, status is ok
                        isRTL = (paraDir == 1);
                        useSingleRun = true;
                    } else if (!U_SUCCESS(status) || rc < 1) {
                        LOGW("computeValuesWithHarfbuzz -- need to force to single run");
                        isRTL = (paraDir == 1);
                        useSingleRun = true;
                    } else {
                        int32_t end = start + count;
                        for (size_t i = 0; i < size_t(rc); ++i) {
                            int32_t startRun = -1;
                            int32_t lengthRun = -1;
                            UBiDiDirection runDir = ubidi_getVisualRun(bidi, i, &startRun, &lengthRun);

                            if (startRun == -1 || lengthRun == -1) {
                                // Something went wrong when getting the visual run, need to clear
                                // already computed data before doing a single run pass
                                LOGW("computeValuesWithHarfbuzz -- visual run is not valid");
                                outGlyphs->clear();
                                outAdvances->clear();
                                *outTotalAdvance = 0;
                                isRTL = (paraDir == 1);
                                useSingleRun = true;
                                break;
                            }

                            if (startRun >= end) {
                                continue;
                            }
                            int32_t endRun = startRun + lengthRun;
                            if (endRun <= int32_t(start)) {
                                continue;
                            }
                            if (startRun < int32_t(start)) {
                                startRun = int32_t(start);
                            }
                            if (endRun > end) {
                                endRun = end;
                            }

                            lengthRun = endRun - startRun;
                            isRTL = (runDir == UBIDI_RTL);
                            jfloat runTotalAdvance = 0;
#if DEBUG_GLYPHS
                            ALOGD("computeValuesWithHarfbuzz -- run-start=%d run-len=%d isRTL=%d",
                                    startRun, lengthRun, isRTL);
#endif
                            computeRunValuesWithHarfbuzz(shaperItem, paint,
                                    startRun, lengthRun, isRTL,
                                    outAdvances, &runTotalAdvance, outGlyphs);

                            *outTotalAdvance += runTotalAdvance;
                        }
                    }
                } else {
                    LOGW("computeValuesWithHarfbuzz -- cannot set Para");
                    useSingleRun = true;
                    isRTL = (bidiReq = 1) || (bidiReq = UBIDI_DEFAULT_RTL);
                }
                ubidi_close(bidi);
            } else {
                LOGW("computeValuesWithHarfbuzz -- cannot ubidi_open()");
                useSingleRun = true;
                isRTL = (bidiReq = 1) || (bidiReq = UBIDI_DEFAULT_RTL);
            }
        }

        // Default single run case
        if (useSingleRun){
#if DEBUG_GLYPHS
            ALOGD("computeValuesWithHarfbuzz -- Using a SINGLE Run "
                    "-- run-start=%d run-len=%d isRTL=%d", start, count, isRTL);
#endif
            computeRunValuesWithHarfbuzz(shaperItem, paint,
                    start, count, isRTL,
                    outAdvances, outTotalAdvance, outGlyphs);
        }

        // Cleaning
        freeShaperItem(shaperItem);

#if DEBUG_GLYPHS
        ALOGD("computeValuesWithHarfbuzz -- total-glyphs-count=%d", outGlyphs->size());
#endif
}

static void logGlyphs(HB_ShaperItem shaperItem) {
    ALOGD("Got glyphs - count=%d", shaperItem.num_glyphs);
    for (size_t i = 0; i < shaperItem.num_glyphs; i++) {
        ALOGD("      glyph[%d]=%d - offset.x=%f offset.y=%f", i, shaperItem.glyphs[i],
                HBFixedToFloat(shaperItem.offsets[i].x),
                HBFixedToFloat(shaperItem.offsets[i].y));
    }
}

void TextLayoutCacheValue::computeRunValuesWithHarfbuzz(HB_ShaperItem& shaperItem, SkPaint* paint,
        size_t start, size_t count, bool isRTL,
        Vector<jfloat>* const outAdvances, jfloat* outTotalAdvance,
        Vector<jchar>* const outGlyphs) {
    if (!count) {
        *outTotalAdvance = 0;
        return;
    }

    shapeRun(shaperItem, start, count, isRTL);

#if DEBUG_GLYPHS
    ALOGD("HARFBUZZ -- num_glypth=%d - kerning_applied=%d", shaperItem.num_glyphs,
            shaperItem.kerning_applied);
    ALOGD("         -- string= '%s'", String8(shaperItem.string + start, count).string());
    ALOGD("         -- isDevKernText=%d", paint->isDevKernText());

    logGlyphs(shaperItem);
#endif

    if (shaperItem.advances == NULL || shaperItem.num_glyphs == 0) {
#if DEBUG_GLYPHS
    ALOGD("HARFBUZZ -- advances array is empty or num_glypth = 0");
#endif
        outAdvances->insertAt(0, outAdvances->size(), count);
        *outTotalAdvance = 0;
        return;
    }

    // Get Advances and their total
    jfloat currentAdvance = HBFixedToFloat(shaperItem.advances[shaperItem.log_clusters[0]]);
    jfloat totalAdvance = currentAdvance;
    outAdvances->add(currentAdvance);
    for (size_t i = 1; i < count; i++) {
        size_t clusterPrevious = shaperItem.log_clusters[i - 1];
        size_t cluster = shaperItem.log_clusters[i];
        if (cluster == clusterPrevious) {
            outAdvances->add(0);
        } else {
            currentAdvance = HBFixedToFloat(shaperItem.advances[shaperItem.log_clusters[i]]);
            totalAdvance += currentAdvance;
            outAdvances->add(currentAdvance);
        }
    }
    *outTotalAdvance = totalAdvance;

#if DEBUG_ADVANCES
    for (size_t i = 0; i < count; i++) {
        ALOGD("hb-adv[%d] = %f - log_clusters = %d - total = %f", i,
                (*outAdvances)[i], shaperItem.log_clusters[i], totalAdvance);
    }
#endif

    // Get Glyphs and reverse them in place if RTL
    if (outGlyphs) {
        size_t countGlyphs = shaperItem.num_glyphs;
        for (size_t i = 0; i < countGlyphs; i++) {
            jchar glyph = (jchar) shaperItem.glyphs[(!isRTL) ? i : countGlyphs - 1 - i];
#if DEBUG_GLYPHS
            ALOGD("HARFBUZZ  -- glyph[%d]=%d", i, glyph);
#endif
            outGlyphs->add(glyph);
        }
    }
}

void TextLayoutCacheValue::deleteGlyphArrays(HB_ShaperItem& shaperItem) {
    delete[] shaperItem.glyphs;
    delete[] shaperItem.attributes;
    delete[] shaperItem.advances;
    delete[] shaperItem.offsets;
    delete[] shaperItem.log_clusters;
}

void TextLayoutCacheValue::createGlyphArrays(HB_ShaperItem& shaperItem, int size) {
    shaperItem.num_glyphs = size;

    // These arrays are all indexed by glyph
    shaperItem.glyphs = new HB_Glyph[size];
    shaperItem.attributes = new HB_GlyphAttributes[size];
    shaperItem.advances = new HB_Fixed[size];
    shaperItem.offsets = new HB_FixedPoint[size];

    // Although the log_clusters array is indexed by character, Harfbuzz expects that
    // it is big enough to hold one element per glyph.  So we allocate log_clusters along
    // with the other glyph arrays above.
    shaperItem.log_clusters = new unsigned short[size];
}

} // namespace android
