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

LnbClient::LnbClient(shared_ptr<ITunerLnb> tunerLnb) {
    mTunerLnb = tunerLnb;
    mId = -1;
}

LnbClient::~LnbClient() {
    mTunerLnb = NULL;
    mLnb = NULL;
    mId = -1;
}

// TODO: remove after migration to Tuner Service is done.
void LnbClient::setHidlLnb(sp<ILnb> lnb) {
    mLnb = lnb;
}

Result LnbClient::setCallback(sp<LnbClientCallback> cb) {
    if (mTunerLnb != NULL) {
        shared_ptr<TunerLnbCallback> aidlCallback =
                ::ndk::SharedRefBase::make<TunerLnbCallback>(cb);
        Status s = mTunerLnb->setCallback(aidlCallback);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mLnb != NULL) {
        sp<HidlLnbCallback> hidlCallback = new HidlLnbCallback(cb);
        return mLnb->setCallback(hidlCallback);
    }

    return Result::INVALID_STATE;
}

Result LnbClient::setVoltage(LnbVoltage voltage) {
    if (mTunerLnb != NULL) {
        Status s = mTunerLnb->setVoltage((int)voltage);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mLnb != NULL) {
        return mLnb->setVoltage(voltage);
    }

    return Result::INVALID_STATE;
}

Result LnbClient::setTone(LnbTone tone) {
    if (mTunerLnb != NULL) {
        Status s = mTunerLnb->setTone((int)tone);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mLnb != NULL) {
        return mLnb->setTone(tone);
    }

    return Result::INVALID_STATE;
}

Result LnbClient::setSatellitePosition(LnbPosition position) {
    if (mTunerLnb != NULL) {
        Status s = mTunerLnb->setSatellitePosition((int)position);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mLnb != NULL) {
        return mLnb->setSatellitePosition(position);
    }

    return Result::INVALID_STATE;
}

Result LnbClient::sendDiseqcMessage(vector<uint8_t> diseqcMessage) {
    if (mTunerLnb != NULL) {
        Status s = mTunerLnb->sendDiseqcMessage(diseqcMessage);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mLnb != NULL) {
        return mLnb->sendDiseqcMessage(diseqcMessage);
    }

    return Result::INVALID_STATE;
}

Result LnbClient::close() {
    if (mTunerLnb != NULL) {
        Status s = mTunerLnb->close();
        mTunerLnb = NULL;
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mLnb != NULL) {
        Result res = mLnb->close();
        mLnb = NULL;
        return res;
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

/////////////// TunerLnbCallback ///////////////////////

TunerLnbCallback::TunerLnbCallback(sp<LnbClientCallback> lnbClientCallback)
        : mLnbClientCallback(lnbClientCallback) {}

Status TunerLnbCallback::onEvent(int lnbEventType) {
    if (mLnbClientCallback != NULL) {
        mLnbClientCallback->onEvent(static_cast<LnbEventType>(lnbEventType));
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

Status TunerLnbCallback::onDiseqcMessage(const vector<uint8_t>& diseqcMessage) {
    if (mLnbClientCallback != NULL) {
        hidl_vec<uint8_t> msg(begin(diseqcMessage), end(diseqcMessage));
        mLnbClientCallback->onDiseqcMessage(msg);
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}
}  // namespace android
