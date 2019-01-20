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

#include <cstdio>  // fclose

#include <fstream>
#include <memory>
#include <sstream>
#include <string>
#include <vector>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "android-base/macros.h"
#include "androidfw/ApkAssets.h"

#include "idmap2/BinaryStreamVisitor.h"
#include "idmap2/CommandLineOptions.h"
#include "idmap2/Idmap.h"

#include "TestHelpers.h"

using ::testing::IsNull;
using ::testing::NotNull;

namespace android::idmap2 {

TEST(IdmapTests, TestCanonicalIdmapPathFor) {
  ASSERT_EQ(Idmap::CanonicalIdmapPathFor("/foo", "/vendor/overlay/bar.apk"),
            "/foo/vendor@overlay@bar.apk@idmap");
}

TEST(IdmapTests, CreateIdmapHeaderFromBinaryStream) {
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data), idmap_raw_data_len);
  std::istringstream stream(raw);
  std::unique_ptr<const IdmapHeader> header = IdmapHeader::FromBinaryStream(stream);
  ASSERT_THAT(header, NotNull());
  ASSERT_EQ(header->GetMagic(), 0x504d4449U);
  ASSERT_EQ(header->GetVersion(), 0x01U);
  ASSERT_EQ(header->GetTargetCrc(), 0x1234U);
  ASSERT_EQ(header->GetOverlayCrc(), 0x5678U);
  ASSERT_EQ(header->GetTargetPath().to_string(), "target.apk");
  ASSERT_EQ(header->GetOverlayPath().to_string(), "overlay.apk");
}

TEST(IdmapTests, FailToCreateIdmapHeaderFromBinaryStreamIfPathTooLong) {
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data), idmap_raw_data_len);
  // overwrite the target path string, including the terminating null, with '.'
  for (size_t i = 0x10; i < 0x110; i++) {
    raw[i] = '.';
  }
  std::istringstream stream(raw);
  std::unique_ptr<const IdmapHeader> header = IdmapHeader::FromBinaryStream(stream);
  ASSERT_THAT(header, IsNull());
}

TEST(IdmapTests, CreateIdmapDataHeaderFromBinaryStream) {
  const size_t offset = 0x210;
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data + offset),
                  idmap_raw_data_len - offset);
  std::istringstream stream(raw);

  std::unique_ptr<const IdmapData::Header> header = IdmapData::Header::FromBinaryStream(stream);
  ASSERT_THAT(header, NotNull());
  ASSERT_EQ(header->GetTargetPackageId(), 0x7fU);
  ASSERT_EQ(header->GetTypeCount(), 2U);
}

TEST(IdmapTests, CreateIdmapDataResourceTypeFromBinaryStream) {
  const size_t offset = 0x214;
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data + offset),
                  idmap_raw_data_len - offset);
  std::istringstream stream(raw);

  std::unique_ptr<const IdmapData::TypeEntry> data = IdmapData::TypeEntry::FromBinaryStream(stream);
  ASSERT_THAT(data, NotNull());
  ASSERT_EQ(data->GetTargetTypeId(), 0x02U);
  ASSERT_EQ(data->GetOverlayTypeId(), 0x02U);
  ASSERT_EQ(data->GetEntryCount(), 1U);
  ASSERT_EQ(data->GetEntryOffset(), 0U);
  ASSERT_EQ(data->GetEntry(0), 0U);
}

TEST(IdmapTests, CreateIdmapDataFromBinaryStream) {
  const size_t offset = 0x210;
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data + offset),
                  idmap_raw_data_len - offset);
  std::istringstream stream(raw);

  std::unique_ptr<const IdmapData> data = IdmapData::FromBinaryStream(stream);
  ASSERT_THAT(data, NotNull());
  ASSERT_EQ(data->GetHeader()->GetTargetPackageId(), 0x7fU);
  ASSERT_EQ(data->GetHeader()->GetTypeCount(), 2U);
  const std::vector<std::unique_ptr<const IdmapData::TypeEntry>>& types = data->GetTypeEntries();
  ASSERT_EQ(types.size(), 2U);

  ASSERT_EQ(types[0]->GetTargetTypeId(), 0x02U);
  ASSERT_EQ(types[0]->GetOverlayTypeId(), 0x02U);
  ASSERT_EQ(types[0]->GetEntryCount(), 1U);
  ASSERT_EQ(types[0]->GetEntryOffset(), 0U);
  ASSERT_EQ(types[0]->GetEntry(0), 0x0000U);

  ASSERT_EQ(types[1]->GetTargetTypeId(), 0x03U);
  ASSERT_EQ(types[1]->GetOverlayTypeId(), 0x03U);
  ASSERT_EQ(types[1]->GetEntryCount(), 3U);
  ASSERT_EQ(types[1]->GetEntryOffset(), 3U);
  ASSERT_EQ(types[1]->GetEntry(0), 0x0000U);
  ASSERT_EQ(types[1]->GetEntry(1), kNoEntry);
  ASSERT_EQ(types[1]->GetEntry(2), 0x0001U);
}

TEST(IdmapTests, CreateIdmapFromBinaryStream) {
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data), idmap_raw_data_len);
  std::istringstream stream(raw);

  std::stringstream error;
  std::unique_ptr<const Idmap> idmap = Idmap::FromBinaryStream(stream, error);
  ASSERT_THAT(idmap, NotNull());

  ASSERT_THAT(idmap->GetHeader(), NotNull());
  ASSERT_EQ(idmap->GetHeader()->GetMagic(), 0x504d4449U);
  ASSERT_EQ(idmap->GetHeader()->GetVersion(), 0x01U);
  ASSERT_EQ(idmap->GetHeader()->GetTargetCrc(), 0x1234U);
  ASSERT_EQ(idmap->GetHeader()->GetOverlayCrc(), 0x5678U);
  ASSERT_EQ(idmap->GetHeader()->GetTargetPath().to_string(), "target.apk");
  ASSERT_EQ(idmap->GetHeader()->GetOverlayPath().to_string(), "overlay.apk");

  const std::vector<std::unique_ptr<const IdmapData>>& dataBlocks = idmap->GetData();
  ASSERT_EQ(dataBlocks.size(), 1U);

  const std::unique_ptr<const IdmapData>& data = dataBlocks[0];
  ASSERT_EQ(data->GetHeader()->GetTargetPackageId(), 0x7fU);
  ASSERT_EQ(data->GetHeader()->GetTypeCount(), 2U);
  const std::vector<std::unique_ptr<const IdmapData::TypeEntry>>& types = data->GetTypeEntries();
  ASSERT_EQ(types.size(), 2U);

  ASSERT_EQ(types[0]->GetTargetTypeId(), 0x02U);
  ASSERT_EQ(types[0]->GetOverlayTypeId(), 0x02U);
  ASSERT_EQ(types[0]->GetEntryCount(), 1U);
  ASSERT_EQ(types[0]->GetEntryOffset(), 0U);
  ASSERT_EQ(types[0]->GetEntry(0), 0x0000U);

  ASSERT_EQ(types[1]->GetTargetTypeId(), 0x03U);
  ASSERT_EQ(types[1]->GetOverlayTypeId(), 0x03U);
  ASSERT_EQ(types[1]->GetEntryCount(), 3U);
  ASSERT_EQ(types[1]->GetEntryOffset(), 3U);
  ASSERT_EQ(types[1]->GetEntry(0), 0x0000U);
  ASSERT_EQ(types[1]->GetEntry(1), kNoEntry);
  ASSERT_EQ(types[1]->GetEntry(2), 0x0001U);
}

TEST(IdmapTests, GracefullyFailToCreateIdmapFromCorruptBinaryStream) {
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data),
                  10);  // data too small
  std::istringstream stream(raw);

  std::stringstream error;
  std::unique_ptr<const Idmap> idmap = Idmap::FromBinaryStream(stream, error);
  ASSERT_THAT(idmap, IsNull());
}

TEST(IdmapTests, CreateIdmapFromApkAssets) {
  const std::string target_apk_path(GetTestDataPath() + "/target/target.apk");
  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  ASSERT_THAT(target_apk, NotNull());

  const std::string overlay_apk_path(GetTestDataPath() + "/overlay/overlay.apk");
  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  ASSERT_THAT(overlay_apk, NotNull());

  std::stringstream error;
  std::unique_ptr<const Idmap> idmap =
      Idmap::FromApkAssets(target_apk_path, *target_apk, overlay_apk_path, *overlay_apk,
                           PolicyFlags::POLICY_PUBLIC, /* enforce_overlayable */ true, error);
  ASSERT_THAT(idmap, NotNull());

  ASSERT_THAT(idmap->GetHeader(), NotNull());
  ASSERT_EQ(idmap->GetHeader()->GetMagic(), 0x504d4449U);
  ASSERT_EQ(idmap->GetHeader()->GetVersion(), 0x01U);
  ASSERT_EQ(idmap->GetHeader()->GetTargetCrc(), 0xab7cf70d);
  ASSERT_EQ(idmap->GetHeader()->GetOverlayCrc(), 0xd470336b);
  ASSERT_EQ(idmap->GetHeader()->GetTargetPath().to_string(), target_apk_path);
  ASSERT_EQ(idmap->GetHeader()->GetOverlayPath(), overlay_apk_path);
  ASSERT_EQ(idmap->GetHeader()->GetOverlayPath(), overlay_apk_path);

  const std::vector<std::unique_ptr<const IdmapData>>& dataBlocks = idmap->GetData();
  ASSERT_EQ(dataBlocks.size(), 1U);

  const std::unique_ptr<const IdmapData>& data = dataBlocks[0];

  ASSERT_EQ(data->GetHeader()->GetTargetPackageId(), 0x7fU);
  ASSERT_EQ(data->GetHeader()->GetTypeCount(), 2U);

  const std::vector<std::unique_ptr<const IdmapData::TypeEntry>>& types = data->GetTypeEntries();
  ASSERT_EQ(types.size(), 2U);

  ASSERT_EQ(types[0]->GetTargetTypeId(), 0x01U);
  ASSERT_EQ(types[0]->GetOverlayTypeId(), 0x01U);
  ASSERT_EQ(types[0]->GetEntryCount(), 1U);
  ASSERT_EQ(types[0]->GetEntryOffset(), 0U);
  ASSERT_EQ(types[0]->GetEntry(0), 0x0000U);

  ASSERT_EQ(types[1]->GetTargetTypeId(), 0x02U);
  ASSERT_EQ(types[1]->GetOverlayTypeId(), 0x02U);
  ASSERT_EQ(types[1]->GetEntryCount(), 4U);
  ASSERT_EQ(types[1]->GetEntryOffset(), 9U);
  ASSERT_EQ(types[1]->GetEntry(0), 0x0000U);
  ASSERT_EQ(types[1]->GetEntry(1), kNoEntry);
  ASSERT_EQ(types[1]->GetEntry(2), 0x0001U);
  ASSERT_EQ(types[1]->GetEntry(3), 0x0002U);
}

TEST(IdmapTests, CreateIdmapFromApkAssetsPolicySystemPublic) {
  const std::string target_apk_path(GetTestDataPath() + "/target/target.apk");
  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  ASSERT_THAT(target_apk, NotNull());

  const std::string overlay_apk_path(GetTestDataPath() + "/system-overlay/system-overlay.apk");
  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  ASSERT_THAT(overlay_apk, NotNull());

  std::stringstream error;
  std::unique_ptr<const Idmap> idmap =
      Idmap::FromApkAssets(target_apk_path, *target_apk, overlay_apk_path, *overlay_apk,
                           PolicyFlags::POLICY_SYSTEM_PARTITION | PolicyFlags::POLICY_PUBLIC,
                           /* enforce_overlayable */ true, error);
  ASSERT_THAT(idmap, NotNull());

  const std::vector<std::unique_ptr<const IdmapData>>& dataBlocks = idmap->GetData();
  ASSERT_EQ(dataBlocks.size(), 1U);

  const std::unique_ptr<const IdmapData>& data = dataBlocks[0];

  ASSERT_EQ(data->GetHeader()->GetTargetPackageId(), 0x7fU);
  ASSERT_EQ(data->GetHeader()->GetTypeCount(), 1U);

  const std::vector<std::unique_ptr<const IdmapData::TypeEntry>>& types = data->GetTypeEntries();
  ASSERT_EQ(types.size(), 1U);

  ASSERT_EQ(types[0]->GetTargetTypeId(), 0x02U);
  ASSERT_EQ(types[0]->GetOverlayTypeId(), 0x01U);
  ASSERT_EQ(types[0]->GetEntryCount(), 3U);
  ASSERT_EQ(types[0]->GetEntryOffset(), 6U);
  ASSERT_EQ(types[0]->GetEntry(0), 0x0000U);  // string/policy_public
  ASSERT_EQ(types[0]->GetEntry(1), 0x0001U);  // string/policy_system
  ASSERT_EQ(types[0]->GetEntry(2), 0x0002U);  // string/policy_system_vendor
}

TEST(IdmapTests, CreateIdmapFromApkAssetsPolicySystemPublicInvalid) {
  const std::string target_apk_path(GetTestDataPath() + "/target/target.apk");
  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  ASSERT_THAT(target_apk, NotNull());

  const std::string overlay_apk_path(GetTestDataPath() +
                                     "/system-overlay-invalid/system-overlay-invalid.apk");
  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  ASSERT_THAT(overlay_apk, NotNull());

  std::stringstream error;
  std::unique_ptr<const Idmap> idmap =
      Idmap::FromApkAssets(target_apk_path, *target_apk, overlay_apk_path, *overlay_apk,
                           PolicyFlags::POLICY_SYSTEM_PARTITION | PolicyFlags::POLICY_PUBLIC,
                           /* enforce_overlayable */ true, error);
  ASSERT_THAT(idmap, NotNull());

  const std::vector<std::unique_ptr<const IdmapData>>& dataBlocks = idmap->GetData();
  ASSERT_EQ(dataBlocks.size(), 1U);

  const std::unique_ptr<const IdmapData>& data = dataBlocks[0];

  ASSERT_EQ(data->GetHeader()->GetTargetPackageId(), 0x7fU);
  ASSERT_EQ(data->GetHeader()->GetTypeCount(), 1U);

  const std::vector<std::unique_ptr<const IdmapData::TypeEntry>>& types = data->GetTypeEntries();
  ASSERT_EQ(types.size(), 1U);

  ASSERT_EQ(types[0]->GetTargetTypeId(), 0x02U);
  ASSERT_EQ(types[0]->GetOverlayTypeId(), 0x01U);
  ASSERT_EQ(types[0]->GetEntryCount(), 6U);
  ASSERT_EQ(types[0]->GetEntryOffset(), 3U);
  ASSERT_EQ(types[0]->GetEntry(0), 0x0000U);   // string/not_overlayable
  ASSERT_EQ(types[0]->GetEntry(1), kNoEntry);  // string/other
  ASSERT_EQ(types[0]->GetEntry(2), kNoEntry);  // string/policy_product
  ASSERT_EQ(types[0]->GetEntry(3), 0x0003U);   // string/policy_public
  ASSERT_EQ(types[0]->GetEntry(4), 0x0004U);   // string/policy_system
  ASSERT_EQ(types[0]->GetEntry(5), 0x0005U);   // string/policy_system_vendor
}

TEST(IdmapTests, CreateIdmapFromApkAssetsPolicySystemPublicInvalidIgnoreOverlayable) {
  const std::string target_apk_path(GetTestDataPath() + "/target/target.apk");
  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  ASSERT_THAT(target_apk, NotNull());

  const std::string overlay_apk_path(GetTestDataPath() +
                                     "/system-overlay-invalid/system-overlay-invalid.apk");
  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  ASSERT_THAT(overlay_apk, NotNull());

  std::stringstream error;
  std::unique_ptr<const Idmap> idmap =
      Idmap::FromApkAssets(target_apk_path, *target_apk, overlay_apk_path, *overlay_apk,
                           PolicyFlags::POLICY_SYSTEM_PARTITION | PolicyFlags::POLICY_PUBLIC,
                           /* enforce_overlayable */ false, error);
  ASSERT_THAT(idmap, NotNull());

  const std::vector<std::unique_ptr<const IdmapData>>& dataBlocks = idmap->GetData();
  ASSERT_EQ(dataBlocks.size(), 1U);

  const std::unique_ptr<const IdmapData>& data = dataBlocks[0];

  ASSERT_EQ(data->GetHeader()->GetTargetPackageId(), 0x7fU);
  ASSERT_EQ(data->GetHeader()->GetTypeCount(), 1U);

  const std::vector<std::unique_ptr<const IdmapData::TypeEntry>>& types = data->GetTypeEntries();
  ASSERT_EQ(types.size(), 1U);

  ASSERT_EQ(types[0]->GetTargetTypeId(), 0x02U);
  ASSERT_EQ(types[0]->GetOverlayTypeId(), 0x01U);
  ASSERT_EQ(types[0]->GetEntryCount(), 6U);
  ASSERT_EQ(types[0]->GetEntryOffset(), 3U);
  ASSERT_EQ(types[0]->GetEntry(0), 0x0000U);  // string/not_overlayable
  ASSERT_EQ(types[0]->GetEntry(1), 0x0001U);  // string/other
  ASSERT_EQ(types[0]->GetEntry(2), 0x0002U);  // string/policy_product
  ASSERT_EQ(types[0]->GetEntry(3), 0x0003U);  // string/policy_public
  ASSERT_EQ(types[0]->GetEntry(4), 0x0004U);  // string/policy_system
  ASSERT_EQ(types[0]->GetEntry(5), 0x0005U);  // string/policy_system_vendor
}

TEST(IdmapTests, FailToCreateIdmapFromApkAssetsIfPathTooLong) {
  std::string target_apk_path(GetTestDataPath());
  for (int i = 0; i < 32; i++) {
    target_apk_path += "/target/../";
  }
  target_apk_path += "/target/target.apk";
  ASSERT_GT(target_apk_path.size(), kIdmapStringLength);
  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  ASSERT_THAT(target_apk, NotNull());

  const std::string overlay_apk_path(GetTestDataPath() + "/overlay/overlay.apk");
  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  ASSERT_THAT(overlay_apk, NotNull());

  std::stringstream error;
  std::unique_ptr<const Idmap> idmap =
      Idmap::FromApkAssets(target_apk_path, *target_apk, overlay_apk_path, *overlay_apk,
                           PolicyFlags::POLICY_PUBLIC, /* enforce_overlayable */ true, error);
  ASSERT_THAT(idmap, IsNull());
}

TEST(IdmapTests, IdmapHeaderIsUpToDate) {
  fclose(stderr);  // silence expected warnings from libandroidfw

  const std::string target_apk_path(GetTestDataPath() + "/target/target.apk");
  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  ASSERT_THAT(target_apk, NotNull());

  const std::string overlay_apk_path(GetTestDataPath() + "/overlay/overlay.apk");
  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  ASSERT_THAT(overlay_apk, NotNull());

  std::stringstream error;
  std::unique_ptr<const Idmap> idmap = Idmap::FromApkAssets(
      target_apk_path, *target_apk, overlay_apk_path, *overlay_apk, PolicyFlags::POLICY_PUBLIC,
      /* enforce_overlayable */ true, error);
  ASSERT_THAT(idmap, NotNull());

  std::stringstream stream;
  BinaryStreamVisitor visitor(stream);
  idmap->accept(&visitor);

  std::unique_ptr<const IdmapHeader> header = IdmapHeader::FromBinaryStream(stream);
  ASSERT_THAT(header, NotNull());
  ASSERT_TRUE(header->IsUpToDate(error)) << error.str();

  // magic: bytes (0x0, 0x03)
  std::string bad_magic_string(stream.str());
  bad_magic_string[0x0] = '.';
  bad_magic_string[0x1] = '.';
  bad_magic_string[0x2] = '.';
  bad_magic_string[0x3] = '.';
  std::stringstream bad_magic_stream(bad_magic_string);
  std::unique_ptr<const IdmapHeader> bad_magic_header =
      IdmapHeader::FromBinaryStream(bad_magic_stream);
  ASSERT_THAT(bad_magic_header, NotNull());
  ASSERT_NE(header->GetMagic(), bad_magic_header->GetMagic());
  ASSERT_FALSE(bad_magic_header->IsUpToDate(error));

  // version: bytes (0x4, 0x07)
  std::string bad_version_string(stream.str());
  bad_version_string[0x4] = '.';
  bad_version_string[0x5] = '.';
  bad_version_string[0x6] = '.';
  bad_version_string[0x7] = '.';
  std::stringstream bad_version_stream(bad_version_string);
  std::unique_ptr<const IdmapHeader> bad_version_header =
      IdmapHeader::FromBinaryStream(bad_version_stream);
  ASSERT_THAT(bad_version_header, NotNull());
  ASSERT_NE(header->GetVersion(), bad_version_header->GetVersion());
  ASSERT_FALSE(bad_version_header->IsUpToDate(error));

  // target crc: bytes (0x8, 0xb)
  std::string bad_target_crc_string(stream.str());
  bad_target_crc_string[0x8] = '.';
  bad_target_crc_string[0x9] = '.';
  bad_target_crc_string[0xa] = '.';
  bad_target_crc_string[0xb] = '.';
  std::stringstream bad_target_crc_stream(bad_target_crc_string);
  std::unique_ptr<const IdmapHeader> bad_target_crc_header =
      IdmapHeader::FromBinaryStream(bad_target_crc_stream);
  ASSERT_THAT(bad_target_crc_header, NotNull());
  ASSERT_NE(header->GetTargetCrc(), bad_target_crc_header->GetTargetCrc());
  ASSERT_FALSE(bad_target_crc_header->IsUpToDate(error));

  // overlay crc: bytes (0xc, 0xf)
  std::string bad_overlay_crc_string(stream.str());
  bad_overlay_crc_string[0xc] = '.';
  bad_overlay_crc_string[0xd] = '.';
  bad_overlay_crc_string[0xe] = '.';
  bad_overlay_crc_string[0xf] = '.';
  std::stringstream bad_overlay_crc_stream(bad_overlay_crc_string);
  std::unique_ptr<const IdmapHeader> bad_overlay_crc_header =
      IdmapHeader::FromBinaryStream(bad_overlay_crc_stream);
  ASSERT_THAT(bad_overlay_crc_header, NotNull());
  ASSERT_NE(header->GetOverlayCrc(), bad_overlay_crc_header->GetOverlayCrc());
  ASSERT_FALSE(bad_overlay_crc_header->IsUpToDate(error));

  // target path: bytes (0x10, 0x10f)
  std::string bad_target_path_string(stream.str());
  bad_target_path_string[0x10] = '\0';
  std::stringstream bad_target_path_stream(bad_target_path_string);
  std::unique_ptr<const IdmapHeader> bad_target_path_header =
      IdmapHeader::FromBinaryStream(bad_target_path_stream);
  ASSERT_THAT(bad_target_path_header, NotNull());
  ASSERT_NE(header->GetTargetPath(), bad_target_path_header->GetTargetPath());
  ASSERT_FALSE(bad_target_path_header->IsUpToDate(error));

  // overlay path: bytes (0x110, 0x20f)
  std::string bad_overlay_path_string(stream.str());
  bad_overlay_path_string[0x110] = '\0';
  std::stringstream bad_overlay_path_stream(bad_overlay_path_string);
  std::unique_ptr<const IdmapHeader> bad_overlay_path_header =
      IdmapHeader::FromBinaryStream(bad_overlay_path_stream);
  ASSERT_THAT(bad_overlay_path_header, NotNull());
  ASSERT_NE(header->GetOverlayPath(), bad_overlay_path_header->GetOverlayPath());
  ASSERT_FALSE(bad_overlay_path_header->IsUpToDate(error));
}

class TestVisitor : public Visitor {
 public:
  explicit TestVisitor(std::ostream& stream) : stream_(stream) {
  }

  void visit(const Idmap& idmap ATTRIBUTE_UNUSED) override {
    stream_ << "TestVisitor::visit(Idmap)" << std::endl;
  }

  void visit(const IdmapHeader& idmap ATTRIBUTE_UNUSED) override {
    stream_ << "TestVisitor::visit(IdmapHeader)" << std::endl;
  }

  void visit(const IdmapData& idmap ATTRIBUTE_UNUSED) override {
    stream_ << "TestVisitor::visit(IdmapData)" << std::endl;
  }

  void visit(const IdmapData::Header& idmap ATTRIBUTE_UNUSED) override {
    stream_ << "TestVisitor::visit(IdmapData::Header)" << std::endl;
  }

  void visit(const IdmapData::TypeEntry& idmap ATTRIBUTE_UNUSED) override {
    stream_ << "TestVisitor::visit(IdmapData::TypeEntry)" << std::endl;
  }

 private:
  std::ostream& stream_;
};

TEST(IdmapTests, TestVisitor) {
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data), idmap_raw_data_len);
  std::istringstream stream(raw);

  std::stringstream error;
  std::unique_ptr<const Idmap> idmap = Idmap::FromBinaryStream(stream, error);
  ASSERT_THAT(idmap, NotNull());

  std::stringstream test_stream;
  TestVisitor visitor(test_stream);
  idmap->accept(&visitor);

  ASSERT_EQ(test_stream.str(),
            "TestVisitor::visit(Idmap)\n"
            "TestVisitor::visit(IdmapHeader)\n"
            "TestVisitor::visit(IdmapData)\n"
            "TestVisitor::visit(IdmapData::Header)\n"
            "TestVisitor::visit(IdmapData::TypeEntry)\n"
            "TestVisitor::visit(IdmapData::TypeEntry)\n");
}

}  // namespace android::idmap2
