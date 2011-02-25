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

namespace android {

TextLayoutCache::TextLayoutCache():
        mCache(GenerationCache<TextLayoutCacheKey, RunAdvanceDescription*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(MB(DEFAULT_TEXT_LAYOUT_CACHE_SIZE_IN_MB)),
        mCacheHitCount(0), mNanosecondsSaved(0) {
    init();
}

TextLayoutCache::TextLayoutCache(uint32_t max):
        mCache(GenerationCache<TextLayoutCacheKey, RunAdvanceDescription*>::kUnlimitedCapacity),
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
void TextLayoutCache::operator()(TextLayoutCacheKey& text, RunAdvanceDescription*& desc) {
    if (desc) {
        size_t totalSizeToDelete = text.getSize() + desc->getSize();
        mSize -= totalSizeToDelete;
        if (mDebugEnabled) {
            LOGD("RunAdvance description deleted, size = %d", totalSizeToDelete);
        }
        delete desc;
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
void TextLayoutCache::getRunAdvances(SkPaint* paint, const jchar* text,
        jint start, jint count, jint contextCount, jint dirFlags,
        jfloat* outAdvances, jfloat* outTotalAdvance) {

    AutoMutex _l(mLock);

    nsecs_t startTime = 0;
    if (mDebugEnabled) {
        startTime = systemTime(SYSTEM_TIME_MONOTONIC);
    }

    TextLayoutCacheKey entry(paint, text, start, count, contextCount, dirFlags);

    // Get entry for cache if possible
    RunAdvanceDescription* desc = mCache.get(entry);

    // Value not found for the entry, we need to add a new value in the cache
    if (!desc) {
        desc = new RunAdvanceDescription();

        // Compute advances and store them
        desc->computeAdvances(paint, text, start, count, contextCount, dirFlags);
        desc->copyResult(outAdvances, outTotalAdvance);

        // Don't bother to add in the cache if the entry is too big
        size_t size = entry.getSize() + desc->getSize();
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
            entry.internalTextCopy();
            mCache.put(entry, desc);

            if (mDebugEnabled) {
                // Update timing information for statistics.
                desc->setElapsedTime(systemTime(SYSTEM_TIME_MONOTONIC) - startTime);

                LOGD("CACHE MISS: Added entry for text='%s' with start=%d, count=%d, "
                        "contextCount=%d, entry size %d bytes, remaining space %d bytes"
                        " - Compute time in nanos: %d",
                        String8(text, contextCount).string(), start, count, contextCount,
                        size, mMaxSize - mSize, desc->getElapsedTime());
            }
        } else {
            if (mDebugEnabled) {
                LOGD("CACHE MISS: Calculated but not storing entry because it is too big "
                        "for text='%s' with start=%d, count=%d, contextCount=%d, "
                        "entry size %d bytes, remaining space %d bytes"
                        " - Compute time in nanos: %d",
                        String8(text, contextCount).string(), start, count, contextCount,
                        size, mMaxSize - mSize, desc->getElapsedTime());
            }
            delete desc;
        }
    } else {
        // This is a cache hit, just copy the pre-computed results
        desc->copyResult(outAdvances, outTotalAdvance);
        if (mDebugEnabled) {
            nsecs_t elapsedTimeThruCacheGet = systemTime(SYSTEM_TIME_MONOTONIC) - startTime;
            mNanosecondsSaved += (desc->getElapsedTime() - elapsedTimeThruCacheGet);
            ++mCacheHitCount;

            if (desc->getElapsedTime() > 0) {
                float deltaPercent = 100 * ((desc->getElapsedTime() - elapsedTimeThruCacheGet)
                        / ((float)desc->getElapsedTime()));
                LOGD("CACHE HIT #%d for text='%s' with start=%d, count=%d, contextCount=%d "
                        "- Compute time in nanos: %d - "
                        "Cache get time in nanos: %lld - Gain in percent: %2.2f",
                        mCacheHitCount, String8(text, contextCount).string(), start, count,
                        contextCount,
                        desc->getElapsedTime(), elapsedTimeThruCacheGet, deltaPercent);
            }
            if (mCacheHitCount % DEFAULT_DUMP_STATS_CACHE_HIT_INTERVAL == 0) {
                dumpCacheStats();
            }
        }
    }
}

void TextLayoutCache::dumpCacheStats() {
    float remainingPercent = 100 * ((mMaxSize - mSize) / ((float)mMaxSize));
    float timeRunningInSec = (systemTime(SYSTEM_TIME_MONOTONIC) - mCacheStartTime) / 1000000000;
    LOGD("------------------------------------------------");
    LOGD("TextLayoutCache stats");
    LOGD("------------------------------------------------");
    LOGD("running   : %.0f seconds", timeRunningInSec);
    LOGD("size      : %d bytes", mMaxSize);
    LOGD("remaining : %d bytes or %2.2f percent", mMaxSize - mSize, remainingPercent);
    LOGD("hits      : %d", mCacheHitCount);
    LOGD("saved     : %lld milliseconds", mNanosecondsSaved / 1000000);
    LOGD("------------------------------------------------");
}

} // namespace android
