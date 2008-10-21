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
#include <utils/ported.h>     // may need usleep
#include <utils/Log.h>

#include <stdlib.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/time.h>
#include <time.h>
#include <errno.h>

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

//#define MONITOR_USLEEP

/*
 * Sleep long enough that we'll wake up "interval" milliseconds after
 * the previous snooze.
 *
 * The "nextTick" argument is updated on each call, and should be passed
 * in every time.  Set its fields to zero on the first call.
 *
 * Returns the #of intervals we have overslept, which will be zero if we're
 * on time.  [Currently just returns 0 or 1.]
 */
int sleepForInterval(long interval, struct timeval* pNextTick)
{
    struct timeval now;
    long long timeBeforeNext;
    long sleepTime = 0;
    bool overSlept = false;
    //int usleepBias = 0;

#ifdef USLEEP_BIAS
    /*
     * Linux likes to add 9000ms or so.
     * [not using this for now]
     */
    //usleepBias = USLEEP_BIAS;
#endif

    gettimeofday(&now, NULL);

    if (pNextTick->tv_sec == 0) {
        /* special-case for first time through */
        *pNextTick = now;
        sleepTime = interval;
        android::DurationTimer::addToTimeval(pNextTick, interval);
    } else {
        /*
         * Compute how much time there is before the next tick.  If this
         * value is negative, we've run over.  If we've run over a little
         * bit we can shorten the next frame to keep the pace steady, but
         * if we've dramatically overshot we need to re-sync.
         */
        timeBeforeNext = android::DurationTimer::subtractTimevals(pNextTick, &now);
        //printf("TOP: now=%ld.%ld next=%ld.%ld diff=%ld\n",
        //    now.tv_sec, now.tv_usec, pNextTick->tv_sec, pNextTick->tv_usec,
        //    (long) timeBeforeNext);
        if (timeBeforeNext < -interval) {
            /* way over */
            overSlept = true;
            sleepTime = 0;
            *pNextTick = now;
        } else if (timeBeforeNext <= 0) {
            /* slightly over, keep the pace steady */
            overSlept = true;
            sleepTime = 0;
        } else if (timeBeforeNext <= interval) {
            /* right on schedule */
            sleepTime = timeBeforeNext;
        } else if (timeBeforeNext > interval && timeBeforeNext <= 2*interval) {
            /* sleep call returned early; do a longer sleep this time */
            sleepTime = timeBeforeNext;
        } else if (timeBeforeNext > interval) {
            /* we went back in time -- somebody updated system clock? */
            /* (could also be a *seriously* broken usleep()) */
            LOG(LOG_DEBUG, "",
                " Impossible: timeBeforeNext = %ld\n", (long)timeBeforeNext);
            sleepTime = 0;
            *pNextTick = now;
        }
        android::DurationTimer::addToTimeval(pNextTick, interval);
    }
    //printf(" Before sleep: now=%ld.%ld next=%ld.%ld sleepTime=%ld\n",
    //    now.tv_sec, now.tv_usec, pNextTick->tv_sec, pNextTick->tv_usec,
    //    sleepTime);

    /*
     * Sleep for the designated period of time.
     *
     * Linux tends to sleep for longer than requested, often by 17-18ms.
     * MinGW tends to sleep for less than requested, by as much as 14ms,
     * but occasionally oversleeps for 40+ms (looks like some external
     * factors plus round-off on a 64Hz clock).  Cygwin is pretty steady.
     *
     * If you start the MinGW version, and then launch the Cygwin version,
     * the MinGW clock becomes more erratic.  Not entirely sure why.
     *
     * (There's a lot of stuff here; it's really just a usleep() call with
     * a bunch of instrumentation.)
     */
    if (sleepTime > 0) {
#if defined(MONITOR_USLEEP)
        struct timeval before, after;
        long long actual;

        gettimeofday(&before, NULL);
        usleep((long) sleepTime);
        gettimeofday(&after, NULL);

        /* check usleep() accuracy; default Linux threads are pretty sloppy */
        actual = android::DurationTimer::subtractTimevals(&after, &before);
        if ((long) actual < sleepTime - 14000 /*(sleepTime/10)*/ ||
            (long) actual > sleepTime + 20000 /*(sleepTime/10)*/)
        {
            LOG(LOG_DEBUG, "", " Odd usleep: req=%ld, actual=%ld\n", sleepTime,
                (long) actual);
        }
#else
#ifdef HAVE_WIN32_THREADS
        Sleep( sleepTime/1000 );
#else        
        usleep((long) sleepTime);
#endif        
#endif
    }

    //printf("slept %d\n", sleepTime);

    if (overSlept)
        return 1;       // close enough
    else
        return 0;
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
        LOG(LOG_WARN, "", "Negative values not supported in addToTimeval\n");
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

