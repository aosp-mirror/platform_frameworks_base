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

#ifndef _THREAD_CPU_USAGE_H
#define _THREAD_CPU_USAGE_H

#include <fcntl.h>
#include <pthread.h>

namespace android {

// Track CPU usage for the current thread.
// Units are in per-thread CPU ns, as reported by
// clock_gettime(CLOCK_THREAD_CPUTIME_ID).  Simple usage: for cyclic
// threads where you want to measure the execution time of the whole
// cycle, just call sampleAndEnable() at the start of each cycle.
// For acyclic threads, or for cyclic threads where you want to measure/track
// only part of each cycle, call enable(), disable(), and/or setEnabled()
// to demarcate the region(s) of interest, and then call sample() periodically.
// This class is not thread-safe for concurrent calls from multiple threads;
// the methods of this class may only be called by the current thread
// which constructed the object.

class ThreadCpuUsage
{

public:
    ThreadCpuUsage() :
        mIsEnabled(false),
        mWasEverEnabled(false),
        mAccumulator(0),
        // mPreviousTs
        // mMonotonicTs
        mMonotonicKnown(false)
        {
            (void) pthread_once(&sOnceControl, &init);
            for (int i = 0; i < sKernelMax; ++i) {
                mCurrentkHz[i] = (uint32_t) ~0;   // unknown
            }
        }

    ~ThreadCpuUsage() { }

    // Return whether currently tracking CPU usage by current thread
    bool isEnabled() const  { return mIsEnabled; }

    // Enable tracking of CPU usage by current thread;
    // any CPU used from this point forward will be tracked.
    // Returns the previous enabled status.
    bool enable()       { return setEnabled(true); }

    // Disable tracking of CPU usage by current thread;
    // any CPU used from this point forward will be ignored.
    // Returns the previous enabled status.
    bool disable()      { return setEnabled(false); }

    // Set the enabled status and return the previous enabled status.
    // This method is intended to be used for safe nested enable/disabling.
    bool setEnabled(bool isEnabled);

    // Add a sample point, and also enable tracking if needed.
    // If tracking has never been enabled, then this call enables tracking but
    // does _not_ add a sample -- it is not possible to add a sample the
    // first time because there is no previous point to subtract from.
    // Otherwise, if tracking is enabled,
    // then adds a sample for tracked CPU ns since the previous
    // sample, or since the first call to sampleAndEnable(), enable(), or
    // setEnabled(true).  If there was a previous sample but tracking is
    // now disabled, then adds a sample for the tracked CPU ns accumulated
    // up until the most recent disable(), resets this accumulator, and then
    // enables tracking.  Calling this method rather than enable() followed
    // by sample() avoids a race condition for the first sample.
    // Returns true if the sample 'ns' is valid, or false if invalid.
    // Note that 'ns' is an output parameter passed by reference.
    // The caller does not need to initialize this variable.
    // The units are CPU nanoseconds consumed by current thread.
    bool sampleAndEnable(double& ns);

    // Add a sample point, but do not
    // change the tracking enabled status.  If tracking has either never been
    // enabled, or has never been enabled since the last sample, then log a warning
    // and don't add sample.  Otherwise, adds a sample for tracked CPU ns since
    // the previous sample or since the first call to sampleAndEnable(),
    // enable(), or setEnabled(true) if no previous sample.
    // Returns true if the sample is valid, or false if invalid.
    // Note that 'ns' is an output parameter passed by reference.
    // The caller does not need to initialize this variable.
    // The units are CPU nanoseconds consumed by current thread.
    bool sample(double& ns);

    // Return the elapsed delta wall clock ns since initial enable or reset,
    // as reported by clock_gettime(CLOCK_MONOTONIC).
    long long elapsed() const;

    // Reset elapsed wall clock.  Has no effect on tracking or accumulator.
    void resetElapsed();

    // Return current clock frequency for specified CPU, in kHz.
    // You can get your CPU number using sched_getcpu(2).  Note that, unless CPU affinity
    // has been configured appropriately, the CPU number can change.
    // Also note that, unless the CPU governor has been configured appropriately,
    // the CPU frequency can change.  And even if the CPU frequency is locked down
    // to a particular value, that the frequency might still be adjusted
    // to prevent thermal overload.  Therefore you should poll for your thread's
    // current CPU number and clock frequency periodically.
    uint32_t getCpukHz(int cpuNum);

private:
    bool mIsEnabled;                // whether tracking is currently enabled
    bool mWasEverEnabled;           // whether tracking was ever enabled
    long long mAccumulator;         // accumulated thread CPU time since last sample, in ns
    struct timespec mPreviousTs;    // most recent thread CPU time, valid only if mIsEnabled is true
    struct timespec mMonotonicTs;   // most recent monotonic time
    bool mMonotonicKnown;           // whether mMonotonicTs has been set

    static const int MAX_CPU = 8;
    static int sScalingFds[MAX_CPU];// file descriptor per CPU for reading scaling_cur_freq
    uint32_t mCurrentkHz[MAX_CPU];  // current CPU frequency in kHz, not static to avoid a race
    static pthread_once_t sOnceControl;
    static int sKernelMax;          // like MAX_CPU, but determined at runtime == cpu/kernel_max + 1
    static void init();
};

}   // namespace android

#endif //  _THREAD_CPU_USAGE_H
