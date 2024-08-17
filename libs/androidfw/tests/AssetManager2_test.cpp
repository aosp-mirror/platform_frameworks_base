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

#include "TestHelpers.h"
#include "android-base/file.h"
#include "android-base/logging.h"
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
    // Move to the test data directory so the idmap can locate the overlay APK.
    std::string original_path = base::GetExecutableDirectory();
    chdir(GetTestDataPath().c_str());

    basic_assets_ = ApkAssets::Load("basic/basic.apk");
    ASSERT_NE(nullptr, basic_assets_);

    basic_de_fr_assets_ = ApkAssets::Load("basic/basic_de_fr.apk");
    ASSERT_NE(nullptr, basic_de_fr_assets_);

    basic_xhdpi_assets_ = ApkAssets::Load("basic/basic_xhdpi-v4.apk");
    ASSERT_NE(nullptr, basic_de_fr_assets_);

    basic_xxhdpi_assets_ = ApkAssets::Load("basic/basic_xxhdpi-v4.apk");
    ASSERT_NE(nullptr, basic_de_fr_assets_);

    style_assets_ = ApkAssets::Load("styles/styles.apk");
    ASSERT_NE(nullptr, style_assets_);

    lib_one_assets_ = ApkAssets::Load("lib_one/lib_one.apk");
    ASSERT_NE(nullptr, lib_one_assets_);

    lib_two_assets_ = ApkAssets::Load("lib_two/lib_two.apk");
    ASSERT_NE(nullptr, lib_two_assets_);

    libclient_assets_ = ApkAssets::Load("libclient/libclient.apk");
    ASSERT_NE(nullptr, libclient_assets_);

    appaslib_assets_ = ApkAssets::Load("appaslib/appaslib.apk", PROPERTY_DYNAMIC);
    ASSERT_NE(nullptr, appaslib_assets_);

    system_assets_ = ApkAssets::Load("system/system.apk", PROPERTY_SYSTEM);
    ASSERT_NE(nullptr, system_assets_);

    app_assets_ = ApkAssets::Load("app/app.apk");
    ASSERT_THAT(app_assets_, NotNull());

    overlay_assets_ = ApkAssets::LoadOverlay("overlay/overlay.idmap");
    ASSERT_NE(nullptr, overlay_assets_);

    overlayable_assets_ = ApkAssets::Load("overlayable/overlayable.apk");
    ASSERT_THAT(overlayable_assets_, NotNull());
    chdir(original_path.c_str());
  }

 protected:
  AssetManager2::ApkAssetsPtr basic_assets_;
  AssetManager2::ApkAssetsPtr basic_de_fr_assets_;
  AssetManager2::ApkAssetsPtr basic_xhdpi_assets_;
  AssetManager2::ApkAssetsPtr basic_xxhdpi_assets_;
  AssetManager2::ApkAssetsPtr style_assets_;
  AssetManager2::ApkAssetsPtr lib_one_assets_;
  AssetManager2::ApkAssetsPtr lib_two_assets_;
  AssetManager2::ApkAssetsPtr libclient_assets_;
  AssetManager2::ApkAssetsPtr appaslib_assets_;
  AssetManager2::ApkAssetsPtr system_assets_;
  AssetManager2::ApkAssetsPtr app_assets_;
  AssetManager2::ApkAssetsPtr overlay_assets_;
  AssetManager2::ApkAssetsPtr overlayable_assets_;
};

TEST_F(AssetManager2Test, FindsResourceFromSingleApkAssets) {
  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));
  desired_config.language[0] = 'd';
  desired_config.language[1] = 'e';

  AssetManager2 assetmanager;
  assetmanager.SetConfigurations({{desired_config}});
  assetmanager.SetApkAssets({basic_assets_});

  auto value = assetmanager.GetResource(basic::R::string::test1);
  ASSERT_TRUE(value.has_value());

  // Came from our ApkAssets.
  EXPECT_EQ(0, value->cookie);

  // It is the default config.
  EXPECT_EQ(0, value->config.language[0]);
  EXPECT_EQ(0, value->config.language[1]);

  // It is a string.
  EXPECT_EQ(Res_value::TYPE_STRING, value->type);
}

TEST_F(AssetManager2Test, FindsResourceFromMultipleApkAssets) {
  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));
  desired_config.language[0] = 'd';
  desired_config.language[1] = 'e';

  AssetManager2 assetmanager;
  assetmanager.SetConfigurations({{desired_config}});
  assetmanager.SetApkAssets({basic_assets_, basic_de_fr_assets_});

  auto value = assetmanager.GetResource(basic::R::string::test1);
  ASSERT_TRUE(value.has_value());

  // Came from our de_fr ApkAssets.
  EXPECT_EQ(1, value->cookie);

  // The configuration is German.
  EXPECT_EQ('d', value->config.language[0]);
  EXPECT_EQ('e', value->config.language[1]);

  // It is a string.
  EXPECT_EQ(Res_value::TYPE_STRING, value->type);
}

TEST_F(AssetManager2Test, FindsResourceFromSharedLibrary) {
  AssetManager2 assetmanager;

  // libclient is built with lib_one and then lib_two in order.
  // Reverse the order to test that proper package ID re-assignment is happening.
  assetmanager.SetApkAssets({lib_two_assets_, lib_one_assets_, libclient_assets_});

  auto value = assetmanager.GetResource(libclient::R::string::foo_one);
  ASSERT_TRUE(value.has_value());

  // Reference comes from libclient.
  EXPECT_EQ(2, value->cookie);
  EXPECT_EQ(Res_value::TYPE_REFERENCE, value->type);

  // Lookup the reference.
  value = assetmanager.GetResource(value->data);
  ASSERT_TRUE(value.has_value());
  EXPECT_EQ(1, value->cookie);
  EXPECT_EQ(Res_value::TYPE_STRING, value->type);
  EXPECT_EQ(std::string("Foo from lib_one"),
            GetStringFromPool(assetmanager.GetStringPoolForCookie(value->cookie), value->data));

  value = assetmanager.GetResource(libclient::R::string::foo_two);
  ASSERT_TRUE(value.has_value());

  // Reference comes from libclient.
  EXPECT_EQ(2, value->cookie);
  EXPECT_EQ(Res_value::TYPE_REFERENCE, value->type);

  // Lookup the reference.
  value = assetmanager.GetResource(value->data);
  ASSERT_TRUE(value.has_value());
  EXPECT_EQ(0, value->cookie);
  EXPECT_EQ(Res_value::TYPE_STRING, value->type);
  EXPECT_EQ(std::string("Foo from lib_two"),
            GetStringFromPool(assetmanager.GetStringPoolForCookie(value->cookie), value->data));
}

TEST_F(AssetManager2Test, FindsResourceFromAppLoadedAsSharedLibrary) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({appaslib_assets_});

  // The appaslib package will have been assigned the package ID 0x02.
  auto value = assetmanager.GetResource(fix_package_id(appaslib::R::integer::number1, 0x02));
  ASSERT_TRUE(value.has_value());
  EXPECT_EQ(Res_value::TYPE_REFERENCE, value->type);
  EXPECT_EQ(fix_package_id(appaslib::R::array::integerArray1, 0x02), value->data);
}

TEST_F(AssetManager2Test, AssignsOverlayPackageIdLast) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({overlayable_assets_, overlay_assets_, lib_one_assets_});

  ASSERT_EQ(3, assetmanager.GetApkAssetsCount());
  auto op = assetmanager.StartOperation();
  ASSERT_EQ(overlayable_assets_, assetmanager.GetApkAssets(0));
  ASSERT_EQ(overlay_assets_, assetmanager.GetApkAssets(1));
  ASSERT_EQ(lib_one_assets_, assetmanager.GetApkAssets(2));

  auto get_first_package_id = [&assetmanager](auto apkAssets) -> uint8_t {
    return assetmanager.GetAssignedPackageId(apkAssets->GetLoadedArsc()->GetPackages()[0].get());
  };

  ASSERT_EQ(0x7f, get_first_package_id(overlayable_assets_));
  ASSERT_EQ(0x03, get_first_package_id(overlay_assets_));
  ASSERT_EQ(0x02, get_first_package_id(lib_one_assets_));
}

TEST_F(AssetManager2Test, GetSharedLibraryResourceName) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({lib_one_assets_});

  auto name = assetmanager.GetResourceName(lib_one::R::string::foo);
  ASSERT_TRUE(name.has_value());
  ASSERT_EQ("com.android.lib_one:string/foo", ToFormattedResourceString(*name));
}

TEST_F(AssetManager2Test, GetResourceNameNonMatchingConfig) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_de_fr_assets_});

  auto value = assetmanager.GetResourceName(basic::R::string::test1);
  ASSERT_TRUE(value.has_value());
  EXPECT_EQ("com.android.basic:string/test1", ToFormattedResourceString(*value));
}

TEST_F(AssetManager2Test, GetResourceTypeSpecFlags) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_de_fr_assets_});

  auto value = assetmanager.GetResourceTypeSpecFlags(basic::R::string::test1);
  ASSERT_TRUE(value.has_value());
  EXPECT_EQ(ResTable_typeSpec::SPEC_PUBLIC | ResTable_config::CONFIG_LOCALE, *value);
}

TEST_F(AssetManager2Test, FindsBagResourceFromSingleApkAssets) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_assets_});

  auto bag = assetmanager.GetBag(basic::R::array::integerArray1);
  ASSERT_TRUE(bag.has_value());

  ASSERT_EQ(3u, (*bag)->entry_count);

  EXPECT_EQ(static_cast<uint8_t>(Res_value::TYPE_INT_DEC), (*bag)->entries[0].value.dataType);
  EXPECT_EQ(1u, (*bag)->entries[0].value.data);
  EXPECT_EQ(0, (*bag)->entries[0].cookie);

  EXPECT_EQ(static_cast<uint8_t>(Res_value::TYPE_INT_DEC), (*bag)->entries[1].value.dataType);
  EXPECT_EQ(2u, (*bag)->entries[1].value.data);
  EXPECT_EQ(0, (*bag)->entries[1].cookie);

  EXPECT_EQ(static_cast<uint8_t>(Res_value::TYPE_INT_DEC), (*bag)->entries[2].value.dataType);
  EXPECT_EQ(3u, (*bag)->entries[2].value.data);
  EXPECT_EQ(0, (*bag)->entries[2].cookie);
}

TEST_F(AssetManager2Test, FindsBagResourceFromMultipleApkAssets) {}

TEST_F(AssetManager2Test, FindsBagResourceFromSharedLibrary) {
  AssetManager2 assetmanager;

  // libclient is built with lib_one and then lib_two in order.
  // Reverse the order to test that proper package ID re-assignment is happening.
  assetmanager.SetApkAssets({lib_two_assets_, lib_one_assets_, libclient_assets_});

  auto bag = assetmanager.GetBag(fix_package_id(lib_one::R::style::Theme, 0x03));
  ASSERT_TRUE(bag.has_value());

  ASSERT_GE((*bag)->entry_count, 2u);

  // First two attributes come from lib_one.
  EXPECT_EQ(1, (*bag)->entries[0].cookie);
  EXPECT_EQ(0x03, get_package_id((*bag)->entries[0].key));
  EXPECT_EQ(1, (*bag)->entries[1].cookie);
  EXPECT_EQ(0x03, get_package_id((*bag)->entries[1].key));
}

TEST_F(AssetManager2Test, FindsBagResourceFromMultipleSharedLibraries) {
  AssetManager2 assetmanager;

  // libclient is built with lib_one and then lib_two in order.
  // Reverse the order to test that proper package ID re-assignment is happening.
  assetmanager.SetApkAssets({lib_two_assets_, lib_one_assets_, libclient_assets_});

  auto bag = assetmanager.GetBag(libclient::R::style::ThemeMultiLib);
  ASSERT_TRUE(bag.has_value());
  ASSERT_EQ((*bag)->entry_count, 2u);

  // First attribute comes from lib_two.
  EXPECT_EQ(2, (*bag)->entries[0].cookie);
  EXPECT_EQ(0x02, get_package_id((*bag)->entries[0].key));

  // The next two attributes come from lib_one.
  EXPECT_EQ(2, (*bag)->entries[1].cookie);
  EXPECT_EQ(0x03, get_package_id((*bag)->entries[1].key));
}

TEST_F(AssetManager2Test, FindsStyleResourceWithParentFromSharedLibrary) {
  AssetManager2 assetmanager;

  // libclient is built with lib_one and then lib_two in order.
  // Reverse the order to test that proper package ID re-assignment is happening.
  assetmanager.SetApkAssets({lib_two_assets_, lib_one_assets_, libclient_assets_});

  auto bag = assetmanager.GetBag(libclient::R::style::Theme);
  ASSERT_TRUE(bag.has_value());
  ASSERT_GE((*bag)->entry_count, 2u);

  // First two attributes come from lib_one.
  EXPECT_EQ(1, (*bag)->entries[0].cookie);
  EXPECT_EQ(0x03, get_package_id((*bag)->entries[0].key));
  EXPECT_EQ(1, (*bag)->entries[1].cookie);
  EXPECT_EQ(0x03, get_package_id((*bag)->entries[1].key));
}

TEST_F(AssetManager2Test, MergesStylesWithParentFromSingleApkAssets) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({style_assets_});

  auto bag_one = assetmanager.GetBag(app::R::style::StyleOne);
  ASSERT_TRUE(bag_one.has_value());
  ASSERT_EQ(2u, (*bag_one)->entry_count);

  EXPECT_EQ(app::R::attr::attr_one, (*bag_one)->entries[0].key);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, (*bag_one)->entries[0].value.dataType);
  EXPECT_EQ(1u, (*bag_one)->entries[0].value.data);
  EXPECT_EQ(0, (*bag_one)->entries[0].cookie);

  EXPECT_EQ(app::R::attr::attr_two, (*bag_one)->entries[1].key);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, (*bag_one)->entries[1].value.dataType);
  EXPECT_EQ(2u, (*bag_one)->entries[1].value.data);
  EXPECT_EQ(0, (*bag_one)->entries[1].cookie);

  auto bag_two = assetmanager.GetBag(app::R::style::StyleTwo);
  ASSERT_TRUE(bag_two.has_value());
  ASSERT_EQ(6u, (*bag_two)->entry_count);

  // attr_one is inherited from StyleOne.
  EXPECT_EQ(app::R::attr::attr_one, (*bag_two)->entries[0].key);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, (*bag_two)->entries[0].value.dataType);
  EXPECT_EQ(1u, (*bag_two)->entries[0].value.data);
  EXPECT_EQ(0, (*bag_two)->entries[0].cookie);
  EXPECT_EQ(app::R::style::StyleOne, (*bag_two)->entries[0].style);

  // attr_two should be overridden from StyleOne by StyleTwo.
  EXPECT_EQ(app::R::attr::attr_two, (*bag_two)->entries[1].key);
  EXPECT_EQ(Res_value::TYPE_STRING, (*bag_two)->entries[1].value.dataType);
  EXPECT_EQ(0, (*bag_two)->entries[1].cookie);
  EXPECT_EQ(app::R::style::StyleTwo, (*bag_two)->entries[1].style);
  EXPECT_EQ(std::string("string"), GetStringFromPool(assetmanager.GetStringPoolForCookie(0),
                                                     (*bag_two)->entries[1].value.data));

  // The rest are new attributes.

  EXPECT_EQ(app::R::attr::attr_three, (*bag_two)->entries[2].key);
  EXPECT_EQ(Res_value::TYPE_ATTRIBUTE, (*bag_two)->entries[2].value.dataType);
  EXPECT_EQ(app::R::attr::attr_indirect, (*bag_two)->entries[2].value.data);
  EXPECT_EQ(0, (*bag_two)->entries[2].cookie);
  EXPECT_EQ(app::R::style::StyleTwo, (*bag_two)->entries[2].style);

  EXPECT_EQ(app::R::attr::attr_five, (*bag_two)->entries[3].key);
  EXPECT_EQ(Res_value::TYPE_REFERENCE, (*bag_two)->entries[3].value.dataType);
  EXPECT_EQ(app::R::string::string_one, (*bag_two)->entries[3].value.data);
  EXPECT_EQ(0, (*bag_two)->entries[3].cookie);
  EXPECT_EQ(app::R::style::StyleTwo, (*bag_two)->entries[3].style);

  EXPECT_EQ(app::R::attr::attr_indirect, (*bag_two)->entries[4].key);
  EXPECT_EQ(Res_value::TYPE_INT_DEC, (*bag_two)->entries[4].value.dataType);
  EXPECT_EQ(3u, (*bag_two)->entries[4].value.data);
  EXPECT_EQ(0, (*bag_two)->entries[4].cookie);
  EXPECT_EQ(app::R::style::StyleTwo, (*bag_two)->entries[4].style);

  EXPECT_EQ(app::R::attr::attr_empty, (*bag_two)->entries[5].key);
  EXPECT_EQ(Res_value::TYPE_NULL, (*bag_two)->entries[5].value.dataType);
  EXPECT_EQ(Res_value::DATA_NULL_EMPTY, (*bag_two)->entries[5].value.data);
  EXPECT_EQ(0, (*bag_two)->entries[5].cookie);
  EXPECT_EQ(app::R::style::StyleTwo, (*bag_two)->entries[5].style);
}

TEST_F(AssetManager2Test, MergeStylesCircularDependency) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({style_assets_});

  // GetBag should stop traversing the parents of styles when a circular
  // dependency is detected
  auto bag = assetmanager.GetBag(app::R::style::StyleFour);
  ASSERT_TRUE(bag.has_value());
  ASSERT_EQ(3u, (*bag)->entry_count);
}

TEST_F(AssetManager2Test, ResolveReferenceToResource) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_assets_});

  auto value = assetmanager.GetResource(basic::R::integer::ref1);
  ASSERT_TRUE(value.has_value());
  EXPECT_EQ(Res_value::TYPE_REFERENCE, value->type);
  EXPECT_EQ(basic::R::integer::ref2, value->data);

  auto result = assetmanager.ResolveReference(*value);
  ASSERT_TRUE(result.has_value());
  EXPECT_EQ(Res_value::TYPE_INT_DEC, value->type);
  EXPECT_EQ(12000u, value->data);
  EXPECT_EQ(basic::R::integer::ref2, value->resid);
}

TEST_F(AssetManager2Test, ResolveReferenceToBag) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_assets_});

  auto value = assetmanager.GetResource(basic::R::integer::number2, true /*may_be_bag*/);
  ASSERT_TRUE(value.has_value());
  EXPECT_EQ(Res_value::TYPE_REFERENCE, value->type);
  EXPECT_EQ(basic::R::array::integerArray1, value->data);

  auto result = assetmanager.ResolveReference(*value);
  ASSERT_TRUE(result.has_value());
  EXPECT_EQ(Res_value::TYPE_REFERENCE, value->type);
  EXPECT_EQ(basic::R::array::integerArray1, value->data);
  EXPECT_EQ(basic::R::array::integerArray1, value->resid);
}

TEST_F(AssetManager2Test, ResolveDeepIdReference) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_assets_});

  // Set up the resource ids
  auto high_ref = assetmanager.GetResourceId("@id/high_ref", "values", "com.android.basic");
  ASSERT_TRUE(high_ref.has_value());

  auto middle_ref = assetmanager.GetResourceId("@id/middle_ref", "values", "com.android.basic");
  ASSERT_TRUE(middle_ref.has_value());

  auto low_ref = assetmanager.GetResourceId("@id/low_ref", "values", "com.android.basic");
  ASSERT_TRUE(low_ref.has_value());

  // Retrieve the most shallow resource
  auto value = assetmanager.GetResource(*high_ref);
  ASSERT_TRUE(value.has_value());
  EXPECT_EQ(Res_value::TYPE_REFERENCE, value->type);
  EXPECT_EQ(*middle_ref, value->data);;

  // Check that resolving the reference resolves to the deepest id
  auto result = assetmanager.ResolveReference(*value);
  ASSERT_TRUE(result.has_value());
  EXPECT_EQ(*low_ref, value->resid);
}

TEST_F(AssetManager2Test, DensityOverride) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_assets_, basic_xhdpi_assets_, basic_xxhdpi_assets_});
  assetmanager.SetConfigurations({{{
    .density = ResTable_config::DENSITY_XHIGH,
    .sdkVersion = 21,
  }}});

  auto value = assetmanager.GetResource(basic::R::string::density, false /*may_be_bag*/);
  ASSERT_TRUE(value.has_value());
  EXPECT_EQ(Res_value::TYPE_STRING, value->type);
  EXPECT_EQ("xhdpi", GetStringFromPool(assetmanager.GetStringPoolForCookie(value->cookie),
                                       value->data));

  value = assetmanager.GetResource(basic::R::string::density, false /*may_be_bag*/,
                                   ResTable_config::DENSITY_XXHIGH);
  ASSERT_TRUE(value.has_value());
  EXPECT_EQ(Res_value::TYPE_STRING, value->type);
  EXPECT_EQ("xxhdpi", GetStringFromPool(assetmanager.GetStringPoolForCookie(value->cookie),
                                        value->data));
}

TEST_F(AssetManager2Test, KeepLastReferenceIdUnmodifiedIfNoReferenceIsResolved) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_assets_});

  // Create some kind of value that is NOT a reference.
  AssetManager2::SelectedValue value{};
  value.cookie = 1;
  value.type = Res_value::TYPE_STRING;
  value.resid = basic::R::string::test1;

  auto result = assetmanager.ResolveReference(value);
  ASSERT_TRUE(result.has_value());
  EXPECT_EQ(1, value.cookie);
  EXPECT_EQ(basic::R::string::test1, value.resid);
}

TEST_F(AssetManager2Test, ResolveReferenceMissingResourceDoNotCacheFlags) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_assets_});
  {
    AssetManager2::SelectedValue value{};
    value.data = basic::R::string::test1;
    value.type = Res_value::TYPE_REFERENCE;
    value.flags = ResTable_config::CONFIG_KEYBOARD;

    auto result = assetmanager.ResolveReference(value);
    ASSERT_TRUE(result.has_value());
    EXPECT_EQ(Res_value::TYPE_STRING, value.type);
    EXPECT_EQ(0, value.cookie);
    EXPECT_EQ(basic::R::string::test1, value.resid);
    EXPECT_EQ(ResTable_typeSpec::SPEC_PUBLIC | ResTable_config::CONFIG_KEYBOARD, value.flags);
  }
  {
    AssetManager2::SelectedValue value{};
    value.data = basic::R::string::test1;
    value.type = Res_value::TYPE_REFERENCE;
    value.flags = ResTable_config::CONFIG_COLOR_MODE;

    auto result = assetmanager.ResolveReference(value);
    ASSERT_TRUE(result.has_value());
    EXPECT_EQ(Res_value::TYPE_STRING, value.type);
    EXPECT_EQ(0, value.cookie);
    EXPECT_EQ(basic::R::string::test1, value.resid);
    EXPECT_EQ(ResTable_typeSpec::SPEC_PUBLIC | ResTable_config::CONFIG_COLOR_MODE, value.flags);
  }
}

TEST_F(AssetManager2Test, ResolveReferenceMissingResource) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({basic_assets_});

  const uint32_t kMissingResId = 0x8001ffff;
  AssetManager2::SelectedValue value{};
  value.type = Res_value::TYPE_REFERENCE;
  value.data = kMissingResId;

  auto result = assetmanager.ResolveReference(value);
  ASSERT_FALSE(result.has_value());
  EXPECT_EQ(Res_value::TYPE_REFERENCE, value.type);
  EXPECT_EQ(kMissingResId, value.data);
  EXPECT_EQ(kMissingResId, value.resid);
  EXPECT_EQ(-1, value.cookie);
  EXPECT_EQ(0, value.flags);
}

TEST_F(AssetManager2Test, ResolveReferenceMissingResourceLib) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({libclient_assets_});

  AssetManager2::SelectedValue value{};
  value.type = Res_value::TYPE_REFERENCE;
  value.data = libclient::R::string::foo_one;

  auto result = assetmanager.ResolveReference(value);
  ASSERT_TRUE(result.has_value());
  EXPECT_EQ(Res_value::TYPE_DYNAMIC_REFERENCE, value.type);
  EXPECT_EQ(lib_one::R::string::foo, value.data);
  EXPECT_EQ(libclient::R::string::foo_one, value.resid);
  EXPECT_EQ(0, value.cookie);
  EXPECT_EQ(static_cast<uint32_t>(ResTable_typeSpec::SPEC_PUBLIC), value.flags);
}

static bool IsConfigurationPresent(const std::set<ResTable_config>& configurations,
                                   const ResTable_config& configuration) {
  return configurations.count(configuration) > 0;
}

TEST_F(AssetManager2Test, GetResourceConfigurations) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({system_assets_, basic_de_fr_assets_});

  auto configurations = assetmanager.GetResourceConfigurations();
  ASSERT_TRUE(configurations.has_value());

  // We expect the locale sv from the system assets, and de and fr from basic_de_fr assets.
  // And one extra for the default configuration.
  EXPECT_EQ(4u, configurations->size());

  ResTable_config expected_config;
  memset(&expected_config, 0, sizeof(expected_config));
  expected_config.language[0] = 's';
  expected_config.language[1] = 'v';
  EXPECT_TRUE(IsConfigurationPresent(*configurations, expected_config));

  expected_config.language[0] = 'd';
  expected_config.language[1] = 'e';
  EXPECT_TRUE(IsConfigurationPresent(*configurations, expected_config));

  expected_config.language[0] = 'f';
  expected_config.language[1] = 'r';
  EXPECT_TRUE(IsConfigurationPresent(*configurations, expected_config));

  // Take out the system assets.
  configurations = assetmanager.GetResourceConfigurations(true /* exclude_system */);
  ASSERT_TRUE(configurations.has_value());

  // We expect de and fr from basic_de_fr assets.
  EXPECT_EQ(2u, configurations->size());

  expected_config.language[0] = 's';
  expected_config.language[1] = 'v';
  EXPECT_FALSE(IsConfigurationPresent(*configurations, expected_config));

  expected_config.language[0] = 'd';
  expected_config.language[1] = 'e';
  EXPECT_TRUE(IsConfigurationPresent(*configurations, expected_config));

  expected_config.language[0] = 'f';
  expected_config.language[1] = 'r';
  EXPECT_TRUE(IsConfigurationPresent(*configurations, expected_config));
}

TEST_F(AssetManager2Test, GetResourceLocales) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({system_assets_, basic_de_fr_assets_});

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
  assetmanager.SetApkAssets({basic_assets_});

  auto resid = assetmanager.GetResourceId("com.android.basic:layout/main", "", "");
  ASSERT_TRUE(resid.has_value());
  EXPECT_EQ(basic::R::layout::main, *resid);

  resid = assetmanager.GetResourceId("layout/main", "", "com.android.basic");
  ASSERT_TRUE(resid.has_value());
  EXPECT_EQ(basic::R::layout::main, *resid);

  resid = assetmanager.GetResourceId("main", "layout", "com.android.basic");
  ASSERT_TRUE(resid.has_value());
  EXPECT_EQ(basic::R::layout::main, *resid);
}

TEST_F(AssetManager2Test, OpensFileFromSingleApkAssets) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({system_assets_});

  std::unique_ptr<Asset> asset = assetmanager.Open("file.txt", Asset::ACCESS_BUFFER);
  ASSERT_THAT(asset, NotNull());

  const char* data = reinterpret_cast<const char*>(asset->getBuffer(false /*wordAligned*/));
  ASSERT_THAT(data, NotNull());
  EXPECT_THAT(std::string(data, asset->getLength()), StrEq("file\n"));
}

TEST_F(AssetManager2Test, OpensFileFromMultipleApkAssets) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({system_assets_, app_assets_});

  std::unique_ptr<Asset> asset = assetmanager.Open("file.txt", Asset::ACCESS_BUFFER);
  ASSERT_THAT(asset, NotNull());

  const char* data = reinterpret_cast<const char*>(asset->getBuffer(false /*wordAligned*/));
  ASSERT_THAT(data, NotNull());
  EXPECT_THAT(std::string(data, asset->getLength()), StrEq("app override file\n"));
}

TEST_F(AssetManager2Test, OpenDir) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({system_assets_});

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
  assetmanager.SetApkAssets({system_assets_, app_assets_});

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

TEST_F(AssetManager2Test, GetLastPathWithoutEnablingReturnsEmpty) {
  ResTable_config desired_config;

  AssetManager2 assetmanager;
  assetmanager.SetConfigurations({{desired_config}});
  assetmanager.SetApkAssets({basic_assets_});
  assetmanager.SetResourceResolutionLoggingEnabled(false);

  auto value = assetmanager.GetResource(basic::R::string::test1);
  ASSERT_TRUE(value.has_value());

  auto result = assetmanager.GetLastResourceResolution();
  EXPECT_EQ("", result);
}

TEST_F(AssetManager2Test, GetLastPathWithoutResolutionReturnsEmpty) {
  ResTable_config desired_config;

  AssetManager2 assetmanager;
  assetmanager.SetConfigurations({{desired_config}});
  assetmanager.SetApkAssets({basic_assets_});

  auto result = assetmanager.GetLastResourceResolution();
  EXPECT_EQ("", result);
}

TEST_F(AssetManager2Test, GetLastPathWithSingleApkAssets) {
  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));
  desired_config.language[0] = 'd';
  desired_config.language[1] = 'e';

  AssetManager2 assetmanager;
  assetmanager.SetResourceResolutionLoggingEnabled(true);
  assetmanager.SetConfigurations({{desired_config}});
  assetmanager.SetApkAssets({basic_assets_});

  auto value = assetmanager.GetResource(basic::R::string::test1);
  ASSERT_TRUE(value.has_value());

  auto result = assetmanager.GetLastResourceResolution();
  EXPECT_EQ(
      "Resolution for 0x7f030000 com.android.basic:string/test1\n"
      "\tFor config - de\n"
      "\tFound initial: basic/basic.apk #0\n"
      "Best matching is from default configuration of com.android.basic",
      result);
}

TEST_F(AssetManager2Test, GetLastPathWithMultipleApkAssets) {
  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));
  desired_config.language[0] = 'd';
  desired_config.language[1] = 'e';

  AssetManager2 assetmanager;
  assetmanager.SetResourceResolutionLoggingEnabled(true);
  assetmanager.SetConfigurations({{desired_config}});
  assetmanager.SetApkAssets({basic_assets_, basic_de_fr_assets_});

  auto value = assetmanager.GetResource(basic::R::string::test1);
  ASSERT_TRUE(value.has_value());

  auto result = assetmanager.GetLastResourceResolution();
  EXPECT_EQ(
      "Resolution for 0x7f030000 com.android.basic:string/test1\n"
      "\tFor config - de\n"
      "\tFound initial: basic/basic.apk #0\n"
      "\tFound better: basic/basic_de_fr.apk #1 - de\n"
      "Best matching is from de configuration of com.android.basic",
      result);
}

TEST_F(AssetManager2Test, GetLastPathAfterDisablingReturnsEmpty) {
  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));

  AssetManager2 assetmanager;
  assetmanager.SetResourceResolutionLoggingEnabled(true);
  assetmanager.SetConfigurations({{desired_config}});
  assetmanager.SetApkAssets({basic_assets_});

  auto value = assetmanager.GetResource(basic::R::string::test1);
  ASSERT_TRUE(value.has_value());

  auto resultEnabled = assetmanager.GetLastResourceResolution();
  ASSERT_NE("", resultEnabled);

  assetmanager.SetResourceResolutionLoggingEnabled(false);

  auto resultDisabled = assetmanager.GetLastResourceResolution();
  EXPECT_EQ("", resultDisabled);
}

TEST_F(AssetManager2Test, GetOverlayablesToString) {
  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));

  AssetManager2 assetmanager;
  assetmanager.SetResourceResolutionLoggingEnabled(true);
  assetmanager.SetConfigurations({{desired_config}});
  assetmanager.SetApkAssets({overlayable_assets_});

  const auto map = assetmanager.GetOverlayableMapForPackage(0x7f);
  ASSERT_NE(nullptr, map);
  ASSERT_EQ(3, map->size());
  ASSERT_EQ(map->at("OverlayableResources1"), "overlay://theme");
  ASSERT_EQ(map->at("OverlayableResources2"), "overlay://com.android.overlayable");
  ASSERT_EQ(map->at("OverlayableResources3"), "");

  std::string api;
  ASSERT_TRUE(assetmanager.GetOverlayablesToString("com.android.overlayable", &api));
  ASSERT_EQ(api.find("not_overlayable"), std::string::npos);
  ASSERT_NE(api.find("resource='com.android.overlayable:string/overlayable2' overlayable='OverlayableResources1' actor='overlay://theme' policy='0x0000000a'\n"),
            std::string::npos);
}

TEST_F(AssetManager2Test, GetApkAssets) {
  AssetManager2 assetmanager;
  assetmanager.SetApkAssets({overlayable_assets_, overlay_assets_, lib_one_assets_});

  ASSERT_EQ(3, assetmanager.GetApkAssetsCount());
  EXPECT_EQ(1, overlayable_assets_->getStrongCount());
  EXPECT_EQ(1, overlay_assets_->getStrongCount());
  EXPECT_EQ(1, lib_one_assets_->getStrongCount());

  {
    auto op = assetmanager.StartOperation();
    ASSERT_EQ(overlayable_assets_, assetmanager.GetApkAssets(0));
    ASSERT_EQ(overlay_assets_, assetmanager.GetApkAssets(1));
    EXPECT_EQ(2, overlayable_assets_->getStrongCount());
    EXPECT_EQ(2, overlay_assets_->getStrongCount());
    EXPECT_EQ(1, lib_one_assets_->getStrongCount());
  }
  EXPECT_EQ(1, overlayable_assets_->getStrongCount());
  EXPECT_EQ(1, overlay_assets_->getStrongCount());
  EXPECT_EQ(1, lib_one_assets_->getStrongCount());
}

}  // namespace android
