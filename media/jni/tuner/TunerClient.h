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

#ifndef _ANDROID_MEDIA_TV_TUNER_CLIENT_H_
#define _ANDROID_MEDIA_TV_TUNER_CLIENT_H_

#include <aidl/android/media/tv/tuner/ITunerService.h>
#include <android/binder_parcel_utils.h>

#include "ClientHelper.h"
#include "DemuxClient.h"
#include "DescramblerClient.h"
#include "FilterClient.h"
#include "FilterClientCallback.h"
#include "FrontendClient.h"
#include "LnbClient.h"

using Status = ::ndk::ScopedAStatus;

using ::aidl::android::hardware::tv::tuner::DemuxCapabilities;
using ::aidl::android::hardware::tv::tuner::FrontendInfo;
using ::aidl::android::hardware::tv::tuner::FrontendType;
using ::aidl::android::hardware::tv::tuner::Result;
using ::aidl::android::media::tv::tuner::ITunerService;

using namespace std;

namespace android {

const static int32_t TUNER_HAL_VERSION_UNKNOWN = 0;
const static int32_t TUNER_HAL_VERSION_1_0 = 1 << 16;
const static int32_t TUNER_HAL_VERSION_1_1 = (1 << 16) | 1;
const static int32_t TUNER_HAL_VERSION_2_0 = 2 << 16;

struct TunerClient : public RefBase {

public:
    TunerClient();
    ~TunerClient();

    /**
     * Retrieve all the frontend ids.
     *
     * @return a list of the available frontend ids
     */
    vector<int32_t> getFrontendIds();

    /**
     * Open a new interface of FrontendClient given a frontendHandle.
     *
     * @param frontendHandle the handle of the frontend granted by TRM.
     * @return a newly created FrontendClient interface.
     */
    sp<FrontendClient> openFrontend(int32_t frontendHandle);

    /**
     * Retrieve the granted frontend's information.
     *
     * @param id the id of the frontend granted by TRM.
     * @return the information for the frontend.
     */
    shared_ptr<FrontendInfo> getFrontendInfo(int32_t id);

    /**
     * Open a new interface of DemuxClient given a demuxHandle.
     *
     * @param demuxHandle the handle of the demux granted by TRM.
     * @return a newly created DemuxClient interface.
     */
    sp<DemuxClient> openDemux(int32_t demuxHandle);

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
    sp<DescramblerClient> openDescrambler(int32_t descramblerHandle);

    /**
     * Open a new interface of LnbClient given an lnbHandle.
     *
     * @param lnbHandle the handle of the LNB granted by TRM.
     * @return a newly created LnbClient interface.
     */
    sp<LnbClient> openLnb(int32_t lnbHandle);

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
    int32_t getHalTunerVersion() { return mTunerVersion; }

    /**
     * Open a new shared filter client.
     *
     * @param filterToken the shared filter token created by FilterClient.
     * @param cb the FilterClientCallback to receive filter events.
     * @return a newly created TunerFilter interface.
     */
    sp<FilterClient> openSharedFilter(const string& filterToken, sp<FilterClientCallback> cb);

    /**
     * Enable or Disable Low Noise Amplifier (LNA).
     */
    Result setLna(bool bEnable);

    /**
     * Set the maximum frontend number of a given frontend type.
     *
     * @param frontendType the frontend type which maximum number will be set.
     * @param maxNumber the new maximum number.
     */
    Result setMaxNumberOfFrontends(FrontendType frontendType, int32_t maxNumber);

    /**
     * Get the maximum frontend number of a given frontend type.
     *
     * @param frontendType the frontend type which maximum number will be queried.
     */
    int getMaxNumberOfFrontends(FrontendType frontendType);

private:
    /**
     * An AIDL Tuner Service Singleton assigned at the first time the Tuner Client
     * connects with the Tuner Service. Default null when the service does not exist.
     */
    static shared_ptr<ITunerService> mTunerService;

    // An integer that carries the Tuner version. The high 16 bits are the major version number
    // while the low 16 bits are the minor version. Default value is unknown version 0.
    static int32_t mTunerVersion;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_TUNER_CLIENT_H_
