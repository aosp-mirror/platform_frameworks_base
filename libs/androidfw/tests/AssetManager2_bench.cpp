/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "benchmark/benchmark.h"

#include "android-base/stringprintf.h"
#include "androidfw/ApkAssets.h"
#include "androidfw/AssetManager.h"
#include "androidfw/AssetManager2.h"
#include "androidfw/ResourceTypes.h"

#include "BenchmarkHelpers.h"
#include "data/basic/R.h"
#include "data/libclient/R.h"
#include "data/styles/R.h"

namespace app = com::android::app;
namespace basic = com::android::basic;
namespace libclient = com::android::libclient;

namespace android {

constexpr const static char* kFrameworkPath = "/system/framework/framework-res.apk";

static void BM_AssetManagerLoadAssets(benchmark::State& state) {
  std::string path = GetTestDataPath() + "/basic/basic.apk";
  while (state.KeepRunning()) {
    auto apk = ApkAssets::Load(path);
    AssetManager2 assets;
    assets.SetApkAssets({apk});
  }
}
BENCHMARK(BM_AssetManagerLoadAssets);

static void BM_AssetManagerLoadAssetsOld(benchmark::State& state) {
  String8 path((GetTestDataPath() + "/basic/basic.apk").data());
  while (state.KeepRunning()) {
    AssetManager assets;
    assets.addAssetPath(path, nullptr /* cookie */, false /* appAsLib */,
                        false /* isSystemAsset */);

    // Force creation.
    assets.getResources(true);
  }
}
BENCHMARK(BM_AssetManagerLoadAssetsOld);

static void BM_AssetManagerLoadFrameworkAssets(benchmark::State& state) {
  std::string path = kFrameworkPath;
  while (state.KeepRunning()) {
    auto apk = ApkAssets::Load(path);
    AssetManager2 assets;
    assets.SetApkAssets({apk});
  }
}
BENCHMARK(BM_AssetManagerLoadFrameworkAssets);

static void BM_AssetManagerLoadFrameworkAssetsOld(benchmark::State& state) {
  String8 path(kFrameworkPath);
  while (state.KeepRunning()) {
    AssetManager assets;
    assets.addAssetPath(path, nullptr /* cookie */, false /* appAsLib */,
                        false /* isSystemAsset */);

    // Force creation.
    assets.getResources(true);
  }
}
BENCHMARK(BM_AssetManagerLoadFrameworkAssetsOld);

static void BM_AssetManagerGetResource(benchmark::State& state, uint32_t resid) {
  GetResourceBenchmark({GetTestDataPath() + "/basic/basic.apk"}, nullptr /*config*/, resid, state);
}
BENCHMARK_CAPTURE(BM_AssetManagerGetResource, number1, basic::R::integer::number1);
BENCHMARK_CAPTURE(BM_AssetManagerGetResource, deep_ref, basic::R::integer::deep_ref);

static void BM_AssetManagerGetResourceOld(benchmark::State& state, uint32_t resid) {
  GetResourceBenchmarkOld({GetTestDataPath() + "/basic/basic.apk"}, nullptr /*config*/, resid,
                          state);
}
BENCHMARK_CAPTURE(BM_AssetManagerGetResourceOld, number1, basic::R::integer::number1);
BENCHMARK_CAPTURE(BM_AssetManagerGetResourceOld, deep_ref, basic::R::integer::deep_ref);

static void BM_AssetManagerGetLibraryResource(benchmark::State& state) {
  GetResourceBenchmark(
      {GetTestDataPath() + "/lib_two/lib_two.apk", GetTestDataPath() + "/lib_one/lib_one.apk",
       GetTestDataPath() + "/libclient/libclient.apk"},
      nullptr /*config*/, libclient::R::string::foo_one, state);
}
BENCHMARK(BM_AssetManagerGetLibraryResource);

static void BM_AssetManagerGetLibraryResourceOld(benchmark::State& state) {
  GetResourceBenchmarkOld(
      {GetTestDataPath() + "/lib_two/lib_two.apk", GetTestDataPath() + "/lib_one/lib_one.apk",
       GetTestDataPath() + "/libclient/libclient.apk"},
      nullptr /*config*/, libclient::R::string::foo_one, state);
}
BENCHMARK(BM_AssetManagerGetLibraryResourceOld);

constexpr static const uint32_t kStringOkId = 0x0104000au;

static void BM_AssetManagerGetResourceFrameworkLocale(benchmark::State& state) {
  ResTable_config config;
  memset(&config, 0, sizeof(config));
  memcpy(config.language, "fr", 2);
  GetResourceBenchmark({kFrameworkPath}, &config, kStringOkId, state);
}
BENCHMARK(BM_AssetManagerGetResourceFrameworkLocale);

static void BM_AssetManagerGetResourceFrameworkLocaleOld(benchmark::State& state) {
  ResTable_config config;
  memset(&config, 0, sizeof(config));
  memcpy(config.language, "fr", 2);
  GetResourceBenchmarkOld({kFrameworkPath}, &config, kStringOkId, state);
}
BENCHMARK(BM_AssetManagerGetResourceFrameworkLocaleOld);

static void BM_AssetManagerGetBag(benchmark::State& state) {
  auto apk = ApkAssets::Load(GetTestDataPath() + "/styles/styles.apk");
  if (apk == nullptr) {
    state.SkipWithError("Failed to load assets");
    return;
  }

  AssetManager2 assets;
  assets.SetApkAssets({apk});

  while (state.KeepRunning()) {
    auto bag = assets.GetBag(app::R::style::StyleTwo);
    if (!bag.has_value()) {
      state.SkipWithError("Failed to load get bag");
      return;
    }
    const auto bag_end = end(*bag);
    for (auto iter = begin(*bag); iter != bag_end; ++iter) {
      uint32_t key = iter->key;
      Res_value value = iter->value;
      benchmark::DoNotOptimize(key);
      benchmark::DoNotOptimize(value);
    }
  }
}
BENCHMARK(BM_AssetManagerGetBag);

static void BM_AssetManagerGetBagOld(benchmark::State& state) {
  AssetManager assets;
  if (!assets.addAssetPath(String8((GetTestDataPath() + "/styles/styles.apk").data()),
                           nullptr /*cookie*/, false /*appAsLib*/, false /*isSystemAssets*/)) {
    state.SkipWithError("Failed to load assets");
    return;
  }

  const ResTable& table = assets.getResources(true);

  while (state.KeepRunning()) {
    const ResTable::bag_entry* bag_begin;
    const ssize_t N = table.lockBag(app::R::style::StyleTwo, &bag_begin);
    const ResTable::bag_entry* const bag_end = bag_begin + N;
    for (auto iter = bag_begin; iter != bag_end; ++iter) {
      uint32_t key = iter->map.name.ident;
      Res_value value = iter->map.value;
      benchmark::DoNotOptimize(key);
      benchmark::DoNotOptimize(value);
    }
    table.unlockBag(bag_begin);
  }
}
BENCHMARK(BM_AssetManagerGetBagOld);

static void BM_AssetManagerGetResourceLocales(benchmark::State& state) {
  auto apk = ApkAssets::Load(kFrameworkPath);
  if (apk == nullptr) {
    state.SkipWithError("Failed to load assets");
    return;
  }

  AssetManager2 assets;
  assets.SetApkAssets({apk});

  while (state.KeepRunning()) {
    std::set<std::string> locales =
        assets.GetResourceLocales(false /*exclude_system*/, true /*merge_equivalent_languages*/);
    benchmark::DoNotOptimize(locales);
  }
}
BENCHMARK(BM_AssetManagerGetResourceLocales);

static void BM_AssetManagerGetResourceLocalesOld(benchmark::State& state) {
  AssetManager assets;
  if (!assets.addAssetPath(String8(kFrameworkPath), nullptr /*cookie*/, false /*appAsLib*/,
                           true /*isSystemAssets*/)) {
    state.SkipWithError("Failed to load assets");
    return;
  }

  const ResTable& table = assets.getResources(true);

  while (state.KeepRunning()) {
    Vector<String8> locales;
    table.getLocales(&locales, true /*includeSystemLocales*/, true /*mergeEquivalentLangs*/);
    benchmark::DoNotOptimize(locales);
  }
}
BENCHMARK(BM_AssetManagerGetResourceLocalesOld);

static void BM_AssetManagerSetConfigurationFramework(benchmark::State& state) {
  auto apk = ApkAssets::Load(kFrameworkPath);
  if (apk == nullptr) {
    state.SkipWithError("Failed to load assets");
    return;
  }

  AssetManager2 assets;
  assets.SetApkAssets({apk});

  ResTable_config config;
  memset(&config, 0, sizeof(config));
  std::vector<ResTable_config> configs;
  configs.push_back(config);

  while (state.KeepRunning()) {
    configs[0].sdkVersion = ~configs[0].sdkVersion;
    assets.SetConfigurations(configs);
  }
}
BENCHMARK(BM_AssetManagerSetConfigurationFramework);

static void BM_AssetManagerSetConfigurationFrameworkOld(benchmark::State& state) {
  AssetManager assets;
  if (!assets.addAssetPath(String8(kFrameworkPath), nullptr /*cookie*/, false /*appAsLib*/,
                           true /*isSystemAssets*/)) {
    state.SkipWithError("Failed to load assets");
    return;
  }

  const ResTable& table = assets.getResources(true);

  ResTable_config config;
  memset(&config, 0, sizeof(config));

  while (state.KeepRunning()) {
    config.sdkVersion = ~config.sdkVersion;
    assets.setConfiguration(config);
  }
}
BENCHMARK(BM_AssetManagerSetConfigurationFrameworkOld);

}  // namespace android
