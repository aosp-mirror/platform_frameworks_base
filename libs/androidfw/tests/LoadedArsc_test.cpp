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

#include "android-base/file.h"
#include "androidfw/ResourceUtils.h"

#include "TestHelpers.h"
#include "data/basic/R.h"
#include "data/libclient/R.h"
#include "data/overlayable/R.h"
#include "data/sparse/R.h"
#include "data/styles/R.h"

namespace app = com::android::app;
namespace basic = com::android::basic;
namespace libclient = com::android::libclient;
namespace overlayable = com::android::overlayable;
namespace sparse = com::android::sparse;

using ::android::base::ReadFileToString;
using ::testing::Eq;
using ::testing::Ge;
using ::testing::IsNull;
using ::testing::NotNull;
using ::testing::SizeIs;
using ::testing::StrEq;

namespace android {

TEST(LoadedArscTest, LoadSinglePackageArsc) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/styles/styles.apk", "resources.arsc",
                                      &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(StringPiece(contents));
  ASSERT_THAT(loaded_arsc, NotNull());

  const LoadedPackage* package =
      loaded_arsc->GetPackageById(get_package_id(app::R::string::string_one));
  ASSERT_THAT(package, NotNull());
  EXPECT_THAT(package->GetPackageName(), StrEq("com.android.app"));
  EXPECT_THAT(package->GetPackageId(), Eq(0x7f));

  const uint8_t type_index = get_type_id(app::R::string::string_one) - 1;
  const uint16_t entry_index = get_entry_id(app::R::string::string_one);

  const TypeSpec* type_spec = package->GetTypeSpecByTypeIndex(type_index);
  ASSERT_THAT(type_spec, NotNull());
  ASSERT_THAT(type_spec->type_count, Ge(1u));

  const ResTable_type* type = type_spec->types[0];
  ASSERT_THAT(type, NotNull());
  ASSERT_THAT(LoadedPackage::GetEntry(type, entry_index), NotNull());
}

TEST(LoadedArscTest, LoadSparseEntryApp) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/sparse/sparse.apk", "resources.arsc",
                                      &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(StringPiece(contents));
  ASSERT_THAT(loaded_arsc, NotNull());

  const LoadedPackage* package =
      loaded_arsc->GetPackageById(get_package_id(sparse::R::integer::foo_9));
  ASSERT_THAT(package, NotNull());

  const uint8_t type_index = get_type_id(sparse::R::integer::foo_9) - 1;
  const uint16_t entry_index = get_entry_id(sparse::R::integer::foo_9);

  const TypeSpec* type_spec = package->GetTypeSpecByTypeIndex(type_index);
  ASSERT_THAT(type_spec, NotNull());
  ASSERT_THAT(type_spec->type_count, Ge(1u));

  const ResTable_type* type = type_spec->types[0];
  ASSERT_THAT(type, NotNull());
  ASSERT_THAT(LoadedPackage::GetEntry(type, entry_index), NotNull());
}

TEST(LoadedArscTest, LoadSharedLibrary) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/lib_one/lib_one.apk", "resources.arsc",
                                      &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(StringPiece(contents));
  ASSERT_THAT(loaded_arsc, NotNull());

  const auto& packages = loaded_arsc->GetPackages();
  ASSERT_THAT(packages, SizeIs(1u));
  EXPECT_TRUE(packages[0]->IsDynamic());
  EXPECT_THAT(packages[0]->GetPackageName(), StrEq("com.android.lib_one"));
  EXPECT_THAT(packages[0]->GetPackageId(), Eq(0));

  const auto& dynamic_pkg_map = packages[0]->GetDynamicPackageMap();

  // The library has no dependencies.
  ASSERT_TRUE(dynamic_pkg_map.empty());
}

TEST(LoadedArscTest, LoadAppLinkedAgainstSharedLibrary) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/libclient/libclient.apk",
                                      "resources.arsc", &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(StringPiece(contents));
  ASSERT_THAT(loaded_arsc, NotNull());

  const auto& packages = loaded_arsc->GetPackages();
  ASSERT_THAT(packages, SizeIs(1u));
  EXPECT_FALSE(packages[0]->IsDynamic());
  EXPECT_THAT(packages[0]->GetPackageName(), StrEq("com.android.libclient"));
  EXPECT_THAT(packages[0]->GetPackageId(), Eq(0x7f));

  const auto& dynamic_pkg_map = packages[0]->GetDynamicPackageMap();

  // The library has two dependencies.
  ASSERT_THAT(dynamic_pkg_map, SizeIs(2u));
  EXPECT_THAT(dynamic_pkg_map[0].package_name, StrEq("com.android.lib_one"));
  EXPECT_THAT(dynamic_pkg_map[0].package_id, Eq(0x02));

  EXPECT_THAT(dynamic_pkg_map[1].package_name, StrEq("com.android.lib_two"));
  EXPECT_THAT(dynamic_pkg_map[1].package_id, Eq(0x03));
}

TEST(LoadedArscTest, LoadAppAsSharedLibrary) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/appaslib/appaslib.apk",
                                      "resources.arsc", &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc =
      LoadedArsc::Load(StringPiece(contents), nullptr /*loaded_idmap*/, false /*system*/,
                       true /*load_as_shared_library*/);
  ASSERT_THAT(loaded_arsc, NotNull());

  const auto& packages = loaded_arsc->GetPackages();
  ASSERT_THAT(packages, SizeIs(1u));
  EXPECT_TRUE(packages[0]->IsDynamic());
  EXPECT_THAT(packages[0]->GetPackageId(), Eq(0x7f));
}

TEST(LoadedArscTest, LoadFeatureSplit) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/feature/feature.apk", "resources.arsc",
                                      &contents));
  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(StringPiece(contents));
  ASSERT_THAT(loaded_arsc, NotNull());

  const LoadedPackage* package =
      loaded_arsc->GetPackageById(get_package_id(basic::R::string::test3));
  ASSERT_THAT(package, NotNull());

  uint8_t type_index = get_type_id(basic::R::string::test3) - 1;
  uint8_t entry_index = get_entry_id(basic::R::string::test3);

  const TypeSpec* type_spec = package->GetTypeSpecByTypeIndex(type_index);
  ASSERT_THAT(type_spec, NotNull());
  ASSERT_THAT(type_spec->type_count, Ge(1u));
  ASSERT_THAT(type_spec->types[0], NotNull());

  size_t len;
  const char16_t* type_name16 =
      package->GetTypeStringPool()->stringAt(type_spec->type_spec->id - 1, &len);
  ASSERT_THAT(type_name16, NotNull());
  EXPECT_THAT(util::Utf16ToUtf8(StringPiece16(type_name16, len)), StrEq("string"));

  ASSERT_THAT(LoadedPackage::GetEntry(type_spec->types[0], entry_index), NotNull());
}

// AAPT(2) generates resource tables with chunks in a certain order. The rule is that
// a RES_TABLE_TYPE_TYPE with id `i` must always be preceded by a RES_TABLE_TYPE_SPEC_TYPE with
// id `i`. The RES_TABLE_TYPE_SPEC_TYPE does not need to be directly preceding, however.
//
// AAPT(2) generates something like:
//   RES_TABLE_TYPE_SPEC_TYPE id=1
//   RES_TABLE_TYPE_TYPE id=1
//   RES_TABLE_TYPE_SPEC_TYPE id=2
//   RES_TABLE_TYPE_TYPE id=2
//
// But the following is valid too:
//   RES_TABLE_TYPE_SPEC_TYPE id=1
//   RES_TABLE_TYPE_SPEC_TYPE id=2
//   RES_TABLE_TYPE_TYPE id=1
//   RES_TABLE_TYPE_TYPE id=2
//
TEST(LoadedArscTest, LoadOutOfOrderTypeSpecs) {
  std::string contents;
  ASSERT_TRUE(
      ReadFileFromZipToString(GetTestDataPath() + "/out_of_order_types/out_of_order_types.apk",
                              "resources.arsc", &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(StringPiece(contents));
  ASSERT_THAT(loaded_arsc, NotNull());

  ASSERT_THAT(loaded_arsc->GetPackages(), SizeIs(1u));
  const auto& package = loaded_arsc->GetPackages()[0];
  ASSERT_THAT(package, NotNull());

  const TypeSpec* type_spec = package->GetTypeSpecByTypeIndex(0);
  ASSERT_THAT(type_spec, NotNull());
  ASSERT_THAT(type_spec->type_count, Ge(1u));
  ASSERT_THAT(type_spec->types[0], NotNull());

  type_spec = package->GetTypeSpecByTypeIndex(1);
  ASSERT_THAT(type_spec, NotNull());
  ASSERT_THAT(type_spec->type_count, Ge(1u));
  ASSERT_THAT(type_spec->types[0], NotNull());
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
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/overlay/overlay.apk", "resources.arsc",
                                      &contents));

  MockLoadedIdmap loaded_idmap;

  std::unique_ptr<const LoadedArsc> loaded_arsc =
      LoadedArsc::Load(StringPiece(contents), &loaded_idmap);
  ASSERT_THAT(loaded_arsc, NotNull());

  const LoadedPackage* package = loaded_arsc->GetPackageById(0x08u);
  ASSERT_THAT(package, NotNull());

  const TypeSpec* type_spec = package->GetTypeSpecByTypeIndex(0x03u - 1);
  ASSERT_THAT(type_spec, NotNull());
  ASSERT_THAT(type_spec->type_count, Ge(1u));
  ASSERT_THAT(type_spec->types[0], NotNull());

  // The entry being overlaid doesn't exist at the original entry index.
  ASSERT_THAT(LoadedPackage::GetEntry(type_spec->types[0], 0x0001u), IsNull());

  // Since this is an overlay, the actual entry ID must be mapped.
  ASSERT_THAT(type_spec->idmap_entries, NotNull());
  uint16_t target_entry_id = 0u;
  ASSERT_TRUE(LoadedIdmap::Lookup(type_spec->idmap_entries, 0x0001u, &target_entry_id));
  ASSERT_THAT(target_entry_id, Eq(0x0u));
  ASSERT_THAT(LoadedPackage::GetEntry(type_spec->types[0], 0x0000), NotNull());
}

TEST(LoadedArscTest, LoadOverlayable) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/overlayable/overlayable.apk",
                                      "resources.arsc", &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc =
      LoadedArsc::Load(StringPiece(contents), nullptr /*loaded_idmap*/, false /*system*/,
                       false /*load_as_shared_library*/);

  ASSERT_THAT(loaded_arsc, NotNull());
  const LoadedPackage* package = loaded_arsc->GetPackageById(
      get_package_id(overlayable::R::string::not_overlayable));

  const OverlayableInfo* info = package->GetOverlayableInfo(
      overlayable::R::string::not_overlayable);
  ASSERT_THAT(info, IsNull());

  info = package->GetOverlayableInfo(overlayable::R::string::overlayable1);
  ASSERT_THAT(info, NotNull());
  EXPECT_THAT(info->name, Eq("OverlayableResources1"));
  EXPECT_THAT(info->actor, Eq("overlay://theme"));
  EXPECT_THAT(info->policy_flags, Eq(ResTable_overlayable_policy_header::POLICY_PUBLIC));

  info = package->GetOverlayableInfo(overlayable::R::string::overlayable2);
  ASSERT_THAT(info, NotNull());
  EXPECT_THAT(info->name, Eq("OverlayableResources1"));
  EXPECT_THAT(info->actor, Eq("overlay://theme"));
  EXPECT_THAT(info->policy_flags,
              Eq(ResTable_overlayable_policy_header::POLICY_SYSTEM_PARTITION
                 | ResTable_overlayable_policy_header::POLICY_PRODUCT_PARTITION));

  info = package->GetOverlayableInfo(overlayable::R::string::overlayable3);
  ASSERT_THAT(info, NotNull());
  EXPECT_THAT(info->name, Eq("OverlayableResources2"));
  EXPECT_THAT(info->actor, Eq("overlay://com.android.overlayable"));
  EXPECT_THAT(info->policy_flags,
              Eq(ResTable_overlayable_policy_header::POLICY_VENDOR_PARTITION
                 | ResTable_overlayable_policy_header::POLICY_PRODUCT_PARTITION));

  info = package->GetOverlayableInfo(overlayable::R::string::overlayable4);
  EXPECT_THAT(info->name, Eq("OverlayableResources1"));
  EXPECT_THAT(info->actor, Eq("overlay://theme"));
  ASSERT_THAT(info, NotNull());
  EXPECT_THAT(info->policy_flags, Eq(ResTable_overlayable_policy_header::POLICY_PUBLIC));
}

TEST(LoadedArscTest, ResourceIdentifierIterator) {
  std::string contents;
  ASSERT_TRUE(
      ReadFileFromZipToString(GetTestDataPath() + "/basic/basic.apk", "resources.arsc", &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(StringPiece(contents));
  ASSERT_NE(nullptr, loaded_arsc);

  const std::vector<std::unique_ptr<const LoadedPackage>>& packages = loaded_arsc->GetPackages();
  ASSERT_EQ(1u, packages.size());
  ASSERT_EQ(std::string("com.android.basic"), packages[0]->GetPackageName());

  const auto& loaded_package = packages[0];
  auto iter = loaded_package->begin();
  auto end = loaded_package->end();

  ASSERT_NE(end, iter);
  ASSERT_EQ(0x7f010000u, *iter++);
  ASSERT_EQ(0x7f010001u, *iter++);
  ASSERT_EQ(0x7f020000u, *iter++);
  ASSERT_EQ(0x7f020001u, *iter++);
  ASSERT_EQ(0x7f030000u, *iter++);
  ASSERT_EQ(0x7f030001u, *iter++);
  ASSERT_EQ(0x7f030002u, *iter++);  // note: string without default, excluded by aapt2 dump
  ASSERT_EQ(0x7f040000u, *iter++);
  ASSERT_EQ(0x7f040001u, *iter++);
  ASSERT_EQ(0x7f040002u, *iter++);
  ASSERT_EQ(0x7f040003u, *iter++);
  ASSERT_EQ(0x7f040004u, *iter++);
  ASSERT_EQ(0x7f040005u, *iter++);
  ASSERT_EQ(0x7f040006u, *iter++);
  ASSERT_EQ(0x7f040007u, *iter++);
  ASSERT_EQ(0x7f040008u, *iter++);
  ASSERT_EQ(0x7f040009u, *iter++);
  ASSERT_EQ(0x7f04000au, *iter++);
  ASSERT_EQ(0x7f04000bu, *iter++);
  ASSERT_EQ(0x7f04000cu, *iter++);
  ASSERT_EQ(0x7f04000du, *iter++);
  ASSERT_EQ(0x7f050000u, *iter++);
  ASSERT_EQ(0x7f050001u, *iter++);
  ASSERT_EQ(0x7f060000u, *iter++);
  ASSERT_EQ(0x7f070000u, *iter++);
  ASSERT_EQ(0x7f070001u, *iter++);
  ASSERT_EQ(0x7f070002u, *iter++);
  ASSERT_EQ(0x7f070003u, *iter++);
  ASSERT_EQ(end, iter);
}

TEST(LoadedArscTest, GetOverlayableMap) {
  std::string contents;
  ASSERT_TRUE(ReadFileFromZipToString(GetTestDataPath() + "/overlayable/overlayable.apk",
                                      "resources.arsc", &contents));

  std::unique_ptr<const LoadedArsc> loaded_arsc = LoadedArsc::Load(StringPiece(contents));
  ASSERT_NE(nullptr, loaded_arsc);

  const std::vector<std::unique_ptr<const LoadedPackage>>& packages = loaded_arsc->GetPackages();
  ASSERT_EQ(1u, packages.size());
  ASSERT_EQ(std::string("com.android.overlayable"), packages[0]->GetPackageName());

  const auto map = packages[0]->GetOverlayableMap();
  ASSERT_EQ(2, map.size());
  ASSERT_EQ(map.at("OverlayableResources1"), "overlay://theme");
  ASSERT_EQ(map.at("OverlayableResources2"), "overlay://com.android.overlayable");
}

// structs with size fields (like Res_value, ResTable_entry) should be
// backwards and forwards compatible (aka checking the size field against
// sizeof(Res_value) might not be backwards compatible.
// TEST(LoadedArscTest, LoadingShouldBeForwardsAndBackwardsCompatible) { ASSERT_TRUE(false); }

}  // namespace android
