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

#include "benchmark/benchmark.h"

//#include "android-base/stringprintf.h"
#include "androidfw/ApkAssets.h"
#include "androidfw/AssetManager.h"
#include "androidfw/AssetManager2.h"
#include "androidfw/AttributeResolution.h"
#include "androidfw/ResourceTypes.h"

#include "BenchmarkHelpers.h"
#include "data/basic/R.h"
#include "data/styles/R.h"

namespace app = com::android::app;
namespace basic = com::android::basic;

namespace android {

constexpr const static char* kFrameworkPath = "/system/framework/framework-res.apk";
constexpr const static uint32_t Theme_Material_Light = 0x01030237u;

static void BM_ApplyStyle(benchmark::State& state) {
  std::unique_ptr<const ApkAssets> styles_apk =
      ApkAssets::Load(GetTestDataPath() + "/styles/styles.apk");
  if (styles_apk == nullptr) {
    state.SkipWithError("failed to load assets");
    return;
  }

  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({styles_apk.get()});

  std::unique_ptr<Asset> asset =
      assetmanager.OpenNonAsset("res/layout/layout.xml", Asset::ACCESS_BUFFER);
  if (asset == nullptr) {
    state.SkipWithError("failed to load layout");
    return;
  }

  ResXMLTree xml_tree;
  if (xml_tree.setTo(asset->getBuffer(true), asset->getLength(), false /*copyData*/) != NO_ERROR) {
    state.SkipWithError("corrupt xml layout");
    return;
  }

  // Skip to the first tag.
  while (xml_tree.next() != ResXMLParser::START_TAG) {
  }

  std::unique_ptr<Theme> theme = assetmanager.NewTheme();
  theme->ApplyStyle(app::R::style::StyleTwo);

  std::array<uint32_t, 6> attrs{{app::R::attr::attr_one, app::R::attr::attr_two,
                                 app::R::attr::attr_three, app::R::attr::attr_four,
                                 app::R::attr::attr_five, app::R::attr::attr_empty}};
  std::array<uint32_t, attrs.size() * STYLE_NUM_ENTRIES> values;
  std::array<uint32_t, attrs.size() + 1> indices;

  while (state.KeepRunning()) {
    ApplyStyle(theme.get(), &xml_tree, 0u /*def_style_attr*/, 0u /*def_style_res*/, attrs.data(),
               attrs.size(), values.data(), indices.data());
  }
}
BENCHMARK(BM_ApplyStyle);

static void BM_ApplyStyleFramework(benchmark::State& state) {
  std::unique_ptr<const ApkAssets> framework_apk = ApkAssets::Load(kFrameworkPath);
  if (framework_apk == nullptr) {
    state.SkipWithError("failed to load framework assets");
    return;
  }

  std::unique_ptr<const ApkAssets> basic_apk =
      ApkAssets::Load(GetTestDataPath() + "/basic/basic.apk");
  if (basic_apk == nullptr) {
    state.SkipWithError("failed to load assets");
    return;
  }

  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({framework_apk.get(), basic_apk.get()});

  ResTable_config device_config;
  memset(&device_config, 0, sizeof(device_config));
  device_config.language[0] = 'e';
  device_config.language[1] = 'n';
  device_config.country[0] = 'U';
  device_config.country[1] = 'S';
  device_config.orientation = ResTable_config::ORIENTATION_PORT;
  device_config.smallestScreenWidthDp = 700;
  device_config.screenWidthDp = 700;
  device_config.screenHeightDp = 1024;
  device_config.sdkVersion = 27;

  auto value = assetmanager.GetResource(basic::R::layout::layoutt);
  if (!value.has_value()) {
    state.SkipWithError("failed to find R.layout.layout");
    return;
  }

  auto layout_path = assetmanager.GetStringPoolForCookie(value->cookie)->string8At(value->data);
  if (!layout_path.has_value()) {
    state.SkipWithError("failed to lookup layout path");
    return;
  }

  std::unique_ptr<Asset> asset = assetmanager.OpenNonAsset(layout_path->to_string(), value->cookie,
                                                           Asset::ACCESS_BUFFER);
  if (asset == nullptr) {
    state.SkipWithError("failed to load layout");
    return;
  }

  ResXMLTree xml_tree;
  if (xml_tree.setTo(asset->getBuffer(true), asset->getLength(), false /*copyData*/) != NO_ERROR) {
    state.SkipWithError("corrupt xml layout");
    return;
  }

  // Skip to the first tag.
  while (xml_tree.next() != ResXMLParser::START_TAG) {
  }

  std::unique_ptr<Theme> theme = assetmanager.NewTheme();
  theme->ApplyStyle(Theme_Material_Light);

  std::array<uint32_t, 92> attrs{
      {0x0101000e, 0x01010034, 0x01010095, 0x01010096, 0x01010097, 0x01010098, 0x01010099,
       0x0101009a, 0x0101009b, 0x010100ab, 0x010100af, 0x010100b0, 0x010100b1, 0x0101011f,
       0x01010120, 0x0101013f, 0x01010140, 0x0101014e, 0x0101014f, 0x01010150, 0x01010151,
       0x01010152, 0x01010153, 0x01010154, 0x01010155, 0x01010156, 0x01010157, 0x01010158,
       0x01010159, 0x0101015a, 0x0101015b, 0x0101015c, 0x0101015d, 0x0101015e, 0x0101015f,
       0x01010160, 0x01010161, 0x01010162, 0x01010163, 0x01010164, 0x01010165, 0x01010166,
       0x01010167, 0x01010168, 0x01010169, 0x0101016a, 0x0101016b, 0x0101016c, 0x0101016d,
       0x0101016e, 0x0101016f, 0x01010170, 0x01010171, 0x01010217, 0x01010218, 0x0101021d,
       0x01010220, 0x01010223, 0x01010224, 0x01010264, 0x01010265, 0x01010266, 0x010102c5,
       0x010102c6, 0x010102c7, 0x01010314, 0x01010315, 0x01010316, 0x0101035e, 0x0101035f,
       0x01010362, 0x01010374, 0x0101038c, 0x01010392, 0x01010393, 0x010103ac, 0x0101045d,
       0x010104b6, 0x010104b7, 0x010104d6, 0x010104d7, 0x010104dd, 0x010104de, 0x010104df,
       0x01010535, 0x01010536, 0x01010537, 0x01010538, 0x01010546, 0x01010567, 0x011100c9,
       0x011100ca}};

  std::array<uint32_t, attrs.size() * STYLE_NUM_ENTRIES> values;
  std::array<uint32_t, attrs.size() + 1> indices;
  while (state.KeepRunning()) {
    ApplyStyle(theme.get(), &xml_tree, 0x01010084u /*def_style_attr*/, 0u /*def_style_res*/,
               attrs.data(), attrs.size(), values.data(), indices.data());
  }
}
BENCHMARK(BM_ApplyStyleFramework);

}  // namespace android
