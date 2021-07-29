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

namespace android {

/////////////// LnbClient ///////////////////////
LnbClient::LnbClient(shared_ptr<ITunerLnb> tunerLnb) {
    mTunerLnb = tunerLnb;
}

LnbClient::~LnbClient() {
    mTunerLnb = nullptr;
}

Result LnbClient::setCallback(sp<LnbClientCallback> cb) {
    if (mTunerLnb != nullptr) {
        shared_ptr<TunerLnbCallback> aidlCallback =
                ::ndk::SharedRefBase::make<TunerLnbCallback>(cb);
        Status s = mTunerLnb->setCallback(aidlCallback);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result LnbClient::setVoltage(LnbVoltage voltage) {
    if (mTunerLnb != nullptr) {
        Status s = mTunerLnb->setVoltage(voltage);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result LnbClient::setTone(LnbTone tone) {
    if (mTunerLnb != nullptr) {
        Status s = mTunerLnb->setTone(tone);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result LnbClient::setSatellitePosition(LnbPosition position) {
    if (mTunerLnb != nullptr) {
        Status s = mTunerLnb->setSatellitePosition(position);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result LnbClient::sendDiseqcMessage(vector<uint8_t> diseqcMessage) {
    if (mTunerLnb != nullptr) {
        Status s = mTunerLnb->sendDiseqcMessage(diseqcMessage);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result LnbClient::close() {
    if (mTunerLnb != nullptr) {
        Status s = mTunerLnb->close();
        mTunerLnb = nullptr;
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

/////////////// TunerLnbCallback ///////////////////////
TunerLnbCallback::TunerLnbCallback(sp<LnbClientCallback> lnbClientCallback)
        : mLnbClientCallback(lnbClientCallback) {}

Status TunerLnbCallback::onEvent(LnbEventType lnbEventType) {
    if (mLnbClientCallback != nullptr) {
        mLnbClientCallback->onEvent(lnbEventType);
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}

Status TunerLnbCallback::onDiseqcMessage(const vector<uint8_t>& diseqcMessage) {
    if (mLnbClientCallback != nullptr) {
        mLnbClientCallback->onDiseqcMessage(diseqcMessage);
        return Status::ok();
    }
    return Status::fromServiceSpecificError(static_cast<int32_t>(Result::INVALID_STATE));
}
}  // namespace android
