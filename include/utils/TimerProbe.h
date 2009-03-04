/*
 * Copyright (C) 2007 The Android Open Source Project
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

#ifndef ANDROID_TIMER_PROBE_H
#define ANDROID_TIMER_PROBE_H

#if 0 && defined(HAVE_POSIX_CLOCKS)
#define ENABLE_TIMER_PROBE 1
#else
#define ENABLE_TIMER_PROBE 0
#endif

#if ENABLE_TIMER_PROBE

#include <time.h>
#include <sys/time.h>
#include <utils/Vector.h>

#define TIMER_PROBE(tag) \
    static int _timer_slot_; \
    android::TimerProbe probe(tag, &_timer_slot_)
#define TIMER_PROBE_END() probe.end()
#else
#define TIMER_PROBE(tag)
#define TIMER_PROBE_END()
#endif

#if ENABLE_TIMER_PROBE
namespace android {

class TimerProbe {
public:
    TimerProbe(const char tag[], int* slot);
    void end();
    ~TimerProbe();
private:
    struct Bucket {
        int mStart, mReal, mProcess, mThread, mCount;
        const char* mTag;
        int* mSlotPtr;
        int mIndent;
    };
    static Vector<Bucket> gBuckets;
    static TimerProbe* gExecuteChain;
    static int gIndent;
    static timespec gRealBase;
    TimerProbe* mNext;
    static uint32_t ElapsedTime(const timespec& start, const timespec& end);
    void print(const timespec& r, const timespec& p, const timespec& t) const;
    timespec mRealStart, mPStart, mTStart;
    const char* mTag;
    int mIndent;
    int mBucket;
};

}; // namespace android

#endif
#endif
