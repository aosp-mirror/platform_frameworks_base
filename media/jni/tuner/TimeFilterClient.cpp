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

#define LOG_TAG "TimeFilterClient"

#include "TimeFilterClient.h"

#include <aidl/android/hardware/tv/tuner/Constant64Bit.h>
#include <android-base/logging.h>
#include <utils/Log.h>

#include "ClientHelper.h"

using ::aidl::android::hardware::tv::tuner::Constant64Bit;

namespace android {

/////////////// TimeFilterClient ///////////////////////

TimeFilterClient::TimeFilterClient(shared_ptr<ITunerTimeFilter> tunerTimeFilter) {
    mTunerTimeFilter = tunerTimeFilter;
}

TimeFilterClient::~TimeFilterClient() {
    mTunerTimeFilter = nullptr;
}

Result TimeFilterClient::setTimeStamp(int64_t timeStamp) {
    if (mTunerTimeFilter != nullptr) {
        Status s = mTunerTimeFilter->setTimeStamp(timeStamp);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

Result TimeFilterClient::clearTimeStamp() {
    if (mTunerTimeFilter != nullptr) {
        Status s = mTunerTimeFilter->clearTimeStamp();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}

int64_t TimeFilterClient::getTimeStamp() {
    if (mTunerTimeFilter != nullptr) {
        int64_t timeStamp;
        Status s = mTunerTimeFilter->getTimeStamp(&timeStamp);
        if (!s.isOk()) {
            return static_cast<int64_t>(Constant64Bit::INVALID_PRESENTATION_TIME_STAMP);
        }
        return timeStamp;
    }

    return static_cast<int64_t>(Constant64Bit::INVALID_PRESENTATION_TIME_STAMP);
}

int64_t TimeFilterClient::getSourceTime() {
    if (mTunerTimeFilter != nullptr) {
        int64_t sourceTime;
        Status s = mTunerTimeFilter->getTimeStamp(&sourceTime);
        if (!s.isOk()) {
            return static_cast<int64_t>(Constant64Bit::INVALID_PRESENTATION_TIME_STAMP);
        }
        return sourceTime;
    }

    return static_cast<int64_t>(Constant64Bit::INVALID_PRESENTATION_TIME_STAMP);
}

Result TimeFilterClient::close() {
    if (mTunerTimeFilter != nullptr) {
        Status s = mTunerTimeFilter->close();
        mTunerTimeFilter = nullptr;
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    return Result::INVALID_STATE;
}
}  // namespace android
