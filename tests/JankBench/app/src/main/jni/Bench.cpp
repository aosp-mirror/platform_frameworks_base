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

#include <android/log.h>
#include <math.h>
#include <stdlib.h>
#include <unistd.h>

#include "Bench.h"


Bench::Bench()
{
    mTimeBucket = NULL;
    mTimeBuckets = 0;
    mTimeBucketDivisor = 1;

    mMemLatencyLastSize = 0;
    mMemDst = NULL;
    mMemSrc = NULL;
    mMemLoopCount = 0;
}


Bench::~Bench()
{
}

uint64_t Bench::getTimeNanos() const
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_nsec + ((uint64_t)t.tv_sec * 1000 * 1000 * 1000);
}

uint64_t Bench::getTimeMillis() const
{
    return getTimeNanos() / 1000000;
}


void Bench::testWork(void *usr, uint32_t idx)
{
    Bench *b = (Bench *)usr;
    //__android_log_print(ANDROID_LOG_INFO, "bench", "test %i   %p", idx, b);

    float f1 = 0.f;
    float f2 = 0.f;
    float f3 = 0.f;
    float f4 = 0.f;

    float *ipk = b->mIpKernel[idx];
    volatile float *src = b->mSrcBuf[idx];
    volatile float *out = b->mOutBuf[idx];

    //__android_log_print(ANDROID_LOG_INFO, "bench", "test %p %p %p", ipk, src, out);

    do {

        for (int i = 0; i < 1024; i++) {
            f1 += src[i * 4] * ipk[i];
            f2 += src[i * 4 + 1] * ipk[i];
            f3 += src[i * 4 + 2] * ipk[i];
            f4 += sqrtf(f1 + f2 + f3);
        }
        out[0] = f1;
        out[1] = f2;
        out[2] = f3;
        out[3] = f4;

    } while (b->incTimeBucket());
}

bool Bench::initIP() {
    int workers = mWorkers.getWorkerCount();

    mIpKernel = new float *[workers];
    mSrcBuf = new float *[workers];
    mOutBuf = new float *[workers];

    for (int i = 0; i < workers; i++) {
        mIpKernel[i] = new float[1024];
        mSrcBuf[i] = new float[4096];
        mOutBuf[i] = new float[4];
    }

    return true;
}

bool Bench::runPowerManagementTest(uint64_t options) {
    //__android_log_print(ANDROID_LOG_INFO, "bench", "rpmt x %i", options);

    mTimeBucketDivisor = 1000 * 1000;  // use ms
    allocateBuckets(2 * 1000);

    usleep(2 * 1000 * 1000);

    //__android_log_print(ANDROID_LOG_INFO, "bench", "rpmt 2  b %i", mTimeBuckets);

    mTimeStartNanos = getTimeNanos();
    mTimeEndNanos = mTimeStartNanos + mTimeBuckets * mTimeBucketDivisor;
    memset(mTimeBucket, 0, sizeof(uint32_t) * mTimeBuckets);

    bool useMT = false;

    //__android_log_print(ANDROID_LOG_INFO, "bench", "rpmt 2.1  b %i", mTimeBuckets);
    mTimeEndGroupNanos = mTimeStartNanos;
    do  {
        // Advance 8ms
        mTimeEndGroupNanos += 8 * 1000 * 1000;

        int threads = useMT ? 1 : 0;
        useMT = !useMT;
        if ((options & 0x1f) != 0) {
            threads = options & 0x1f;
        }

        //__android_log_print(ANDROID_LOG_INFO, "bench", "threads %i", threads);

        mWorkers.launchWork(testWork, this, threads);
    } while (mTimeEndGroupNanos <= mTimeEndNanos);

    return true;
}

bool Bench::allocateBuckets(size_t bucketCount) {
    if (bucketCount == mTimeBuckets) {
        return true;
    }

    if (mTimeBucket != NULL) {
        delete[] mTimeBucket;
        mTimeBucket = NULL;
    }

    mTimeBuckets = bucketCount;
    if (mTimeBuckets > 0) {
        mTimeBucket = new uint32_t[mTimeBuckets];
    }

    return true;
}

bool Bench::init() {
    mWorkers.init();

    initIP();
    //ALOGV("%p Launching thread(s), CPUs %i", mRSC, mWorkers.mCount + 1);

    return true;
}

bool Bench::incTimeBucket() const {
    uint64_t time = getTimeNanos();
    uint64_t bucket = (time - mTimeStartNanos) / mTimeBucketDivisor;

    if (bucket >= mTimeBuckets) {
        return false;
    }

    __sync_fetch_and_add(&mTimeBucket[bucket], 1);

    return time < mTimeEndGroupNanos;
}

void Bench::getData(float *data, size_t count) const {
    if (count > mTimeBuckets) {
        count = mTimeBuckets;
    }
    for (size_t ct = 0; ct < count; ct++) {
        data[ct] = (float)mTimeBucket[ct];
    }
}

bool Bench::runCPUHeatSoak(uint64_t /* options */)
{
    mTimeBucketDivisor = 1000 * 1000;  // use ms
    allocateBuckets(1000);

    mTimeStartNanos = getTimeNanos();
    mTimeEndNanos = mTimeStartNanos + mTimeBuckets * mTimeBucketDivisor;
    memset(mTimeBucket, 0, sizeof(uint32_t) * mTimeBuckets);

    mTimeEndGroupNanos = mTimeEndNanos;
    mWorkers.launchWork(testWork, this, 0);
    return true;
}

float Bench::runMemoryBandwidthTest(uint64_t size)
{
    uint64_t t1 = getTimeMillis();
    for (size_t ct = mMemLoopCount; ct > 0; ct--) {
        memcpy(mMemDst, mMemSrc, size);
    }
    double dt = getTimeMillis() - t1;
    dt /= 1000;

    double bw = ((double)size) * mMemLoopCount / dt;
    bw /= 1024 * 1024 * 1024;

    float targetTime = 0.2f;
    if (dt > targetTime) {
        mMemLoopCount = (size_t)((double)mMemLoopCount / (dt / targetTime));
    }

    return (float)bw;
}

float Bench::runMemoryLatencyTest(uint64_t size)
{
    //__android_log_print(ANDROID_LOG_INFO, "bench", "latency %i", (int)size);
    void ** sp = (void **)mMemSrc;
    size_t maxIndex = size / sizeof(void *);
    size_t loops = ((maxIndex / 2) & (~3));
    //loops = 10;

    if (size != mMemLatencyLastSize) {
        __android_log_print(ANDROID_LOG_INFO, "bench", "latency build %i %i", (int)maxIndex, loops);
        mMemLatencyLastSize = size;
        memset((void *)mMemSrc, 0, mMemLatencyLastSize);

        size_t lastIdx = 0;
        for (size_t ct = 0; ct < loops; ct++) {
            size_t ni = rand() * rand();
            ni = ni % maxIndex;
            while ((sp[ni] != NULL) || (ni == lastIdx)) {
                ni++;
                if (ni >= maxIndex) {
                    ni = 1;
                }
    //            __android_log_print(ANDROID_LOG_INFO, "bench", "gen ni loop %i %i", lastIdx, ni);
            }
      //      __android_log_print(ANDROID_LOG_INFO, "bench", "gen ct = %i  %i  %i  %p  %p", (int)ct, lastIdx, ni, &sp[lastIdx], &sp[ni]);
            sp[lastIdx] = &sp[ni];
            lastIdx = ni;
        }
        sp[lastIdx] = 0;
    }
    //__android_log_print(ANDROID_LOG_INFO, "bench", "latency testing");

    uint64_t t1 = getTimeNanos();
    for (size_t ct = mMemLoopCount; ct > 0; ct--) {
        size_t lc = 1;
        volatile void *p = sp[0];
        while (p != NULL) {
            // Unroll once to minimize branching overhead.
            void **pn = (void **)p;
            p = pn[0];
            pn = (void **)p;
            p = pn[0];
        }
    }
    //__android_log_print(ANDROID_LOG_INFO, "bench", "v %i %i", loops * mMemLoopCount, v);

    double dt = getTimeNanos() - t1;
    double dts = dt / 1000000000;
    double lat = dt / (loops * mMemLoopCount);
    __android_log_print(ANDROID_LOG_INFO, "bench", "latency ret %f", lat);

    float targetTime = 0.2f;
    if (dts > targetTime) {
        mMemLoopCount = (size_t)((double)mMemLoopCount / (dts / targetTime));
        if (mMemLoopCount < 1) {
            mMemLoopCount = 1;
        }
    }

    return (float)lat;
}

bool Bench::startMemTests()
{
    mMemSrc = (uint8_t *)malloc(1024*1024*64);
    mMemDst = (uint8_t *)malloc(1024*1024*64);

    memset(mMemSrc, 0, 1024*1024*16);
    memset(mMemDst, 0, 1024*1024*16);

    mMemLoopCount = 1;
    uint64_t start = getTimeMillis();
    while((getTimeMillis() - start) < 500) {
        memcpy(mMemDst, mMemSrc, 1024);
        mMemLoopCount++;
    }
    mMemLatencyLastSize = 0;
    return true;
}

void Bench::endMemTests()
{
    free(mMemSrc);
    free(mMemDst);
    mMemSrc = NULL;
    mMemDst = NULL;
    mMemLatencyLastSize = 0;
}

void Bench::GflopKernelC() {
    int halfKX = (mGFlop.kernelXSize / 2);
    for (int x = halfKX; x < (mGFlop.imageXSize - halfKX - 1); x++) {
        const float * krnPtr = mGFlop.kernelBuffer;
        float sum = 0.f;

        int srcInc = mGFlop.imageXSize - mGFlop.kernelXSize;
        const float * srcPtr = &mGFlop.srcBuffer[x - halfKX];

        for (int ix = 0; ix < mGFlop.kernelXSize; ix++) {
            sum += srcPtr[0] * krnPtr[0];
            krnPtr++;
            srcPtr++;
        }

        float * dstPtr = &mGFlop.dstBuffer[x];
        dstPtr[0] = sum;

    }

}

void Bench::GflopKernelC_y3() {
}

float Bench::runGFlopsTest(uint64_t /* options */)
{
    mTimeBucketDivisor = 1000 * 1000;  // use ms
    allocateBuckets(1000);

    mTimeStartNanos = getTimeNanos();
    mTimeEndNanos = mTimeStartNanos + mTimeBuckets * mTimeBucketDivisor;
    memset(mTimeBucket, 0, sizeof(uint32_t) * mTimeBuckets);

    mTimeEndGroupNanos = mTimeEndNanos;
    mWorkers.launchWork(testWork, this, 0);

    // Simulate image convolve
    mGFlop.kernelXSize = 27;
    mGFlop.imageXSize = 1024 * 1024;

    mGFlop.srcBuffer = (float *)malloc(mGFlop.imageXSize * sizeof(float));
    mGFlop.dstBuffer = (float *)malloc(mGFlop.imageXSize * sizeof(float));
    mGFlop.kernelBuffer = (float *)malloc(mGFlop.kernelXSize * sizeof(float));

    double ops = mGFlop.kernelXSize;
    ops = ops * 2.f - 1.f;
    ops *= mGFlop.imageXSize;

    uint64_t t1 = getTimeNanos();
    GflopKernelC();
    double dt = getTimeNanos() - t1;

    dt /= 1000.f * 1000.f * 1000.f;

    double gflops = ops / dt / 1000000000.f;

    __android_log_print(ANDROID_LOG_INFO, "bench", "v %f %f %f", dt, ops, gflops);

    return (float)gflops;
}


