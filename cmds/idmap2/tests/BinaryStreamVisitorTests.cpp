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

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "androidfw/ApkAssets.h"
#include "androidfw/Idmap.h"

#include "idmap2/BinaryStreamVisitor.h"
#include "idmap2/Idmap.h"

#include "TestHelpers.h"

using ::testing::IsNull;
using ::testing::NotNull;

namespace android {
namespace idmap2 {

TEST(BinaryStreamVisitorTests, CreateBinaryStreamViaBinaryStreamVisitor) {
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data), idmap_raw_data_len);
  std::istringstream raw_stream(raw);

  std::stringstream error;
  std::unique_ptr<const Idmap> idmap1 = Idmap::FromBinaryStream(raw_stream, error);
  ASSERT_THAT(idmap1, NotNull());

  std::stringstream stream;
  BinaryStreamVisitor visitor(stream);
  idmap1->accept(&visitor);

  std::unique_ptr<const Idmap> idmap2 = Idmap::FromBinaryStream(stream, error);
  ASSERT_THAT(idmap2, NotNull());

  ASSERT_EQ(idmap1->GetHeader()->GetTargetCrc(), idmap2->GetHeader()->GetTargetCrc());
  ASSERT_EQ(idmap1->GetHeader()->GetTargetPath(), idmap2->GetHeader()->GetTargetPath());
  ASSERT_EQ(idmap1->GetData().size(), 1u);
  ASSERT_EQ(idmap1->GetData().size(), idmap2->GetData().size());

  const auto& data1 = idmap1->GetData()[0];
  const auto& data2 = idmap2->GetData()[0];

  ASSERT_EQ(data1->GetHeader()->GetTargetPackageId(), data2->GetHeader()->GetTargetPackageId());
  ASSERT_EQ(data1->GetTypeEntries().size(), 2u);
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

  std::stringstream error;
  std::unique_ptr<const Idmap> idmap =
      Idmap::FromApkAssets(target_apk_path, *target_apk, overlay_apk_path, *overlay_apk, error);
  ASSERT_THAT(idmap, NotNull());

  std::stringstream stream;
  BinaryStreamVisitor visitor(stream);
  idmap->accept(&visitor);
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

  success = LoadedIdmap::Lookup(header, 0x0002, &entry);
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x0003, &entry);
  ASSERT_TRUE(success);
  ASSERT_EQ(entry, 0x0000);

  success = LoadedIdmap::Lookup(header, 0x0004, &entry);
  ASSERT_FALSE(success);

  success = LoadedIdmap::Lookup(header, 0x0005, &entry);
  ASSERT_TRUE(success);
  ASSERT_EQ(entry, 0x0001);

  success = LoadedIdmap::Lookup(header, 0x0006, &entry);
  ASSERT_TRUE(success);
  ASSERT_EQ(entry, 0x0002);

  success = LoadedIdmap::Lookup(header, 0x0007, &entry);
  ASSERT_FALSE(success);
}

}  // namespace idmap2
}  // namespace android
