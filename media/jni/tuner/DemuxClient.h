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

#ifndef _ANDROID_MEDIA_TV_DEMUX_CLIENT_H_
#define _ANDROID_MEDIA_TV_DEMUX_CLIENT_H_

#include <aidl/android/media/tv/tuner/ITunerDemux.h>
#include <android/hardware/tv/tuner/1.0/IDemux.h>
#include <android/hardware/tv/tuner/1.1/types.h>

#include "DvrClient.h"
#include "ClientHelper.h"
#include "DvrClientCallback.h"
#include "FilterClient.h"
#include "FilterClientCallback.h"
#include "FrontendClient.h"
#include "TimeFilterClient.h"

using Status = ::ndk::ScopedAStatus;
using ::aidl::android::media::tv::tuner::ITunerDemux;
using ::aidl::android::media::tv::tuner::ITunerTimeFilter;

using ::android::hardware::tv::tuner::V1_0::IDemux;
using ::android::hardware::tv::tuner::V1_0::DemuxFilterType;
using ::android::hardware::tv::tuner::V1_0::DvrType;
using ::android::hardware::tv::tuner::V1_0::IDemux;
using ::android::hardware::tv::tuner::V1_0::ITimeFilter;

using namespace std;

const int64_t INVALID_AV_SYNC_TIME = -1;
const int INVALID_AV_SYNC_HW_ID = -1;

namespace android {

struct DemuxClient : public RefBase {

public:
    DemuxClient(shared_ptr<ITunerDemux> tunerDemux);
    ~DemuxClient();

    // TODO: remove after migration to Tuner Service is done.
    void setHidlDemux(sp<IDemux> demux);

    /**
     * Set a frontend resource as data input of the demux.
     */
    Result setFrontendDataSource(sp<FrontendClient> frontendClient);

    /**
     * Open a new filter client.
     */
    sp<FilterClient> openFilter(DemuxFilterType type, int bufferSize, sp<FilterClientCallback> cb);

    /**
     * Open time filter of the demux.
     */
    sp<TimeFilterClient> openTimeFilter();

    /**
     * Get hardware sync ID for audio and video.
     */
    int getAvSyncHwId(sp<FilterClient> filterClient);

    /**
     * Get current time stamp to use for A/V sync.
     */
    long getAvSyncTime(int avSyncHwId);

    /**
     * Open a DVR (Digital Video Record) client.
     */
    sp<DvrClient> openDvr(DvrType dvbType, int bufferSize, sp<DvrClientCallback> cb);

    /**
     * Connect Conditional Access Modules (CAM) through Common Interface (CI).
     */
    Result connectCiCam(int ciCamId);

    /**
     * Disconnect Conditional Access Modules (CAM).
     */
    Result disconnectCiCam();

    /**
     * Release the Demux Client.
     */
    Result close();

    /**
     * Get the Aidl demux to set as source.
     */
    shared_ptr<ITunerDemux> getAidlDemux() { return mTunerDemux; }

    void setId(int id) { mId = id; }
    int getId() { return mId; }

private:
    sp<IFilter> openHidlFilter(DemuxFilterType type, int bufferSize, sp<HidlFilterCallback> cb);
    sp<ITimeFilter> openHidlTimeFilter();
    sp<IDvr> openHidlDvr(DvrType type, int bufferSize, sp<HidlDvrCallback> cb);
    int getSubType(DemuxFilterType filterType);

    /**
     * An AIDL Tuner Demux Singleton assigned at the first time the Tuner Client
     * opens a demux. Default null when demux is not opened.
     */
    shared_ptr<ITunerDemux> mTunerDemux;

    /**
     * A Demux HAL interface that is ready before migrating to the TunerDemux.
     * This is a temprary interface before Tuner Framework migrates to use TunerService.
     * Default null when the HAL service does not exist.
     */
    sp<IDemux> mDemux;

    int mId;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_DEMUX_CLIENT_H_
