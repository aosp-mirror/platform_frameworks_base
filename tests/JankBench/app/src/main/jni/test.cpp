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

#include <stdlib.h>
#include <stdio.h>
//#include <fcntl.h>
//#include <unistd.h>
#include <math.h>
#include <inttypes.h>
#include <time.h>
#include <android/log.h>

#include "jni.h"
#include "Bench.h"

#define FUNC(name) Java_com_android_benchmark_synthetic_TestInterface_##name

static uint64_t GetTime() {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_nsec + ((uint64_t)t.tv_sec * 1000 * 1000 * 1000);
}

extern "C" {

jlong Java_com_android_benchmark_synthetic_TestInterface_nInit(JNIEnv *_env, jobject _this, jlong options) {
    Bench *b = new Bench();
    bool ret = b->init();

    if (ret) {
        return (jlong)b;
    }

    delete b;
    return 0;
}

void Java_com_android_benchmark_synthetic_TestInterface_nDestroy(JNIEnv *_env, jobject _this, jlong _b) {
    Bench *b = (Bench *)_b;

    delete b;
}

jboolean Java_com_android_benchmark_synthetic_TestInterface_nRunPowerManagementTest(
        JNIEnv *_env, jobject _this, jlong _b, jlong options) {
    Bench *b = (Bench *)_b;
    return b->runPowerManagementTest(options);
}

jboolean Java_com_android_benchmark_synthetic_TestInterface_nRunCPUHeatSoakTest(
        JNIEnv *_env, jobject _this, jlong _b, jlong options) {
    Bench *b = (Bench *)_b;
    return b->runCPUHeatSoak(options);
}

float Java_com_android_benchmark_synthetic_TestInterface_nGetData(
        JNIEnv *_env, jobject _this, jlong _b, jfloatArray data) {
    Bench *b = (Bench *)_b;

    jsize len = _env->GetArrayLength(data);
    float * ptr = _env->GetFloatArrayElements(data, 0);

    b->getData(ptr, len);

    _env->ReleaseFloatArrayElements(data, (jfloat *)ptr, 0);

    return 0;
}

jboolean Java_com_android_benchmark_synthetic_TestInterface_nMemTestStart(
        JNIEnv *_env, jobject _this, jlong _b) {
    Bench *b = (Bench *)_b;
    return b->startMemTests();
}

float Java_com_android_benchmark_synthetic_TestInterface_nMemTestBandwidth(
        JNIEnv *_env, jobject _this, jlong _b, jlong opt) {
    Bench *b = (Bench *)_b;
    return b->runMemoryBandwidthTest(opt);
}

float Java_com_android_benchmark_synthetic_TestInterface_nGFlopsTest(
        JNIEnv *_env, jobject _this, jlong _b, jlong opt) {
    Bench *b = (Bench *)_b;
    return b->runGFlopsTest(opt);
}

float Java_com_android_benchmark_synthetic_TestInterface_nMemTestLatency(
        JNIEnv *_env, jobject _this, jlong _b, jlong opt) {
    Bench *b = (Bench *)_b;
    return b->runMemoryLatencyTest(opt);
}

void Java_com_android_benchmark_synthetic_TestInterface_nMemTestEnd(
        JNIEnv *_env, jobject _this, jlong _b) {
    Bench *b = (Bench *)_b;
    b->endMemTests();
}

float Java_com_android_benchmark_synthetic_TestInterface_nMemoryTest(
        JNIEnv *_env, jobject _this, jint subtest) {

    uint8_t * volatile m1 = (uint8_t *)malloc(1024*1024*64);
    uint8_t * m2 = (uint8_t *)malloc(1024*1024*64);

    memset(m1, 0, 1024*1024*16);
    memset(m2, 0, 1024*1024*16);

    //__android_log_print(ANDROID_LOG_INFO, "bench", "test %i  %p  %p", subtest, m1, m2);


    size_t loopCount = 0;
    uint64_t start = GetTime();
    while((GetTime() - start) < 1000000000) {
        memcpy(m1, m2, subtest);
        loopCount++;
    }
    if (loopCount == 0) {
        loopCount = 1;
    }

    size_t count = loopCount;
    uint64_t t1 = GetTime();
    while (loopCount > 0) {
        memcpy(m1, m2, subtest);
        loopCount--;
    }
    uint64_t t2 = GetTime();

    double dt = t2 - t1;
    dt /= 1000 * 1000 * 1000;
    double bw = ((double)subtest) * count / dt;

    bw /= 1024 * 1024 * 1024;

    __android_log_print(ANDROID_LOG_INFO, "bench", "size %i, bw %f", subtest, bw);

    free (m1);
    free (m2);
    return (float)bw;
}

jlong Java_com_android_benchmark_synthetic_MemoryAvailableLoad1_nMemTestMalloc(
        JNIEnv *_env, jobject _this, jint bytes) {
    uint8_t *p = (uint8_t *)malloc(bytes);
    memset(p, 0, bytes);
    return (jlong)p;
}

void Java_com_android_benchmark_synthetic_MemoryAvailableLoad1_nMemTestFree(
        JNIEnv *_env, jobject _this, jlong ptr) {
    free((void *)ptr);
}

jlong Java_com_android_benchmark_synthetic_MemoryAvailableLoad2_nMemTestMalloc(
        JNIEnv *_env, jobject _this, jint bytes) {
    return Java_com_android_benchmark_synthetic_MemoryAvailableLoad1_nMemTestMalloc(_env, _this, bytes);
}

void Java_com_android_benchmark_synthetic_MemoryAvailableLoad2_nMemTestFree(
        JNIEnv *_env, jobject _this, jlong ptr) {
    Java_com_android_benchmark_synthetic_MemoryAvailableLoad1_nMemTestFree(_env, _this, ptr);
}

}; // extern "C"
