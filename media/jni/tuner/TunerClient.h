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

#ifndef _ANDROID_MEDIA_TV_TUNER_CLIENT_H_
#define _ANDROID_MEDIA_TV_TUNER_CLIENT_H_

#include <aidl/android/media/tv/tuner/ITunerService.h>
#include <android/hardware/tv/tuner/1.1/ITuner.h>
#include <android/hardware/tv/tuner/1.1/types.h>

#include "FrontendClient.h"
#include "DemuxClient.h"

using ::aidl::android::media::tv::tuner::ITunerService;
using ::aidl::android::media::tv::tuner::TunerServiceFrontendInfo;

using ::android::hardware::tv::tuner::V1_0::DemuxCapabilities;
using ::android::hardware::tv::tuner::V1_0::FrontendId;
using ::android::hardware::tv::tuner::V1_0::ITuner;
using ::android::hardware::tv::tuner::V1_0::Result;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbCapabilities;

using namespace std;

namespace android {

struct TunerClient : public RefBase {

public:
    TunerClient();
    ~TunerClient();

    /**
     * Retrieve all the frontend ids.
     *
     * @return a list of the available frontend ids
     */
    vector<FrontendId> getFrontendIds();

    /**
     * Open a new interface of FrontendClient given a frontendHandle.
     *
     * @param frontendHandle the handle of the frontend granted by TRM.
     * @return a newly created FrontendClient interface.
     */
    sp<FrontendClient> openFrontend(int frontendHandle);

    /**
     * Retrieve the granted frontend's information.
     *
     * @param id the id of the frontend granted by TRM.
     * @return the information for the frontend.
     */
    shared_ptr<FrontendInfo> getFrontendInfo(int id);

    /**
     * Retrieve the DTMB frontend's capabilities.
     *
     * @param id the id of the DTMB frontend.
     * @return the capabilities of the frontend.
     */
    shared_ptr<FrontendDtmbCapabilities> getFrontendDtmbCapabilities(int id);

    /**
     * Open a new interface of DemuxClient given a demuxHandle.
     *
     * @param demuxHandle the handle of the demux granted by TRM.
     * @return a newly created DemuxClient interface.
     */
    sp<DemuxClient> openDemux(int demuxHandle);

    /**
     * Retrieve the Demux capabilities.
     *
     * @return the demux’s capabilities.
     */
    //DemuxCapabilities getDemuxCaps() {};

    /**
     * Get the current Tuner HAL version. The high 16 bits are the major version number
     * while the low 16 bits are the minor version. Default value is unknown version 0.
     */
    int getHalTunerVersion() { return mTunerVersion; }

    static int getResourceIdFromHandle(int handle) {
        return (handle & 0x00ff0000) >> 16;
    }

private:
    /**
     * An AIDL Tuner Service Singleton assigned at the first time the Tuner Client
     * connects with the Tuner Service. Default null when the service does not exist.
     */
    static shared_ptr<ITunerService> mTunerService;

    /**
     * A Tuner 1.0 HAL interface that is ready before connecting to the TunerService
     * This is a temprary connection before the Tuner Framework fully migrates to the TunerService.
     * Default null.
     */
    static sp<ITuner> mTuner;

    /**
     * A Tuner 1.1 HAL interface that is ready before connecting to the TunerService
     * This is a temprary connection before the Tuner Framework fully migrates to the TunerService.
     * Default null.
     */
    static sp<::android::hardware::tv::tuner::V1_1::ITuner> mTuner_1_1;

    // An integer that carries the Tuner version. The high 16 bits are the major version number
    // while the low 16 bits are the minor version. Default value is unknown version 0.
    static int mTunerVersion;

    sp<ITuner> getHidlTuner();
    sp<IFrontend> openHidlFrontendByHandle(int frontendHandle);
    sp<IDemux> openHidlDemux();
    Result getHidlFrontendInfo(int id, FrontendInfo& info);
    FrontendInfo FrontendInfoAidlToHidl(TunerServiceFrontendInfo aidlFrontendInfo);
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_TUNER_CLIENT_H_
