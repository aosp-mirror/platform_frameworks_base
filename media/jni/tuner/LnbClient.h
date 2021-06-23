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

#ifndef _ANDROID_MEDIA_TV_LNB_CLIENT_H_
#define _ANDROID_MEDIA_TV_LNB_CLIENT_H_

#include <aidl/android/media/tv/tuner/BnTunerLnbCallback.h>
#include <aidl/android/media/tv/tuner/ITunerLnb.h>
#include <android/hardware/tv/tuner/1.0/ILnb.h>
#include <android/hardware/tv/tuner/1.0/ILnbCallback.h>
#include <android/hardware/tv/tuner/1.1/types.h>

#include "ClientHelper.h"
#include "LnbClientCallback.h"

using Status = ::ndk::ScopedAStatus;

using ::aidl::android::media::tv::tuner::BnTunerLnbCallback;
using ::aidl::android::media::tv::tuner::ITunerLnb;

using ::android::hardware::Return;
using ::android::hardware::Void;
using ::android::hardware::hidl_vec;
using ::android::hardware::tv::tuner::V1_0::ILnb;
using ::android::hardware::tv::tuner::V1_0::ILnbCallback;
using ::android::hardware::tv::tuner::V1_0::LnbId;
using ::android::hardware::tv::tuner::V1_0::LnbPosition;
using ::android::hardware::tv::tuner::V1_0::LnbTone;
using ::android::hardware::tv::tuner::V1_0::LnbVoltage;
using ::android::hardware::tv::tuner::V1_0::Result;

using namespace std;

namespace android {

class TunerLnbCallback : public BnTunerLnbCallback {

public:
    TunerLnbCallback(sp<LnbClientCallback> lnbClientCallback);

    Status onEvent(int lnbEventType);
    Status onDiseqcMessage(const vector<uint8_t>& diseqcMessage);

private:
    sp<LnbClientCallback> mLnbClientCallback;
};

struct HidlLnbCallback : public ILnbCallback {

public:
    HidlLnbCallback(sp<LnbClientCallback> lnbClientCallback);
    virtual Return<void> onEvent(const LnbEventType lnbEventType);
    virtual Return<void> onDiseqcMessage(const hidl_vec<uint8_t>& diseqcMessage);

private:
    sp<LnbClientCallback> mLnbClientCallback;
};

struct LnbClient : public RefBase {

public:
    LnbClient(shared_ptr<ITunerLnb> tunerLnb);
    ~LnbClient();

    // TODO: remove after migration to Tuner Service is done.
    void setHidlLnb(sp<ILnb> lnb);

    /**
     * Set the lnb callback.
     */
    Result setCallback(sp<LnbClientCallback> cb);

    /**
     * Set the lnb's power voltage.
     */
    Result setVoltage(LnbVoltage voltage);

    /**
     * Set the lnb's tone mode.
     */
    Result setTone(LnbTone tone);

    /**
     * Select the lnb's position.
     */
    Result setSatellitePosition(LnbPosition position);

    /**
     * Sends DiSEqC (Digital Satellite Equipment Control) message.
     */
    Result sendDiseqcMessage(vector<uint8_t> diseqcMessage);

    /**
     * Releases the LNB instance.
     */
    Result close();

    shared_ptr<ITunerLnb> getAidlLnb() { return mTunerLnb; }
    void setId(LnbId id) { mId = id; }
    LnbId getId() { return mId; }

private:
    /**
     * An AIDL Tuner Lnb Singleton assigned at the first time the Tuner Client
     * opens an Lnb. Default null when lnb is not opened.
     */
    shared_ptr<ITunerLnb> mTunerLnb;

    /**
     * A Lnb HAL interface that is ready before migrating to the TunerLnb.
     * This is a temprary interface before Tuner Framework migrates to use TunerService.
     * Default null when the HAL service does not exist.
     */
    sp<ILnb> mLnb;

    LnbId mId;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_LNB_CLIENT_H_
