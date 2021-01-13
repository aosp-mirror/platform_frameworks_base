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

#define LOG_TAG "LnbClient"

#include <android-base/logging.h>
#include <utils/Log.h>

#include "LnbClient.h"

using ::android::hardware::tv::tuner::V1_0::Result;

namespace android {

/////////////// LnbClient ///////////////////////

// TODO: pending aidl interface
LnbClient::LnbClient() {
    //mTunerLnb = tunerLnb;
    mId = -1;
}

LnbClient::~LnbClient() {
    //mTunerLnb = NULL;
    mLnb = NULL;
    mId = -1;
}

// TODO: remove after migration to Tuner Service is done.
void LnbClient::setHidlLnb(sp<ILnb> lnb) {
    mLnb = lnb;
}

Result LnbClient::setCallback(sp<LnbClientCallback> cb) {
    // TODO: pending aidl interface
    /*if (mTunerFrontend != NULL) {
        mAidlCallback = ::ndk::SharedRefBase::make<TunerLnbCallback>(cb);
        mTunerLnb->setCallback(mAidlCallback);
        return Result::SUCCESS;
    }*/

    mHidlCallback = new HidlLnbCallback(cb);
    return mLnb->setCallback(mHidlCallback);
}

Result LnbClient::setVoltage(LnbVoltage voltage) {
    // TODO: pending aidl interface

    if (mLnb != NULL) {
        return mLnb->setVoltage(voltage);
    }

    return Result::INVALID_STATE;
}

Result LnbClient::setTone(LnbTone tone) {
    // TODO: pending aidl interface

    if (mLnb != NULL) {
        return mLnb->setTone(tone);
    }

    return Result::INVALID_STATE;
}

Result LnbClient::setSatellitePosition(LnbPosition position) {
    // TODO: pending aidl interface

    if (mLnb != NULL) {
        return mLnb->setSatellitePosition(position);
    }

    return Result::INVALID_STATE;
}

Result LnbClient::sendDiseqcMessage(vector<uint8_t> diseqcMessage) {
    // TODO: pending aidl interface

    if (mLnb != NULL) {
        return mLnb->sendDiseqcMessage(diseqcMessage);
    }

    return Result::INVALID_STATE;
}

Result LnbClient::close() {
    // TODO: pending aidl interface

    if (mLnb != NULL) {
        return mLnb->close();
    }

    return Result::INVALID_STATE;
}

/////////////// ILnbCallback ///////////////////////

HidlLnbCallback::HidlLnbCallback(sp<LnbClientCallback> lnbClientCallback)
        : mLnbClientCallback(lnbClientCallback) {}

Return<void> HidlLnbCallback::onEvent(const LnbEventType lnbEventType) {
    if (mLnbClientCallback != NULL) {
        mLnbClientCallback->onEvent(lnbEventType);
    }
    return Void();
}

Return<void> HidlLnbCallback::onDiseqcMessage(const hidl_vec<uint8_t>& diseqcMessage) {
    if (mLnbClientCallback != NULL) {
        mLnbClientCallback->onDiseqcMessage(diseqcMessage);
    }
    return Void();
}

/////////////// LnbClient Helper Methods ///////////////////////

}  // namespace android
