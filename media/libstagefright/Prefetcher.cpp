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

#define LOG_TAG "Prefetcher"
//#define LOG_NDEBUG 0
#include <utils/Log.h>

#include "include/Prefetcher.h"

#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <utils/List.h>

namespace android {

struct PrefetchedSource : public MediaSource {
    PrefetchedSource(
            size_t index,
            const sp<MediaSource> &source);

    virtual status_t start(MetaData *params);
    virtual status_t stop();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options);

    virtual sp<MetaData> getFormat();

protected:
    virtual ~PrefetchedSource();

private:
    friend struct Prefetcher;

    Mutex mLock;
    Condition mCondition;

    sp<MediaSource> mSource;
    size_t mIndex;
    bool mStarted;
    bool mReachedEOS;
    status_t mFinalStatus;
    int64_t mSeekTimeUs;
    int64_t mCacheDurationUs;
    size_t mCacheSizeBytes;
    bool mPrefetcherStopped;
    bool mCurrentlyPrefetching;

    List<MediaBuffer *> mCachedBuffers;

    // Returns true iff source is currently caching.
    bool getCacheDurationUs(int64_t *durationUs, size_t *totalSize = NULL);

    void updateCacheDuration_l();
    void clearCache_l();

    void cacheMore();
    void onPrefetcherStopped();

    PrefetchedSource(const PrefetchedSource &);
    PrefetchedSource &operator=(const PrefetchedSource &);
};

Prefetcher::Prefetcher()
    : mDone(false),
      mThreadExited(false) {
    startThread();
}

Prefetcher::~Prefetcher() {
    stopThread();
}

sp<MediaSource> Prefetcher::addSource(const sp<MediaSource> &source) {
    Mutex::Autolock autoLock(mLock);

    sp<PrefetchedSource> psource =
        new PrefetchedSource(mSources.size(), source);

    mSources.add(psource);

    return psource;
}

void Prefetcher::startThread() {
    mThreadExited = false;
    mDone = false;

    int res = androidCreateThreadEtc(
            ThreadWrapper, this, "Prefetcher",
            ANDROID_PRIORITY_DEFAULT, 0, &mThread);

    CHECK_EQ(res, 1);
}

void Prefetcher::stopThread() {
    Mutex::Autolock autoLock(mLock);

    while (!mThreadExited) {
        mDone = true;
        mCondition.signal();
        mCondition.wait(mLock);
    }
}

// static
int Prefetcher::ThreadWrapper(void *me) {
    static_cast<Prefetcher *>(me)->threadFunc();

    return 0;
}

// Cache at most 1 min for each source.
static int64_t kMaxCacheDurationUs = 60 * 1000000ll;

// At the same time cache at most 5MB per source.
static size_t kMaxCacheSizeBytes = 5 * 1024 * 1024;

// If the amount of cached data drops below this,
// fill the cache up to the max duration again.
static int64_t kLowWaterDurationUs = 5000000ll;

void Prefetcher::threadFunc() {
    bool fillingCache = false;

    for (;;) {
        sp<PrefetchedSource> minSource;
        int64_t minCacheDurationUs = -1;

        {
            Mutex::Autolock autoLock(mLock);
            if (mDone) {
                break;
            }

            mCondition.waitRelative(
                    mLock, fillingCache ? 1ll : 1000000000ll);


            ssize_t minIndex = -1;
            for (size_t i = 0; i < mSources.size(); ++i) {
                sp<PrefetchedSource> source = mSources[i].promote();

                if (source == NULL) {
                    continue;
                }

                int64_t cacheDurationUs;
                size_t cacheSizeBytes;
                if (!source->getCacheDurationUs(&cacheDurationUs, &cacheSizeBytes)) {
                    continue;
                }

                if (cacheSizeBytes > kMaxCacheSizeBytes) {
                    LOGI("max cache size reached");
                    continue;
                }

                if (mSources.size() > 1 && cacheDurationUs >= kMaxCacheDurationUs) {
                    LOGI("max duration reached, size = %d bytes", cacheSizeBytes);
                    continue;
                }

                if (minIndex < 0 || cacheDurationUs < minCacheDurationUs) {
                    minCacheDurationUs = cacheDurationUs;
                    minIndex = i;
                    minSource = source;
                }
            }

            if (minIndex < 0) {
                if (fillingCache) {
                    LOGV("[%p] done filling the cache, above high water mark.",
                         this);
                    fillingCache = false;
                }
                continue;
            }
        }

        if (!fillingCache && minCacheDurationUs < kLowWaterDurationUs) {
            LOGI("[%p] cache below low water mark, filling cache.", this);
            fillingCache = true;
        }

        if (fillingCache) {
            // Make sure not to hold the lock while calling into the source.
            // The lock guards the list of sources, not the individual sources
            // themselves.
            minSource->cacheMore();
        }
    }

    Mutex::Autolock autoLock(mLock);
    for (size_t i = 0; i < mSources.size(); ++i) {
        sp<PrefetchedSource> source = mSources[i].promote();

        if (source == NULL) {
            continue;
        }

        source->onPrefetcherStopped();
    }

    mThreadExited = true;
    mCondition.signal();
}

int64_t Prefetcher::getCachedDurationUs(bool *noMoreData) {
    Mutex::Autolock autoLock(mLock);

    int64_t minCacheDurationUs = -1;
    ssize_t minIndex = -1;
    bool anySourceActive = false;
    for (size_t i = 0; i < mSources.size(); ++i) {
        int64_t cacheDurationUs;
        sp<PrefetchedSource> source = mSources[i].promote();
        if (source == NULL) {
            continue;
        }

        if (source->getCacheDurationUs(&cacheDurationUs)) {
            anySourceActive = true;
        }

        if (minIndex < 0 || cacheDurationUs < minCacheDurationUs) {
            minCacheDurationUs = cacheDurationUs;
            minIndex = i;
        }
    }

    if (noMoreData) {
        *noMoreData = !anySourceActive;
    }

    return minCacheDurationUs < 0 ? 0 : minCacheDurationUs;
}

status_t Prefetcher::prepare(
        bool (*continueFunc)(void *cookie), void *cookie) {
    // Fill the cache.

    int64_t duration;
    bool noMoreData;
    do {
        usleep(100000);

        if (continueFunc && !(*continueFunc)(cookie)) {
            return -EINTR;
        }

        duration = getCachedDurationUs(&noMoreData);
    } while (!noMoreData && duration < 2000000ll);

    return OK;
}

////////////////////////////////////////////////////////////////////////////////

PrefetchedSource::PrefetchedSource(
        size_t index,
        const sp<MediaSource> &source)
    : mSource(source),
      mIndex(index),
      mStarted(false),
      mReachedEOS(false),
      mSeekTimeUs(0),
      mCacheDurationUs(0),
      mCacheSizeBytes(0),
      mPrefetcherStopped(false),
      mCurrentlyPrefetching(false) {
}

PrefetchedSource::~PrefetchedSource() {
    if (mStarted) {
        stop();
    }
}

status_t PrefetchedSource::start(MetaData *params) {
    CHECK(!mStarted);

    Mutex::Autolock autoLock(mLock);

    status_t err = mSource->start(params);

    if (err != OK) {
        return err;
    }

    mStarted = true;

    return OK;
}

status_t PrefetchedSource::stop() {
    CHECK(mStarted);

    Mutex::Autolock autoLock(mLock);

    while (mCurrentlyPrefetching) {
        mCondition.wait(mLock);
    }

    clearCache_l();

    status_t err = mSource->stop();

    mStarted = false;

    return err;
}

status_t PrefetchedSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    Mutex::Autolock autoLock(mLock);

    CHECK(mStarted);

    int64_t seekTimeUs;
    if (options && options->getSeekTo(&seekTimeUs)) {
        CHECK(seekTimeUs >= 0);

        clearCache_l();

        mReachedEOS = false;
        mSeekTimeUs = seekTimeUs;
    }

    while (!mPrefetcherStopped && !mReachedEOS && mCachedBuffers.empty()) {
        mCondition.wait(mLock);
    }

    if (mCachedBuffers.empty()) {
        return mReachedEOS ? mFinalStatus : ERROR_END_OF_STREAM;
    }

    *out = *mCachedBuffers.begin();
    mCachedBuffers.erase(mCachedBuffers.begin());
    updateCacheDuration_l();
    mCacheSizeBytes -= (*out)->size();

    return OK;
}

sp<MetaData> PrefetchedSource::getFormat() {
    return mSource->getFormat();
}

bool PrefetchedSource::getCacheDurationUs(
        int64_t *durationUs, size_t *totalSize) {
    Mutex::Autolock autoLock(mLock);

    *durationUs = mCacheDurationUs;
    if (totalSize != NULL) {
        *totalSize = mCacheSizeBytes;
    }

    if (!mStarted || mReachedEOS) {
        return false;
    }

    return true;
}

void PrefetchedSource::cacheMore() {
    MediaSource::ReadOptions options;

    Mutex::Autolock autoLock(mLock);

    if (!mStarted) {
        return;
    }

    mCurrentlyPrefetching = true;

    if (mSeekTimeUs >= 0) {
        options.setSeekTo(mSeekTimeUs);
        mSeekTimeUs = -1;
    }

    // Ensure our object does not go away while we're not holding
    // the lock.
    sp<PrefetchedSource> me = this;

    mLock.unlock();
    MediaBuffer *buffer;
    status_t err = mSource->read(&buffer, &options);
    mLock.lock();

    if (err != OK) {
        mCurrentlyPrefetching = false;
        mReachedEOS = true;
        mFinalStatus = err;
        mCondition.signal();

        return;
    }

    CHECK(buffer != NULL);

    MediaBuffer *copy = new MediaBuffer(buffer->range_length());
    memcpy(copy->data(),
           (const uint8_t *)buffer->data() + buffer->range_offset(),
           buffer->range_length());

    sp<MetaData> from = buffer->meta_data();
    sp<MetaData> to = copy->meta_data();

    int64_t timeUs;
    if (from->findInt64(kKeyTime, &timeUs)) {
        to->setInt64(kKeyTime, timeUs);
    }

    buffer->release();
    buffer = NULL;

    mCachedBuffers.push_back(copy);
    updateCacheDuration_l();
    mCacheSizeBytes += copy->size();

    mCurrentlyPrefetching = false;
    mCondition.signal();
}

void PrefetchedSource::updateCacheDuration_l() {
    if (mCachedBuffers.size() < 2) {
        mCacheDurationUs = 0;
    } else {
        int64_t firstTimeUs, lastTimeUs;
        CHECK((*mCachedBuffers.begin())->meta_data()->findInt64(
                    kKeyTime, &firstTimeUs));
        CHECK((*--mCachedBuffers.end())->meta_data()->findInt64(
                    kKeyTime, &lastTimeUs));

        mCacheDurationUs = lastTimeUs - firstTimeUs;
    }
}

void PrefetchedSource::clearCache_l() {
    List<MediaBuffer *>::iterator it = mCachedBuffers.begin();
    while (it != mCachedBuffers.end()) {
        (*it)->release();

        it = mCachedBuffers.erase(it);
    }

    updateCacheDuration_l();
    mCacheSizeBytes = 0;
}

void PrefetchedSource::onPrefetcherStopped() {
    Mutex::Autolock autoLock(mLock);
    mPrefetcherStopped = true;
    mCondition.signal();
}

}  // namespace android
