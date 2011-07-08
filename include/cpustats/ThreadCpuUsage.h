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

#include <cpustats/CentralTendencyStatistics.h>

// Track CPU usage for the current thread, and maintain statistics on
// the CPU usage.  Units are in per-thread CPU ns, as reported by
// clock_gettime(CLOCK_THREAD_CPUTIME_ID).  Simple usage: for cyclic
// threads where you want to measure the execution time of the whole
// cycle, just call sampleAndEnable() at the start of each cycle.
// Then call statistics() to get the results, and resetStatistics()
// to start a new set of measurements.
// For acyclic threads, or for cyclic threads where you want to measure
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
        // mStatistics
        { }

    ~ThreadCpuUsage() { }

    // Return whether currently tracking CPU usage by current thread
    bool isEnabled()    { return mIsEnabled; }

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

    // Add a sample point for central tendency statistics, and also
    // enable tracking if needed.  If tracking has never been enabled, then
    // enables tracking but does not add a sample (it is not possible to add
    // a sample the first time because no previous).  Otherwise if tracking is
    // enabled, then adds a sample for tracked CPU ns since the previous
    // sample, or since the first call to sampleAndEnable(), enable(), or
    // setEnabled(true).  If there was a previous sample but tracking is
    // now disabled, then adds a sample for the tracked CPU ns accumulated
    // up until the most recent disable(), resets this accumulator, and then
    // enables tracking.  Calling this method rather than enable() followed
    // by sample() avoids a race condition for the first sample.
    void sampleAndEnable();

    // Add a sample point for central tendency statistics, but do not
    // change the tracking enabled status.  If tracking has either never been
    // enabled, or has never been enabled since the last sample, then log a warning
    // and don't add sample.  Otherwise, adds a sample for tracked CPU ns since
    // the previous sample or since the first call to sampleAndEnable(),
    // enable(), or setEnabled(true) if no previous sample.
    void sample();

    // Return the elapsed delta wall clock ns since initial enable or statistics reset,
    // as reported by clock_gettime(CLOCK_MONOTONIC).
    long long elapsed() const;

    // Reset statistics and elapsed.  Has no effect on tracking or accumulator.
    void resetStatistics();

    // Return a const reference to the central tendency statistics.
    // Note that only the const methods can be called on this object.
    const CentralTendencyStatistics& statistics() const {
        return mStatistics;
    }

private:
    bool mIsEnabled;                // whether tracking is currently enabled
    bool mWasEverEnabled;           // whether tracking was ever enabled
    long long mAccumulator;         // accumulated thread CPU time since last sample, in ns
    struct timespec mPreviousTs;    // most recent thread CPU time, valid only if mIsEnabled is true
    struct timespec mMonotonicTs;   // most recent monotonic time
    bool mMonotonicKnown;           // whether mMonotonicTs has been set
    CentralTendencyStatistics mStatistics;
};

#endif //  _THREAD_CPU_USAGE_H
