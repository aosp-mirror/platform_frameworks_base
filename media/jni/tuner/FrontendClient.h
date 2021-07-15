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

#include "ClientHelper.h"
#include "FrontendClientCallback.h"
#include "LnbClient.h"

using Status = ::ndk::ScopedAStatus;

using ::aidl::android::media::tv::tuner::BnTunerFrontendCallback;
using ::aidl::android::media::tv::tuner::ITunerFrontend;
using ::aidl::android::media::tv::tuner::TunerFrontendAnalogSettings;
using ::aidl::android::media::tv::tuner::TunerFrontendAtscSettings;
using ::aidl::android::media::tv::tuner::TunerFrontendAtsc3Settings;
using ::aidl::android::media::tv::tuner::TunerFrontendCableSettings;
using ::aidl::android::media::tv::tuner::TunerFrontendDvbsSettings;
using ::aidl::android::media::tv::tuner::TunerFrontendDvbtSettings;
using ::aidl::android::media::tv::tuner::TunerFrontendDtmbSettings;
using ::aidl::android::media::tv::tuner::TunerFrontendIsdbsSettings;
using ::aidl::android::media::tv::tuner::TunerFrontendIsdbs3Settings;
using ::aidl::android::media::tv::tuner::TunerFrontendIsdbtSettings;
using ::aidl::android::media::tv::tuner::TunerFrontendScanMessage;
using ::aidl::android::media::tv::tuner::TunerFrontendSettings;
using ::aidl::android::media::tv::tuner::TunerFrontendStatus;

using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::hardware::tv::tuner::V1_0::FrontendInfo;
using ::android::hardware::tv::tuner::V1_0::FrontendEventType;
using ::android::hardware::tv::tuner::V1_0::FrontendScanMessage;
using ::android::hardware::tv::tuner::V1_0::FrontendScanMessageType;
using ::android::hardware::tv::tuner::V1_0::FrontendScanType;
using ::android::hardware::tv::tuner::V1_0::FrontendSettings;
using ::android::hardware::tv::tuner::V1_0::FrontendStatus;
using ::android::hardware::tv::tuner::V1_0::FrontendStatusType;
using ::android::hardware::tv::tuner::V1_0::IFrontend;
using ::android::hardware::tv::tuner::V1_0::Result;

using ::android::hardware::tv::tuner::V1_1::FrontendScanMessageExt1_1;
using ::android::hardware::tv::tuner::V1_1::FrontendScanMessageTypeExt1_1;
using ::android::hardware::tv::tuner::V1_1::FrontendSettingsExt1_1;
using ::android::hardware::tv::tuner::V1_1::FrontendStatusExt1_1;
using ::android::hardware::tv::tuner::V1_1::FrontendStatusTypeExt1_1;
using ::android::hardware::tv::tuner::V1_1::IFrontendCallback;

using namespace std;

namespace android {

class TunerFrontendCallback : public BnTunerFrontendCallback {

public:
    TunerFrontendCallback(sp<FrontendClientCallback> frontendClientCallback);

    Status onEvent(int frontendEventType);

    Status onScanMessage(int messageType, const TunerFrontendScanMessage& message);

    void setFrontendType(int frontendType) { mType = frontendType; }

private:
    FrontendScanMessage getHalScanMessage(int messageType, const TunerFrontendScanMessage& message);
    FrontendScanMessageExt1_1 getHalScanMessageExt1_1(int messageType,
            const TunerFrontendScanMessage& message);
    bool is1_1ExtendedScanMessage(int messageType);

    sp<FrontendClientCallback> mFrontendClientCallback;
    int mType;
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
    FrontendClient(shared_ptr<ITunerFrontend> tunerFrontend, int type);
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
     * Scan the frontend to use the settings given.
     */
    Result scan(const FrontendSettings& settings, FrontendScanType frontendScanType,
            const FrontendSettingsExt1_1& settingsExt1_1);

    /**
     * Stop the previous scanning.
     */
    Result stopScan();

    /**
     * Gets the statuses of the frontend.
     */
    vector<FrontendStatus> getStatus(vector<FrontendStatusType> statusTypes);

    /**
     * Gets the 1.1 extended statuses of the frontend.
     */
    vector<FrontendStatusExt1_1> getStatusExtended_1_1(
            vector<FrontendStatusTypeExt1_1> statusTypes);

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
    int linkCiCamToFrontend(int ciCamId);

    /**
     * Unink Frontend to the cicam with given id.
     */
    Result unlinkCiCamToFrontend(int ciCamId);

    /**
     * Close Frontend.
     */
    Result close();

    shared_ptr<ITunerFrontend> getAidlFrontend();

    void setId(int id);
    int getId();

private:
    vector<FrontendStatus> getHidlStatus(vector<TunerFrontendStatus>& aidlStatus);
    vector<FrontendStatusExt1_1> getHidlStatusExt(vector<TunerFrontendStatus>& aidlStatus);

    TunerFrontendSettings getAidlFrontendSettings(
            const FrontendSettings& settings, const FrontendSettingsExt1_1& settingsExt1_1);
    TunerFrontendAnalogSettings getAidlAnalogSettings(
            const FrontendSettings& settings, const FrontendSettingsExt1_1& settingsExt1_1);
    TunerFrontendDvbsSettings getAidlDvbsSettings(
            const FrontendSettings& settings, const FrontendSettingsExt1_1& settingsExt1_1);
    TunerFrontendCableSettings getAidlCableSettings(
            const FrontendSettings& settings, const FrontendSettingsExt1_1& settingsExt1_1);
    TunerFrontendDvbtSettings getAidlDvbtSettings(
            const FrontendSettings& settings, const FrontendSettingsExt1_1& settingsExt1_1);
    TunerFrontendDtmbSettings getAidlDtmbSettings(const FrontendSettingsExt1_1& settingsExt1_1);
    TunerFrontendAtscSettings getAidlAtscSettings(const FrontendSettings& settings);
    TunerFrontendAtsc3Settings getAidlAtsc3Settings(const FrontendSettings& settings);
    TunerFrontendIsdbsSettings getAidlIsdbsSettings(const FrontendSettings& settings);
    TunerFrontendIsdbs3Settings getAidlIsdbs3Settings(const FrontendSettings& settings);
    TunerFrontendIsdbtSettings getAidlIsdbtSettings(const FrontendSettings& settings);

    bool validateExtendedSettings(const FrontendSettingsExt1_1& settingsExt1_1);

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

    int mId;
    int mType;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_FRONTEND_CLIENT_H_
