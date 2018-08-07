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

#include <benchmark/benchmark.h>

#include "PathParser.h"
#include "VectorDrawable.h"

#include <SkPath.h>

using namespace android;
using namespace android::uirenderer;

static const char* sPathString =
        "M 1 1 m 2 2, l 3 3 L 3 3 H 4 h4 V5 v5, Q6 6 6 6 q 6 6 6 6t 7 7 T 7 7 C 8 8 8 8 8 8 c 8 8 "
        "8 8 8 8 S 9 9 9 9 s 9 9 9 9 A 10 10 0 1 1 10 10 a 10 10 0 1 1 10 10";

void BM_PathParser_parseStringPathForSkPath(benchmark::State& state) {
    SkPath skPath;
    size_t length = strlen(sPathString);
    PathParser::ParseResult result;
    while (state.KeepRunning()) {
        PathParser::parseAsciiStringForSkPath(&skPath, &result, sPathString, length);
        benchmark::DoNotOptimize(&result);
        benchmark::DoNotOptimize(&skPath);
    }
}
BENCHMARK(BM_PathParser_parseStringPathForSkPath);

void BM_PathParser_parseStringPathForPathData(benchmark::State& state) {
    size_t length = strlen(sPathString);
    PathData outData;
    PathParser::ParseResult result;
    while (state.KeepRunning()) {
        PathParser::getPathDataFromAsciiString(&outData, &result, sPathString, length);
        benchmark::DoNotOptimize(&result);
        benchmark::DoNotOptimize(&outData);
    }
}
BENCHMARK(BM_PathParser_parseStringPathForPathData);
