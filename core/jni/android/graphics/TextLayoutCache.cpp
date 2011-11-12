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
#include "SkFontHost.h"

extern "C" {
  #include "harfbuzz-unicode.h"
}

namespace android {

//--------------------------------------------------------------------------------------------------
#define TYPEFACE_ARABIC "/system/fonts/DroidNaskh-Regular.ttf"
#define TYPE_FACE_HEBREW_REGULAR "/system/fonts/DroidSansHebrew-Regular.ttf"
#define TYPE_FACE_HEBREW_BOLD "/system/fonts/DroidSansHebrew-Bold.ttf"

#if USE_TEXT_LAYOUT_CACHE

    ANDROID_SINGLETON_STATIC_INSTANCE(TextLayoutCache);

    static SkTypeface* gDefaultTypeface = SkFontHost::CreateTypeface(
            NULL, NULL, NULL, 0, SkTypeface::kNormal);

    static SkTypeface* gArabicTypeface = NULL;
    static SkTypeface* gHebrewRegularTypeface = NULL;
    static SkTypeface* gHebrewBoldTypeface = NULL;

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
    LOGD("Using debug level: %d - Debug Enabled: %d", mDebugLevel, mDebugEnabled);

    mCacheStartTime = systemTime(SYSTEM_TIME_MONOTONIC);

    if (mDebugEnabled) {
        LOGD("Initialization is done - Start time: %lld", mCacheStartTime);
    }

    mInitialized = true;
}

/**
 *  Callbacks
 */
void TextLayoutCache::operator()(TextLayoutCacheKey& text, sp<TextLayoutCacheValue>& desc) {
    size_t totalSizeToDelete = text.getSize() + desc->getSize();
    mSize -= totalSizeToDelete;
    if (mDebugEnabled) {
        LOGD("Cache value %p deleted, size = %d", desc.get(), totalSizeToDelete);
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

        if (mDebugEnabled) {
            value->setElapsedTime(systemTime(SYSTEM_TIME_MONOTONIC) - startTime);
        }

        // Don't bother to add in the cache if the entry is too big
        size_t size = key.getSize() + value->getSize();
        if (size <= mMaxSize) {
            // Cleanup to make some room if needed
            if (mSize + size > mMaxSize) {
                if (mDebugEnabled) {
                    LOGD("Need to clean some entries for making some room for a new entry");
                }
                while (mSize + size > mMaxSize) {
                    // This will call the callback
                    bool removedOne = mCache.removeOldest() != NULL;
                    LOG_ALWAYS_FATAL_IF(!removedOne, "The cache is non-empty but we "
                            "failed to remove the oldest entry.  "
                            "mSize=%u, size=%u, mMaxSize=%u, mCache.size()=%u",
                            mSize, size, mMaxSize, mCache.size());
                }
            }

            // Update current cache size
            mSize += size;

            // Copy the text when we insert the new entry
            key.internalTextCopy();

            bool putOne = mCache.put(key, value);
            LOG_ALWAYS_FATAL_IF(!putOne, "Failed to put an entry into the cache.  "
                    "This indicates that the cache already has an entry with the "
                    "same key but it should not since we checked earlier!"
                    " - start=%d count=%d contextCount=%d - Text='%s'",
                    start, count, contextCount, String8(text + start, count).string());

            if (mDebugEnabled) {
                nsecs_t totalTime = systemTime(SYSTEM_TIME_MONOTONIC) - startTime;
                LOGD("CACHE MISS: Added entry %p "
                        "with start=%d count=%d contextCount=%d, "
                        "entry size %d bytes, remaining space %d bytes"
                        " - Compute time %0.6f ms - Put time %0.6f ms - Text='%s'",
                        value.get(), start, count, contextCount, size, mMaxSize - mSize,
                        value->getElapsedTime() * 0.000001f,
                        (totalTime - value->getElapsedTime()) * 0.000001f,
                        String8(text + start, count).string());
            }
        } else {
            if (mDebugEnabled) {
                LOGD("CACHE MISS: Calculated but not storing entry because it is too big "
                        "with start=%d count=%d contextCount=%d, "
                        "entry size %d bytes, remaining space %d bytes"
                        " - Compute time %0.6f ms - Text='%s'",
                        start, count, contextCount, size, mMaxSize - mSize,
                        value->getElapsedTime() * 0.000001f,
                        String8(text + start, count).string());
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
                LOGD("CACHE HIT #%d with start=%d count=%d contextCount=%d"
                        "- Compute time %0.6f ms - "
                        "Cache get time %0.6f ms - Gain in percent: %2.2f - Text='%s' ",
                        mCacheHitCount, start, count, contextCount,
                        value->getElapsedTime() * 0.000001f,
                        elapsedTimeThruCacheGet * 0.000001f,
                        deltaPercent,
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

    size_t bytes = 0;
    size_t cacheSize = mCache.size();
    for (size_t i = 0; i < cacheSize; i++) {
        bytes += mCache.getKeyAt(i).getSize() + mCache.getValueAt(i)->getSize();
    }

    LOGD("------------------------------------------------");
    LOGD("Cache stats");
    LOGD("------------------------------------------------");
    LOGD("pid       : %d", getpid());
    LOGD("running   : %.0f seconds", timeRunningInSec);
    LOGD("entries   : %d", cacheSize);
    LOGD("max size  : %d bytes", mMaxSize);
    LOGD("used      : %d bytes according to mSize, %d bytes actual", mSize, bytes);
    LOGD("remaining : %d bytes or %2.2f percent", mMaxSize - mSize, remainingPercent);
    LOGD("hits      : %d", mCacheHitCount);
    LOGD("saved     : %0.6f ms", mNanosecondsSaved * 0.000001f);
    LOGD("------------------------------------------------");
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

size_t TextLayoutCacheKey::getSize() const {
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
    LOGD("Advances - start=%d, count=%d, contextCount=%d, totalAdvance=%f", start, count,
            contextCount, mTotalAdvance);
#endif
}

size_t TextLayoutCacheValue::getSize() const {
    return sizeof(TextLayoutCacheValue) + sizeof(jfloat) * mAdvances.capacity() +
            sizeof(jchar) * mGlyphs.capacity();
}

void TextLayoutCacheValue::initShaperItem(HB_ShaperItem& shaperItem, HB_FontRec* font,
        FontData* fontData, SkPaint* paint, const UChar* chars, size_t count) {
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
    fontData->textSize = paint->getTextSize();
    fontData->textSkewX = paint->getTextSkewX();
    fontData->textScaleX = paint->getTextScaleX();
    fontData->flags = paint->getFlags();
    fontData->hinting = paint->getHinting();

    shaperItem.font = font;
    shaperItem.font->userData = fontData;

    // We cannot know, ahead of time, how many glyphs a given script run
    // will produce. We take a guess that script runs will not produce more
    // than twice as many glyphs as there are code points plus a bit of
    // padding and fallback if we find that we are wrong.
    createGlyphArrays(shaperItem, (count + 2) * 2);

    // Create log clusters array
    shaperItem.log_clusters = new unsigned short[count];

    // Set the string properties
    shaperItem.string = chars;
    shaperItem.stringLength = count;
}

void TextLayoutCacheValue::freeShaperItem(HB_ShaperItem& shaperItem) {
    deleteGlyphArrays(shaperItem);
    delete[] shaperItem.log_clusters;
    HB_FreeFace(shaperItem.face);
}

unsigned TextLayoutCacheValue::shapeFontRun(HB_ShaperItem& shaperItem, SkPaint* paint,
        size_t count, bool isRTL) {
    // Update Harfbuzz Shaper
    shaperItem.item.pos = 0;
    shaperItem.item.length = count;
    shaperItem.item.bidiLevel = isRTL;

    // Get the glyphs base count for offsetting the glyphIDs returned by Harfbuzz
    // This is needed as the Typeface used for shaping can be not the default one
    // when we are shapping any script that needs to use a fallback Font.
    // If we are a "common" script we dont need to shift
    unsigned result = 0;
    switch(shaperItem.item.script) {
        case HB_Script_Arabic:
        case HB_Script_Hebrew: {
            const uint16_t* text16 = (const uint16_t*)shaperItem.string;
            SkUnichar firstUnichar = SkUTF16_NextUnichar(&text16);
            result = paint->getBaseGlyphCount(firstUnichar);
            break;
        }
        default:
            break;
    }

    // Set the correct Typeface depending on the script
    FontData* data = reinterpret_cast<FontData*>(shaperItem.font->userData);
    switch(shaperItem.item.script) {
        case HB_Script_Arabic:
            data->typeFace = getCachedTypeface(gArabicTypeface, TYPEFACE_ARABIC);
#if DEBUG_GLYPHS
            LOGD("Using Arabic Typeface");
#endif
            break;

        case HB_Script_Hebrew:
            if(paint->getTypeface()) {
                switch(paint->getTypeface()->style()) {
                    case SkTypeface::kNormal:
                    case SkTypeface::kItalic:
                    default:
                        data->typeFace = getCachedTypeface(gHebrewRegularTypeface, TYPE_FACE_HEBREW_REGULAR);
#if DEBUG_GLYPHS
                        LOGD("Using Hebrew Regular/Italic Typeface");
#endif
                        break;
                    case SkTypeface::kBold:
                    case SkTypeface::kBoldItalic:
                        data->typeFace = getCachedTypeface(gHebrewBoldTypeface, TYPE_FACE_HEBREW_BOLD);
#if DEBUG_GLYPHS
                        LOGD("Using Hebrew Bold/BoldItalic Typeface");
#endif
                        break;
                }
            } else {
                data->typeFace = getCachedTypeface(gHebrewRegularTypeface, TYPE_FACE_HEBREW_REGULAR);
#if DEBUG_GLYPHS
                        LOGD("Using Hebrew Regular Typeface");
#endif
            }
            break;

        default:
            if(paint->getTypeface()) {
                data->typeFace = paint->getTypeface();
#if DEBUG_GLYPHS
            LOGD("Using Paint Typeface");
#endif
            } else {
                data->typeFace = gDefaultTypeface;
#if DEBUG_GLYPHS
            LOGD("Using Default Typeface");
#endif
            }
            break;
    }

    shaperItem.face = HB_NewFace(data, harfbuzzSkiaGetTable);

#if DEBUG_GLYPHS
    LOGD("Run typeFace = %p", data->typeFace);
    LOGD("Run typeFace->uniqueID = %d", data->typeFace->uniqueID());
#endif

    // Shape
    while (!HB_ShapeItem(&shaperItem)) {
        // We overflowed our arrays. Resize and retry.
        // HB_ShapeItem fills in shaperItem.num_glyphs with the needed size.
        deleteGlyphArrays(shaperItem);
        createGlyphArrays(shaperItem, shaperItem.num_glyphs << 1);
    }

    return result;
}

SkTypeface* TextLayoutCacheValue::getCachedTypeface(SkTypeface* typeface, const char path[]) {
    if (!typeface) {
        typeface = SkTypeface::CreateFromFile(path);
    }
    return typeface;
}

void TextLayoutCacheValue::computeValuesWithHarfbuzz(SkPaint* paint, const UChar* chars,
        size_t start, size_t count, size_t contextCount, int dirFlags,
        Vector<jfloat>* const outAdvances, jfloat* outTotalAdvance,
        Vector<jchar>* const outGlyphs) {

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
                LOGD("computeValuesWithHarfbuzz -- bidiReq=%d", bidiReq);
#endif
                ubidi_setPara(bidi, chars, contextCount, bidiReq, NULL, &status);
                if (U_SUCCESS(status)) {
                    int paraDir = ubidi_getParaLevel(bidi) & kDirection_Mask; // 0 if ltr, 1 if rtl
                    ssize_t rc = ubidi_countRuns(bidi, &status);
#if DEBUG_GLYPHS
                    LOGD("computeValuesWithHarfbuzz -- dirFlags=%d run-count=%d paraDir=%d",
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
                            LOGD("computeValuesWithHarfbuzz -- run-start=%d run-len=%d isRTL=%d",
                                    startRun, lengthRun, isRTL);
#endif
                            computeRunValuesWithHarfbuzz(paint, chars + startRun, lengthRun, isRTL,
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
            LOGD("computeValuesWithHarfbuzz -- Using a SINGLE Run "
                    "-- run-start=%d run-len=%d isRTL=%d", start, count, isRTL);
#endif
            computeRunValuesWithHarfbuzz(paint, chars + start, count, isRTL,
                    outAdvances, outTotalAdvance, outGlyphs);
        }

#if DEBUG_GLYPHS
        LOGD("computeValuesWithHarfbuzz -- total-glyphs-count=%d", outGlyphs->size());
#endif
}

static void logGlyphs(HB_ShaperItem shaperItem) {
    LOGD("Got glyphs - count=%d", shaperItem.num_glyphs);
    for (size_t i = 0; i < shaperItem.num_glyphs; i++) {
        LOGD("      glyph[%d]=%d - offset.x=%f offset.y=%f", i, shaperItem.glyphs[i],
                HBFixedToFloat(shaperItem.offsets[i].x),
                HBFixedToFloat(shaperItem.offsets[i].y));
    }
}

void TextLayoutCacheValue::computeRunValuesWithHarfbuzz(SkPaint* paint, const UChar* chars,
        size_t count, bool isRTL,
        Vector<jfloat>* const outAdvances, jfloat* outTotalAdvance,
        Vector<jchar>* const outGlyphs) {

    unsigned glyphBaseCount = 0;

    *outTotalAdvance = 0;
    jfloat totalAdvance = 0;

    unsigned numCodePoints = 0;

    ssize_t startFontRun = 0;
    ssize_t endFontRun = 0;
    ssize_t indexFontRun = isRTL ? count - 1 : 0;
    size_t countFontRun = 0;

    HB_ShaperItem shaperItem;
    HB_FontRec font;
    FontData fontData;

    // Zero the Shaper struct
    memset(&shaperItem, 0, sizeof(shaperItem));

    // Split the BiDi run into Script runs. Harfbuzz will populate the script into the shaperItem
    while((isRTL) ?
            hb_utf16_script_run_prev(&numCodePoints, &shaperItem.item, chars,
                    count, &indexFontRun):
            hb_utf16_script_run_next(&numCodePoints, &shaperItem.item, chars,
                    count, &indexFontRun)) {

        startFontRun = shaperItem.item.pos;
        countFontRun = shaperItem.item.length;
        endFontRun = startFontRun + countFontRun;

#if DEBUG_GLYPHS
        LOGD("Shaped Font Run with");
        LOGD("         -- isRTL=%d", isRTL);
        LOGD("         -- HB script=%d", shaperItem.item.script);
        LOGD("         -- startFontRun=%d", startFontRun);
        LOGD("         -- endFontRun=%d", endFontRun);
        LOGD("         -- countFontRun=%d", countFontRun);
        LOGD("         -- run='%s'", String8(chars + startFontRun, countFontRun).string());
        LOGD("         -- string='%s'", String8(chars, count).string());
#endif

        // Initialize Harfbuzz Shaper
        initShaperItem(shaperItem, &font, &fontData, paint, chars + startFontRun, countFontRun);

        // Shape the Font run and get the base glyph count for offsetting the glyphIDs later on
        glyphBaseCount = shapeFontRun(shaperItem, paint, countFontRun, isRTL);

#if DEBUG_GLYPHS
        LOGD("HARFBUZZ -- num_glypth=%d - kerning_applied=%d", shaperItem.num_glyphs,
                shaperItem.kerning_applied);
        LOGD("         -- isDevKernText=%d", paint->isDevKernText());
        LOGD("         -- glyphBaseCount=%d", glyphBaseCount);

        logGlyphs(shaperItem);
#endif
        if (isRTL) {
            endFontRun = startFontRun;
#if DEBUG_GLYPHS
            LOGD("         -- updated endFontRun=%d", endFontRun);
#endif
        } else {
            startFontRun = endFontRun;
#if DEBUG_GLYPHS
            LOGD("         -- updated startFontRun=%d", startFontRun);
#endif
        }

        if (shaperItem.advances == NULL || shaperItem.num_glyphs == 0) {
#if DEBUG_GLYPHS
            LOGD("HARFBUZZ -- advances array is empty or num_glypth = 0");
#endif
            outAdvances->insertAt(0, outAdvances->size(), countFontRun);
            continue;
        }

        // Get Advances and their total
        jfloat currentAdvance = HBFixedToFloat(shaperItem.advances[shaperItem.log_clusters[0]]);
        jfloat totalFontRunAdvance = currentAdvance;
        outAdvances->add(currentAdvance);
        for (size_t i = 1; i < countFontRun; i++) {
            size_t clusterPrevious = shaperItem.log_clusters[i - 1];
            size_t cluster = shaperItem.log_clusters[i];
            if (cluster == clusterPrevious) {
                outAdvances->add(0);
            } else {
                currentAdvance = HBFixedToFloat(shaperItem.advances[shaperItem.log_clusters[i]]);
                totalFontRunAdvance += currentAdvance;
                outAdvances->add(currentAdvance);
            }
        }
        totalAdvance += totalFontRunAdvance;

#if DEBUG_ADVANCES
        for (size_t i = 0; i < countFontRun; i++) {
            LOGD("hb-adv[%d] = %f - log_clusters = %d - total = %f", i,
                    (*outAdvances)[i], shaperItem.log_clusters[i], totalFontRunAdvance);
        }
#endif

        // Get Glyphs and reverse them in place if RTL
        if (outGlyphs) {
            size_t countGlyphs = shaperItem.num_glyphs;
            for (size_t i = 0; i < countGlyphs; i++) {
                jchar glyph = glyphBaseCount +
                        (jchar) shaperItem.glyphs[(!isRTL) ? i : countGlyphs - 1 - i];
#if DEBUG_GLYPHS
                LOGD("HARFBUZZ  -- glyph[%d]=%d", i, glyph);
#endif
                outGlyphs->add(glyph);
            }
        }
        // Cleaning
        freeShaperItem(shaperItem);
    }
    *outTotalAdvance = totalAdvance;
}

void TextLayoutCacheValue::deleteGlyphArrays(HB_ShaperItem& shaperItem) {
    delete[] shaperItem.glyphs;
    delete[] shaperItem.attributes;
    delete[] shaperItem.advances;
    delete[] shaperItem.offsets;
}

void TextLayoutCacheValue::createGlyphArrays(HB_ShaperItem& shaperItem, int size) {
#if DEBUG_GLYPHS
    LOGD("createGlyphArrays  -- size=%d", size);
#endif
    shaperItem.glyphs = new HB_Glyph[size];
    shaperItem.attributes = new HB_GlyphAttributes[size];
    shaperItem.advances = new HB_Fixed[size];
    shaperItem.offsets = new HB_FixedPoint[size];
    shaperItem.num_glyphs = size;
}

} // namespace android
