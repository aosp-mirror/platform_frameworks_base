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

#include <stddef.h>
#include <utils/threads.h>
#include <utils/String16.h>
#include <utils/GenerationCache.h>
#include <utils/KeyedVector.h>
#include <utils/Compare.h>
#include <utils/RefBase.h>
#include <utils/Singleton.h>

#include <SkPaint.h>
#include <SkTemplates.h>
#include <SkUtils.h>
#include <SkAutoKern.h>

#include <unicode/ubidi.h>
#include <unicode/ushape.h>
#include <unicode/unistr.h>

#include "HarfbuzzSkia.h"
#include "harfbuzz-shaper.h"

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
#define DEFAULT_TEXT_LAYOUT_CACHE_SIZE_IN_MB 0.250f

// Define the interval in number of cache hits between two statistics dump
#define DEFAULT_DUMP_STATS_CACHE_HIT_INTERVAL 100

namespace android {

/**
 * TextLayoutCacheKey is the Cache key
 */
class TextLayoutCacheKey {
public:
    TextLayoutCacheKey();

    TextLayoutCacheKey(const SkPaint* paint, const UChar* text, size_t start, size_t count,
            size_t contextCount, int dirFlags);

    TextLayoutCacheKey(const TextLayoutCacheKey& other);

    /**
     * We need to copy the text when we insert the key into the cache itself.
     * We don't need to copy the text when we are only comparing keys.
     */
    void internalTextCopy();

    /**
     * Get the size of the Cache key.
     */
    size_t getSize() const;

    static int compare(const TextLayoutCacheKey& lhs, const TextLayoutCacheKey& rhs);

private:
    const UChar* text; // if text is NULL, use textCopy
    String16 textCopy;
    size_t start;
    size_t count;
    size_t contextCount;
    int dirFlags;
    SkTypeface* typeface;
    SkScalar textSize;
    SkScalar textSkewX;
    SkScalar textScaleX;
    uint32_t flags;
    SkPaint::Hinting hinting;

    inline const UChar* getText() const { return text ? text : textCopy.string(); }

}; // TextLayoutCacheKey

inline int strictly_order_type(const TextLayoutCacheKey& lhs, const TextLayoutCacheKey& rhs) {
    return TextLayoutCacheKey::compare(lhs, rhs) < 0;
}

inline int compare_type(const TextLayoutCacheKey& lhs, const TextLayoutCacheKey& rhs) {
    return TextLayoutCacheKey::compare(lhs, rhs);
}

/*
 * TextLayoutCacheValue is the Cache value
 */
class TextLayoutCacheValue : public RefBase {
public:
    TextLayoutCacheValue(size_t contextCount);

    void setElapsedTime(uint32_t time);
    uint32_t getElapsedTime();

    inline const jfloat* getAdvances() const { return mAdvances.array(); }
    inline size_t getAdvancesCount() const { return mAdvances.size(); }
    inline jfloat getTotalAdvance() const { return mTotalAdvance; }
    inline const jchar* getGlyphs() const { return mGlyphs.array(); }
    inline size_t getGlyphsCount() const { return mGlyphs.size(); }

    /**
     * Advances vector
     */
    Vector<jfloat> mAdvances;

    /**
     * Total number of advances
     */
    jfloat mTotalAdvance;

    /**
     * Glyphs vector
     */
    Vector<jchar> mGlyphs;

    /**
     * Get the size of the Cache entry
     */
    size_t getSize() const;

private:
    /**
     * Time for computing the values (in milliseconds)
     */
    uint32_t mElapsedTime;

}; // TextLayoutCacheValue

/**
 * Cache of text layout information.
 */
class TextLayoutCache : public OnEntryRemoved<TextLayoutCacheKey, sp<TextLayoutCacheValue> >,
        public Singleton<TextLayoutCache>
{
public:
    TextLayoutCache();

    virtual ~TextLayoutCache();

    bool isInitialized() {
        return mInitialized;
    }

    /**
     * Used as a callback when an entry is removed from the cache
     * Do not invoke directly
     */
    void operator()(TextLayoutCacheKey& text, sp<TextLayoutCacheValue>& desc);

    sp<TextLayoutCacheValue> getValue(SkPaint* paint, const jchar* text, jint start, jint count,
            jint contextCount, jint dirFlags);

    /**
     * Clear the cache
     */
    void clear();

private:
    Mutex mLock;
    bool mInitialized;

    GenerationCache<TextLayoutCacheKey, sp<TextLayoutCacheValue> > mCache;

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
     * Dump Cache statistics
     */
    void dumpCacheStats();

}; // TextLayoutCache

/**
 * The TextLayoutEngine is responsible for shaping with Harfbuzz library
 */
class TextLayoutEngine : public Singleton<TextLayoutEngine> {
public:
    TextLayoutEngine();
    virtual ~TextLayoutEngine();

    void computeValues(TextLayoutCacheValue* value, SkPaint* paint, const UChar* chars,
            size_t start, size_t count, size_t contextCount, int dirFlags);

private:
    /**
     * Harfbuzz shaper item
     */
    HB_ShaperItem mShaperItem;

    /**
     * Harfbuzz font
     */
    HB_FontRec mFontRec;

    /**
     * Skia Paint used for shaping
     */
    SkPaint mShapingPaint;

    /**
     * Skia typefaces cached for shaping
     */
    SkTypeface* mDefaultTypeface;
    SkTypeface* mArabicTypeface;
    SkTypeface* mHebrewRegularTypeface;
    SkTypeface* mHebrewBoldTypeface;
    SkTypeface* mBengaliTypeface;
    SkTypeface* mThaiTypeface;

    /**
     * Cache of Harfbuzz faces
     */
    KeyedVector<SkFontID, HB_Face> mCachedHBFaces;

    /**
     * Cache of glyph array size
     */
    size_t mShaperItemGlyphArraySize;

    /**
     * Buffer for containing the ICU normalized form of a run
     */
    UnicodeString mNormalizedString;

    /**
     * Buffer for normalizing a piece of a run with ICU
     */
    UnicodeString mBuffer;

    size_t shapeFontRun(SkPaint* paint, bool isRTL);

    void computeValues(SkPaint* paint, const UChar* chars,
            size_t start, size_t count, size_t contextCount, int dirFlags,
            Vector<jfloat>* const outAdvances, jfloat* outTotalAdvance,
            Vector<jchar>* const outGlyphs);

    void computeRunValues(SkPaint* paint, const UChar* chars,
            size_t count, bool isRTL,
            Vector<jfloat>* const outAdvances, jfloat* outTotalAdvance,
            Vector<jchar>* const outGlyphs);

    SkTypeface* getCachedTypeface(SkTypeface** typeface, const char path[]);
    HB_Face getCachedHBFace(SkTypeface* typeface);

    void ensureShaperItemGlyphArrays(size_t size);
    void createShaperItemGlyphArrays(size_t size);
    void deleteShaperItemGlyphArrays();

}; // TextLayoutEngine


} // namespace android
#endif /* ANDROID_TEXT_LAYOUT_CACHE_H */

