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

#ifndef _ANDROID_MEDIA_TV_DEMUX_CLIENT_H_
#define _ANDROID_MEDIA_TV_DEMUX_CLIENT_H_

#include <aidl/android/hardware/tv/tuner/Result.h>
#include <aidl/android/media/tv/tuner/ITunerDemux.h>

#include "ClientHelper.h"
#include "DvrClient.h"
#include "DvrClientCallback.h"
#include "FilterClient.h"
#include "FilterClientCallback.h"
#include "FrontendClient.h"
#include "TimeFilterClient.h"

using Status = ::ndk::ScopedAStatus;
using ::aidl::android::hardware::tv::tuner::DemuxFilterType;
using ::aidl::android::hardware::tv::tuner::DvrType;
using ::aidl::android::hardware::tv::tuner::Result;
using ::aidl::android::media::tv::tuner::ITunerDemux;
using ::aidl::android::media::tv::tuner::ITunerTimeFilter;

using namespace std;

namespace android {

struct DemuxClient : public RefBase {

public:
    DemuxClient(shared_ptr<ITunerDemux> tunerDemux);
    ~DemuxClient();

    /**
     * Set a frontend resource as data input of the demux.
     */
    Result setFrontendDataSource(sp<FrontendClient> frontendClient);

    /**
     * Set a frontend resource by handle as data input of the demux.
     */
    Result setFrontendDataSourceById(int frontendId);

    /**
     * Open a new filter client.
     */
    sp<FilterClient> openFilter(const DemuxFilterType& type, int32_t bufferSize,
                                sp<FilterClientCallback> cb);

    /**
     * Open time filter of the demux.
     */
    sp<TimeFilterClient> openTimeFilter();

    /**
     * Get hardware sync ID for audio and video.
     */
    int32_t getAvSyncHwId(sp<FilterClient> filterClient);

    /**
     * Get current time stamp to use for A/V sync.
     */
    int64_t getAvSyncTime(int32_t avSyncHwId);

    /**
     * Open a DVR (Digital Video Record) client.
     */
    sp<DvrClient> openDvr(DvrType dvbType, int32_t bufferSize, sp<DvrClientCallback> cb);

    /**
     * Connect Conditional Access Modules (CAM) through Common Interface (CI).
     */
    Result connectCiCam(int32_t ciCamId);

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

private:
    /**
     * An AIDL Tuner Demux Singleton assigned at the first time the Tuner Client
     * opens a demux. Default null when demux is not opened.
     */
    shared_ptr<ITunerDemux> mTunerDemux;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_DEMUX_CLIENT_H_
