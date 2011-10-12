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

//
// Timer functions.
//
#include <utils/Timers.h>
#include <utils/Log.h>

#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/time.h>
#include <time.h>
#include <errno.h>
#include <limits.h>

#ifdef HAVE_WIN32_THREADS
#include <windows.h>
#endif

nsecs_t systemTime(int clock)
{
#if defined(HAVE_POSIX_CLOCKS)
    static const clockid_t clocks[] = {
            CLOCK_REALTIME,
            CLOCK_MONOTONIC,
            CLOCK_PROCESS_CPUTIME_ID,
            CLOCK_THREAD_CPUTIME_ID
    };
    struct timespec t;
    t.tv_sec = t.tv_nsec = 0;
    clock_gettime(clocks[clock], &t);
    return nsecs_t(t.tv_sec)*1000000000LL + t.tv_nsec;
#else
    // we don't support the clocks here.
    struct timeval t;
    t.tv_sec = t.tv_usec = 0;
    gettimeofday(&t, NULL);
    return nsecs_t(t.tv_sec)*1000000000LL + nsecs_t(t.tv_usec)*1000LL;
#endif
}

int toMillisecondTimeoutDelay(nsecs_t referenceTime, nsecs_t timeoutTime)
{
    int timeoutDelayMillis;
    if (timeoutTime > referenceTime) {
        uint64_t timeoutDelay = uint64_t(timeoutTime - referenceTime);
        if (timeoutDelay > uint64_t((INT_MAX - 1) * 1000000LL)) {
            timeoutDelayMillis = -1;
        } else {
            timeoutDelayMillis = (timeoutDelay + 999999LL) / 1000000LL;
        }
    } else {
        timeoutDelayMillis = 0;
    }
    return timeoutDelayMillis;
}


/*
 * ===========================================================================
 *      DurationTimer
 * ===========================================================================
 */

using namespace android;

// Start the timer.
void DurationTimer::start(void)
{
    gettimeofday(&mStartWhen, NULL);
}

// Stop the timer.
void DurationTimer::stop(void)
{
    gettimeofday(&mStopWhen, NULL);
}

// Get the duration in microseconds.
long long DurationTimer::durationUsecs(void) const
{
    return (long) subtractTimevals(&mStopWhen, &mStartWhen);
}

// Subtract two timevals.  Returns the difference (ptv1-ptv2) in
// microseconds.
/*static*/ long long DurationTimer::subtractTimevals(const struct timeval* ptv1,
    const struct timeval* ptv2)
{
    long long stop  = ((long long) ptv1->tv_sec) * 1000000LL +
                      ((long long) ptv1->tv_usec);
    long long start = ((long long) ptv2->tv_sec) * 1000000LL +
                      ((long long) ptv2->tv_usec);
    return stop - start;
}

// Add the specified amount of time to the timeval.
/*static*/ void DurationTimer::addToTimeval(struct timeval* ptv, long usec)
{
    if (usec < 0) {
        ALOG(LOG_WARN, "", "Negative values not supported in addToTimeval\n");
        return;
    }

    // normalize tv_usec if necessary
    if (ptv->tv_usec >= 1000000) {
        ptv->tv_sec += ptv->tv_usec / 1000000;
        ptv->tv_usec %= 1000000;
    }

    ptv->tv_usec += usec % 1000000;
    if (ptv->tv_usec >= 1000000) {
        ptv->tv_usec -= 1000000;
        ptv->tv_sec++;
    }
    ptv->tv_sec += usec / 1000000;
}

