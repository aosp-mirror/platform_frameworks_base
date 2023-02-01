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

#include <aidl/android/hardware/tv/tuner/LnbPosition.h>
#include <aidl/android/hardware/tv/tuner/LnbTone.h>
#include <aidl/android/hardware/tv/tuner/LnbVoltage.h>
#include <aidl/android/media/tv/tuner/BnTunerLnbCallback.h>
#include <aidl/android/media/tv/tuner/ITunerLnb.h>
#include <utils/RefBase.h>

#include "ClientHelper.h"
#include "LnbClientCallback.h"

using Status = ::ndk::ScopedAStatus;

using ::aidl::android::hardware::tv::tuner::LnbPosition;
using ::aidl::android::hardware::tv::tuner::LnbTone;
using ::aidl::android::hardware::tv::tuner::LnbVoltage;
using ::aidl::android::media::tv::tuner::BnTunerLnbCallback;
using ::aidl::android::media::tv::tuner::ITunerLnb;

using namespace std;

namespace android {

class TunerLnbCallback : public BnTunerLnbCallback {

public:
    TunerLnbCallback(sp<LnbClientCallback> lnbClientCallback);

    Status onEvent(LnbEventType lnbEventType);
    Status onDiseqcMessage(const vector<uint8_t>& diseqcMessage);

private:
    sp<LnbClientCallback> mLnbClientCallback;
};

struct LnbClient : public RefBase {

public:
    LnbClient(shared_ptr<ITunerLnb> tunerLnb);
    ~LnbClient();

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

private:
    /**
     * An AIDL Tuner Lnb Singleton assigned at the first time the Tuner Client
     * opens an Lnb. Default null when lnb is not opened.
     */
    shared_ptr<ITunerLnb> mTunerLnb;
};
}  // namespace android

#endif  // _ANDROID_MEDIA_TV_LNB_CLIENT_H_
