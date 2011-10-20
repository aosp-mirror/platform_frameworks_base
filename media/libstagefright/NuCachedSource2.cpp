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

//#define LOG_NDEBUG 0
#define LOG_TAG "NuCachedSource2"
#include <utils/Log.h>

#include "include/NuCachedSource2.h"
#include "include/HTTPBase.h"

#include <cutils/properties.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaErrors.h>

namespace android {

struct PageCache {
    PageCache(size_t pageSize);
    ~PageCache();

    struct Page {
        void *mData;
        size_t mSize;
    };

    Page *acquirePage();
    void releasePage(Page *page);

    void appendPage(Page *page);
    size_t releaseFromStart(size_t maxBytes);

    size_t totalSize() const {
        return mTotalSize;
    }

    void copy(size_t from, void *data, size_t size);

private:
    size_t mPageSize;
    size_t mTotalSize;

    List<Page *> mActivePages;
    List<Page *> mFreePages;

    void freePages(List<Page *> *list);

    DISALLOW_EVIL_CONSTRUCTORS(PageCache);
};

PageCache::PageCache(size_t pageSize)
    : mPageSize(pageSize),
      mTotalSize(0) {
}

PageCache::~PageCache() {
    freePages(&mActivePages);
    freePages(&mFreePages);
}

void PageCache::freePages(List<Page *> *list) {
    List<Page *>::iterator it = list->begin();
    while (it != list->end()) {
        Page *page = *it;

        free(page->mData);
        delete page;
        page = NULL;

        ++it;
    }
}

PageCache::Page *PageCache::acquirePage() {
    if (!mFreePages.empty()) {
        List<Page *>::iterator it = mFreePages.begin();
        Page *page = *it;
        mFreePages.erase(it);

        return page;
    }

    Page *page = new Page;
    page->mData = malloc(mPageSize);
    page->mSize = 0;

    return page;
}

void PageCache::releasePage(Page *page) {
    page->mSize = 0;
    mFreePages.push_back(page);
}

void PageCache::appendPage(Page *page) {
    mTotalSize += page->mSize;
    mActivePages.push_back(page);
}

size_t PageCache::releaseFromStart(size_t maxBytes) {
    size_t bytesReleased = 0;

    while (maxBytes > 0 && !mActivePages.empty()) {
        List<Page *>::iterator it = mActivePages.begin();

        Page *page = *it;

        if (maxBytes < page->mSize) {
            break;
        }

        mActivePages.erase(it);

        maxBytes -= page->mSize;
        bytesReleased += page->mSize;

        releasePage(page);
    }

    mTotalSize -= bytesReleased;
    return bytesReleased;
}

void PageCache::copy(size_t from, void *data, size_t size) {
    ALOGV("copy from %d size %d", from, size);

    if (size == 0) {
        return;
    }

    CHECK_LE(from + size, mTotalSize);

    size_t offset = 0;
    List<Page *>::iterator it = mActivePages.begin();
    while (from >= offset + (*it)->mSize) {
        offset += (*it)->mSize;
        ++it;
    }

    size_t delta = from - offset;
    size_t avail = (*it)->mSize - delta;

    if (avail >= size) {
        memcpy(data, (const uint8_t *)(*it)->mData + delta, size);
        return;
    }

    memcpy(data, (const uint8_t *)(*it)->mData + delta, avail);
    ++it;
    data = (uint8_t *)data + avail;
    size -= avail;

    while (size > 0) {
        size_t copy = (*it)->mSize;
        if (copy > size) {
            copy = size;
        }
        memcpy(data, (*it)->mData, copy);
        data = (uint8_t *)data + copy;
        size -= copy;
        ++it;
    }
}

////////////////////////////////////////////////////////////////////////////////

NuCachedSource2::NuCachedSource2(
        const sp<DataSource> &source,
        const char *cacheConfig,
        bool disconnectAtHighwatermark)
    : mSource(source),
      mReflector(new AHandlerReflector<NuCachedSource2>(this)),
      mLooper(new ALooper),
      mCache(new PageCache(kPageSize)),
      mCacheOffset(0),
      mFinalStatus(OK),
      mLastAccessPos(0),
      mFetching(true),
      mLastFetchTimeUs(-1),
      mNumRetriesLeft(kMaxNumRetries),
      mHighwaterThresholdBytes(kDefaultHighWaterThreshold),
      mLowwaterThresholdBytes(kDefaultLowWaterThreshold),
      mKeepAliveIntervalUs(kDefaultKeepAliveIntervalUs),
      mDisconnectAtHighwatermark(disconnectAtHighwatermark) {
    // We are NOT going to support disconnect-at-highwatermark indefinitely
    // and we are not guaranteeing support for client-specified cache
    // parameters. Both of these are temporary measures to solve a specific
    // problem that will be solved in a better way going forward.

    updateCacheParamsFromSystemProperty();

    if (cacheConfig != NULL) {
        updateCacheParamsFromString(cacheConfig);
    }

    if (mDisconnectAtHighwatermark) {
        // Makes no sense to disconnect and do keep-alives...
        mKeepAliveIntervalUs = 0;
    }

    mLooper->setName("NuCachedSource2");
    mLooper->registerHandler(mReflector);
    mLooper->start();

    Mutex::Autolock autoLock(mLock);
    (new AMessage(kWhatFetchMore, mReflector->id()))->post();
}

NuCachedSource2::~NuCachedSource2() {
    mLooper->stop();
    mLooper->unregisterHandler(mReflector->id());

    delete mCache;
    mCache = NULL;
}

status_t NuCachedSource2::getEstimatedBandwidthKbps(int32_t *kbps) {
    if (mSource->flags() & kIsHTTPBasedSource) {
        HTTPBase* source = static_cast<HTTPBase *>(mSource.get());
        return source->getEstimatedBandwidthKbps(kbps);
    }
    return ERROR_UNSUPPORTED;
}

status_t NuCachedSource2::setCacheStatCollectFreq(int32_t freqMs) {
    if (mSource->flags() & kIsHTTPBasedSource) {
        HTTPBase *source = static_cast<HTTPBase *>(mSource.get());
        return source->setBandwidthStatCollectFreq(freqMs);
    }
    return ERROR_UNSUPPORTED;
}

status_t NuCachedSource2::initCheck() const {
    return mSource->initCheck();
}

status_t NuCachedSource2::getSize(off64_t *size) {
    return mSource->getSize(size);
}

uint32_t NuCachedSource2::flags() {
    // Remove HTTP related flags since NuCachedSource2 is not HTTP-based.
    uint32_t flags = mSource->flags() & ~(kWantsPrefetching | kIsHTTPBasedSource);
    return (flags | kIsCachingDataSource);
}

void NuCachedSource2::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatFetchMore:
        {
            onFetch();
            break;
        }

        case kWhatRead:
        {
            onRead(msg);
            break;
        }

        default:
            TRESPASS();
    }
}

void NuCachedSource2::fetchInternal() {
    ALOGV("fetchInternal");

    bool reconnect = false;

    {
        Mutex::Autolock autoLock(mLock);
        CHECK(mFinalStatus == OK || mNumRetriesLeft > 0);

        if (mFinalStatus != OK) {
            --mNumRetriesLeft;

            reconnect = true;
        }
    }

    if (reconnect) {
        status_t err =
            mSource->reconnectAtOffset(mCacheOffset + mCache->totalSize());

        Mutex::Autolock autoLock(mLock);

        if (err == ERROR_UNSUPPORTED) {
            mNumRetriesLeft = 0;
            return;
        } else if (err != OK) {
            LOGI("The attempt to reconnect failed, %d retries remaining",
                 mNumRetriesLeft);

            return;
        }
    }

    PageCache::Page *page = mCache->acquirePage();

    ssize_t n = mSource->readAt(
            mCacheOffset + mCache->totalSize(), page->mData, kPageSize);

    Mutex::Autolock autoLock(mLock);

    if (n < 0) {
        LOGE("source returned error %ld, %d retries left", n, mNumRetriesLeft);
        mFinalStatus = n;
        mCache->releasePage(page);
    } else if (n == 0) {
        LOGI("ERROR_END_OF_STREAM");

        mNumRetriesLeft = 0;
        mFinalStatus = ERROR_END_OF_STREAM;

        mCache->releasePage(page);
    } else {
        if (mFinalStatus != OK) {
            LOGI("retrying a previously failed read succeeded.");
        }
        mNumRetriesLeft = kMaxNumRetries;
        mFinalStatus = OK;

        page->mSize = n;
        mCache->appendPage(page);
    }
}

void NuCachedSource2::onFetch() {
    ALOGV("onFetch");

    if (mFinalStatus != OK && mNumRetriesLeft == 0) {
        ALOGV("EOS reached, done prefetching for now");
        mFetching = false;
    }

    bool keepAlive =
        !mFetching
            && mFinalStatus == OK
            && mKeepAliveIntervalUs > 0
            && ALooper::GetNowUs() >= mLastFetchTimeUs + mKeepAliveIntervalUs;

    if (mFetching || keepAlive) {
        if (keepAlive) {
            LOGI("Keep alive");
        }

        fetchInternal();

        mLastFetchTimeUs = ALooper::GetNowUs();

        if (mFetching && mCache->totalSize() >= mHighwaterThresholdBytes) {
            LOGI("Cache full, done prefetching for now");
            mFetching = false;

            if (mDisconnectAtHighwatermark
                    && (mSource->flags() & DataSource::kIsHTTPBasedSource)) {
                ALOGV("Disconnecting at high watermark");
                static_cast<HTTPBase *>(mSource.get())->disconnect();
            }
        }
    } else {
        Mutex::Autolock autoLock(mLock);
        restartPrefetcherIfNecessary_l();
    }

    int64_t delayUs;
    if (mFetching) {
        if (mFinalStatus != OK && mNumRetriesLeft > 0) {
            // We failed this time and will try again in 3 seconds.
            delayUs = 3000000ll;
        } else {
            delayUs = 0;
        }
    } else {
        delayUs = 100000ll;
    }

    (new AMessage(kWhatFetchMore, mReflector->id()))->post(delayUs);
}

void NuCachedSource2::onRead(const sp<AMessage> &msg) {
    ALOGV("onRead");

    int64_t offset;
    CHECK(msg->findInt64("offset", &offset));

    void *data;
    CHECK(msg->findPointer("data", &data));

    size_t size;
    CHECK(msg->findSize("size", &size));

    ssize_t result = readInternal(offset, data, size);

    if (result == -EAGAIN) {
        msg->post(50000);
        return;
    }

    Mutex::Autolock autoLock(mLock);

    CHECK(mAsyncResult == NULL);

    mAsyncResult = new AMessage;
    mAsyncResult->setInt32("result", result);

    mCondition.signal();
}

void NuCachedSource2::restartPrefetcherIfNecessary_l(
        bool ignoreLowWaterThreshold, bool force) {
    static const size_t kGrayArea = 1024 * 1024;

    if (mFetching || (mFinalStatus != OK && mNumRetriesLeft == 0)) {
        return;
    }

    if (!ignoreLowWaterThreshold && !force
            && mCacheOffset + mCache->totalSize() - mLastAccessPos
                >= mLowwaterThresholdBytes) {
        return;
    }

    size_t maxBytes = mLastAccessPos - mCacheOffset;

    if (!force) {
        if (maxBytes < kGrayArea) {
            return;
        }

        maxBytes -= kGrayArea;
    }

    size_t actualBytes = mCache->releaseFromStart(maxBytes);
    mCacheOffset += actualBytes;

    LOGI("restarting prefetcher, totalSize = %d", mCache->totalSize());
    mFetching = true;
}

ssize_t NuCachedSource2::readAt(off64_t offset, void *data, size_t size) {
    Mutex::Autolock autoSerializer(mSerializer);

    ALOGV("readAt offset %lld, size %d", offset, size);

    Mutex::Autolock autoLock(mLock);

    // If the request can be completely satisfied from the cache, do so.

    if (offset >= mCacheOffset
            && offset + size <= mCacheOffset + mCache->totalSize()) {
        size_t delta = offset - mCacheOffset;
        mCache->copy(delta, data, size);

        mLastAccessPos = offset + size;

        return size;
    }

    sp<AMessage> msg = new AMessage(kWhatRead, mReflector->id());
    msg->setInt64("offset", offset);
    msg->setPointer("data", data);
    msg->setSize("size", size);

    CHECK(mAsyncResult == NULL);
    msg->post();

    while (mAsyncResult == NULL) {
        mCondition.wait(mLock);
    }

    int32_t result;
    CHECK(mAsyncResult->findInt32("result", &result));

    mAsyncResult.clear();

    if (result > 0) {
        mLastAccessPos = offset + result;
    }

    return (ssize_t)result;
}

size_t NuCachedSource2::cachedSize() {
    Mutex::Autolock autoLock(mLock);
    return mCacheOffset + mCache->totalSize();
}

size_t NuCachedSource2::approxDataRemaining(status_t *finalStatus) {
    Mutex::Autolock autoLock(mLock);
    return approxDataRemaining_l(finalStatus);
}

size_t NuCachedSource2::approxDataRemaining_l(status_t *finalStatus) {
    *finalStatus = mFinalStatus;

    if (mFinalStatus != OK && mNumRetriesLeft > 0) {
        // Pretend that everything is fine until we're out of retries.
        *finalStatus = OK;
    }

    off64_t lastBytePosCached = mCacheOffset + mCache->totalSize();
    if (mLastAccessPos < lastBytePosCached) {
        return lastBytePosCached - mLastAccessPos;
    }
    return 0;
}

ssize_t NuCachedSource2::readInternal(off64_t offset, void *data, size_t size) {
    CHECK_LE(size, (size_t)mHighwaterThresholdBytes);

    ALOGV("readInternal offset %lld size %d", offset, size);

    Mutex::Autolock autoLock(mLock);

    if (!mFetching) {
        mLastAccessPos = offset;
        restartPrefetcherIfNecessary_l(
                false, // ignoreLowWaterThreshold
                true); // force
    }

    if (offset < mCacheOffset
            || offset >= (off64_t)(mCacheOffset + mCache->totalSize())) {
        static const off64_t kPadding = 256 * 1024;

        // In the presence of multiple decoded streams, once of them will
        // trigger this seek request, the other one will request data "nearby"
        // soon, adjust the seek position so that that subsequent request
        // does not trigger another seek.
        off64_t seekOffset = (offset > kPadding) ? offset - kPadding : 0;

        seekInternal_l(seekOffset);
    }

    size_t delta = offset - mCacheOffset;

    if (mFinalStatus != OK) {
        if (delta >= mCache->totalSize()) {
            return mFinalStatus;
        }

        size_t avail = mCache->totalSize() - delta;

        if (avail > size) {
            avail = size;
        }

        mCache->copy(delta, data, avail);

        return avail;
    }

    if (offset + size <= mCacheOffset + mCache->totalSize()) {
        mCache->copy(delta, data, size);

        return size;
    }

    ALOGV("deferring read");

    return -EAGAIN;
}

status_t NuCachedSource2::seekInternal_l(off64_t offset) {
    mLastAccessPos = offset;

    if (offset >= mCacheOffset
            && offset <= (off64_t)(mCacheOffset + mCache->totalSize())) {
        return OK;
    }

    LOGI("new range: offset= %lld", offset);

    mCacheOffset = offset;

    size_t totalSize = mCache->totalSize();
    CHECK_EQ(mCache->releaseFromStart(totalSize), totalSize);

    mFinalStatus = OK;
    mFetching = true;

    return OK;
}

void NuCachedSource2::resumeFetchingIfNecessary() {
    Mutex::Autolock autoLock(mLock);

    restartPrefetcherIfNecessary_l(true /* ignore low water threshold */);
}

sp<DecryptHandle> NuCachedSource2::DrmInitialization() {
    return mSource->DrmInitialization();
}

void NuCachedSource2::getDrmInfo(sp<DecryptHandle> &handle, DrmManagerClient **client) {
    mSource->getDrmInfo(handle, client);
}

String8 NuCachedSource2::getUri() {
    return mSource->getUri();
}

String8 NuCachedSource2::getMIMEType() const {
    return mSource->getMIMEType();
}

void NuCachedSource2::updateCacheParamsFromSystemProperty() {
    char value[PROPERTY_VALUE_MAX];
    if (!property_get("media.stagefright.cache-params", value, NULL)) {
        return;
    }

    updateCacheParamsFromString(value);
}

void NuCachedSource2::updateCacheParamsFromString(const char *s) {
    ssize_t lowwaterMarkKb, highwaterMarkKb;
    int keepAliveSecs;

    if (sscanf(s, "%ld/%ld/%d",
               &lowwaterMarkKb, &highwaterMarkKb, &keepAliveSecs) != 3) {
        LOGE("Failed to parse cache parameters from '%s'.", s);
        return;
    }

    if (lowwaterMarkKb >= 0) {
        mLowwaterThresholdBytes = lowwaterMarkKb * 1024;
    } else {
        mLowwaterThresholdBytes = kDefaultLowWaterThreshold;
    }

    if (highwaterMarkKb >= 0) {
        mHighwaterThresholdBytes = highwaterMarkKb * 1024;
    } else {
        mHighwaterThresholdBytes = kDefaultHighWaterThreshold;
    }

    if (mLowwaterThresholdBytes >= mHighwaterThresholdBytes) {
        LOGE("Illegal low/highwater marks specified, reverting to defaults.");

        mLowwaterThresholdBytes = kDefaultLowWaterThreshold;
        mHighwaterThresholdBytes = kDefaultHighWaterThreshold;
    }

    if (keepAliveSecs >= 0) {
        mKeepAliveIntervalUs = keepAliveSecs * 1000000ll;
    } else {
        mKeepAliveIntervalUs = kDefaultKeepAliveIntervalUs;
    }

    ALOGV("lowwater = %d bytes, highwater = %d bytes, keepalive = %lld us",
         mLowwaterThresholdBytes,
         mHighwaterThresholdBytes,
         mKeepAliveIntervalUs);
}

// static
void NuCachedSource2::RemoveCacheSpecificHeaders(
        KeyedVector<String8, String8> *headers,
        String8 *cacheConfig,
        bool *disconnectAtHighwatermark) {
    *cacheConfig = String8();
    *disconnectAtHighwatermark = false;

    if (headers == NULL) {
        return;
    }

    ssize_t index;
    if ((index = headers->indexOfKey(String8("x-cache-config"))) >= 0) {
        *cacheConfig = headers->valueAt(index);

        headers->removeItemsAt(index);

        ALOGV("Using special cache config '%s'", cacheConfig->string());
    }

    if ((index = headers->indexOfKey(
                    String8("x-disconnect-at-highwatermark"))) >= 0) {
        *disconnectAtHighwatermark = true;
        headers->removeItemsAt(index);

        ALOGV("Client requested disconnection at highwater mark");
    }
}

}  // namespace android
