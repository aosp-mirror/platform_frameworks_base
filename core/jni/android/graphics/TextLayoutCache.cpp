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

#include <utils/JenkinsHash.h>

#include "TextLayoutCache.h"
#include "TextLayout.h"
#include "SkGlyphCache.h"
#include "SkTypeface_android.h"
#include "HarfBuzzNGFaceSkia.h"
#include <unicode/unistr.h>
#include <unicode/uchar.h>
#include <hb-icu.h>

namespace android {

//--------------------------------------------------------------------------------------------------

ANDROID_SINGLETON_STATIC_INSTANCE(TextLayoutEngine);

//--------------------------------------------------------------------------------------------------

TextLayoutCache::TextLayoutCache(TextLayoutShaper* shaper) :
        mShaper(shaper),
        mCache(LruCache<TextLayoutCacheKey, sp<TextLayoutValue> >::kUnlimitedCapacity),
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
    ALOGD("Using debug level = %d - Debug Enabled = %d", mDebugLevel, mDebugEnabled);

    mCacheStartTime = systemTime(SYSTEM_TIME_MONOTONIC);

    if (mDebugEnabled) {
        ALOGD("Initialization is done - Start time = %lld", mCacheStartTime);
    }

    mInitialized = true;
}

/**
 *  Callbacks
 */
void TextLayoutCache::operator()(TextLayoutCacheKey& text, sp<TextLayoutValue>& desc) {
    size_t totalSizeToDelete = text.getSize() + desc->getSize();
    mSize -= totalSizeToDelete;
    if (mDebugEnabled) {
        ALOGD("Cache value %p deleted, size = %d", desc.get(), totalSizeToDelete);
    }
}

/*
 * Cache clearing
 */
void TextLayoutCache::purgeCaches() {
    AutoMutex _l(mLock);
    mCache.clear();
    mShaper->purgeCaches();
}

/*
 * Caching
 */
sp<TextLayoutValue> TextLayoutCache::getValue(const SkPaint* paint,
            const jchar* text, jint start, jint count, jint contextCount, jint dirFlags) {
    AutoMutex _l(mLock);
    nsecs_t startTime = 0;
    if (mDebugEnabled) {
        startTime = systemTime(SYSTEM_TIME_MONOTONIC);
    }

    // Create the key
    TextLayoutCacheKey key(paint, text, start, count, contextCount, dirFlags);

    // Get value from cache if possible
    sp<TextLayoutValue> value = mCache.get(key);

    // Value not found for the key, we need to add a new value in the cache
    if (value == NULL) {
        if (mDebugEnabled) {
            startTime = systemTime(SYSTEM_TIME_MONOTONIC);
        }

        value = new TextLayoutValue(contextCount);

        // Compute advances and store them
        mShaper->computeValues(value.get(), paint,
                reinterpret_cast<const UChar*>(key.getText()), start, count,
                size_t(contextCount), int(dirFlags));

        if (mDebugEnabled) {
            value->setElapsedTime(systemTime(SYSTEM_TIME_MONOTONIC) - startTime);
        }

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
                    bool removedOne = mCache.removeOldest();
                    LOG_ALWAYS_FATAL_IF(!removedOne, "The cache is non-empty but we "
                            "failed to remove the oldest entry.  "
                            "mSize = %u, size = %u, mMaxSize = %u, mCache.size() = %u",
                            mSize, size, mMaxSize, mCache.size());
                }
            }

            // Update current cache size
            mSize += size;

            bool putOne = mCache.put(key, value);
            LOG_ALWAYS_FATAL_IF(!putOne, "Failed to put an entry into the cache.  "
                    "This indicates that the cache already has an entry with the "
                    "same key but it should not since we checked earlier!"
                    " - start = %d, count = %d, contextCount = %d - Text = '%s'",
                    start, count, contextCount, String8(key.getText() + start, count).string());

            if (mDebugEnabled) {
                nsecs_t totalTime = systemTime(SYSTEM_TIME_MONOTONIC) - startTime;
                ALOGD("CACHE MISS: Added entry %p "
                        "with start = %d, count = %d, contextCount = %d, "
                        "entry size %d bytes, remaining space %d bytes"
                        " - Compute time %0.6f ms - Put time %0.6f ms - Text = '%s'",
                        value.get(), start, count, contextCount, size, mMaxSize - mSize,
                        value->getElapsedTime() * 0.000001f,
                        (totalTime - value->getElapsedTime()) * 0.000001f,
                        String8(key.getText() + start, count).string());
            }
        } else {
            if (mDebugEnabled) {
                ALOGD("CACHE MISS: Calculated but not storing entry because it is too big "
                        "with start = %d, count = %d, contextCount = %d, "
                        "entry size %d bytes, remaining space %d bytes"
                        " - Compute time %0.6f ms - Text = '%s'",
                        start, count, contextCount, size, mMaxSize - mSize,
                        value->getElapsedTime() * 0.000001f,
                        String8(key.getText() + start, count).string());
            }
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
                ALOGD("CACHE HIT #%d with start = %d, count = %d, contextCount = %d"
                        "- Compute time %0.6f ms - "
                        "Cache get time %0.6f ms - Gain in percent: %2.2f - Text = '%s'",
                        mCacheHitCount, start, count, contextCount,
                        value->getElapsedTime() * 0.000001f,
                        elapsedTimeThruCacheGet * 0.000001f,
                        deltaPercent,
                        String8(key.getText() + start, count).string());
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

    size_t cacheSize = mCache.size();

    ALOGD("------------------------------------------------");
    ALOGD("Cache stats");
    ALOGD("------------------------------------------------");
    ALOGD("pid       : %d", getpid());
    ALOGD("running   : %.0f seconds", timeRunningInSec);
    ALOGD("entries   : %d", cacheSize);
    ALOGD("max size  : %d bytes", mMaxSize);
    ALOGD("used      : %d bytes according to mSize", mSize);
    ALOGD("remaining : %d bytes or %2.2f percent", mMaxSize - mSize, remainingPercent);
    ALOGD("hits      : %d", mCacheHitCount);
    ALOGD("saved     : %0.6f ms", mNanosecondsSaved * 0.000001f);
    ALOGD("------------------------------------------------");
}

/**
 * TextLayoutCacheKey
 */
TextLayoutCacheKey::TextLayoutCacheKey(): start(0), count(0), contextCount(0),
        dirFlags(0), typeface(NULL), textSize(0), textSkewX(0), textScaleX(0), flags(0),
        hinting(SkPaint::kNo_Hinting) {
    paintOpts.setUseFontFallbacks(true);
}

TextLayoutCacheKey::TextLayoutCacheKey(const SkPaint* paint, const UChar* text,
        size_t start, size_t count, size_t contextCount, int dirFlags) :
            start(start), count(count), contextCount(contextCount),
            dirFlags(dirFlags) {
    textCopy.setTo(text, contextCount);
    typeface = paint->getTypeface();
    textSize = paint->getTextSize();
    textSkewX = paint->getTextSkewX();
    textScaleX = paint->getTextScaleX();
    flags = paint->getFlags();
    hinting = paint->getHinting();
    paintOpts = paint->getPaintOptionsAndroid();
}

TextLayoutCacheKey::TextLayoutCacheKey(const TextLayoutCacheKey& other) :
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
        hinting(other.hinting),
        paintOpts(other.paintOpts) {
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

    if (lhs.paintOpts != rhs.paintOpts)
        return memcmp(&lhs.paintOpts, &rhs.paintOpts, sizeof(SkPaintOptionsAndroid));

    return memcmp(lhs.getText(), rhs.getText(), lhs.contextCount * sizeof(UChar));
}

size_t TextLayoutCacheKey::getSize() const {
    return sizeof(TextLayoutCacheKey) + sizeof(UChar) * contextCount;
}

hash_t TextLayoutCacheKey::hash() const {
    uint32_t hash = JenkinsHashMix(0, start);
    hash = JenkinsHashMix(hash, count);
    /* contextCount not needed because it's included in text, below */
    hash = JenkinsHashMix(hash, hash_type(typeface));
    hash = JenkinsHashMix(hash, hash_type(textSize));
    hash = JenkinsHashMix(hash, hash_type(textSkewX));
    hash = JenkinsHashMix(hash, hash_type(textScaleX));
    hash = JenkinsHashMix(hash, flags);
    hash = JenkinsHashMix(hash, hinting);
    hash = JenkinsHashMix(hash, paintOpts.getFontVariant());
    // Note: leaving out language is not problematic, as equality comparisons
    // are still valid - the only bad thing that could happen is collisions.
    hash = JenkinsHashMixShorts(hash, getText(), contextCount);
    return JenkinsHashWhiten(hash);
}

/**
 * TextLayoutCacheValue
 */
TextLayoutValue::TextLayoutValue(size_t contextCount) :
        mTotalAdvance(0), mElapsedTime(0) {
    mBounds.setEmpty();
    // Give a hint for advances and glyphs vectors size
    mAdvances.setCapacity(contextCount);
    mGlyphs.setCapacity(contextCount);
    mPos.setCapacity(contextCount * 2);
}

size_t TextLayoutValue::getSize() const {
    return sizeof(TextLayoutValue) + sizeof(jfloat) * mAdvances.capacity() +
            sizeof(jchar) * mGlyphs.capacity() + sizeof(jfloat) * mPos.capacity();
}

void TextLayoutValue::setElapsedTime(uint32_t time) {
    mElapsedTime = time;
}

uint32_t TextLayoutValue::getElapsedTime() {
    return mElapsedTime;
}

TextLayoutShaper::TextLayoutShaper() {
    mBuffer = hb_buffer_create();
}

TextLayoutShaper::~TextLayoutShaper() {
    hb_buffer_destroy(mBuffer);
}

void TextLayoutShaper::computeValues(TextLayoutValue* value, const SkPaint* paint,
        const UChar* chars, size_t start, size_t count, size_t contextCount, int dirFlags) {
    computeValues(paint, chars, start, count, contextCount, dirFlags,
            &value->mAdvances, &value->mTotalAdvance, &value->mBounds,
            &value->mGlyphs, &value->mPos);
#if DEBUG_ADVANCES
    ALOGD("Advances - start = %d, count = %d, contextCount = %d, totalAdvance = %f", start, count,
            contextCount, value->mTotalAdvance);
#endif
}

void TextLayoutShaper::computeValues(const SkPaint* paint, const UChar* chars,
        size_t start, size_t count, size_t contextCount, int dirFlags,
        Vector<jfloat>* const outAdvances, jfloat* outTotalAdvance, SkRect* outBounds,
        Vector<jchar>* const outGlyphs, Vector<jfloat>* const outPos) {
        *outTotalAdvance = 0;
        if (!count) {
            return;
        }

        UBiDiLevel bidiReq = 0;
        bool forceLTR = false;
        bool forceRTL = false;

        switch (dirFlags & kBidi_Mask) {
            case kBidi_LTR: bidiReq = 0; break; // no ICU constant, canonical LTR level
            case kBidi_RTL: bidiReq = 1; break; // no ICU constant, canonical RTL level
            case kBidi_Default_LTR: bidiReq = UBIDI_DEFAULT_LTR; break;
            case kBidi_Default_RTL: bidiReq = UBIDI_DEFAULT_RTL; break;
            case kBidi_Force_LTR: forceLTR = true; break; // every char is LTR
            case kBidi_Force_RTL: forceRTL = true; break; // every char is RTL
        }

        bool useSingleRun = false;
        bool isRTL = forceRTL;
        if (forceLTR || forceRTL) {
            useSingleRun = true;
        } else {
            UBiDi* bidi = ubidi_open();
            if (bidi) {
                UErrorCode status = U_ZERO_ERROR;
#if DEBUG_GLYPHS
                ALOGD("******** ComputeValues -- start");
                ALOGD("      -- string = '%s'", String8(chars + start, count).string());
                ALOGD("      -- start = %d", start);
                ALOGD("      -- count = %d", count);
                ALOGD("      -- contextCount = %d", contextCount);
                ALOGD("      -- bidiReq = %d", bidiReq);
#endif
                ubidi_setPara(bidi, chars, contextCount, bidiReq, NULL, &status);
                if (U_SUCCESS(status)) {
                    int paraDir = ubidi_getParaLevel(bidi) & kDirection_Mask; // 0 if ltr, 1 if rtl
                    ssize_t rc = ubidi_countRuns(bidi, &status);
#if DEBUG_GLYPHS
                    ALOGD("      -- dirFlags = %d", dirFlags);
                    ALOGD("      -- paraDir = %d", paraDir);
                    ALOGD("      -- run-count = %d", int(rc));
#endif
                    if (U_SUCCESS(status) && rc == 1) {
                        // Normal case: one run, status is ok
                        isRTL = (paraDir == 1);
                        useSingleRun = true;
                    } else if (!U_SUCCESS(status) || rc < 1) {
                        ALOGW("Need to force to single run -- string = '%s',"
                                " status = %d, rc = %d",
                                String8(chars + start, count).string(), status, int(rc));
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
                                ALOGW("Visual run is not valid");
                                outGlyphs->clear();
                                outAdvances->clear();
                                outPos->clear();
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
#if DEBUG_GLYPHS
                            ALOGD("Processing Bidi Run = %d -- run-start = %d, run-len = %d, isRTL = %d",
                                    i, startRun, lengthRun, isRTL);
#endif
                            computeRunValues(paint, chars, startRun, lengthRun, contextCount, isRTL,
                                    outAdvances, outTotalAdvance, outBounds, outGlyphs, outPos);

                        }
                    }
                } else {
                    ALOGW("Cannot set Para");
                    useSingleRun = true;
                    isRTL = (bidiReq = 1) || (bidiReq = UBIDI_DEFAULT_RTL);
                }
                ubidi_close(bidi);
            } else {
                ALOGW("Cannot ubidi_open()");
                useSingleRun = true;
                isRTL = (bidiReq = 1) || (bidiReq = UBIDI_DEFAULT_RTL);
            }
        }

        // Default single run case
        if (useSingleRun){
#if DEBUG_GLYPHS
            ALOGD("Using a SINGLE BiDi Run "
                    "-- run-start = %d, run-len = %d, isRTL = %d", start, count, isRTL);
#endif
            computeRunValues(paint, chars, start, count, contextCount, isRTL,
                    outAdvances, outTotalAdvance, outBounds, outGlyphs, outPos);
        }

#if DEBUG_GLYPHS
        ALOGD("      -- Total returned glyphs-count = %d", outGlyphs->size());
        ALOGD("******** ComputeValues -- end");
#endif
}

#define HB_IsHighSurrogate(ucs) \
    (((ucs) & 0xfc00) == 0xd800)

#define HB_IsLowSurrogate(ucs) \
    (((ucs) & 0xfc00) == 0xdc00)

#ifndef HB_SurrogateToUcs4
#define HB_SurrogateToUcs4_(high, low) \
    (((hb_codepoint_t)(high))<<10) + (low) - 0x35fdc00;
#endif

#define HB_InvalidCodePoint ~0u

hb_codepoint_t
utf16_to_code_point(const uint16_t *chars, size_t len, ssize_t *iter) {
  const uint16_t v = chars[(*iter)++];
  if (HB_IsHighSurrogate(v)) {
    // surrogate pair
    if (size_t(*iter) >= len) {
      // the surrogate is incomplete.
      return HB_InvalidCodePoint;
    }
    const uint16_t v2 = chars[(*iter)++];
    if (!HB_IsLowSurrogate(v2)) {
      // invalidate surrogate pair.
      (*iter)--;
      return HB_InvalidCodePoint;
    }

    return HB_SurrogateToUcs4(v, v2);
  }

  if (HB_IsLowSurrogate(v)) {
    // this isn't a valid code point
    return HB_InvalidCodePoint;
  }

  return v;
}

hb_codepoint_t
utf16_to_code_point_prev(const uint16_t *chars, size_t len, ssize_t *iter) {
  const uint16_t v = chars[(*iter)--];
  if (HB_IsLowSurrogate(v)) {
    // surrogate pair
    if (*iter < 0) {
      // the surrogate is incomplete.
      return HB_InvalidCodePoint;
    }
    const uint16_t v2 = chars[(*iter)--];
    if (!HB_IsHighSurrogate(v2)) {
      // invalidate surrogate pair.
      (*iter)++;
      return HB_InvalidCodePoint;
    }

    return HB_SurrogateToUcs4(v2, v);
  }

  if (HB_IsHighSurrogate(v)) {
    // this isn't a valid code point
    return HB_InvalidCodePoint;
  }

  return v;
}

struct ScriptRun {
    hb_script_t script;
    size_t pos;
    size_t length;
};

hb_script_t code_point_to_script(hb_codepoint_t codepoint) {
    static hb_unicode_funcs_t* u;
    if (!u) {
        u = hb_icu_get_unicode_funcs();
    }
    return hb_unicode_script(u, codepoint);
}

bool
hb_utf16_script_run_next(ScriptRun* run, const uint16_t *chars, size_t len, ssize_t *iter) {
  if (size_t(*iter) == len)
    return false;

  run->pos = *iter;
  const uint32_t init_cp = utf16_to_code_point(chars, len, iter);
  const hb_script_t init_script = code_point_to_script(init_cp);
  hb_script_t current_script = init_script;
  run->script = init_script;

  for (;;) {
    if (size_t(*iter) == len)
      break;
    const ssize_t prev_iter = *iter;
    const uint32_t cp = utf16_to_code_point(chars, len, iter);
    const hb_script_t script = code_point_to_script(cp);

    if (script != current_script) {
        /* BEGIN android-changed
           The condition was not correct by doing "a == b == constant"
           END android-changed */
      if (current_script == HB_SCRIPT_INHERITED && init_script == HB_SCRIPT_INHERITED) {
        // If we started off as inherited, we take whatever we can find.
        run->script = script;
        current_script = script;
        continue;
      } else if (script == HB_SCRIPT_INHERITED) {
        continue;
      } else {
        *iter = prev_iter;
        break;
      }
    }
  }

  if (run->script == HB_SCRIPT_INHERITED)
    run->script = HB_SCRIPT_COMMON;

  run->length = *iter - run->pos;
  return true;
}

bool
hb_utf16_script_run_prev(ScriptRun* run, const uint16_t *chars, size_t len, ssize_t *iter) {
  if (*iter == -1)
    return false;

  const size_t ending_index = *iter;
  const uint32_t init_cp = utf16_to_code_point_prev(chars, len, iter);
  const hb_script_t init_script = code_point_to_script(init_cp);
  hb_script_t current_script = init_script;
  run->script = init_script;
  size_t break_iter = *iter;

  for (;;) {
    if (*iter < 0)
      break;
    const uint32_t cp = utf16_to_code_point_prev(chars, len, iter);
    const hb_script_t script = code_point_to_script(cp);

    if (script != current_script) {
      if (current_script == HB_SCRIPT_INHERITED && init_script == HB_SCRIPT_INHERITED) {
        // If we started off as inherited, we take whatever we can find.
        run->script = script;
        current_script = script;
        // In cases of script1 + inherited + script2, always group the inherited
        // with script1.
        break_iter = *iter;
        continue;
      } else if (script == HB_SCRIPT_INHERITED) {
        continue;
      } else {
        *iter = break_iter;
        break;
      }
    } else {
        break_iter = *iter;
    }
  }

  if (run->script == HB_SCRIPT_INHERITED)
    run->script = HB_SCRIPT_COMMON;

  run->pos = *iter + 1;
  run->length = ending_index - *iter;
  return true;
}


static void logGlyphs(hb_buffer_t* buffer) {
    unsigned int numGlyphs;
    hb_glyph_info_t* info = hb_buffer_get_glyph_infos(buffer, &numGlyphs);
    hb_glyph_position_t* positions = hb_buffer_get_glyph_positions(buffer, NULL);
    ALOGD("         -- glyphs count=%d", numGlyphs);
    for (size_t i = 0; i < numGlyphs; i++) {
        ALOGD("         -- glyph[%d] = %d, cluster = %u, advance = %0.2f, offset.x = %0.2f, offset.y = %0.2f", i,
                info[i].codepoint,
                info[i].cluster,
                HBFixedToFloat(positions[i].x_advance),
                HBFixedToFloat(positions[i].x_offset),
                HBFixedToFloat(positions[i].y_offset));
    }
}

void TextLayoutShaper::computeRunValues(const SkPaint* paint, const UChar* contextChars,
        size_t start, size_t count, size_t contextCount, bool isRTL,
        Vector<jfloat>* const outAdvances, jfloat* outTotalAdvance, SkRect* outBounds,
        Vector<jchar>* const outGlyphs, Vector<jfloat>* const outPos) {
    if (!count) {
        // We cannot shape an empty run.
        return;
    }

    // To be filled in later
    for (size_t i = 0; i < count; i++) {
        outAdvances->add(0);
    }

    // Set the string properties
    const UChar* chars = contextChars + start;

    // Define shaping paint properties
    mShapingPaint.setTextSize(paint->getTextSize());
    float skewX = paint->getTextSkewX();
    mShapingPaint.setTextSkewX(skewX);
    mShapingPaint.setTextScaleX(paint->getTextScaleX());
    mShapingPaint.setFlags(paint->getFlags());
    mShapingPaint.setHinting(paint->getHinting());
    mShapingPaint.setPaintOptionsAndroid(paint->getPaintOptionsAndroid());

    // Split the BiDi run into Script runs. Harfbuzz will populate the pos, length and script
    // into the shaperItem
    ssize_t indexFontRun = isRTL ? count - 1 : 0;
    jfloat totalAdvance = *outTotalAdvance;
    ScriptRun run;  // relative to chars
    while ((isRTL) ?
            hb_utf16_script_run_prev(&run, chars, count, &indexFontRun):
            hb_utf16_script_run_next(&run, chars, count, &indexFontRun)) {

#if DEBUG_GLYPHS
        ALOGD("-------- Start of Script Run --------");
        ALOGD("Shaping Script Run with");
        ALOGD("         -- isRTL = %d", isRTL);
        ALOGD("         -- HB script = %c%c%c%c", HB_UNTAG(run.script));
        ALOGD("         -- run.pos = %d", int(run.pos));
        ALOGD("         -- run.length = %d", int(run.length));
        ALOGD("         -- run = '%s'", String8(chars + run.pos, run.length).string());
        ALOGD("         -- string = '%s'", String8(chars, count).string());
#endif

        hb_buffer_reset(mBuffer);
        // Note: if we want to set unicode functions, etc., this is the place.
        
        hb_buffer_set_direction(mBuffer, isRTL ? HB_DIRECTION_RTL : HB_DIRECTION_LTR);
        hb_buffer_set_script(mBuffer, run.script);
        SkString langString = paint->getPaintOptionsAndroid().getLanguage().getTag();
        hb_buffer_set_language(mBuffer, hb_language_from_string(langString.c_str(), -1));
        hb_buffer_add_utf16(mBuffer, contextChars, contextCount, start + run.pos, run.length);

        // Initialize Harfbuzz Shaper and get the base glyph count for offsetting the glyphIDs
        // and shape the Font run
        size_t glyphBaseCount = shapeFontRun(paint);
        unsigned int numGlyphs;
        hb_glyph_info_t* info = hb_buffer_get_glyph_infos(mBuffer, &numGlyphs);
        hb_glyph_position_t* positions = hb_buffer_get_glyph_positions(mBuffer, NULL);

#if DEBUG_GLYPHS
        ALOGD("Got from Harfbuzz");
        ALOGD("         -- glyphBaseCount = %d", glyphBaseCount);
        ALOGD("         -- num_glyph = %d", numGlyphs);
        ALOGD("         -- isDevKernText = %d", paint->isDevKernText());
        ALOGD("         -- initial totalAdvance = %f", totalAdvance);

        logGlyphs(mBuffer);
#endif

        for (size_t i = 0; i < numGlyphs; i++) {
            size_t cluster = info[i].cluster - start;
            float xAdvance = HBFixedToFloat(positions[i].x_advance);
            outAdvances->replaceAt(outAdvances->itemAt(cluster) + xAdvance, cluster);
            jchar glyphId = info[i].codepoint + glyphBaseCount;
            outGlyphs->add(glyphId);
            float xo = HBFixedToFloat(positions[i].x_offset);
            float yo = -HBFixedToFloat(positions[i].y_offset);

            float xpos = totalAdvance + xo + yo * skewX;
            float ypos = yo;
            outPos->add(xpos);
            outPos->add(ypos);
            totalAdvance += xAdvance;

            SkAutoGlyphCache autoCache(mShapingPaint, NULL, NULL);
            const SkGlyph& metrics = autoCache.getCache()->getGlyphIDMetrics(glyphId);
            outBounds->join(xpos + metrics.fLeft, ypos + metrics.fTop,
                    xpos + metrics.fLeft + metrics.fWidth, ypos + metrics.fTop + metrics.fHeight);

        }
    }

    *outTotalAdvance = totalAdvance;

#if DEBUG_GLYPHS
    ALOGD("         -- final totalAdvance = %f", totalAdvance);
    ALOGD("-------- End of Script Run --------");
#endif
}

/**
 * Return the first typeface in the logical change, starting with this typeface,
 * that contains the specified unichar, or NULL if none is found.
 */
SkTypeface* TextLayoutShaper::typefaceForScript(const SkPaint* paint, SkTypeface* typeface,
        hb_script_t script) {
    SkTypeface::Style currentStyle = SkTypeface::kNormal;
    if (typeface) {
        currentStyle = typeface->style();
    }
    typeface = SkCreateTypefaceForScriptNG(script, currentStyle);
#if DEBUG_GLYPHS
    ALOGD("Using Harfbuzz Script %c%c%c%c, Style %d", HB_UNTAG(script), currentStyle);
#endif
    return typeface;
}

bool TextLayoutShaper::isComplexScript(hb_script_t script) {
    switch (script) {
    case HB_SCRIPT_COMMON:
    case HB_SCRIPT_GREEK:
    case HB_SCRIPT_CYRILLIC:
    case HB_SCRIPT_HANGUL:
    case HB_SCRIPT_INHERITED:
    case HB_SCRIPT_HAN:
    case HB_SCRIPT_KATAKANA:
    case HB_SCRIPT_HIRAGANA:
        return false;
    default:
        return true;
    }
}

size_t TextLayoutShaper::shapeFontRun(const SkPaint* paint) {
    // Update Harfbuzz Shaper

    SkTypeface* typeface = paint->getTypeface();

    // Get the glyphs base count for offsetting the glyphIDs returned by Harfbuzz
    // This is needed as the Typeface used for shaping can be not the default one
    // when we are shaping any script that needs to use a fallback Font.
    // If we are a "common" script we dont need to shift
    size_t baseGlyphCount = 0;
    hb_codepoint_t firstUnichar = 0;
    if (isComplexScript(hb_buffer_get_script(mBuffer))) {
        unsigned int numGlyphs;
        hb_glyph_info_t* info = hb_buffer_get_glyph_infos(mBuffer, &numGlyphs);
        for (size_t i = 0; i < numGlyphs; i++) {
            firstUnichar = info[i].codepoint;
            if (firstUnichar != ' ') {
                break;
            }
        }
        baseGlyphCount = paint->getBaseGlyphCount(firstUnichar);
    }

    SkTypeface* scriptTypeface = NULL;
    if (baseGlyphCount != 0) {
        scriptTypeface = typefaceForScript(paint, typeface,
            hb_buffer_get_script(mBuffer));
#if DEBUG_GLYPHS
        ALOGD("Using Default Typeface for script %c%c%c%c",
            HB_UNTAG(hb_buffer_get_script(mBuffer)));
#endif
    }
    if (scriptTypeface) {
        typeface = scriptTypeface;
    } else {
        baseGlyphCount = 0;
        if (typeface) {
            SkSafeRef(typeface);
        } else {
            typeface = SkTypeface::CreateFromName(NULL, SkTypeface::kNormal);
#if DEBUG_GLYPHS
            ALOGD("Using Default Typeface (normal style)");
#endif
        }
    }

    mShapingPaint.setTypeface(typeface);
    hb_face_t* face = referenceCachedHBFace(typeface);

    float sizeY = paint->getTextSize();
    float sizeX = sizeY * paint->getTextScaleX();
    hb_font_t* font = createFont(face, &mShapingPaint, sizeX, sizeY);
    hb_face_destroy(face);

#if DEBUG_GLYPHS
    ALOGD("Run typeface = %p, uniqueID = %d, face = %p",
            typeface, typeface->uniqueID(), face);
#endif
    SkSafeUnref(typeface);

    hb_shape(font, mBuffer, NULL, 0);
    hb_font_destroy(font);

    mShapingPaint.setTypeface(paint->getTypeface());
    return baseGlyphCount;
}

hb_face_t* TextLayoutShaper::referenceCachedHBFace(SkTypeface* typeface) {
    SkFontID fontId = typeface->uniqueID();
    ssize_t index = mCachedHBFaces.indexOfKey(fontId);
    if (index >= 0) {
        return hb_face_reference(mCachedHBFaces.valueAt(index));
    }
    // TODO: destroy function
    hb_face_t* face = hb_face_create_for_tables(harfbuzzSkiaReferenceTable, typeface, NULL);
#if DEBUG_GLYPHS
    ALOGD("Created HB_NewFace %p from paint typeface = %p", face, typeface);
#endif
    mCachedHBFaces.add(fontId, face);
    return hb_face_reference(face);
}

void TextLayoutShaper::purgeCaches() {
    size_t cacheSize = mCachedHBFaces.size();
    for (size_t i = 0; i < cacheSize; i++) {
        hb_face_destroy(mCachedHBFaces.valueAt(i));
    }
    mCachedHBFaces.clear();
}

TextLayoutEngine::TextLayoutEngine() {
    mShaper = new TextLayoutShaper();
#if USE_TEXT_LAYOUT_CACHE
    mTextLayoutCache = new TextLayoutCache(mShaper);
#else
    mTextLayoutCache = NULL;
#endif
}

TextLayoutEngine::~TextLayoutEngine() {
    delete mTextLayoutCache;
    delete mShaper;
}

sp<TextLayoutValue> TextLayoutEngine::getValue(const SkPaint* paint, const jchar* text,
        jint start, jint count, jint contextCount, jint dirFlags) {
    sp<TextLayoutValue> value;
#if USE_TEXT_LAYOUT_CACHE
    value = mTextLayoutCache->getValue(paint, text, start, count,
            contextCount, dirFlags);
    if (value == NULL) {
        ALOGE("Cannot get TextLayoutCache value for text = '%s'",
                String8(text + start, count).string());
    }
#else
    value = new TextLayoutValue(count);
    mShaper->computeValues(value.get(), paint,
            reinterpret_cast<const UChar*>(text), start, count, contextCount, dirFlags);
#endif
    return value;
}

void TextLayoutEngine::purgeCaches() {
#if USE_TEXT_LAYOUT_CACHE
    mTextLayoutCache->purgeCaches();
#if DEBUG_GLYPHS
    ALOGD("Purged TextLayoutEngine caches");
#endif
#endif
}


} // namespace android
