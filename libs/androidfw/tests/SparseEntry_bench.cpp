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

#include "androidfw/AssetManager.h"
#include "androidfw/ResourceTypes.h"

#include "BenchmarkHelpers.h"
#include "TestHelpers.h"
#include "data/sparse/R.h"

namespace sparse = com::android::sparse;

namespace android {

static void BM_SparseEntryGetResourceSparseSmall(benchmark::State& state) {
  ResTable_config config;
  memset(&config, 0, sizeof(config));
  config.sdkVersion = 26;
  GetResourceBenchmarkOld({GetTestDataPath() + "/sparse/sparse.apk"}, &config,
                          sparse::R::integer::foo_9, state);
}
BENCHMARK(BM_SparseEntryGetResourceSparseSmall);

static void BM_SparseEntryGetResourceNotSparseSmall(benchmark::State& state) {
  ResTable_config config;
  memset(&config, 0, sizeof(config));
  config.sdkVersion = 26;
  GetResourceBenchmarkOld({GetTestDataPath() + "/sparse/not_sparse.apk"}, &config,
                          sparse::R::integer::foo_9, state);
}
BENCHMARK(BM_SparseEntryGetResourceNotSparseSmall);

static void BM_SparseEntryGetResourceSparseLarge(benchmark::State& state) {
  ResTable_config config;
  memset(&config, 0, sizeof(config));
  config.sdkVersion = 26;
  GetResourceBenchmarkOld({GetTestDataPath() + "/sparse/sparse.apk"}, &config,
                          sparse::R::string::foo_999, state);
}
BENCHMARK(BM_SparseEntryGetResourceSparseLarge);

static void BM_SparseEntryGetResourceNotSparseLarge(benchmark::State& state) {
  ResTable_config config;
  memset(&config, 0, sizeof(config));
  config.sdkVersion = 26;
  GetResourceBenchmarkOld({GetTestDataPath() + "/sparse/not_sparse.apk"}, &config,
                          sparse::R::string::foo_999, state);
}
BENCHMARK(BM_SparseEntryGetResourceNotSparseLarge);

}  // namespace android
