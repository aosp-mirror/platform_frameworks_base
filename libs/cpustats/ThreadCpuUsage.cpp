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

#define LOG_TAG "ThreadCpuUsage"
//#define LOG_NDEBUG 0

#include <errno.h>
#include <stdlib.h>
#include <time.h>

#include <utils/Debug.h>
#include <utils/Log.h>

#include <cpustats/ThreadCpuUsage.h>

namespace android {

bool ThreadCpuUsage::setEnabled(bool isEnabled)
{
    bool wasEnabled = mIsEnabled;
    // only do something if there is a change
    if (isEnabled != wasEnabled) {
        ALOGV("setEnabled(%d)", isEnabled);
        int rc;
        // enabling
        if (isEnabled) {
            rc = clock_gettime(CLOCK_THREAD_CPUTIME_ID, &mPreviousTs);
            if (rc) {
                ALOGE("clock_gettime(CLOCK_THREAD_CPUTIME_ID) errno=%d", errno);
                isEnabled = false;
            } else {
                mWasEverEnabled = true;
                // record wall clock time at first enable
                if (!mMonotonicKnown) {
                    rc = clock_gettime(CLOCK_MONOTONIC, &mMonotonicTs);
                    if (rc) {
                        ALOGE("clock_gettime(CLOCK_MONOTONIC) errno=%d", errno);
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
                ALOGE("clock_gettime(CLOCK_THREAD_CPUTIME_ID) errno=%d", errno);
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

bool ThreadCpuUsage::sampleAndEnable(double& ns)
{
    bool ret;
    bool wasEverEnabled = mWasEverEnabled;
    if (enable()) {
        // already enabled, so add a new sample relative to previous
        return sample(ns);
    } else if (wasEverEnabled) {
        // was disabled, but add sample for accumulated time while enabled
        ns = (double) mAccumulator;
        mAccumulator = 0;
        ALOGV("sampleAndEnable %.0f", ns);
        return true;
    } else {
        // first time called
        ns = 0.0;
        ALOGV("sampleAndEnable false");
        return false;
    }
}

bool ThreadCpuUsage::sample(double &ns)
{
    if (mWasEverEnabled) {
        if (mIsEnabled) {
            struct timespec ts;
            int rc;
            rc = clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
            if (rc) {
                ALOGE("clock_gettime(CLOCK_THREAD_CPUTIME_ID) errno=%d", errno);
                ns = 0.0;
                return false;
            } else {
                long long delta = (ts.tv_sec - mPreviousTs.tv_sec) * 1000000000LL +
                        (ts.tv_nsec - mPreviousTs.tv_nsec);
                mAccumulator += delta;
                mPreviousTs = ts;
            }
        } else {
            mWasEverEnabled = false;
        }
        ns = (double) mAccumulator;
        ALOGV("sample %.0f", ns);
        mAccumulator = 0;
        return true;
    } else {
        ALOGW("Can't add sample because measurements have never been enabled");
        ns = 0.0;
        return false;
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
            ALOGE("clock_gettime(CLOCK_MONOTONIC) errno=%d", errno);
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
    ALOGV("elapsed %lld", elapsed);
    return elapsed;
}

void ThreadCpuUsage::resetElapsed()
{
    ALOGV("resetElapsed");
    if (mMonotonicKnown) {
        int rc;
        rc = clock_gettime(CLOCK_MONOTONIC, &mMonotonicTs);
        if (rc) {
            ALOGE("clock_gettime(CLOCK_MONOTONIC) errno=%d", errno);
            mMonotonicKnown = false;
        }
    }
}

/*static*/
int ThreadCpuUsage::sScalingFds[ThreadCpuUsage::MAX_CPU];
pthread_once_t ThreadCpuUsage::sOnceControl = PTHREAD_ONCE_INIT;
int ThreadCpuUsage::sKernelMax;

/*static*/
void ThreadCpuUsage::init()
{
    // read the number of CPUs
    sKernelMax = 1;
    int fd = open("/sys/devices/system/cpu/kernel_max", O_RDONLY);
    if (fd >= 0) {
#define KERNEL_MAX_SIZE 12
        char kernelMax[KERNEL_MAX_SIZE];
        ssize_t actual = read(fd, kernelMax, sizeof(kernelMax));
        if (actual >= 2 && kernelMax[actual-1] == '\n') {
            sKernelMax = atoi(kernelMax);
            if (sKernelMax >= MAX_CPU - 1) {
                ALOGW("kernel_max %d but MAX_CPU %d", sKernelMax, MAX_CPU);
                sKernelMax = MAX_CPU;
            } else if (sKernelMax < 0) {
                ALOGW("kernel_max invalid %d", sKernelMax);
                sKernelMax = 1;
            } else {
                ++sKernelMax;
                ALOGV("number of CPUs %d", sKernelMax);
            }
        } else {
            ALOGW("Can't read number of CPUs");
        }
        (void) close(fd);
    } else {
        ALOGW("Can't open number of CPUs");
    }

    // open fd to each frequency per CPU
#define FREQ_SIZE 64
    char freq_path[FREQ_SIZE];
#define FREQ_DIGIT 27
    COMPILE_TIME_ASSERT_FUNCTION_SCOPE(MAX_CPU <= 10);
    strlcpy(freq_path, "/sys/devices/system/cpu/cpu?/cpufreq/scaling_cur_freq", sizeof(freq_path));
    int i;
    for (i = 0; i < MAX_CPU; ++i) {
        sScalingFds[i] = -1;
    }
    for (i = 0; i < sKernelMax; ++i) {
        freq_path[FREQ_DIGIT] = i + '0';
        fd = open(freq_path, O_RDONLY);
        if (fd >= 0) {
            // keep this fd until process exit
            sScalingFds[i] = fd;
        } else {
            ALOGW("Can't open CPU %d", i);
        }
    }
}

uint32_t ThreadCpuUsage::getCpukHz(int cpuNum)
{
    if (cpuNum < 0 || cpuNum >= MAX_CPU) {
        ALOGW("getCpukHz called with invalid CPU %d", cpuNum);
        return 0;
    }
    int fd = sScalingFds[cpuNum];
    if (fd < 0) {
        ALOGW("getCpukHz called for unopened CPU %d", cpuNum);
        return 0;
    }
#define KHZ_SIZE 12
    char kHz[KHZ_SIZE];   // kHz base 10
    ssize_t actual = pread(fd, kHz, sizeof(kHz), (off_t) 0);
    uint32_t ret;
    if (actual >= 2 && kHz[actual-1] == '\n') {
        ret = atoi(kHz);
    } else {
        ret = 0;
    }
    if (ret != mCurrentkHz[cpuNum]) {
        if (ret > 0) {
            ALOGV("CPU %d frequency %u kHz", cpuNum, ret);
        } else {
            ALOGW("Can't read CPU %d frequency", cpuNum);
        }
        mCurrentkHz[cpuNum] = ret;
    }
    return ret;
}

}   // namespace android
