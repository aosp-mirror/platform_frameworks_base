/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include <errno.h>
#include <time.h>

#include <utils/Log.h>

#include <cpustats/ThreadCpuUsage.h>

bool ThreadCpuUsage::setEnabled(bool isEnabled)
{
    bool wasEnabled = mIsEnabled;
    // only do something if there is a change
    if (isEnabled != wasEnabled) {
        int rc;
        // enabling
        if (isEnabled) {
            rc = clock_gettime(CLOCK_THREAD_CPUTIME_ID, &mPreviousTs);
            if (rc) {
                LOGE("clock_gettime(CLOCK_THREAD_CPUTIME_ID) errno=%d", errno);
                isEnabled = false;
            } else {
                mWasEverEnabled = true;
                // record wall clock time at first enable
                if (!mMonotonicKnown) {
                    rc = clock_gettime(CLOCK_MONOTONIC, &mMonotonicTs);
                    if (rc) {
                        LOGE("clock_gettime(CLOCK_MONOTONIC) errno=%d", errno);
                    } else {
                        mMonotonicKnown = true;
                    }
                }
            }
        // disabling
        } else {
            struct timespec ts;
            rc = clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
            if (rc) {
                LOGE("clock_gettime(CLOCK_THREAD_CPUTIME_ID) errno=%d", errno);
            } else {
                long long delta = (ts.tv_sec - mPreviousTs.tv_sec) * 1000000000LL +
                        (ts.tv_nsec - mPreviousTs.tv_nsec);
                mAccumulator += delta;
#if 0
                mPreviousTs = ts;
#endif
            }
        }
        mIsEnabled = isEnabled;
    }
    return wasEnabled;
}

void ThreadCpuUsage::sampleAndEnable()
{
    bool wasEverEnabled = mWasEverEnabled;
    if (enable()) {
        // already enabled, so add a new sample relative to previous
        sample();
    } else if (wasEverEnabled) {
        // was disabled, but add sample for accumulated time while enabled
        mStatistics.sample((double) mAccumulator);
        mAccumulator = 0;
    }
}

void ThreadCpuUsage::sample()
{
    if (mWasEverEnabled) {
        if (mIsEnabled) {
            struct timespec ts;
            int rc;
            rc = clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
            if (rc) {
                LOGE("clock_gettime(CLOCK_THREAD_CPUTIME_ID) errno=%d", errno);
            } else {
                long long delta = (ts.tv_sec - mPreviousTs.tv_sec) * 1000000000LL +
                        (ts.tv_nsec - mPreviousTs.tv_nsec);
                mAccumulator += delta;
                mPreviousTs = ts;
            }
        } else {
            mWasEverEnabled = false;
        }
        mStatistics.sample((double) mAccumulator);
        mAccumulator = 0;
    } else {
        ALOGW("Can't add sample because measurements have never been enabled");
    }
}

long long ThreadCpuUsage::elapsed() const
{
    long long elapsed;
    if (mMonotonicKnown) {
        struct timespec ts;
        int rc;
        rc = clock_gettime(CLOCK_MONOTONIC, &ts);
        if (rc) {
            LOGE("clock_gettime(CLOCK_MONOTONIC) errno=%d", errno);
            elapsed = 0;
        } else {
            // mMonotonicTs is updated only at first enable and resetStatistics
            elapsed = (ts.tv_sec - mMonotonicTs.tv_sec) * 1000000000LL +
                    (ts.tv_nsec - mMonotonicTs.tv_nsec);
        }
    } else {
        ALOGW("Can't compute elapsed time because measurements have never been enabled");
        elapsed = 0;
    }
    return elapsed;
}

void ThreadCpuUsage::resetStatistics()
{
    mStatistics.reset();
    if (mMonotonicKnown) {
        int rc;
        rc = clock_gettime(CLOCK_MONOTONIC, &mMonotonicTs);
        if (rc) {
            LOGE("clock_gettime(CLOCK_MONOTONIC) errno=%d", errno);
            mMonotonicKnown = false;
        }
    }
}
