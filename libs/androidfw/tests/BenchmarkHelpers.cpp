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

#include "BenchmarkHelpers.h"

#include "android-base/stringprintf.h"
#include "androidfw/AssetManager.h"
#include "androidfw/AssetManager2.h"

namespace android {

void GetResourceBenchmarkOld(const std::vector<std::string>& paths, const ResTable_config* config,
                             uint32_t resid, benchmark::State& state) {
  AssetManager assetmanager;
  for (const std::string& path : paths) {
    if (!assetmanager.addAssetPath(String8(path.c_str()), nullptr /* cookie */,
                                   false /* appAsLib */, false /* isSystemAssets */)) {
      state.SkipWithError(base::StringPrintf("Failed to old-load assets %s", path.c_str()).c_str());
      return;
    }
  }

  // Make sure to force creation of the ResTable first, or else the configuration doesn't get set.
  const ResTable& table = assetmanager.getResources(true);
  if (config != nullptr) {
    assetmanager.setConfiguration(*config);
  }

  Res_value value;
  ResTable_config selected_config;
  uint32_t flags;
  uint32_t last_ref = 0u;

  while (state.KeepRunning()) {
    ssize_t block = table.getResource(resid, &value, false /*may_be_bag*/, 0u /*density*/, &flags,
                                      &selected_config);
    table.resolveReference(&value, block, &last_ref, &flags, &selected_config);
  }
}

void GetResourceBenchmark(const std::vector<std::string>& paths, const ResTable_config* config,
                          uint32_t resid, benchmark::State& state) {
  std::vector<AssetManager2::ApkAssetsPtr> apk_assets;
  for (const std::string& path : paths) {
    auto apk = ApkAssets::Load(path);
    if (apk == nullptr) {
      state.SkipWithError(base::StringPrintf("Failed to new-load assets %s", path.c_str()).c_str());
      return;
    }
    apk_assets.push_back(std::move(apk));
  }

  AssetManager2 assetmanager;
  assetmanager.SetApkAssets(apk_assets);
  if (config != nullptr) {
    assetmanager.SetConfigurations({*config});
  }

  while (state.KeepRunning()) {
    auto value = assetmanager.GetResource(resid);
    assetmanager.ResolveReference(*value);
  }
}

}  // namespace android
