/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include "android-base/file.h"
#include "androidfw/ApkAssets.h"
#include "androidfw/AssetManager2.h"
#include "androidfw/ResourceTypes.h"

#include "utils/String16.h"
#include "utils/String8.h"

#include "TestHelpers.h"
#include "data/overlay/R.h"
#include "data/overlayable/R.h"
#include "data/system/R.h"

namespace overlay = com::android::overlay;
namespace overlayable = com::android::overlayable;

namespace android {

namespace {

class IdmapTest : public ::testing::Test {
 protected:
  void SetUp() override {
    // Move to the test data directory so the idmap can locate the overlay APK.
    original_path = base::GetExecutableDirectory();
    chdir(GetTestDataPath().c_str());

    system_assets_ = ApkAssets::Load("system/system.apk");
    ASSERT_NE(nullptr, system_assets_);

    overlay_assets_ = ApkAssets::LoadOverlay("overlay/overlay.idmap");
    ASSERT_NE(nullptr, overlay_assets_);

    overlayable_assets_ = ApkAssets::Load("overlayable/overlayable.apk");
    ASSERT_NE(nullptr, overlayable_assets_);
  }

  void TearDown() override {
    chdir(original_path.c_str());
  }

 protected:
  std::string original_path;
  std::unique_ptr<const ApkAssets> system_assets_;
  std::unique_ptr<const ApkAssets> overlay_assets_;
  std::unique_ptr<const ApkAssets> overlayable_assets_;
};

std::string GetStringFromApkAssets(const AssetManager2& asset_manager, const Res_value& value,
                                   ApkAssetsCookie cookie) {
  auto assets = asset_manager.GetApkAssets();
  const ResStringPool* string_pool = assets[cookie]->GetLoadedArsc()->GetStringPool();
  return GetStringFromPool(string_pool, value.data);
}

}

TEST_F(IdmapTest, OverlayOverridesResourceValue) {
  AssetManager2 asset_manager;
  asset_manager.SetApkAssets({system_assets_.get(), overlayable_assets_.get(),
                              overlay_assets_.get()});
  Res_value val;
  ResTable_config config;
  uint32_t flags;
  ApkAssetsCookie cookie = asset_manager.GetResource(overlayable::R::string::overlayable5,
                                                    false /* may_be_bag */,
                                                    0 /* density_override */, &val, &config,
                                                    &flags);
  ASSERT_EQ(cookie, 2U);
  ASSERT_EQ(val.dataType, Res_value::TYPE_STRING);
  ASSERT_EQ(GetStringFromApkAssets(asset_manager, val, cookie), "Overlay One");
}

TEST_F(IdmapTest, OverlayOverridesResourceValueUsingDifferentPackage) {
  AssetManager2 asset_manager;
  asset_manager.SetApkAssets({system_assets_.get(), overlayable_assets_.get(),
                              overlay_assets_.get()});
  Res_value val;
  ResTable_config config;
  uint32_t flags;
  ApkAssetsCookie cookie = asset_manager.GetResource(overlayable::R::string::overlayable10,
                                                    false /* may_be_bag */,
                                                    0 /* density_override */, &val, &config,
                                                    &flags);
  ASSERT_EQ(cookie, 0U);
  ASSERT_EQ(val.dataType, Res_value::TYPE_STRING);
  ASSERT_EQ(GetStringFromApkAssets(asset_manager, val, cookie), "yes");
}

TEST_F(IdmapTest, OverlayOverridesResourceValueUsingInternalResource) {
  AssetManager2 asset_manager;
  asset_manager.SetApkAssets({system_assets_.get(), overlayable_assets_.get(),
                              overlay_assets_.get()});
  Res_value val;
  ResTable_config config;
  uint32_t flags;
  ApkAssetsCookie cookie = asset_manager.GetResource(overlayable::R::string::overlayable8,
                                                    false /* may_be_bag */,
                                                    0 /* density_override */, &val, &config,
                                                    &flags);
  ASSERT_EQ(cookie, 2U);
  ASSERT_EQ(val.dataType, Res_value::TYPE_REFERENCE);
  ASSERT_EQ(val.data, (overlay::R::string::internal & 0x00ffffff) | (0x02 << 24));
}

TEST_F(IdmapTest, OverlayOverridesResourceValueUsingInlineInteger) {
  AssetManager2 asset_manager;
  asset_manager.SetApkAssets({system_assets_.get(), overlayable_assets_.get(),
                              overlay_assets_.get()});
  Res_value val;
  ResTable_config config;
  uint32_t flags;
  ApkAssetsCookie cookie = asset_manager.GetResource(overlayable::R::integer::config_integer,
                                                  false /* may_be_bag */,
                                                  0 /* density_override */, &val, &config,
                                                  &flags);
  ASSERT_EQ(cookie, 2U);
  ASSERT_EQ(val.dataType, Res_value::TYPE_INT_DEC);
  ASSERT_EQ(val.data, 42);
}

TEST_F(IdmapTest, OverlayOverridesResourceValueUsingInlineString) {
  AssetManager2 asset_manager;
  asset_manager.SetApkAssets({system_assets_.get(), overlayable_assets_.get(),
                              overlay_assets_.get()});
  Res_value val;
  ResTable_config config;
  uint32_t flags;

  ApkAssetsCookie cookie = asset_manager.GetResource(overlayable::R::string::overlayable11,
                                                  false /* may_be_bag */,
                                                  0 /* density_override */, &val, &config,
                                                  &flags);
  ASSERT_EQ(cookie, 2U);
  ASSERT_EQ(val.dataType, Res_value::TYPE_STRING);
  ASSERT_EQ(GetStringFromApkAssets(asset_manager, val, cookie), "Hardcoded string");
}

TEST_F(IdmapTest, OverlayOverridesResourceValueUsingOverlayingResource) {
  AssetManager2 asset_manager;
  asset_manager.SetApkAssets({system_assets_.get(), overlayable_assets_.get(),
                              overlay_assets_.get()});
  Res_value val;
  ResTable_config config;
  uint32_t flags;
  ApkAssetsCookie cookie = asset_manager.GetResource(overlayable::R::string::overlayable9,
                                                    false /* may_be_bag */,
                                                    0 /* density_override */, &val, &config,
                                                    &flags);
  ASSERT_EQ(cookie, 2U);
  ASSERT_EQ(val.dataType, Res_value::TYPE_REFERENCE);
  ASSERT_EQ(val.data, overlayable::R::string::overlayable7);
}

TEST_F(IdmapTest, OverlayOverridesXmlParser) {
  AssetManager2 asset_manager;
  asset_manager.SetApkAssets({system_assets_.get(), overlayable_assets_.get(),
                              overlay_assets_.get()});
  Res_value val;
  ResTable_config config;
  uint32_t flags;
  ApkAssetsCookie cookie = asset_manager.GetResource(overlayable::R::layout::hello_view,
                                                    false /* may_be_bag */,
                                                    0 /* density_override */, &val, &config,
                                                    &flags);
  ASSERT_EQ(cookie, 2U);
  ASSERT_EQ(val.dataType, Res_value::TYPE_STRING);
  ASSERT_EQ(GetStringFromApkAssets(asset_manager, val, cookie), "res/layout/hello_view.xml");

  auto asset = asset_manager.OpenNonAsset("res/layout/hello_view.xml", cookie,
                                          Asset::ACCESS_RANDOM);
  auto dynamic_ref_table = asset_manager.GetDynamicRefTableForCookie(cookie);
  auto xml_tree = util::make_unique<ResXMLTree>(std::move(dynamic_ref_table));
  status_t err = xml_tree->setTo(asset->getBuffer(true), asset->getLength(), false);
  ASSERT_EQ(err, NO_ERROR);

  while (xml_tree->next() != ResXMLParser::START_TAG) { }

  // The resource id of @id/hello_view should be rewritten to the resource id/hello_view within the
  // target.
  ASSERT_EQ(xml_tree->getAttributeNameResID(0), 0x010100d0 /* android:attr/id */);
  ASSERT_EQ(xml_tree->getAttributeDataType(0), Res_value::TYPE_REFERENCE);
  ASSERT_EQ(xml_tree->getAttributeData(0), overlayable::R::id::hello_view);

  // The resource id of @android:string/yes should not be rewritten even though it overlays
  // string/overlayable10 in the target.
  ASSERT_EQ(xml_tree->getAttributeNameResID(1), 0x0101014f /* android:attr/text */);
  ASSERT_EQ(xml_tree->getAttributeDataType(1), Res_value::TYPE_REFERENCE);
  ASSERT_EQ(xml_tree->getAttributeData(1), 0x01040013 /* android:string/yes */);

  // The resource id of the attribute within the overlay should be rewritten to the resource id of
  // the attribute in the target.
  ASSERT_EQ(xml_tree->getAttributeNameResID(2), overlayable::R::attr::max_lines);
  ASSERT_EQ(xml_tree->getAttributeDataType(2), Res_value::TYPE_INT_DEC);
  ASSERT_EQ(xml_tree->getAttributeData(2), 4);
}

TEST_F(IdmapTest, OverlaidResourceHasSameName) {
  AssetManager2 asset_manager;
  asset_manager.SetApkAssets({system_assets_.get(), overlayable_assets_.get(),
                              overlay_assets_.get()});

  AssetManager2::ResourceName name;
  ASSERT_TRUE(asset_manager.GetResourceName(overlayable::R::string::overlayable9, &name));
  ASSERT_EQ(std::string(name.package), "com.android.overlayable");
  ASSERT_EQ(String16(name.type16), u"string");
  ASSERT_EQ(std::string(name.entry), "overlayable9");
}

TEST_F(IdmapTest, OverlayLoaderInterop) {
  std::string contents;
  auto loader_assets = ApkAssets::LoadTable("loader/resources.arsc", PROPERTY_LOADER);

  AssetManager2 asset_manager;
  asset_manager.SetApkAssets({overlayable_assets_.get(), loader_assets.get(),
                              overlay_assets_.get()});

  Res_value val;
  ResTable_config config;
  uint32_t flags;
  ApkAssetsCookie cookie = asset_manager.GetResource(overlayable::R::string::overlayable11,
                                                    false /* may_be_bag */,
                                                    0 /* density_override */, &val, &config,
                                                    &flags);
  std::cout << asset_manager.GetLastResourceResolution();
  ASSERT_EQ(cookie, 1U);
  ASSERT_EQ(val.dataType, Res_value::TYPE_STRING);
  ASSERT_EQ(GetStringFromApkAssets(asset_manager, val, cookie), "loader");
}

TEST_F(IdmapTest, OverlayAssetsIsUpToDate) {
  std::string idmap_contents;
  ASSERT_TRUE(base::ReadFileToString("overlay/overlay.idmap", &idmap_contents));

  TemporaryFile temp_file;
  ASSERT_TRUE(base::WriteStringToFile(idmap_contents, temp_file.path));

  auto apk_assets = ApkAssets::LoadOverlay(temp_file.path);
  ASSERT_NE(nullptr, apk_assets);
  ASSERT_TRUE(apk_assets->IsUpToDate());

  unlink(temp_file.path);
  ASSERT_FALSE(apk_assets->IsUpToDate());
  sleep(2);

  base::WriteStringToFile("hello", temp_file.path);
  sleep(2);

  ASSERT_FALSE(apk_assets->IsUpToDate());
}

}  // namespace
