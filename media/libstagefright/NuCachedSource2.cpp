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

#define LOG_TAG "NuCachedSource2"
#include <utils/Log.h>

#include "include/NuCachedSource2.h"

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
    LOG(VERBOSE) << "copy from " << from << " size " << size;

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

NuCachedSource2::NuCachedSource2(const sp<DataSource> &source)
    : mSource(source),
      mReflector(new AHandlerReflector<NuCachedSource2>(this)),
      mLooper(new ALooper),
      mCache(new PageCache(kPageSize)),
      mCacheOffset(0),
      mFinalStatus(OK),
      mLastAccessPos(0),
      mFetching(true),
      mLastFetchTimeUs(-1) {
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

status_t NuCachedSource2::initCheck() const {
    return mSource->initCheck();
}

status_t NuCachedSource2::getSize(off_t *size) {
    return mSource->getSize(size);
}

uint32_t NuCachedSource2::flags() {
    return mSource->flags();
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
    LOG(VERBOSE) << "fetchInternal";

    CHECK_EQ(mFinalStatus, (status_t)OK);

    PageCache::Page *page = mCache->acquirePage();

    ssize_t n = mSource->readAt(
            mCacheOffset + mCache->totalSize(), page->mData, kPageSize);

    Mutex::Autolock autoLock(mLock);

    if (n < 0) {
        LOG(ERROR) << "source returned error " << n;
        mFinalStatus = n;
        mCache->releasePage(page);
    } else if (n == 0) {
        LOG(INFO) << "ERROR_END_OF_STREAM";
        mFinalStatus = ERROR_END_OF_STREAM;
        mCache->releasePage(page);
    } else {
        page->mSize = n;
        mCache->appendPage(page);
    }
}

void NuCachedSource2::onFetch() {
    LOG(VERBOSE) << "onFetch";

    if (mFinalStatus != OK) {
        LOG(VERBOSE) << "EOS reached, done prefetching for now";
        mFetching = false;
    }

    bool keepAlive =
        !mFetching
            && mFinalStatus == OK
            && ALooper::GetNowUs() >= mLastFetchTimeUs + kKeepAliveIntervalUs;

    if (mFetching || keepAlive) {
        if (keepAlive) {
            LOG(INFO) << "Keep alive";
        }

        fetchInternal();

        mLastFetchTimeUs = ALooper::GetNowUs();

        if (mFetching && mCache->totalSize() >= kHighWaterThreshold) {
            LOG(INFO) << "Cache full, done prefetching for now";
            mFetching = false;
        }
    } else {
        Mutex::Autolock autoLock(mLock);
        restartPrefetcherIfNecessary_l();
    }

    (new AMessage(kWhatFetchMore, mReflector->id()))->post(
            mFetching ? 0 : 100000ll);
}

void NuCachedSource2::onRead(const sp<AMessage> &msg) {
    LOG(VERBOSE) << "onRead";

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

void NuCachedSource2::restartPrefetcherIfNecessary_l() {
    static const size_t kGrayArea = 256 * 1024;

    if (mFetching || mFinalStatus != OK) {
        return;
    }

    if (mCacheOffset + mCache->totalSize() - mLastAccessPos
            >= kLowWaterThreshold) {
        return;
    }

    size_t maxBytes = mLastAccessPos - mCacheOffset;
    if (maxBytes < kGrayArea) {
        return;
    }

    maxBytes -= kGrayArea;

    size_t actualBytes = mCache->releaseFromStart(maxBytes);
    mCacheOffset += actualBytes;

    LOG(INFO) << "restarting prefetcher, totalSize = " << mCache->totalSize();
    mFetching = true;
}

ssize_t NuCachedSource2::readAt(off_t offset, void *data, size_t size) {
    Mutex::Autolock autoSerializer(mSerializer);

    LOG(VERBOSE) << "readAt offset " << offset << " size " << size;

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

size_t NuCachedSource2::approxDataRemaining(bool *eos) {
    Mutex::Autolock autoLock(mLock);
    return approxDataRemaining_l(eos);
}

size_t NuCachedSource2::approxDataRemaining_l(bool *eos) {
    *eos = (mFinalStatus != OK);
    off_t lastBytePosCached = mCacheOffset + mCache->totalSize();
    if (mLastAccessPos < lastBytePosCached) {
        return lastBytePosCached - mLastAccessPos;
    }
    return 0;
}

ssize_t NuCachedSource2::readInternal(off_t offset, void *data, size_t size) {
    LOG(VERBOSE) << "readInternal offset " << offset << " size " << size;

    Mutex::Autolock autoLock(mLock);

    if (offset < mCacheOffset
            || offset >= (off_t)(mCacheOffset + mCache->totalSize())) {
        static const off_t kPadding = 32768;

        // In the presence of multiple decoded streams, once of them will
        // trigger this seek request, the other one will request data "nearby"
        // soon, adjust the seek position so that that subsequent request
        // does not trigger another seek.
        off_t seekOffset = (offset > kPadding) ? offset - kPadding : 0;

        seekInternal_l(seekOffset);
    }

    size_t delta = offset - mCacheOffset;

    if (mFinalStatus != OK) {
        if (delta >= mCache->totalSize()) {
            return mFinalStatus;
        }

        size_t avail = mCache->totalSize() - delta;
        mCache->copy(delta, data, avail);

        return avail;
    }

    if (offset + size <= mCacheOffset + mCache->totalSize()) {
        mCache->copy(delta, data, size);

        return size;
    }

    LOG(VERBOSE) << "deferring read";

    return -EAGAIN;
}

status_t NuCachedSource2::seekInternal_l(off_t offset) {
    mLastAccessPos = offset;

    if (offset >= mCacheOffset
            && offset <= (off_t)(mCacheOffset + mCache->totalSize())) {
        return OK;
    }

    LOG(INFO) << "new range: offset= " << offset;

    mCacheOffset = offset;

    size_t totalSize = mCache->totalSize();
    CHECK_EQ(mCache->releaseFromStart(totalSize), totalSize);

    mFinalStatus = OK;
    mFetching = true;

    return OK;
}

}  // namespace android

