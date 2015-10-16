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

#include <benchmark/Benchmark.h>

#include "utils/LinearAllocator.h"
#include "microbench/MicroBench.h"

#include <vector>

using namespace android;
using namespace android::uirenderer;

BENCHMARK_NO_ARG(BM_LinearStdAllocator_vectorBaseline);
void BM_LinearStdAllocator_vectorBaseline::Run(int iters) {
    StartBenchmarkTiming();
    for (int i = 0; i < iters; i++) {
        std::vector<char> v;
        for (int j = 0; j < 200; j++) {
            v.push_back(j);
        }
        MicroBench::DoNotOptimize(&v);
    }
    StopBenchmarkTiming();
}

BENCHMARK_NO_ARG(BM_LinearStdAllocator_vector);
void BM_LinearStdAllocator_vector::Run(int iters) {
    StartBenchmarkTiming();
    for (int i = 0; i < iters; i++) {
        LinearAllocator la;
        LinearStdAllocator<void*> stdAllocator(la);
        std::vector<char, LinearStdAllocator<char> > v(stdAllocator);
        for (int j = 0; j < 200; j++) {
            v.push_back(j);
        }
        MicroBench::DoNotOptimize(&v);
    }
    StopBenchmarkTiming();
}
