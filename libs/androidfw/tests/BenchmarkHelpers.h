/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef ANDROIDFW_TESTS_BENCHMARKHELPERS_H
#define ANDROIDFW_TESTS_BENCHMARKHELPERS_H

#include <string>
#include <vector>

#include "androidfw/ResourceTypes.h"
#include "benchmark/benchmark.h"

#include "CommonHelpers.h"

namespace android {

void GetResourceBenchmarkOld(const std::vector<std::string>& paths, const ResTable_config* config,
                             uint32_t resid, ::benchmark::State& state);

void GetResourceBenchmark(const std::vector<std::string>& paths, const ResTable_config* config,
                          uint32_t resid, benchmark::State& state);

}  // namespace android

#endif  // ANDROIDFW_TESTS_BENCHMARKHELPERS_H
