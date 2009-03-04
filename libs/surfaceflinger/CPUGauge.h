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

#ifndef ANDROID_CPUGAUGE_H
#define ANDROID_CPUGAUGE_H

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <stdint.h>
#include <sys/types.h>

#include <utils/Timers.h>

#include <ui/SurfaceComposerClient.h>

namespace android {

class CPUGauge : public Thread
{
public:
    CPUGauge(   const sp<ISurfaceComposer>& composer,
                nsecs_t interval=s2ns(1),
                int clock=SYSTEM_TIME_THREAD,
                int refclock=SYSTEM_TIME_MONOTONIC);
                
    ~CPUGauge();

    const sp<SurfaceComposerClient>& session() const;

    void sample();
 
    inline float cpuUsage() const { return mCpuUsage; }
    inline float idle() const { return mIdleTime; }

private:
    virtual void        onFirstRef();
    virtual status_t    readyToRun();
    virtual bool        threadLoop();

    Mutex mLock;

    sp<SurfaceComposerClient> mSession;

    const nsecs_t mInterval;
    const int mClock;
    const int mRefClock;

    nsecs_t mReferenceTime;
    nsecs_t mReferenceWorkingTime;
    float mCpuUsage;
    nsecs_t mRefIdleTime;
    float mIdleTime;
    FILE*   mFd;
};


}; // namespace android

#endif // ANDROID_CPUGAUGE_H
