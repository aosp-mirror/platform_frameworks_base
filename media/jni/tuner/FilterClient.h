/*
 * Copyright 2021 The Android Open Source Project
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

#ifndef _ANDROID_MEDIA_TV_FILTER_CLIENT_H_
#define _ANDROID_MEDIA_TV_FILTER_CLIENT_H_

#include <aidl/android/hardware/tv/tuner/DemuxFilterType.h>
#include <aidl/android/media/tv/tuner/BnTunerFilterCallback.h>
#include <aidl/android/media/tv/tuner/ITunerFilter.h>
#include <fmq/AidlMessageQueue.h>
#include <utils/Mutex.h>

#include "ClientHelper.h"
#include "FilterClientCallback.h"

using Status = ::ndk::ScopedAStatus;
using ::aidl::android::hardware::common::fmq::MQDescriptor;
using ::aidl::android::hardware::common::fmq::SynchronizedReadWrite;
using ::aidl::android::hardware::tv::tuner::AvStreamType;
using ::aidl::android::hardware::tv::tuner::DemuxFilterEvent;
using ::aidl::android::hardware::tv::tuner::DemuxFilterSettings;
using ::aidl::android::hardware::tv::tuner::DemuxFilterStatus;
using ::aidl::android::hardware::tv::tuner::DemuxFilterType;
using ::aidl::android::hardware::tv::tuner::FilterDelayHint;
using ::aidl::android::media::tv::tuner::BnTunerFilterCallback;
using ::aidl::android::media::tv::tuner::ITunerFilter;
using ::android::hardware::EventFlag;
using ::android::Mutex;

using namespace std;

namespace android {

using AidlMQ = AidlMessageQueue<int8_t, SynchronizedReadWrite>;
using AidlMQDesc = MQDescriptor<int8_t, SynchronizedReadWrite>;

struct SharedHandleInfo {
    native_handle_t* sharedHandle;
    uint64_t size;
};

class TunerFilterCallback : public BnTunerFilterCallback {
public:
    TunerFilterCallback(sp<FilterClientCallback> filterClientCallback);
    Status onFilterStatus(DemuxFilterStatus status);
    Status onFilterEvent(const vector<DemuxFilterEvent>& filterEvents);

private:
    sp<FilterClientCallback> mFilterClientCallback;
};

struct FilterClient : public RefBase {

public:
    FilterClient(DemuxFilterType type, shared_ptr<ITunerFilter> tunerFilter);
    ~FilterClient();

    /**
     * Read size of data from filter FMQ into buffer.
     *
     * @return the actual reading size. -1 if failed to read.
     */
    int64_t read(int8_t* buffer, int64_t size);

    /**
     * Get the a/v shared memory handle information
     */
    SharedHandleInfo getAvSharedHandleInfo();

    /**
     * Configure the filter.
     */
    Result configure(DemuxFilterSettings configure);

    /**
     * Configure the monitor event of the Filter.
     */
    Result configureMonitorEvent(int32_t monitorEventType);

    /**
     * Configure the context id of the IP Filter.
     */
    Result configureIpFilterContextId(int32_t cid);

    /**
     * Configure the stream type of the media Filter.
     */
    Result configureAvStreamType(AvStreamType avStreamType);

    /**
     * Start the filter.
     */
    Result start();

    /**
     * Stop the filter.
     */
    Result stop();

    /**
     * Flush the filter.
     */
    Result flush();

    /**
     * Get the 32-bit filter Id.
     */
    Result getId(int32_t& id);

    /**
     * Get the 64-bit filter Id.
     */
    Result getId64Bit(int64_t& id);

    /**
     * Release the handle reported by the HAL for AV memory.
     */
    Result releaseAvHandle(native_handle_t* handle, uint64_t avDataId);

    /**
     * Set the filter's data source.
     */
    Result setDataSource(sp<FilterClient> filterClient);

    /**
     * Get the Aidl filter to build up filter linkage.
     */
    shared_ptr<ITunerFilter> getAidlFilter() { return mTunerFilter; }

    /**
     * Close a new interface of ITunerFilter.
     */
    Result close();

    /**
     * Accquire a new SharedFiler token.
     */
    string acquireSharedFilterToken();

    /**
     * Release SharedFiler token.
     */
    Result freeSharedFilterToken(const string& filterToken);

    /**
     * Set a filter delay hint.
     */
    Result setDelayHint(const FilterDelayHint& hint);

private:
    Result getFilterMq();
    int64_t copyData(int8_t* buffer, int64_t size);
    void checkIsMediaFilter(DemuxFilterType type);
    void checkIsPassthroughFilter(DemuxFilterSettings configure);
    void handleAvShareMemory();
    void closeAvSharedMemory();

    /**
     * An AIDL Tuner Filter Singleton assigned at the first time when the Tuner Client
     * opens a filter. Default null when Tuner Service does not exist.
     */
    shared_ptr<ITunerFilter> mTunerFilter;

    AidlMQ* mFilterMQ = nullptr;
    EventFlag* mFilterMQEventFlag = nullptr;

    native_handle_t* mAvSharedHandle;
    uint64_t mAvSharedMemSize;
    bool mIsMediaFilter;
    bool mIsPassthroughFilter;
    Mutex mLock;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_FILTER_CLIENT_H_
