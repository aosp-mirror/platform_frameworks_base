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

#ifndef NU_CACHED_SOURCE_2_H_

#define NU_CACHED_SOURCE_2_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/foundation/AHandlerReflector.h>
#include <media/stagefright/DataSource.h>

namespace android {

struct ALooper;
struct PageCache;

struct NuCachedSource2 : public DataSource {
    NuCachedSource2(const sp<DataSource> &source);

    virtual status_t initCheck() const;

    virtual ssize_t readAt(off64_t offset, void *data, size_t size);

    virtual status_t getSize(off64_t *size);
    virtual uint32_t flags();

    virtual sp<DecryptHandle> DrmInitialization();
    virtual void getDrmInfo(sp<DecryptHandle> &handle, DrmManagerClient **client);
    virtual String8 getUri();

    virtual String8 getMIMEType() const;

    ////////////////////////////////////////////////////////////////////////////

    size_t cachedSize();
    size_t approxDataRemaining(status_t *finalStatus);

    void resumeFetchingIfNecessary();

    // The following methods are supported only if the
    // data source is HTTP-based; otherwise, ERROR_UNSUPPORTED
    // is returned.
    status_t getEstimatedBandwidthKbps(int32_t *kbps);
    status_t setCacheStatCollectFreq(int32_t freqMs);

protected:
    virtual ~NuCachedSource2();

private:
    friend struct AHandlerReflector<NuCachedSource2>;

    enum {
        kPageSize            = 65536,
        kHighWaterThreshold  = 20 * 1024 * 1024,
        kLowWaterThreshold   = 4 * 1024 * 1024,

        // Read data after a 15 sec timeout whether we're actively
        // fetching or not.
        kKeepAliveIntervalUs = 15000000,
    };

    enum {
        kWhatFetchMore  = 'fetc',
        kWhatRead       = 'read',
    };

    enum {
        kMaxNumRetries = 10,
    };

    sp<DataSource> mSource;
    sp<AHandlerReflector<NuCachedSource2> > mReflector;
    sp<ALooper> mLooper;

    Mutex mSerializer;
    Mutex mLock;
    Condition mCondition;

    PageCache *mCache;
    off64_t mCacheOffset;
    status_t mFinalStatus;
    off64_t mLastAccessPos;
    sp<AMessage> mAsyncResult;
    bool mFetching;
    int64_t mLastFetchTimeUs;

    int32_t mNumRetriesLeft;

    void onMessageReceived(const sp<AMessage> &msg);
    void onFetch();
    void onRead(const sp<AMessage> &msg);

    void fetchInternal();
    ssize_t readInternal(off64_t offset, void *data, size_t size);
    status_t seekInternal_l(off64_t offset);

    size_t approxDataRemaining_l(status_t *finalStatus);

    void restartPrefetcherIfNecessary_l(
            bool ignoreLowWaterThreshold = false, bool force = false);

    DISALLOW_EVIL_CONSTRUCTORS(NuCachedSource2);
};

}  // namespace android

#endif  // NU_CACHED_SOURCE_2_H_
