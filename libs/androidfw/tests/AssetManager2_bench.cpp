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
#include "TestHelpers.h"
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
    std::unique_ptr<const ApkAssets> apk = ApkAssets::Load(path);
    AssetManager2 assets;
    assets.SetApkAssets({apk.get()});
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
    std::unique_ptr<const ApkAssets> apk = ApkAssets::Load(path);
    AssetManager2 assets;
    assets.SetApkAssets({apk.get()});
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

static void GetResourceBenchmark(const std::vector<std::string>& paths,
                                 const ResTable_config* config, uint32_t resid,
                                 benchmark::State& state) {
  std::vector<std::unique_ptr<const ApkAssets>> apk_assets;
  std::vector<const ApkAssets*> apk_assets_ptrs;
  for (const std::string& path : paths) {
    std::unique_ptr<const ApkAssets> apk = ApkAssets::Load(path);
    if (apk == nullptr) {
      state.SkipWithError(base::StringPrintf("Failed to load assets %s", path.c_str()).c_str());
      return;
    }
    apk_assets_ptrs.push_back(apk.get());
    apk_assets.push_back(std::move(apk));
  }

  AssetManager2 assetmanager;
  assetmanager.SetApkAssets(apk_assets_ptrs);
  if (config != nullptr) {
    assetmanager.SetConfiguration(*config);
  }

  Res_value value;
  ResTable_config selected_config;
  uint32_t flags;

  while (state.KeepRunning()) {
    assetmanager.GetResource(resid, false /* may_be_bag */, 0u /* density_override */, &value,
                             &selected_config, &flags);
  }
}

static void BM_AssetManagerGetResource(benchmark::State& state) {
  GetResourceBenchmark({GetTestDataPath() + "/basic/basic.apk"}, nullptr /*config*/,
                       basic::R::integer::number1, state);
}
BENCHMARK(BM_AssetManagerGetResource);

static void BM_AssetManagerGetResourceOld(benchmark::State& state) {
  GetResourceBenchmarkOld({GetTestDataPath() + "/basic/basic.apk"}, nullptr /*config*/,
                          basic::R::integer::number1, state);
}
BENCHMARK(BM_AssetManagerGetResourceOld);

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
  std::unique_ptr<const ApkAssets> apk = ApkAssets::Load(GetTestDataPath() + "/styles/styles.apk");
  if (apk == nullptr) {
    state.SkipWithError("Failed to load assets");
    return;
  }

  AssetManager2 assets;
  assets.SetApkAssets({apk.get()});

  while (state.KeepRunning()) {
    const ResolvedBag* bag = assets.GetBag(app::R::style::StyleTwo);
    const auto bag_end = end(bag);
    for (auto iter = begin(bag); iter != bag_end; ++iter) {
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
  std::unique_ptr<const ApkAssets> apk = ApkAssets::Load(kFrameworkPath);
  if (apk == nullptr) {
    state.SkipWithError("Failed to load assets");
    return;
  }

  AssetManager2 assets;
  assets.SetApkAssets({apk.get()});

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
                           false /*isSystemAssets*/)) {
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

}  // namespace android
