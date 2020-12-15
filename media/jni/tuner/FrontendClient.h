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

#ifndef _ANDROID_MEDIA_TV_FRONTEND_CLIENT_H_
#define _ANDROID_MEDIA_TV_FRONTEND_CLIENT_H_

#include <aidl/android/media/tv/tuner/BnTunerFrontendCallback.h>
#include <aidl/android/media/tv/tuner/ITunerFrontend.h>
#include <android/hardware/tv/tuner/1.1/IFrontend.h>
#include <android/hardware/tv/tuner/1.1/IFrontendCallback.h>
#include <android/hardware/tv/tuner/1.1/types.h>

#include "FrontendClientCallback.h"

using Status = ::ndk::ScopedAStatus;

using ::aidl::android::media::tv::tuner::BnTunerFrontendCallback;
using ::aidl::android::media::tv::tuner::ITunerFrontend;
using ::aidl::android::media::tv::tuner::TunerAtsc3PlpInfo;

using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::hardware::tv::tuner::V1_0::FrontendInfo;
using ::android::hardware::tv::tuner::V1_0::FrontendEventType;
using ::android::hardware::tv::tuner::V1_0::FrontendScanMessage;
using ::android::hardware::tv::tuner::V1_0::FrontendScanMessageType;
using ::android::hardware::tv::tuner::V1_0::FrontendSettings;
using ::android::hardware::tv::tuner::V1_0::IFrontend;
using ::android::hardware::tv::tuner::V1_0::Result;

using ::android::hardware::tv::tuner::V1_1::FrontendScanMessageExt1_1;
using ::android::hardware::tv::tuner::V1_1::FrontendScanMessageTypeExt1_1;
using ::android::hardware::tv::tuner::V1_1::FrontendSettingsExt1_1;
using ::android::hardware::tv::tuner::V1_1::IFrontendCallback;

using namespace std;

namespace android {

class TunerFrontendCallback : public BnTunerFrontendCallback {

public:
    TunerFrontendCallback(sp<FrontendClientCallback> frontendClientCallback);

    Status onEvent(int frontendEventType);

    Status onLocked();

    Status onScanStopped();

    Status onProgress(int percent);

    Status onFrequenciesReport(const vector<int>& frequency);

    Status onSymbolRates(const vector<int>& rates);

    Status onHierarchy(int hierarchy);

    Status onSignalType(int signalType);

    Status onPlpIds(const vector<int>& plpIds);

    Status onGroupIds(const vector<int>& groupIds);

    Status onInputStreamIds(const vector<int>& inputStreamIds);

    Status onDvbsStandard(int dvbsStandandard);

    Status onAnalogSifStandard(int sifStandandard);

    Status onAtsc3PlpInfos(const vector<TunerAtsc3PlpInfo>& atsc3PlpInfos);

private:
    sp<FrontendClientCallback> mFrontendClientCallback;
};

struct HidlFrontendCallback : public IFrontendCallback {

public:
    HidlFrontendCallback(sp<FrontendClientCallback> frontendClientCallback);

    virtual Return<void> onEvent(FrontendEventType frontendEventType);
    virtual Return<void> onScanMessage(
            FrontendScanMessageType type, const FrontendScanMessage& message);
    virtual Return<void> onScanMessageExt1_1(
            FrontendScanMessageTypeExt1_1 type, const FrontendScanMessageExt1_1& messageExt);

private:
    sp<FrontendClientCallback> mFrontendClientCallback;
};

struct FrontendClient : public RefBase {

public:
    FrontendClient(shared_ptr<ITunerFrontend> tunerFrontend);
    ~FrontendClient();

    /**
     * Set a FrontendClientCallback to receive frontend events and scan messages.
     */
    Result setCallback(sp<FrontendClientCallback> frontendClientCallback);

    // TODO: remove after migration to Tuner Service is done.
    void setHidlFrontend(sp<IFrontend> frontend);

    /**
     * Tuner Frontend with Frontend Settings.
     */
    Result tune(const FrontendSettings& settings, const FrontendSettingsExt1_1& settingsExt1_1);

    /**
     * Stop tune Frontend.
     */
    Result stopTune();

    /**
     * Close Frontend.
     */
    Result close();

private:
    /**
     * An AIDL Tuner Frontend Singleton assigned at the first time when the Tuner Client
     * opens a frontend cient. Default null when the service does not exist.
     */
    shared_ptr<ITunerFrontend> mTunerFrontend;

    /**
     * A Frontend 1.0 HAL interface as a fall back interface when the Tuner Service does not exist.
     * This is a temprary connection before the Tuner Framework fully migrates to the TunerService.
     * Default null.
     */
    sp<IFrontend> mFrontend;

    /**
     * A Frontend 1.1 HAL interface as a fall back interface when the Tuner Service does not exist.
     * This is a temprary connection before the Tuner Framework fully migrates to the TunerService.
     * Default null.
     */
    sp<::android::hardware::tv::tuner::V1_1::IFrontend> mFrontend_1_1;

    shared_ptr<TunerFrontendCallback> mAidlCallback;
    sp<HidlFrontendCallback> mHidlCallback;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_FRONTEND_CLIENT_H_
