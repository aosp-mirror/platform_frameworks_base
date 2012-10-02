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
#include <SkLanguage.h>

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
#define DEFAULT_TEXT_LAYOUT_CACHE_SIZE_IN_MB 0.500f

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
     * Get the size of the Cache key.
     */
    size_t getSize() const;

    static int compare(const TextLayoutCacheKey& lhs, const TextLayoutCacheKey& rhs);

    inline const UChar* getText() const { return textCopy.string(); }

private:
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
    SkPaint::FontVariant variant;
    SkLanguage language;

}; // TextLayoutCacheKey

inline int strictly_order_type(const TextLayoutCacheKey& lhs, const TextLayoutCacheKey& rhs) {
    return TextLayoutCacheKey::compare(lhs, rhs) < 0;
}

inline int compare_type(const TextLayoutCacheKey& lhs, const TextLayoutCacheKey& rhs) {
    return TextLayoutCacheKey::compare(lhs, rhs);
}

/*
 * TextLayoutValue is the Cache value
 */
class TextLayoutValue : public RefBase {
public:
    TextLayoutValue(size_t contextCount);

    void setElapsedTime(uint32_t time);
    uint32_t getElapsedTime();

    inline const jfloat* getAdvances() const { return mAdvances.array(); }
    inline size_t getAdvancesCount() const { return mAdvances.size(); }
    inline jfloat getTotalAdvance() const { return mTotalAdvance; }
    inline const jchar* getGlyphs() const { return mGlyphs.array(); }
    inline size_t getGlyphsCount() const { return mGlyphs.size(); }
    inline const jfloat* getPos() const { return mPos.array(); }
    inline size_t getPosCount() const { return mPos.size(); }

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
     * Pos vector (2 * i is x pos, 2 * i + 1 is y pos, same as drawPosText)
     */
    Vector<jfloat> mPos;

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
 * The TextLayoutShaper is responsible for shaping (with the Harfbuzz library)
 */
class TextLayoutShaper {
public:
    TextLayoutShaper();
    virtual ~TextLayoutShaper();

    void computeValues(TextLayoutValue* value, const SkPaint* paint, const UChar* chars,
            size_t start, size_t count, size_t contextCount, int dirFlags);

    void purgeCaches();

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
     * Skia default typeface to be returned if we cannot resolve script
     */
    SkTypeface* mDefaultTypeface;

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

    void init();
    void unrefTypefaces();

    SkTypeface* typefaceForScript(const SkPaint* paint, SkTypeface* typeface,
        HB_Script script);

    size_t shapeFontRun(const SkPaint* paint, bool isRTL);

    void computeValues(const SkPaint* paint, const UChar* chars,
            size_t start, size_t count, size_t contextCount, int dirFlags,
            Vector<jfloat>* const outAdvances, jfloat* outTotalAdvance,
            Vector<jchar>* const outGlyphs, Vector<jfloat>* const outPos);

    void computeRunValues(const SkPaint* paint, const UChar* chars,
            size_t count, bool isRTL,
            Vector<jfloat>* const outAdvances, jfloat* outTotalAdvance,
            Vector<jchar>* const outGlyphs, Vector<jfloat>* const outPos);

    SkTypeface* getCachedTypeface(SkTypeface** typeface, HB_Script script, SkTypeface::Style style);
    HB_Face getCachedHBFace(SkTypeface* typeface);

    bool doShaping(size_t size);
    void createShaperItemGlyphArrays(size_t size);
    void deleteShaperItemGlyphArrays();
    bool isComplexScript(HB_Script script);

}; // TextLayoutShaper

/**
 * Cache of text layout information.
 */
class TextLayoutCache : private OnEntryRemoved<TextLayoutCacheKey, sp<TextLayoutValue> >
{
public:
    TextLayoutCache(TextLayoutShaper* shaper);

    ~TextLayoutCache();

    bool isInitialized() {
        return mInitialized;
    }

    /**
     * Used as a callback when an entry is removed from the cache
     * Do not invoke directly
     */
    void operator()(TextLayoutCacheKey& text, sp<TextLayoutValue>& desc);

    sp<TextLayoutValue> getValue(const SkPaint* paint, const jchar* text, jint start,
            jint count, jint contextCount, jint dirFlags);

    /**
     * Clear the cache
     */
    void purgeCaches();

private:
    TextLayoutShaper* mShaper;
    Mutex mLock;
    bool mInitialized;

    GenerationCache<TextLayoutCacheKey, sp<TextLayoutValue> > mCache;

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
 * The TextLayoutEngine is reponsible for computing TextLayoutValues
 */
class TextLayoutEngine : public Singleton<TextLayoutEngine> {
public:
    TextLayoutEngine();
    virtual ~TextLayoutEngine();

    /**
     * Note: this method currently does a defensive copy of the text argument, in case
     * there is concurrent mutation of it. The contract may change, and may in the
     * future require the caller to guarantee that the contents will not change during
     * the call. Be careful of this when doing optimization.
     **/
    sp<TextLayoutValue> getValue(const SkPaint* paint, const jchar* text, jint start,
            jint count, jint contextCount, jint dirFlags);

    void purgeCaches();

private:
    TextLayoutCache* mTextLayoutCache;
    TextLayoutShaper* mShaper;
}; // TextLayoutEngine

} // namespace android
#endif /* ANDROID_TEXT_LAYOUT_CACHE_H */

