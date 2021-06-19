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

#include "androidfw/ApkAssets.h"
#include "androidfw/AssetManager.h"
#include "androidfw/AssetManager2.h"
#include "androidfw/ResourceTypes.h"

namespace android {

constexpr const static char* kFrameworkPath = "/system/framework/framework-res.apk";
constexpr const static uint32_t kStyleId = 0x01030237u;  // android:style/Theme.Material.Light
constexpr const static uint32_t kAttrId = 0x01010030u;   // android:attr/colorForeground

static void BM_ThemeApplyStyleFramework(benchmark::State& state) {
  std::unique_ptr<const ApkAssets> apk = ApkAssets::Load(kFrameworkPath);
  if (apk == nullptr) {
    state.SkipWithError("Failed to load assets");
    return;
  }

  AssetManager2 assets;
  assets.SetApkAssets({apk.get()});

  while (state.KeepRunning()) {
    auto theme = assets.NewTheme();
    theme->ApplyStyle(kStyleId, false /* force */);
  }
}
BENCHMARK(BM_ThemeApplyStyleFramework);

static void BM_ThemeApplyStyleFrameworkOld(benchmark::State& state) {
  AssetManager assets;
  if (!assets.addAssetPath(String8(kFrameworkPath), nullptr /* cookie */, false /* appAsLib */,
                           true /* isSystemAsset */)) {
    state.SkipWithError("Failed to load assets");
    return;
  }

  const ResTable& res_table = assets.getResources(true);

  while (state.KeepRunning()) {
    std::unique_ptr<ResTable::Theme> theme{new ResTable::Theme(res_table)};
    theme->applyStyle(kStyleId, false /* force */);
  }
}
BENCHMARK(BM_ThemeApplyStyleFrameworkOld);

static void BM_ThemeGetAttribute(benchmark::State& state) {
  std::unique_ptr<const ApkAssets> apk = ApkAssets::Load(kFrameworkPath);

  AssetManager2 assets;
  assets.SetApkAssets({apk.get()});

  auto theme = assets.NewTheme();
  theme->ApplyStyle(kStyleId, false /* force */);

  while (state.KeepRunning()) {
    theme->GetAttribute(kAttrId);
  }
}
BENCHMARK(BM_ThemeGetAttribute);

static void BM_ThemeGetAttributeOld(benchmark::State& state) {
  AssetManager assets;
  assets.addAssetPath(String8(kFrameworkPath), nullptr /* cookie */, false /* appAsLib */,
                      true /* isSystemAsset */);
  const ResTable& res_table = assets.getResources(true);
  std::unique_ptr<ResTable::Theme> theme{new ResTable::Theme(res_table)};
  theme->applyStyle(kStyleId, false /* force */);

  Res_value value;
  uint32_t flags;

  while (state.KeepRunning()) {
    theme->getAttribute(kAttrId, &value, &flags);
  }
}
BENCHMARK(BM_ThemeGetAttributeOld);

}  // namespace android
