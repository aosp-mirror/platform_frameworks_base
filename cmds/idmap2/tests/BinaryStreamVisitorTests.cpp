/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <memory>
#include <sstream>
#include <string>
#include <utility>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "androidfw/ApkAssets.h"
#include "androidfw/Idmap.h"

#include "idmap2/BinaryStreamVisitor.h"
#include "idmap2/Idmap.h"

#include "TestHelpers.h"

using ::testing::NotNull;

namespace android::idmap2 {

TEST(BinaryStreamVisitorTests, CreateBinaryStreamViaBinaryStreamVisitor) {
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data), idmap_raw_data_len);
  std::istringstream raw_stream(raw);

  auto result1 = Idmap::FromBinaryStream(raw_stream);
  ASSERT_TRUE(result1);
  const auto idmap1 = std::move(*result1);

  std::stringstream stream;
  BinaryStreamVisitor visitor(stream);
  idmap1->accept(&visitor);

  auto result2 = Idmap::FromBinaryStream(stream);
  ASSERT_TRUE(result2);
  const auto idmap2 = std::move(*result2);

  ASSERT_EQ(idmap1->GetHeader()->GetTargetCrc(), idmap2->GetHeader()->GetTargetCrc());
  ASSERT_EQ(idmap1->GetHeader()->GetTargetPath(), idmap2->GetHeader()->GetTargetPath());
  ASSERT_EQ(idmap1->GetData().size(), 1U);
  ASSERT_EQ(idmap1->GetData().size(), idmap2->GetData().size());

  const auto& data1 = idmap1->GetData()[0];
  const auto& data2 = idmap2->GetData()[0];

  ASSERT_EQ(data1->GetHeader()->GetTargetPackageId(), data2->GetHeader()->GetTargetPackageId());
  ASSERT_EQ(data1->GetTypeEntries().size(), 2U);
  ASSERT_EQ(data1->GetTypeEntries().size(), data2->GetTypeEntries().size());
  ASSERT_EQ(data1->GetTypeEntries()[0]->GetEntry(0), data2->GetTypeEntries()[0]->GetEntry(0));
  ASSERT_EQ(data1->GetTypeEntries()[0]->GetEntry(1), data2->GetTypeEntries()[0]->GetEntry(1));
  ASSERT_EQ(data1->GetTypeEntries()[0]->GetEntry(2), data2->GetTypeEntries()[0]->GetEntry(2));
  ASSERT_EQ(data1->GetTypeEntries()[1]->GetEntry(0), data2->GetTypeEntries()[1]->GetEntry(0));
  ASSERT_EQ(data1->GetTypeEntries()[1]->GetEntry(1), data2->GetTypeEntries()[1]->GetEntry(1));
  ASSERT_EQ(data1->GetTypeEntries()[1]->GetEntry(2), data2->GetTypeEntries()[1]->GetEntry(2));
}

TEST(BinaryStreamVisitorTests, CreateIdmapFromApkAssetsInteropWithLoadedIdmap) {
  const std::string target_apk_path(GetTestDataPath() + "/target/target.apk");
  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  ASSERT_THAT(target_apk, NotNull());

  const std::string overlay_apk_path(GetTestDataPath() + "/overlay/overlay.apk");
  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  ASSERT_THAT(overlay_apk, NotNull());

  const auto idmap =
      Idmap::FromApkAssets(target_apk_path, *target_apk, overlay_apk_path, *overlay_apk,
                           PolicyFlags::POLICY_PUBLIC, /* enforce_overlayable */ true);
  ASSERT_TRUE(idmap);

  std::stringstream stream;
  BinaryStreamVisitor visitor(stream);
  (*idmap)->accept(&visitor);
  const std::string str = stream.str();
  const StringPiece data(str);
  std::unique_ptr<const LoadedIdmap> loaded_idmap = LoadedIdmap::Load(data);
  ASSERT_THAT(loaded_idmap, NotNull());
  ASSERT_EQ(loaded_idmap->TargetPackageId(), 0x7f);

  const IdmapEntry_header* header = loaded_idmap->GetEntryMapForType(0x01);
  ASSERT_THAT(header, NotNull());

  EntryId entry;
  bool success = LoadedIdmap::Lookup(header, 0x0000, &entry);
  ASSERT_TRUE(success);
  ASSERT_EQ(entry, 0x0000);

  header = loaded_idmap->GetEntryMapForType(0x02);
  ASSERT_THAT(header, NotNull());

  success = LoadedIdmap::Lookup(header, 0x0000, &entry);  // string/a
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x0001, &entry);  // string/b
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x0002, &entry);  // string/c
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x0003, &entry);  // string/other
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x0004, &entry);  // string/not_overlayable
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x0005, &entry);  // string/policy_product
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x0006, &entry);  // string/policy_public
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x0007, &entry);  // string/policy_system
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x0008, &entry);  // string/policy_system_vendor
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x0009, &entry);  // string/policy_signature
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x000a, &entry);  // string/str1
  ASSERT_TRUE(success);
  ASSERT_EQ(entry, 0x0000);

  success = LoadedIdmap::Lookup(header, 0x000b, &entry);  // string/str2
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x000c, &entry);  // string/str3
  ASSERT_TRUE(success);
  ASSERT_EQ(entry, 0x0001);

  success = LoadedIdmap::Lookup(header, 0x000d, &entry);  // string/str4
  ASSERT_TRUE(success);
  ASSERT_EQ(entry, 0x0002);

  success = LoadedIdmap::Lookup(header, 0x000e, &entry);  // string/x
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x000f, &entry);  // string/y
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x0010, &entry);  // string/z
  ASSERT_FALSE(success);
}

}  // namespace android::idmap2
