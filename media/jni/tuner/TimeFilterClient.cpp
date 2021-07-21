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

#include <android-base/logging.h>
#include <utils/Log.h>

#include "ClientHelper.h"
#include "TimeFilterClient.h"

using ::android::hardware::tv::tuner::V1_0::Result;
using ::android::hardware::tv::tuner::V1_1::Constant64Bit;

namespace android {

/////////////// TimeFilterClient ///////////////////////

TimeFilterClient::TimeFilterClient(shared_ptr<ITunerTimeFilter> tunerTimeFilter) {
    mTunerTimeFilter = tunerTimeFilter;
}

TimeFilterClient::~TimeFilterClient() {
    mTunerTimeFilter = NULL;
    mTimeFilter = NULL;
}

// TODO: remove after migration to Tuner Service is done.
void TimeFilterClient::setHidlTimeFilter(sp<ITimeFilter> timeFilter) {
    mTimeFilter = timeFilter;
}

Result TimeFilterClient::setTimeStamp(long timeStamp) {
    if (mTunerTimeFilter != NULL) {
        Status s = mTunerTimeFilter->setTimeStamp(timeStamp);
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mTimeFilter != NULL) {
        return mTimeFilter->setTimeStamp(timeStamp);
    }

    return Result::INVALID_STATE;
}

Result TimeFilterClient::clearTimeStamp() {
    if (mTunerTimeFilter != NULL) {
        Status s = mTunerTimeFilter->clearTimeStamp();
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mTimeFilter != NULL) {
        return mTimeFilter->clearTimeStamp();
    }

    return Result::INVALID_STATE;
}

long TimeFilterClient::getTimeStamp() {
    if (mTunerTimeFilter != NULL) {
        int64_t timeStamp;
        Status s = mTunerTimeFilter->getTimeStamp(&timeStamp);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return (long)Constant64Bit::INVALID_PRESENTATION_TIME_STAMP;
        }
        return timeStamp;
    }

    if (mTimeFilter != NULL) {
        Result res;
        long timestamp;
        mTimeFilter->getTimeStamp(
                [&](Result r, uint64_t t) {
                    res = r;
                    timestamp = t;
                });
        if (res != Result::SUCCESS) {
            return (long)Constant64Bit::INVALID_PRESENTATION_TIME_STAMP;
        }
        return timestamp;
    }

    return (long)Constant64Bit::INVALID_PRESENTATION_TIME_STAMP;
}

long TimeFilterClient::getSourceTime() {
    if (mTunerTimeFilter != NULL) {
        int64_t sourceTime;
        Status s = mTunerTimeFilter->getTimeStamp(&sourceTime);
        if (ClientHelper::getServiceSpecificErrorCode(s) != Result::SUCCESS) {
            return (long)Constant64Bit::INVALID_PRESENTATION_TIME_STAMP;
        }
        return sourceTime;
    }

    if (mTimeFilter != NULL) {
        Result res;
        long sourceTime;
        mTimeFilter->getSourceTime(
                [&](Result r, uint64_t t) {
                    res = r;
                    sourceTime = t;
                });
        if (res != Result::SUCCESS) {
            return (long)Constant64Bit::INVALID_PRESENTATION_TIME_STAMP;
        }
        return sourceTime;
    }

    return (long)Constant64Bit::INVALID_PRESENTATION_TIME_STAMP;
}

Result TimeFilterClient::close() {
    if (mTunerTimeFilter != NULL) {
        Status s = mTunerTimeFilter->close();
        mTunerTimeFilter = NULL;
        return ClientHelper::getServiceSpecificErrorCode(s);
    }

    if (mTimeFilter != NULL) {
        Result res = mTimeFilter->close();
        mTimeFilter = NULL;
        return res;
    }

    return Result::INVALID_STATE;
}
}  // namespace android
