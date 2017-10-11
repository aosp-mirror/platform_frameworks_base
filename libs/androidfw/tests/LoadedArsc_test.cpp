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

#include "androidfw/LoadedArsc.h"

#include "TestHelpers.h"
#include "data/basic/R.h"
#include "data/libclient/R.h"
#include "data/styles/R.h"

namespace app = com::android::app;
namespace basic = com::android::basic;
namespace libclient = com::android::libclient;

namespace android {

TEST(LoadedArscTest, LoadSinglePackageArsc) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/styles/styles.apk", "resources.arsc",
                                      &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc =
      LoadedArsc::Load(contents.data(), contents.size());
  ASSERT_NE(nullptr, loaded_arsc);

  const std::vector<std::unique_ptr<const LoadedPackage>>& packages = loaded_arsc->GetPackages();
  ASSERT_EQ(1u, packages.size());
  EXPECT_EQ(std::string("com.android.app"), packages[0]->GetPackageName());
  EXPECT_EQ(0x7f, packages[0]->GetPackageId());

  ResTable_config config;
  memset(&config, 0, sizeof(config));
  config.sdkVersion = 24;

  LoadedArscEntry entry;
  ResTable_config selected_config;
  uint32_t flags;

  ASSERT_TRUE(
      loaded_arsc->FindEntry(app::R::string::string_one, config, &entry, &selected_config, &flags));
  ASSERT_NE(nullptr, entry.entry);
}

TEST(LoadedArscTest, FindDefaultEntry) {
  std::string contents;
  ASSERT_TRUE(
      ReadFileFromZipToString(GetTestDataPath() + "/basic/basic.apk", "resources.arsc", &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc =
      LoadedArsc::Load(contents.data(), contents.size());
  ASSERT_NE(nullptr, loaded_arsc);

  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));
  desired_config.language[0] = 'd';
  desired_config.language[1] = 'e';

  LoadedArscEntry entry;
  ResTable_config selected_config;
  uint32_t flags;

  ASSERT_TRUE(loaded_arsc->FindEntry(basic::R::string::test1, desired_config, &entry,
                                     &selected_config, &flags));
  ASSERT_NE(nullptr, entry.entry);
}

TEST(LoadedArscTest, LoadSharedLibrary) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/lib_one/lib_one.apk", "resources.arsc",
                                      &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc =
      LoadedArsc::Load(contents.data(), contents.size());
  ASSERT_NE(nullptr, loaded_arsc);

  const auto& packages = loaded_arsc->GetPackages();
  ASSERT_EQ(1u, packages.size());

  EXPECT_TRUE(packages[0]->IsDynamic());
  EXPECT_EQ(std::string("com.android.lib_one"), packages[0]->GetPackageName());
  EXPECT_EQ(0, packages[0]->GetPackageId());

  const auto& dynamic_pkg_map = packages[0]->GetDynamicPackageMap();

  // The library has no dependencies.
  ASSERT_TRUE(dynamic_pkg_map.empty());
}

TEST(LoadedArscTest, LoadAppLinkedAgainstSharedLibrary) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/libclient/libclient.apk",
                                      "resources.arsc", &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc =
      LoadedArsc::Load(contents.data(), contents.size());
  ASSERT_NE(nullptr, loaded_arsc);

  const auto& packages = loaded_arsc->GetPackages();
  ASSERT_EQ(1u, packages.size());

  EXPECT_FALSE(packages[0]->IsDynamic());
  EXPECT_EQ(std::string("com.android.libclient"), packages[0]->GetPackageName());
  EXPECT_EQ(0x7f, packages[0]->GetPackageId());

  const auto& dynamic_pkg_map = packages[0]->GetDynamicPackageMap();

  // The library has two dependencies.
  ASSERT_EQ(2u, dynamic_pkg_map.size());

  EXPECT_EQ(std::string("com.android.lib_one"), dynamic_pkg_map[0].package_name);
  EXPECT_EQ(0x02, dynamic_pkg_map[0].package_id);

  EXPECT_EQ(std::string("com.android.lib_two"), dynamic_pkg_map[1].package_name);
  EXPECT_EQ(0x03, dynamic_pkg_map[1].package_id);
}

TEST(LoadedArscTest, LoadAppAsSharedLibrary) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/appaslib/appaslib.apk",
                                      "resources.arsc", &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(
      contents.data(), contents.size(), false /*system*/, true /*load_as_shared_library*/);
  ASSERT_NE(nullptr, loaded_arsc);

  const auto& packages = loaded_arsc->GetPackages();
  ASSERT_EQ(1u, packages.size());

  EXPECT_TRUE(packages[0]->IsDynamic());
  EXPECT_EQ(0x7f, packages[0]->GetPackageId());
}

TEST(LoadedArscTest, LoadFeatureSplit) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/feature/feature.apk", "resources.arsc",
                                      &contents));
  std::unique_ptr<const LoadedArsc> loaded_arsc =
      LoadedArsc::Load(contents.data(), contents.size());
  ASSERT_NE(nullptr, loaded_arsc);

  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));

  LoadedArscEntry entry;
  ResTable_config selected_config;
  uint32_t flags;

  ASSERT_TRUE(loaded_arsc->FindEntry(basic::R::string::test3, desired_config, &entry,
                                     &selected_config, &flags));

  size_t len;
  const char16_t* type_name16 = entry.type_string_ref.string16(&len);
  ASSERT_NE(nullptr, type_name16);
  ASSERT_NE(0u, len);

  size_t utf8_len = utf16_to_utf8_length(type_name16, len);
  std::string type_name;
  type_name.resize(utf8_len);
  utf16_to_utf8(type_name16, len, &*type_name.begin(), utf8_len + 1);

  EXPECT_EQ(std::string("string"), type_name);
}

// structs with size fields (like Res_value, ResTable_entry) should be
// backwards and forwards compatible (aka checking the size field against
// sizeof(Res_value) might not be backwards compatible.
TEST(LoadedArscTest, LoadingShouldBeForwardsAndBackwardsCompatible) { ASSERT_TRUE(false); }

}  // namespace android
