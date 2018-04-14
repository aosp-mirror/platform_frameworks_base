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

#include "androidfw/AssetManager2.h"
#include "androidfw/AssetManager.h"

#include "android-base/logging.h"

#include "TestHelpers.h"
#include "androidfw/ResourceUtils.h"
#include "data/appaslib/R.h"
#include "data/basic/R.h"
#include "data/lib_one/R.h"
#include "data/lib_two/R.h"
#include "data/libclient/R.h"
#include "data/styles/R.h"
#include "data/system/R.h"

namespace app = com::android::app;
namespace appaslib = com::android::appaslib::app;
namespace basic = com::android::basic;
namespace lib_one = com::android::lib_one;
namespace lib_two = com::android::lib_two;
namespace libclient = com::android::libclient;

using ::testing::Eq;
using ::testing::NotNull;
using ::testing::StrEq;

namespace android {

class AssetManager2Test : public ::testing::Test {
 public:
  void SetUp() override {
    basic_assets_ = ApkAssets::Load(GetTestDataPath() + "/basic/basic.apk");
    ASSERT_NE(nullptr, basic_assets_);

    basic_de_fr_assets_ = ApkAssets::Load(GetTestDataPath() + "/basic/basic_de_fr.apk");
    ASSERT_NE(nullptr, basic_de_fr_assets_);

    style_assets_ = ApkAssets::Load(GetTestDataPath() + "/styles/styles.apk");
    ASSERT_NE(nullptr, style_assets_);

    lib_one_assets_ = ApkAssets::Load(GetTestDataPath() + "/lib_one/lib_one.apk");
    ASSERT_NE(nullptr, lib_one_assets_);

    lib_two_assets_ = ApkAssets::Load(GetTestDataPath() + "/lib_two/lib_two.apk");
    ASSERT_NE(nullptr, lib_two_assets_);

    libclient_assets_ = ApkAssets::Load(GetTestDataPath() + "/libclient/libclient.apk");
    ASSERT_NE(nullptr, libclient_assets_);

    appaslib_assets_ = ApkAssets::LoadAsSharedLibrary(GetTestDataPath() + "/appaslib/appaslib.apk");
    ASSERT_NE(nullptr, appaslib_assets_);

    system_assets_ = ApkAssets::Load(GetTestDataPath() + "/system/system.apk", true /*system*/);
    ASSERT_NE(nullptr, system_assets_);

    app_assets_ = ApkAssets::Load(GetTestDataPath() + "/app/app.apk");
    ASSERT_THAT(app_assets_, NotNull());
  }

 protected:
  std::unique_ptr<const ApkAssets> basic_assets_;
  std::unique_ptr<const ApkAssets> basic_de_fr_assets_;
  std::unique_ptr<const ApkAssets> style_assets_;
  std::unique_ptr<const ApkAssets> lib_one_assets_;
  std::unique_ptr<const ApkAssets> lib_two_assets_;
  std::unique_ptr<const ApkAssets> libclient_assets_;
  std::unique_ptr<const ApkAssets> appaslib_assets_;
  std::unique_ptr<const ApkAssets> system_assets_;
  std::unique_ptr<const ApkAssets> app_assets_;
};

TEST_F(AssetManager2Test, FindsResourceFromSingleApkAssets) {
  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));
  desired_config.language[0] = 'd';
  desired_config.language[1] = 'e';

  AssetManager2 assetmanager;
  assetmanager.SetConfiguration(desired_config);
  assetmanager.SetApkAssets({basic_assets_.get()});

  Res_value value;
  ResTable_config selected_config;
  uint32_t flags;

  ApkAssetsCookie cookie =
      assetmanager.GetResource(basic::R::string::test1, false /*may_be_bag*/,
                               0 /*density_override*/, &value, &selected_config, &flags);
  ASSERT_NE(kInvalidCookie, cookie);

  // Came from our ApkAssets.
  EXPECT_EQ(0, cookie);

  // It is the default config.
  EXPECT_EQ(0, selected_config.language[0]);
  EXPECT_EQ(0, selected_config.language[1]);

  // It is a string.
  EXPECT_EQ(Res_value::TYPE_STRING, value.dataType);
}

TEST_F(AssetManager2Test, FindsResourceFromMultipleApkAssets) {
  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));
  desired_config.language[0] = 'd';
  desired_config.language[1] = 'e';

  AssetManager2 assetmanager;
  assetmanager.SetConfiguration(desired_config);
  assetmanager.SetApkAssets({basic_assets_.get(), basic_de_fr_assets_.get()});

  Res_value value;
  ResTable_config selected_config;
  uint32_t flags;

  ApkAssetsCookie cookie =
      assetmanager.GetResource(basic::R::string::test1, false /*may_be_bag*/,
                               0 /*density_override*/, &value, &selected_config, &flags);
  ASSERT_NE(kInvalidCookie, cookie);

  // Came from our de_fr ApkAssets.
  EXPECT_EQ(1, cookie);

  // The configuration is German.
  EXPECT_EQ('d', selected_config.language[0]);
  EXPECT_EQ('e', selected_config.language[1]);

  // It is a string.
  EXPECT_EQ(Res_value::TYPE_STRING, value.dataType);
}

TEST_F(AssetManager2Test, FindsResourceFromSharedLibrary) {
  AssetManager2 assetmanager;

  // libclient is built with lib_one and then lib_two in order.
  // Reverse the order to test that proper package ID re-assignment is happening.
  assetmanager.SetApkAssets(
      {lib_two_assets_.get(), lib_one_assets_.get(), libclient_assets_.get()});

  Res_value value;
  ResTable_config selected_config;
  uint32_t flags;

  ApkAssetsCookie cookie =
      assetmanager.GetResource(libclient::R::string::foo_one, false /*may_be_bag*/,
                               0 /*density_override*/, &value, &selected_config, &flags);
  ASSERT_NE(kInvalidCookie, cookie);

  // Reference comes from libclient.
  EXPECT_EQ(2, cookie);
  EXPECT_EQ(Res_value::TYPE_REFERENCE, value.dataType);

  // Lookup the reference.
  cookie = assetmanager.GetResource(value.data, false /* may_be_bag */, 0 /* density_override*/,
                                    &value, &selected_config, &flags);
  ASSERT_NE(kInvalidCookie, cookie);
  EXPECT_EQ(1, cookie);
  EXPECT_EQ(Res_value::TYPE_STRING, value.dataType);
  EXPECT_EQ(std::string("Foo from lib_one"),
            GetStringFromPool(assetmanager.GetStringPoolForCookie(cookie), value.data));

  cookie = assetmanager.GetResource(libclient::R::string::foo_two, false /*may_be_bag*/,
                                    0 /*density_override*/, &value, &selected_config, &flags);
  ASSERT_NE(kInvalidCookie, cookie);

  // Reference comes from libclient.
  EXPECT_EQ(2, cookie);
  EXPECT_EQ(Res_value::TYPE_REFERENCE, value.dataType);

  // Lookup the reference.
  cookie = assetmanager.GetResource(value.data, false /* may_be_bag */, 0 /* density_override*/,
                                    &value, &selected_config, &flags);
  ASSERT_NE(kInvalidCookie, cookie);
  EXPECT_EQ(0, cookie);
  EXPECT_EQ(Res_value::TYPE_STRING, value.dataType);
  EXPECT_EQ(std::string("Foo from lib_two"),
            GetStringFromPool(assetmanager.GetStringPoolForCookie(cookie), value.data));
}

TEST_F(AssetManager2Test, FindsResourceFromAppLoadedAsSharedLibrary) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({appaslib_assets_.get()});

  // The appaslib package will have been assigned the package ID 0x02.

  Res_value value;
  ResTable_config selected_config;
  uint32_t flags;
  ApkAssetsCookie cookie = assetmanager.GetResource(
      fix_package_id(appaslib::R::integer::number1, 0x02), false /*may_be_bag*/,
      0u /*density_override*/, &value, &selected_config, &flags);
  ASSERT_NE(kInvalidCookie, cookie);
  EXPECT_EQ(Res_value::TYPE_REFERENCE, value.dataType);
  EXPECT_EQ(fix_package_id(appaslib::R::array::integerArray1, 0x02), value.data);
}

TEST_F(AssetManager2Test, FindsBagResourceFromSingleApkAssets) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_assets_.get()});

  const ResolvedBag* bag = assetmanager.GetBag(basic::R::array::integerArray1);
  ASSERT_NE(nullptr, bag);
  ASSERT_EQ(3u, bag->entry_count);

  EXPECT_EQ(static_cast<uint8_t>(Res_value::TYPE_INT_DEC), bag->entries[0].value.dataType);
  EXPECT_EQ(1u, bag->entries[0].value.data);
  EXPECT_EQ(0, bag->entries[0].cookie);

  EXPECT_EQ(static_cast<uint8_t>(Res_value::TYPE_INT_DEC), bag->entries[1].value.dataType);
  EXPECT_EQ(2u, bag->entries[1].value.data);
  EXPECT_EQ(0, bag->entries[1].cookie);

  EXPECT_EQ(static_cast<uint8_t>(Res_value::TYPE_INT_DEC), bag->entries[2].value.dataType);
  EXPECT_EQ(3u, bag->entries[2].value.data);
  EXPECT_EQ(0, bag->entries[2].cookie);
}

TEST_F(AssetManager2Test, FindsBagResourceFromMultipleApkAssets) {}

TEST_F(AssetManager2Test, FindsBagResourceFromSharedLibrary) {
  AssetManager2 assetmanager;

  // libclient is built with lib_one and then lib_two in order.
  // Reverse the order to test that proper package ID re-assignment is happening.
  assetmanager.SetApkAssets(
      {lib_two_assets_.get(), lib_one_assets_.get(), libclient_assets_.get()});

  const ResolvedBag* bag = assetmanager.GetBag(fix_package_id(lib_one::R::style::Theme, 0x03));
  ASSERT_NE(nullptr, bag);
  ASSERT_GE(bag->entry_count, 2u);

  // First two attributes come from lib_one.
  EXPECT_EQ(1, bag->entries[0].cookie);
  EXPECT_EQ(0x03, get_package_id(bag->entries[0].key));
  EXPECT_EQ(1, bag->entries[1].cookie);
  EXPECT_EQ(0x03, get_package_id(bag->entries[1].key));
}

TEST_F(AssetManager2Test, FindsStyleResourceWithParentFromSharedLibrary) {
  AssetManager2 assetmanager;

  // libclient is built with lib_one and then lib_two in order.
  // Reverse the order to test that proper package ID re-assignment is happening.
  assetmanager.SetApkAssets(
      {lib_two_assets_.get(), lib_one_assets_.get(), libclient_assets_.get()});

  const ResolvedBag* bag = assetmanager.GetBag(libclient::R::style::Theme);
  ASSERT_NE(nullptr, bag);
  ASSERT_GE(bag->entry_count, 2u);

  // First two attributes come from lib_one.
  EXPECT_EQ(1, bag->entries[0].cookie);
  EXPECT_EQ(0x03, get_package_id(bag->entries[0].key));
  EXPECT_EQ(1, bag->entries[1].cookie);
  EXPECT_EQ(0x03, get_package_id(bag->entries[1].key));
}

TEST_F(AssetManager2Test, MergesStylesWithParentFromSingleApkAssets) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({style_assets_.get()});

  const ResolvedBag* bag_one = assetmanager.GetBag(app::R::style::StyleOne);
  ASSERT_NE(nullptr, bag_one);
  ASSERT_EQ(2u, bag_one->entry_count);

  EXPECT_EQ(app::R::attr::attr_one, bag_one->entries[0].key);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, bag_one->entries[0].value.dataType);
  EXPECT_EQ(1u, bag_one->entries[0].value.data);
  EXPECT_EQ(0, bag_one->entries[0].cookie);

  EXPECT_EQ(app::R::attr::attr_two, bag_one->entries[1].key);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, bag_one->entries[1].value.dataType);
  EXPECT_EQ(2u, bag_one->entries[1].value.data);
  EXPECT_EQ(0, bag_one->entries[1].cookie);

  const ResolvedBag* bag_two = assetmanager.GetBag(app::R::style::StyleTwo);
  ASSERT_NE(nullptr, bag_two);
  ASSERT_EQ(6u, bag_two->entry_count);

  // attr_one is inherited from StyleOne.
  EXPECT_EQ(app::R::attr::attr_one, bag_two->entries[0].key);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, bag_two->entries[0].value.dataType);
  EXPECT_EQ(1u, bag_two->entries[0].value.data);
  EXPECT_EQ(0, bag_two->entries[0].cookie);

  // attr_two should be overridden from StyleOne by StyleTwo.
  EXPECT_EQ(app::R::attr::attr_two, bag_two->entries[1].key);
  EXPECT_EQ(Res_value::TYPE_STRING, bag_two->entries[1].value.dataType);
  EXPECT_EQ(0, bag_two->entries[1].cookie);
  EXPECT_EQ(std::string("string"), GetStringFromPool(assetmanager.GetStringPoolForCookie(0),
                                                     bag_two->entries[1].value.data));

  // The rest are new attributes.

  EXPECT_EQ(app::R::attr::attr_three, bag_two->entries[2].key);
  EXPECT_EQ(Res_value::TYPE_ATTRIBUTE, bag_two->entries[2].value.dataType);
  EXPECT_EQ(app::R::attr::attr_indirect, bag_two->entries[2].value.data);
  EXPECT_EQ(0, bag_two->entries[2].cookie);

  EXPECT_EQ(app::R::attr::attr_five, bag_two->entries[3].key);
  EXPECT_EQ(Res_value::TYPE_REFERENCE, bag_two->entries[3].value.dataType);
  EXPECT_EQ(app::R::string::string_one, bag_two->entries[3].value.data);
  EXPECT_EQ(0, bag_two->entries[3].cookie);

  EXPECT_EQ(app::R::attr::attr_indirect, bag_two->entries[4].key);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, bag_two->entries[4].value.dataType);
  EXPECT_EQ(3u, bag_two->entries[4].value.data);
  EXPECT_EQ(0, bag_two->entries[4].cookie);

  EXPECT_EQ(app::R::attr::attr_empty, bag_two->entries[5].key);
  EXPECT_EQ(Res_value::TYPE_NULL, bag_two->entries[5].value.dataType);
  EXPECT_EQ(Res_value::DATA_NULL_EMPTY, bag_two->entries[5].value.data);
  EXPECT_EQ(0, bag_two->entries[5].cookie);
}

TEST_F(AssetManager2Test, MergeStylesCircularDependency) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({style_assets_.get()});

  // GetBag should stop traversing the parents of styles when a circular
  // dependency is detected
  const ResolvedBag* bag_one = assetmanager.GetBag(app::R::style::StyleFour);
  ASSERT_NE(nullptr, bag_one);
  ASSERT_EQ(3u, bag_one->entry_count);
}

TEST_F(AssetManager2Test, ResolveReferenceToResource) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_assets_.get()});

  Res_value value;
  ResTable_config selected_config;
  uint32_t flags;
  ApkAssetsCookie cookie =
      assetmanager.GetResource(basic::R::integer::ref1, false /*may_be_bag*/,
                               0u /*density_override*/, &value, &selected_config, &flags);
  ASSERT_NE(kInvalidCookie, cookie);

  EXPECT_EQ(Res_value::TYPE_REFERENCE, value.dataType);
  EXPECT_EQ(basic::R::integer::ref2, value.data);

  uint32_t last_ref = 0u;
  cookie = assetmanager.ResolveReference(cookie, &value, &selected_config, &flags, &last_ref);
  ASSERT_NE(kInvalidCookie, cookie);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, value.dataType);
  EXPECT_EQ(12000u, value.data);
  EXPECT_EQ(basic::R::integer::ref2, last_ref);
}

TEST_F(AssetManager2Test, ResolveReferenceToBag) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_assets_.get()});

  Res_value value;
  ResTable_config selected_config;
  uint32_t flags;
  ApkAssetsCookie cookie =
      assetmanager.GetResource(basic::R::integer::number2, true /*may_be_bag*/,
                               0u /*density_override*/, &value, &selected_config, &flags);
  ASSERT_NE(kInvalidCookie, cookie);

  EXPECT_EQ(Res_value::TYPE_REFERENCE, value.dataType);
  EXPECT_EQ(basic::R::array::integerArray1, value.data);

  uint32_t last_ref = 0u;
  cookie = assetmanager.ResolveReference(cookie, &value, &selected_config, &flags, &last_ref);
  ASSERT_NE(kInvalidCookie, cookie);
  EXPECT_EQ(Res_value::TYPE_REFERENCE, value.dataType);
  EXPECT_EQ(basic::R::array::integerArray1, value.data);
  EXPECT_EQ(basic::R::array::integerArray1, last_ref);
}

TEST_F(AssetManager2Test, KeepLastReferenceIdUnmodifiedIfNoReferenceIsResolved) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_assets_.get()});

  ResTable_config selected_config;
  memset(&selected_config, 0, sizeof(selected_config));

  uint32_t flags = 0u;

  // Create some kind of Res_value that is NOT a reference.
  Res_value value;
  value.dataType = Res_value::TYPE_STRING;
  value.data = 0;

  uint32_t last_ref = basic::R::string::test1;
  EXPECT_EQ(1, assetmanager.ResolveReference(1, &value, &selected_config, &flags, &last_ref));
  EXPECT_EQ(basic::R::string::test1, last_ref);
}

static bool IsConfigurationPresent(const std::set<ResTable_config>& configurations,
                                   const ResTable_config& configuration) {
  return configurations.count(configuration) > 0;
}

TEST_F(AssetManager2Test, GetResourceConfigurations) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({system_assets_.get(), basic_de_fr_assets_.get()});

  std::set<ResTable_config> configurations = assetmanager.GetResourceConfigurations();

  // We expect the locale sv from the system assets, and de and fr from basic_de_fr assets.
  // And one extra for the default configuration.
  EXPECT_EQ(4u, configurations.size());

  ResTable_config expected_config;
  memset(&expected_config, 0, sizeof(expected_config));
  expected_config.language[0] = 's';
  expected_config.language[1] = 'v';
  EXPECT_TRUE(IsConfigurationPresent(configurations, expected_config));

  expected_config.language[0] = 'd';
  expected_config.language[1] = 'e';
  EXPECT_TRUE(IsConfigurationPresent(configurations, expected_config));

  expected_config.language[0] = 'f';
  expected_config.language[1] = 'r';
  EXPECT_TRUE(IsConfigurationPresent(configurations, expected_config));

  // Take out the system assets.
  configurations = assetmanager.GetResourceConfigurations(true /* exclude_system */);

  // We expect de and fr from basic_de_fr assets.
  EXPECT_EQ(2u, configurations.size());

  expected_config.language[0] = 's';
  expected_config.language[1] = 'v';
  EXPECT_FALSE(IsConfigurationPresent(configurations, expected_config));

  expected_config.language[0] = 'd';
  expected_config.language[1] = 'e';
  EXPECT_TRUE(IsConfigurationPresent(configurations, expected_config));

  expected_config.language[0] = 'f';
  expected_config.language[1] = 'r';
  EXPECT_TRUE(IsConfigurationPresent(configurations, expected_config));
}

TEST_F(AssetManager2Test, GetResourceLocales) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({system_assets_.get(), basic_de_fr_assets_.get()});

  std::set<std::string> locales = assetmanager.GetResourceLocales();

  // We expect the locale sv from the system assets, and de and fr from basic_de_fr assets.
  EXPECT_EQ(3u, locales.size());
  EXPECT_GT(locales.count("sv"), 0u);
  EXPECT_GT(locales.count("de"), 0u);
  EXPECT_GT(locales.count("fr"), 0u);

  locales = assetmanager.GetResourceLocales(true /*exclude_system*/);
  // We expect the de and fr locales from basic_de_fr assets.
  EXPECT_EQ(2u, locales.size());
  EXPECT_GT(locales.count("de"), 0u);
  EXPECT_GT(locales.count("fr"), 0u);
}

TEST_F(AssetManager2Test, GetResourceId) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_assets_.get()});

  EXPECT_EQ(basic::R::layout::main,
            assetmanager.GetResourceId("com.android.basic:layout/main", "", ""));
  EXPECT_EQ(basic::R::layout::main,
            assetmanager.GetResourceId("layout/main", "", "com.android.basic"));
  EXPECT_EQ(basic::R::layout::main,
            assetmanager.GetResourceId("main", "layout", "com.android.basic"));
}

TEST_F(AssetManager2Test, OpensFileFromSingleApkAssets) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({system_assets_.get()});

  std::unique_ptr<Asset> asset = assetmanager.Open("file.txt", Asset::ACCESS_BUFFER);
  ASSERT_THAT(asset, NotNull());

  const char* data = reinterpret_cast<const char*>(asset->getBuffer(false /*wordAligned*/));
  ASSERT_THAT(data, NotNull());
  EXPECT_THAT(std::string(data, asset->getLength()), StrEq("file\n"));
}

TEST_F(AssetManager2Test, OpensFileFromMultipleApkAssets) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({system_assets_.get(), app_assets_.get()});

  std::unique_ptr<Asset> asset = assetmanager.Open("file.txt", Asset::ACCESS_BUFFER);
  ASSERT_THAT(asset, NotNull());

  const char* data = reinterpret_cast<const char*>(asset->getBuffer(false /*wordAligned*/));
  ASSERT_THAT(data, NotNull());
  EXPECT_THAT(std::string(data, asset->getLength()), StrEq("app override file\n"));
}

TEST_F(AssetManager2Test, OpenDir) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({system_assets_.get()});

  std::unique_ptr<AssetDir> asset_dir = assetmanager.OpenDir("");
  ASSERT_THAT(asset_dir, NotNull());
  ASSERT_THAT(asset_dir->getFileCount(), Eq(2u));

  EXPECT_THAT(asset_dir->getFileName(0), Eq(String8("file.txt")));
  EXPECT_THAT(asset_dir->getFileType(0), Eq(FileType::kFileTypeRegular));

  EXPECT_THAT(asset_dir->getFileName(1), Eq(String8("subdir")));
  EXPECT_THAT(asset_dir->getFileType(1), Eq(FileType::kFileTypeDirectory));

  asset_dir = assetmanager.OpenDir("subdir");
  ASSERT_THAT(asset_dir, NotNull());
  ASSERT_THAT(asset_dir->getFileCount(), Eq(1u));

  EXPECT_THAT(asset_dir->getFileName(0), Eq(String8("subdir_file.txt")));
  EXPECT_THAT(asset_dir->getFileType(0), Eq(FileType::kFileTypeRegular));
}

TEST_F(AssetManager2Test, OpenDirFromManyApks) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({system_assets_.get(), app_assets_.get()});

  std::unique_ptr<AssetDir> asset_dir = assetmanager.OpenDir("");
  ASSERT_THAT(asset_dir, NotNull());
  ASSERT_THAT(asset_dir->getFileCount(), Eq(3u));

  EXPECT_THAT(asset_dir->getFileName(0), Eq(String8("app_file.txt")));
  EXPECT_THAT(asset_dir->getFileType(0), Eq(FileType::kFileTypeRegular));

  EXPECT_THAT(asset_dir->getFileName(1), Eq(String8("file.txt")));
  EXPECT_THAT(asset_dir->getFileType(1), Eq(FileType::kFileTypeRegular));

  EXPECT_THAT(asset_dir->getFileName(2), Eq(String8("subdir")));
  EXPECT_THAT(asset_dir->getFileType(2), Eq(FileType::kFileTypeDirectory));
}

}  // namespace android
