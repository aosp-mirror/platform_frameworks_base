/*
 * Copyright (C) 2005 The Android Open Source Project
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

#define LOG_TAG "StopWatch"

#include <string.h>
#include <stdlib.h>
#include <stdio.h>

#include <utils/Log.h>
#include <utils/Errors.h>
#include <utils/StopWatch.h>

/*****************************************************************************/

namespace android {


StopWatch::StopWatch(const char *name, int clock, uint32_t flags)
    :   mName(name), mClock(clock), mFlags(flags)
{
    reset();
}

StopWatch::~StopWatch()
{
    nsecs_t elapsed = elapsedTime();
    const int n = mNumLaps;
    LOGD("StopWatch %s (us): %lld ", mName, ns2us(elapsed));
    for (int i=0 ; i<n ; i++) {
        const nsecs_t soFar = mLaps[i].soFar;
        const nsecs_t thisLap = mLaps[i].thisLap;
        LOGD(" [%d: %lld, %lld]", i, ns2us(soFar), ns2us(thisLap));
    }
}

const char* StopWatch::name() const
{
    return mName;
}

nsecs_t StopWatch::lap()
{
    nsecs_t elapsed = elapsedTime();
    if (mNumLaps >= 8) {
        elapsed = 0;
    } else {
        const int n = mNumLaps;
        mLaps[n].soFar   = elapsed;
        mLaps[n].thisLap = n ? (elapsed - mLaps[n-1].soFar) : elapsed;
        mNumLaps = n+1;
    }
    return elapsed;
}

nsecs_t StopWatch::elapsedTime() const
{
    return systemTime(mClock) - mStartTime;
}

void StopWatch::reset()
{
    mNumLaps = 0;
    mStartTime = systemTime(mClock);
}


/*****************************************************************************/

}; // namespace android

