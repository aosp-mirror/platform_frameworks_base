/*
 * Copyright (C) 2012 The Android Open Source Project
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
#include <utils/SystemClock.h>

#include "utils.h"

namespace android {

// check ICommonTime every 60 seconds, common to local difference
// shouldn't drift a lot
#define CHECK_CC_INTERNAL 60000

void Timeout::setTimeout(int msec) {
    if (msec < 0) {
        mSystemEndTime = 0;
        return;
    }

    mSystemEndTime = systemTime() + (static_cast<nsecs_t>(msec) * 1000000);
}

int Timeout::msecTillTimeout(nsecs_t nowTime) {
    if (!mSystemEndTime) {
        return -1;
    }

    if (mSystemEndTime < nowTime) {
        return 0;
    }

    nsecs_t delta = mSystemEndTime - nowTime;
    delta += 999999;
    delta /= 1000000;
    if (delta > 0x7FFFFFFF) {
        return 0x7FFFFFFF;
    }

    return static_cast<int>(delta);
}

CommonToSystemTransform::CommonToSystemTransform()
        : mLastTs(-1) {
    mCCHelper.getCommonFreq(&mCommonFreq);
    mCommonToSystem.a_to_b_numer = 1000;
    mCommonToSystem.a_to_b_denom = mCommonFreq;
    LinearTransform::reduce(&mCommonToSystem.a_to_b_numer,
                            &mCommonToSystem.a_to_b_denom);
}

const LinearTransform& CommonToSystemTransform::getCommonToSystem() {
    int64_t st = elapsedRealtime();
    if (mLastTs == -1 || st - mLastTs > CHECK_CC_INTERNAL) {
        int64_t ct;
        mCCHelper.getCommonTime(&ct);
        mCommonToSystem.a_zero = ct;
        mCommonToSystem.b_zero = st;
        mLastTs = st;
    }
    return mCommonToSystem;
}

MediaToSystemTransform::MediaToSystemTransform()
        : mMediaToCommonValid(false) {
}

void MediaToSystemTransform::prepareCommonToSystem() {
    memcpy(&mCommonToSystem, &mCommonToSystemTrans.getCommonToSystem(),
           sizeof(mCommonToSystem));
}

void MediaToSystemTransform::setMediaToCommonTransform(
        const LinearTransform& t) {
    mMediaToCommon = t;
    mMediaToCommonValid = true;
}

bool MediaToSystemTransform::mediaToSystem(int64_t* ts) {
    if (!mMediaToCommonValid)
        return false;

    // TODO: this is not efficient,  we could combine two transform into one
    // during prepareCommonToSystem() and setMediaToCommonTransform()
    int64_t media_time = *ts;
    int64_t common_time;
    int64_t system_time;
    if (!mMediaToCommon.doForwardTransform(media_time, &common_time)) {
        return false;
    }

    if (!mCommonToSystem.doForwardTransform(common_time, &system_time)) {
        return false;
    }
    *ts = system_time;
    return true;
}


}  // namespace android
