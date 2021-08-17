/*
 * Copyright 2020 The Android Open Source Project
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

#include <aidl/android/media/tv/tuner/ITunerFilter.h>
#include <aidl/android/media/tv/tuner/BnTunerFilterCallback.h>
#include <aidl/android/media/tv/tuner/TunerFilterEvent.h>
#include <aidl/android/media/tv/tuner/TunerFilterSettings.h>
#include <aidlcommonsupport/NativeHandle.h>
#include <android/hardware/tv/tuner/1.1/IFilter.h>
#include <android/hardware/tv/tuner/1.1/IFilterCallback.h>
#include <android/hardware/tv/tuner/1.1/types.h>
#include <fmq/AidlMessageQueue.h>
#include <fmq/MessageQueue.h>

#include "ClientHelper.h"
#include "FilterClientCallback.h"

using Status = ::ndk::ScopedAStatus;
using ::aidl::android::hardware::common::fmq::SynchronizedReadWrite;
using ::aidl::android::media::tv::tuner::BnTunerFilterCallback;
using ::aidl::android::media::tv::tuner::ITunerFilter;
using ::aidl::android::media::tv::tuner::TunerDemuxIpAddress;
using ::aidl::android::media::tv::tuner::TunerFilterAvSettings;
using ::aidl::android::media::tv::tuner::TunerFilterConfiguration;
using ::aidl::android::media::tv::tuner::TunerFilterDownloadSettings;
using ::aidl::android::media::tv::tuner::TunerFilterEvent;
using ::aidl::android::media::tv::tuner::TunerFilterPesDataSettings;
using ::aidl::android::media::tv::tuner::TunerFilterRecordSettings;
using ::aidl::android::media::tv::tuner::TunerFilterSectionSettings;
using ::aidl::android::media::tv::tuner::TunerFilterSettings;

using ::android::hardware::EventFlag;
using ::android::hardware::MessageQueue;
using ::android::hardware::MQDescriptorSync;
using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::hardware::hidl_handle;
using ::android::hardware::tv::tuner::V1_0::DemuxAlpFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxMmtpFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterAvSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterDownloadSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterPesDataSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterRecordSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterSectionSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterType;
using ::android::hardware::tv::tuner::V1_0::DemuxIpAddress;
using ::android::hardware::tv::tuner::V1_0::DemuxIpFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxTlvFilterSettings;
using ::android::hardware::tv::tuner::V1_0::DemuxTsFilterSettings;
using ::android::hardware::tv::tuner::V1_0::IFilter;
using ::android::hardware::tv::tuner::V1_0::Result;
using ::android::hardware::tv::tuner::V1_1::AvStreamType;
using ::android::hardware::tv::tuner::V1_1::IFilterCallback;

using namespace std;

namespace android {

using MQ = MessageQueue<uint8_t, kSynchronizedReadWrite>;
using MQDesc = MQDescriptorSync<uint8_t>;
using AidlMQ = AidlMessageQueue<int8_t, SynchronizedReadWrite>;
using AidlMQDesc = MQDescriptor<int8_t, SynchronizedReadWrite>;

struct SharedHandleInfo {
    native_handle_t* sharedHandle;
    uint64_t size;
};

class TunerFilterCallback : public BnTunerFilterCallback {

public:
    TunerFilterCallback(sp<FilterClientCallback> filterClientCallback);
    Status onFilterStatus(int status);
    Status onFilterEvent(const vector<TunerFilterEvent>& filterEvents);

private:
    void getHidlFilterEvent(const vector<TunerFilterEvent>& filterEvents,
            DemuxFilterEvent& event, DemuxFilterEventExt& eventExt);
    void getHidlMediaEvent(
            const vector<TunerFilterEvent>& filterEvents, DemuxFilterEvent& event);
    void getHidlSectionEvent(
            const vector<TunerFilterEvent>& filterEvents, DemuxFilterEvent& event);
    void getHidlPesEvent(
            const vector<TunerFilterEvent>& filterEvents, DemuxFilterEvent& event);
    void getHidlTsRecordEvent(const vector<TunerFilterEvent>& filterEvents,
            DemuxFilterEvent& event, DemuxFilterEventExt& eventExt);
    void getHidlMmtpRecordEvent(const vector<TunerFilterEvent>& filterEvents,
            DemuxFilterEvent& event, DemuxFilterEventExt& eventExt);
    void getHidlDownloadEvent(
            const vector<TunerFilterEvent>& filterEvents, DemuxFilterEvent& event);
    void getHidlIpPayloadEvent(
            const vector<TunerFilterEvent>& filterEvents, DemuxFilterEvent& event);
    void getHidlTemiEvent(
            const vector<TunerFilterEvent>& filterEvents, DemuxFilterEvent& event);
    void getHidlMonitorEvent(
            const vector<TunerFilterEvent>& filterEvents, DemuxFilterEventExt& eventExt);
    void getHidlRestartEvent(
            const vector<TunerFilterEvent>& filterEvents, DemuxFilterEventExt& eventExt);

    sp<FilterClientCallback> mFilterClientCallback;
};

struct HidlFilterCallback : public IFilterCallback {

public:
    HidlFilterCallback(sp<FilterClientCallback> filterClientCallback);
    virtual Return<void> onFilterEvent_1_1(const DemuxFilterEvent& filterEvent,
            const DemuxFilterEventExt& filterEventExt);
    virtual Return<void> onFilterEvent(const DemuxFilterEvent& filterEvent);
    virtual Return<void> onFilterStatus(const DemuxFilterStatus status);

private:
    sp<FilterClientCallback> mFilterClientCallback;
};

struct FilterClient : public RefBase {

public:
    FilterClient(DemuxFilterType type, shared_ptr<ITunerFilter> tunerFilter);
    ~FilterClient();

    // TODO: remove after migration to Tuner Service is done.
    void setHidlFilter(sp<IFilter> filter);

    /**
     * Read size of data from filter FMQ into buffer.
     *
     * @return the actual reading size. -1 if failed to read.
     */
    int read(int8_t* buffer, int size);

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
    Result configureMonitorEvent(int monitorEventType);

    /**
     * Configure the context id of the IP Filter.
     */
    Result configureIpFilterContextId(int cid);

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
    Result getId(uint32_t& id);

    /**
     * Get the 64-bit filter Id.
     */
    Result getId64Bit(uint64_t& id);

    /**
     * Release the handle reported by the HAL for AV memory.
     */
    Result releaseAvHandle(native_handle_t* handle, uint64_t avDataId);

    /**
     * Set the filter's data source.
     */
    Result setDataSource(sp<FilterClient> filterClient);

    /**
     * Get the Hal filter to build up filter linkage.
     */
    sp<IFilter> getHalFilter() { return mFilter; }

    /**
     * Get the Aidl filter to build up filter linkage.
     */
    shared_ptr<ITunerFilter> getAidlFilter() { return mTunerFilter; }

    /**
     * Close a new interface of ITunerFilter.
     */
    Result close();

private:
    TunerFilterConfiguration getAidlFilterSettings(DemuxFilterSettings configure);

    TunerFilterConfiguration getAidlTsSettings(DemuxTsFilterSettings configure);
    TunerFilterConfiguration getAidlMmtpSettings(DemuxMmtpFilterSettings mmtp);
    TunerFilterConfiguration getAidlIpSettings(DemuxIpFilterSettings ip);
    TunerFilterConfiguration getAidlTlvSettings(DemuxTlvFilterSettings tlv);
    TunerFilterConfiguration getAidlAlpSettings(DemuxAlpFilterSettings alp);

    TunerFilterAvSettings getAidlAvSettings(DemuxFilterAvSettings hidlAv);
    TunerFilterSectionSettings getAidlSectionSettings(DemuxFilterSectionSettings hidlSection);
    TunerFilterPesDataSettings getAidlPesDataSettings(DemuxFilterPesDataSettings hidlPesData);
    TunerFilterRecordSettings getAidlRecordSettings(DemuxFilterRecordSettings hidlRecord);
    TunerFilterDownloadSettings getAidlDownloadSettings(DemuxFilterDownloadSettings hidlDownload);

    void getAidlIpAddress(DemuxIpAddress ipAddr,
            TunerDemuxIpAddress& srcIpAddress, TunerDemuxIpAddress& dstIpAddress);
    Result getFilterMq();
    int copyData(int8_t* buffer, int size);
    void checkIsMediaFilter(DemuxFilterType type);
    void checkIsPassthroughFilter(DemuxFilterSettings configure);
    void handleAvShareMemory();
    void closeAvSharedMemory();

    /**
     * An AIDL Tuner Filter Singleton assigned at the first time when the Tuner Client
     * opens a filter. Default null when Tuner Service does not exist.
     */
    shared_ptr<ITunerFilter> mTunerFilter;

    /**
     * A 1.0 Filter HAL interface that is ready before migrating to the TunerFilter.
     * This is a temprary interface before Tuner Framework migrates to use TunerService.
     * Default null when the HAL service does not exist.
     */
    sp<IFilter> mFilter;

    /**
     * A 1.1 Filter HAL interface that is ready before migrating to the TunerFilter.
     * This is a temprary interface before Tuner Framework migrates to use TunerService.
     * Default null when the HAL service does not exist.
     */
    sp<::android::hardware::tv::tuner::V1_1::IFilter> mFilter_1_1;

    AidlMQ* mFilterMQ = NULL;
    EventFlag* mFilterMQEventFlag = NULL;

    native_handle_t* mAvSharedHandle;
    uint64_t mAvSharedMemSize;
    bool mIsMediaFilter;
    bool mIsPassthroughFilter;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_FILTER_CLIENT_H_
