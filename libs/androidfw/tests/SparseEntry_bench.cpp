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
#include "android-base/file.h"

#include "BenchmarkHelpers.h"
#include "data/sparse/R.h"

namespace sparse = com::android::sparse;

namespace android {

static void BM_SparseEntryGetResourceHelper(const std::vector<std::string>& paths,
                    uint32_t resid, benchmark::State& state, void (*GetResourceBenchmarkFunc)(
                    const std::vector<std::string>&, const ResTable_config*,
                    uint32_t, benchmark::State&)){
    ResTable_config config;
    memset(&config, 0, sizeof(config));
    config.orientation = ResTable_config::ORIENTATION_LAND;
    GetResourceBenchmarkFunc(paths, &config, resid, state);
}

static void BM_SparseEntryGetResourceOldSparse(benchmark::State& state, uint32_t resid) {
  BM_SparseEntryGetResourceHelper({GetTestDataPath() + "/sparse/sparse.apk"}, resid,
                                    state, &GetResourceBenchmarkOld);
}
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceOldSparse, Small, sparse::R::integer::foo_9);
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceOldSparse, Large, sparse::R::string::foo_999);

static void BM_SparseEntryGetResourceOldNotSparse(benchmark::State& state, uint32_t resid) {
   BM_SparseEntryGetResourceHelper({GetTestDataPath() + "/sparse/not_sparse.apk"}, resid,
                                   state, &GetResourceBenchmarkOld);
}
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceOldNotSparse, Small, sparse::R::integer::foo_9);
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceOldNotSparse, Large, sparse::R::string::foo_999);

static void BM_SparseEntryGetResourceSparse(benchmark::State& state, uint32_t resid) {
  BM_SparseEntryGetResourceHelper({GetTestDataPath() + "/sparse/sparse.apk"}, resid,
                                  state, &GetResourceBenchmark);
}
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceSparse, Small, sparse::R::integer::foo_9);
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceSparse, Large, sparse::R::string::foo_999);

static void BM_SparseEntryGetResourceNotSparse(benchmark::State& state, uint32_t resid) {
  BM_SparseEntryGetResourceHelper({GetTestDataPath() + "/sparse/not_sparse.apk"}, resid,
                                  state, &GetResourceBenchmark);
}
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceNotSparse, Small, sparse::R::integer::foo_9);
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceNotSparse, Large, sparse::R::string::foo_999);

static void BM_SparseEntryGetResourceOldSparseRuntime(benchmark::State& state, uint32_t resid) {
  BM_SparseEntryGetResourceHelper({base::GetExecutableDirectory() +
                                  "/FrameworkResourcesSparseTestApp.apk"}, resid, state,
                                  &GetResourceBenchmarkOld);
}
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceOldSparseRuntime, Small, sparse::R::integer::foo_9);
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceOldSparseRuntime, Large, sparse::R::string::foo_999);

static void BM_SparseEntryGetResourceOldNotSparseRuntime(benchmark::State& state, uint32_t resid) {
  BM_SparseEntryGetResourceHelper({base::GetExecutableDirectory() +
                                  "/FrameworkResourcesNotSparseTestApp.apk"}, resid, state,
                                  &GetResourceBenchmarkOld);
}
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceOldNotSparseRuntime, Small, sparse::R::integer::foo_9);
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceOldNotSparseRuntime, Large, sparse::R::string::foo_999);

static void BM_SparseEntryGetResourceSparseRuntime(benchmark::State& state, uint32_t resid) {
  BM_SparseEntryGetResourceHelper({base::GetExecutableDirectory() +
                                  "/FrameworkResourcesSparseTestApp.apk"}, resid, state,
                                  &GetResourceBenchmark);
}
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceSparseRuntime, Small, sparse::R::integer::foo_9);
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceSparseRuntime, Large, sparse::R::string::foo_999);

static void BM_SparseEntryGetResourceNotSparseRuntime(benchmark::State& state, uint32_t resid) {
  BM_SparseEntryGetResourceHelper({base::GetExecutableDirectory() +
                                  "/FrameworkResourcesNotSparseTestApp.apk"}, resid, state,
                                  &GetResourceBenchmark);
}
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceNotSparseRuntime, Small, sparse::R::integer::foo_9);
BENCHMARK_CAPTURE(BM_SparseEntryGetResourceNotSparseRuntime, Large, sparse::R::string::foo_999);

}  // namespace android
