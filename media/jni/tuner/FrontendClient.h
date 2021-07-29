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

#ifndef _ANDROID_MEDIA_TV_FRONTEND_CLIENT_H_
#define _ANDROID_MEDIA_TV_FRONTEND_CLIENT_H_

#include <aidl/android/hardware/tv/tuner/DemuxFilterSettings.h>
#include <aidl/android/hardware/tv/tuner/FrontendType.h>
#include <aidl/android/hardware/tv/tuner/Result.h>
#include <aidl/android/media/tv/tuner/BnTunerFrontendCallback.h>
#include <aidl/android/media/tv/tuner/ITunerFrontend.h>
#include <utils/RefBase.h>

#include "ClientHelper.h"
#include "FrontendClientCallback.h"
#include "LnbClient.h"

using Status = ::ndk::ScopedAStatus;
using ::aidl::android::hardware::tv::tuner::FrontendEventType;
using ::aidl::android::hardware::tv::tuner::FrontendScanMessage;
using ::aidl::android::hardware::tv::tuner::FrontendScanMessageType;
using ::aidl::android::hardware::tv::tuner::FrontendScanType;
using ::aidl::android::hardware::tv::tuner::FrontendSettings;
using ::aidl::android::hardware::tv::tuner::FrontendStatus;
using ::aidl::android::hardware::tv::tuner::FrontendStatusType;
using ::aidl::android::hardware::tv::tuner::FrontendType;
using ::aidl::android::hardware::tv::tuner::Result;
using ::aidl::android::media::tv::tuner::BnTunerFrontendCallback;
using ::aidl::android::media::tv::tuner::ITunerFrontend;

using namespace std;

namespace android {

class TunerFrontendCallback : public BnTunerFrontendCallback {

public:
    TunerFrontendCallback(sp<FrontendClientCallback> frontendClientCallback);

    Status onEvent(FrontendEventType frontendEventType);
    Status onScanMessage(FrontendScanMessageType messageType, const FrontendScanMessage& message);

private:
    sp<FrontendClientCallback> mFrontendClientCallback;
};

struct FrontendClient : public RefBase {

public:
    FrontendClient(shared_ptr<ITunerFrontend> tunerFrontend, FrontendType type);
    ~FrontendClient();

    /**
     * Set a FrontendClientCallback to receive frontend events and scan messages.
     */
    Result setCallback(sp<FrontendClientCallback> frontendClientCallback);

    /**
     * Tuner Frontend with Frontend Settings.
     */
    Result tune(const FrontendSettings& settings);

    /**
     * Stop tune Frontend.
     */
    Result stopTune();

    /**
     * Scan the frontend to use the settings given.
     */
    Result scan(const FrontendSettings& settings, FrontendScanType frontendScanType);

    /**
     * Stop the previous scanning.
     */
    Result stopScan();

    /**
     * Gets the statuses of the frontend.
     */
    vector<FrontendStatus> getStatus(vector<FrontendStatusType> statusTypes);

    /**
     * Sets Low-Noise Block downconverter (LNB) for satellite frontend.
     */
    Result setLnb(sp<LnbClient> lnbClient);

    /**
     * Enable or Disable Low Noise Amplifier (LNA).
     */
    Result setLna(bool bEnable);

    /**
     * Link Frontend to the cicam with given id.
     *
     * @return lts id
     */
    int32_t linkCiCamToFrontend(int32_t ciCamId);

    /**
     * Unink Frontend to the cicam with given id.
     */
    Result unlinkCiCamToFrontend(int32_t ciCamId);

    /**
     * Close Frontend.
     */
    Result close();

    int32_t getId();
    shared_ptr<ITunerFrontend> getAidlFrontend();
private:
    /**
     * An AIDL Tuner Frontend Singleton assigned at the first time when the Tuner Client
     * opens a frontend cient. Default null when the service does not exist.
     */
    shared_ptr<ITunerFrontend> mTunerFrontend;

    FrontendType mType;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_FRONTEND_CLIENT_H_
