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
#ifndef _LIBS_UTILS_TIMERS_H
#define _LIBS_UTILS_TIMERS_H

#include <stdint.h>
#include <sys/types.h>
#include <sys/time.h>

// ------------------------------------------------------------------
// C API

#ifdef __cplusplus
extern "C" {
#endif

typedef int64_t nsecs_t;       // nano-seconds

static inline nsecs_t seconds_to_nanoseconds(nsecs_t secs)
{
    return secs*1000000000;
}

static inline nsecs_t milliseconds_to_nanoseconds(nsecs_t secs)
{
    return secs*1000000;
}

static inline nsecs_t microseconds_to_nanoseconds(nsecs_t secs)
{
    return secs*1000;
}

static inline nsecs_t nanoseconds_to_seconds(nsecs_t secs)
{
    return secs/1000000000;
}

static inline nsecs_t nanoseconds_to_milliseconds(nsecs_t secs)
{
    return secs/1000000;
}

static inline nsecs_t nanoseconds_to_microseconds(nsecs_t secs)
{
    return secs/1000;
}

static inline nsecs_t s2ns(nsecs_t v)  {return seconds_to_nanoseconds(v);}
static inline nsecs_t ms2ns(nsecs_t v) {return milliseconds_to_nanoseconds(v);}
static inline nsecs_t us2ns(nsecs_t v) {return microseconds_to_nanoseconds(v);}
static inline nsecs_t ns2s(nsecs_t v)  {return nanoseconds_to_seconds(v);}
static inline nsecs_t ns2ms(nsecs_t v) {return nanoseconds_to_milliseconds(v);}
static inline nsecs_t ns2us(nsecs_t v) {return nanoseconds_to_microseconds(v);}

static inline nsecs_t seconds(nsecs_t v)      { return s2ns(v); }
static inline nsecs_t milliseconds(nsecs_t v) { return ms2ns(v); }
static inline nsecs_t microseconds(nsecs_t v) { return us2ns(v); }

enum {
    SYSTEM_TIME_REALTIME = 0,  // system-wide realtime clock
    SYSTEM_TIME_MONOTONIC = 1, // monotonic time since unspecified starting point
    SYSTEM_TIME_PROCESS = 2,   // high-resolution per-process clock
    SYSTEM_TIME_THREAD = 3     // high-resolution per-thread clock
};
    
// return the system-time according to the specified clock
#ifdef __cplusplus
nsecs_t systemTime(int clock = SYSTEM_TIME_MONOTONIC);
#else
nsecs_t systemTime(int clock);
#endif // def __cplusplus

#ifdef __cplusplus
} // extern "C"
#endif

// ------------------------------------------------------------------
// C++ API

#ifdef __cplusplus

namespace android {
/*
 * Time the duration of something.
 *
 * Includes some timeval manipulation functions.
 */
class DurationTimer {
public:
    DurationTimer() {}
    ~DurationTimer() {}

    // Start the timer.
    void start();
    // Stop the timer.
    void stop();
    // Get the duration in microseconds.
    long long durationUsecs() const;

    // Subtract two timevals.  Returns the difference (ptv1-ptv2) in
    // microseconds.
    static long long subtractTimevals(const struct timeval* ptv1,
        const struct timeval* ptv2);

    // Add the specified amount of time to the timeval.
    static void addToTimeval(struct timeval* ptv, long usec);

private:
    struct timeval  mStartWhen;
    struct timeval  mStopWhen;
};

}; // android
#endif // def __cplusplus

#endif // _LIBS_UTILS_TIMERS_H
