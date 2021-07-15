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

#include <aidl/android/media/tv/tunerresourcemanager/ITunerResourceManager.h>
#include <aidl/android/media/tv/tuner/ITunerService.h>
#include <aidl/android/media/tv/tuner/TunerFrontendInfo.h>
#include <android/binder_parcel_utils.h>
#include <android/hardware/tv/tuner/1.1/ITuner.h>
#include <android/hardware/tv/tuner/1.1/types.h>

#include "DemuxClient.h"
#include "ClientHelper.h"
#include "FrontendClient.h"
#include "DescramblerClient.h"
#include "LnbClient.h"

using Status = ::ndk::ScopedAStatus;

using ::aidl::android::media::tv::tuner::TunerDemuxCapabilities;
using ::aidl::android::media::tv::tuner::ITunerService;
using ::aidl::android::media::tv::tuner::TunerFrontendInfo;
using ::aidl::android::media::tv::tunerresourcemanager::ITunerResourceManager;

using ::android::hardware::tv::tuner::V1_0::DemuxCapabilities;
using ::android::hardware::tv::tuner::V1_0::FrontendId;
using ::android::hardware::tv::tuner::V1_0::ITuner;
using ::android::hardware::tv::tuner::V1_0::LnbId;
using ::android::hardware::tv::tuner::V1_0::Result;
using ::android::hardware::tv::tuner::V1_1::FrontendDtmbCapabilities;

using namespace std;

namespace android {

const static int TUNER_HAL_VERSION_UNKNOWN = 0;
const static int TUNER_HAL_VERSION_1_0 = 1 << 16;
const static int TUNER_HAL_VERSION_1_1 = (1 << 16) | 1;

typedef enum {
    FRONTEND,
    LNB,
    DEMUX,
    DESCRAMBLER,
} TunerResourceType;

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
    shared_ptr<DemuxCapabilities> getDemuxCaps();

    /**
     * Open a new interface of DescramblerClient given a descramblerHandle.
     *
     * @param descramblerHandle the handle of the descrambler granted by TRM.
     * @return a newly created DescramblerClient interface.
     */
    sp<DescramblerClient> openDescrambler(int descramblerHandle);

    /**
     * Open a new interface of LnbClient given an lnbHandle.
     *
     * @param lnbHandle the handle of the LNB granted by TRM.
     * @return a newly created LnbClient interface.
     */
    sp<LnbClient> openLnb(int lnbHandle);

    /**
     * Open a new interface of LnbClient given a LNB name.
     *
     * @param lnbName the name for an external LNB to be opened.
     * @return a newly created LnbClient interface.
     */
    sp<LnbClient> openLnbByName(string lnbName);

    /**
     * Get the current Tuner HAL version. The high 16 bits are the major version number
     * while the low 16 bits are the minor version. Default value is unknown version 0.
     */
    int getHalTunerVersion() { return mTunerVersion; }

private:
    sp<ITuner> getHidlTuner();
    sp<IFrontend> openHidlFrontendById(int id);
    sp<IDemux> openHidlDemux(int& demuxId);
    Result getHidlFrontendInfo(int id, FrontendInfo& info);
    sp<ILnb> openHidlLnbById(int id);
    sp<ILnb> openHidlLnbByName(string name, LnbId& lnbId);
    sp<IDescrambler> openHidlDescrambler();
    vector<int> getLnbHandles();
    DemuxCapabilities getHidlDemuxCaps(TunerDemuxCapabilities& aidlCaps);
    FrontendInfo frontendInfoAidlToHidl(TunerFrontendInfo aidlFrontendInfo);
    void updateTunerResources();
    void updateFrontendResources();
    void updateLnbResources();

    int getResourceIdFromHandle(int handle, int resourceType);

    int getResourceHandleFromId(int id, int resourceType);

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

    shared_ptr<ITunerResourceManager> mTunerResourceManager;

    int mResourceRequestCount = 0;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_TUNER_CLIENT_H_
