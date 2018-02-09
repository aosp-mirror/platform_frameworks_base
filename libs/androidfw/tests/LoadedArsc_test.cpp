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
#include "data/sparse/R.h"
#include "data/styles/R.h"

namespace app = com::android::app;
namespace basic = com::android::basic;
namespace libclient = com::android::libclient;
namespace sparse = com::android::sparse;

namespace android {

TEST(LoadedArscTest, LoadSinglePackageArsc) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/styles/styles.apk", "resources.arsc",
                                      &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(StringPiece(contents));
  ASSERT_NE(nullptr, loaded_arsc);

  const std::vector<std::unique_ptr<const LoadedPackage>>& packages = loaded_arsc->GetPackages();
  ASSERT_EQ(1u, packages.size());
  EXPECT_EQ(std::string("com.android.app"), packages[0]->GetPackageName());
  EXPECT_EQ(0x7f, packages[0]->GetPackageId());

  ResTable_config config;
  memset(&config, 0, sizeof(config));
  config.sdkVersion = 24;

  FindEntryResult entry;

  ASSERT_TRUE(loaded_arsc->FindEntry(app::R::string::string_one, config, &entry));
  ASSERT_NE(nullptr, entry.entry);
}

TEST(LoadedArscTest, FindDefaultEntry) {
  std::string contents;
  ASSERT_TRUE(
      ReadFileFromZipToString(GetTestDataPath() + "/basic/basic.apk", "resources.arsc", &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(StringPiece(contents));
  ASSERT_NE(nullptr, loaded_arsc);

  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));
  desired_config.language[0] = 'd';
  desired_config.language[1] = 'e';

  FindEntryResult entry;
  ASSERT_TRUE(loaded_arsc->FindEntry(basic::R::string::test1, desired_config, &entry));
  ASSERT_NE(nullptr, entry.entry);
}

TEST(LoadedArscTest, LoadSparseEntryApp) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/sparse/sparse.apk", "resources.arsc",
                                      &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(StringPiece(contents));
  ASSERT_NE(nullptr, loaded_arsc);

  ResTable_config config;
  memset(&config, 0, sizeof(config));
  config.sdkVersion = 26;

  FindEntryResult entry;
  ASSERT_TRUE(loaded_arsc->FindEntry(sparse::R::integer::foo_9, config, &entry));
  ASSERT_NE(nullptr, entry.entry);
}

TEST(LoadedArscTest, LoadSharedLibrary) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/lib_one/lib_one.apk", "resources.arsc",
                                      &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(StringPiece(contents));
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

  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(StringPiece(contents));
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

  std::unique_ptr<const LoadedArsc> loaded_arsc =
      LoadedArsc::Load(StringPiece(contents), nullptr /*loaded_idmap*/, false /*system*/,
                       true /*load_as_shared_library*/);
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
  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(StringPiece(contents));
  ASSERT_NE(nullptr, loaded_arsc);

  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));

  FindEntryResult entry;
  ASSERT_TRUE(loaded_arsc->FindEntry(basic::R::string::test3, desired_config, &entry));

  size_t len;
  const char16_t* type_name16 = entry.type_string_ref.string16(&len);
  ASSERT_NE(nullptr, type_name16);
  ASSERT_NE(0u, len);

  std::string type_name = util::Utf16ToUtf8(StringPiece16(type_name16, len));
  EXPECT_EQ(std::string("string"), type_name);
}

class MockLoadedIdmap : public LoadedIdmap {
 public:
  MockLoadedIdmap() : LoadedIdmap() {
    local_header_.magic = kIdmapMagic;
    local_header_.version = kIdmapCurrentVersion;
    local_header_.target_package_id = 0x08;
    local_header_.type_count = 1;
    header_ = &local_header_;

    entry_header = util::unique_cptr<IdmapEntry_header>(
        (IdmapEntry_header*)::malloc(sizeof(IdmapEntry_header) + sizeof(uint32_t)));
    entry_header->target_type_id = 0x03;
    entry_header->overlay_type_id = 0x02;
    entry_header->entry_id_offset = 1;
    entry_header->entry_count = 1;
    entry_header->entries[0] = 0x00000000u;
    type_map_[entry_header->overlay_type_id] = entry_header.get();
  }

 private:
  Idmap_header local_header_;
  util::unique_cptr<IdmapEntry_header> entry_header;
};

TEST(LoadedArscTest, LoadOverlay) {
  std::string contents, overlay_contents;
  ASSERT_TRUE(
      ReadFileFromZipToString(GetTestDataPath() + "/basic/basic.apk", "resources.arsc", &contents));
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/overlay/overlay.apk", "resources.arsc",
                                      &overlay_contents));

  MockLoadedIdmap loaded_idmap;

  std::unique_ptr<const LoadedArsc> loaded_arsc =
      LoadedArsc::Load(StringPiece(overlay_contents), &loaded_idmap);
  ASSERT_NE(nullptr, loaded_arsc);

  ResTable_config desired_config;
  memset(&desired_config, 0, sizeof(desired_config));

  FindEntryResult entry;
  ASSERT_TRUE(loaded_arsc->FindEntry(0x08030001u, desired_config, &entry));
}

// structs with size fields (like Res_value, ResTable_entry) should be
// backwards and forwards compatible (aka checking the size field against
// sizeof(Res_value) might not be backwards compatible.
TEST(LoadedArscTest, LoadingShouldBeForwardsAndBackwardsCompatible) { ASSERT_TRUE(false); }

}  // namespace android
