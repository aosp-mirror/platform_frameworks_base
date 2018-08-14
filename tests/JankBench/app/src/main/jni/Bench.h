/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef ANDROID_BENCH_H
#define ANDROID_BENCH_H

#include <pthread.h>

#include "WorkerPool.h"

#include <string.h>



class Bench {
public:
    Bench();
    ~Bench();

    struct GFlop {
        int kernelXSize;
        //int kernelYSize;
        int imageXSize;
        //int imageYSize;

        float *srcBuffer;
        float *kernelBuffer;
        float *dstBuffer;


    };
    GFlop mGFlop;

    bool init();

    bool runPowerManagementTest(uint64_t options);
    bool runCPUHeatSoak(uint64_t options);

    bool startMemTests();
    void endMemTests();
    float runMemoryBandwidthTest(uint64_t options);
    float runMemoryLatencyTest(uint64_t options);

    float runGFlopsTest(uint64_t options);

    void getData(float *data, size_t count) const;


    void finish();

    void setPriority(int32_t p);
    void destroyWorkerThreadResources();

    uint64_t getTimeNanos() const;
    uint64_t getTimeMillis() const;

    // Adds a work unit completed to the timeline and returns
    // true if the test is ongoing, false if time is up
    bool incTimeBucket() const;


protected:
    WorkerPool mWorkers;

    bool mExit;
    bool mPaused;

    static void testWork(void *usr, uint32_t idx);

private:
    uint8_t * volatile mMemSrc;
    uint8_t * volatile mMemDst;
    size_t mMemLoopCount;
    size_t mMemLatencyLastSize;


    float ** mIpKernel;
    float * volatile * mSrcBuf;
    float * volatile * mOutBuf;
    uint32_t * mTimeBucket;

    uint64_t mTimeStartNanos;
    uint64_t mTimeEndNanos;
    uint64_t mTimeBucketDivisor;
    uint32_t mTimeBuckets;

    uint64_t mTimeEndGroupNanos;

    bool initIP();
    void GflopKernelC();
    void GflopKernelC_y3();

    bool allocateBuckets(size_t);


};


#endif
