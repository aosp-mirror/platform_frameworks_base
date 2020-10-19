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
#include <utility>
#include <vector>

#include "R.h"
#include "TestConstants.h"
#include "TestHelpers.h"
#include "android-base/macros.h"
#include "androidfw/ApkAssets.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "idmap2/BinaryStreamVisitor.h"
#include "idmap2/CommandLineOptions.h"
#include "idmap2/Idmap.h"
#include "idmap2/LogInfo.h"

using android::Res_value;
using ::testing::IsNull;
using ::testing::NotNull;

using PolicyFlags = android::ResTable_overlayable_policy_header::PolicyFlags;

namespace android::idmap2 {

#define ASSERT_TARGET_ENTRY(entry, target_resid, overlay_resid) \
  ASSERT_EQ((entry).target_id, (target_resid));                 \
  ASSERT_EQ((entry).overlay_id, (overlay_resid))

#define ASSERT_TARGET_INLINE_ENTRY(entry, target_resid, expected_type, expected_value) \
  ASSERT_EQ((entry).target_id, target_resid);                                          \
  ASSERT_EQ((entry).value.data_type, (expected_type));                                 \
  ASSERT_EQ((entry).value.data_value, (expected_value))

#define ASSERT_OVERLAY_ENTRY(entry, overlay_resid, target_resid) \
  ASSERT_EQ((entry).overlay_id, (overlay_resid));                \
  ASSERT_EQ((entry).target_id, (target_resid))

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
  ASSERT_EQ(header->GetVersion(), 0x05U);
  ASSERT_EQ(header->GetTargetCrc(), 0x1234U);
  ASSERT_EQ(header->GetOverlayCrc(), 0x5678U);
  ASSERT_EQ(header->GetFulfilledPolicies(), 0x11);
  ASSERT_EQ(header->GetEnforceOverlayable(), true);
  ASSERT_EQ(header->GetTargetPath().to_string(), "targetX.apk");
  ASSERT_EQ(header->GetOverlayPath().to_string(), "overlayX.apk");
  ASSERT_EQ(header->GetDebugInfo(), "debug");
}

TEST(IdmapTests, FailToCreateIdmapHeaderFromBinaryStreamIfPathTooLong) {
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data), idmap_raw_data_len);
  // overwrite the target path string, including the terminating null, with '.'
  for (size_t i = 0x18; i < 0x118; i++) {
    raw[i] = '.';
  }
  std::istringstream stream(raw);
  std::unique_ptr<const IdmapHeader> header = IdmapHeader::FromBinaryStream(stream);
  ASSERT_THAT(header, IsNull());
}

TEST(IdmapTests, CreateIdmapDataHeaderFromBinaryStream) {
  const size_t offset = 0x224;
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data + offset),
                  idmap_raw_data_len - offset);
  std::istringstream stream(raw);

  std::unique_ptr<const IdmapData::Header> header = IdmapData::Header::FromBinaryStream(stream);
  ASSERT_THAT(header, NotNull());
  ASSERT_EQ(header->GetTargetEntryCount(), 0x03);
  ASSERT_EQ(header->GetOverlayEntryCount(), 0x03);
}

TEST(IdmapTests, CreateIdmapDataFromBinaryStream) {
  const size_t offset = 0x224;
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data + offset),
                  idmap_raw_data_len - offset);
  std::istringstream stream(raw);

  std::unique_ptr<const IdmapData> data = IdmapData::FromBinaryStream(stream);
  ASSERT_THAT(data, NotNull());

  const auto& target_entries = data->GetTargetEntries();
  ASSERT_EQ(target_entries.size(), 3U);
  ASSERT_TARGET_ENTRY(target_entries[0], 0x7f020000, 0x7f020000);
  ASSERT_TARGET_ENTRY(target_entries[1], 0x7f030000, 0x7f030000);
  ASSERT_TARGET_ENTRY(target_entries[2], 0x7f030002, 0x7f030001);

  const auto& target_inline_entries = data->GetTargetInlineEntries();
  ASSERT_EQ(target_inline_entries.size(), 1U);
  ASSERT_TARGET_INLINE_ENTRY(target_inline_entries[0], 0x7f040000, Res_value::TYPE_INT_HEX,
                             0x12345678);

  const auto& overlay_entries = data->GetOverlayEntries();
  ASSERT_EQ(target_entries.size(), 3U);
  ASSERT_OVERLAY_ENTRY(overlay_entries[0], 0x7f020000, 0x7f020000);
  ASSERT_OVERLAY_ENTRY(overlay_entries[1], 0x7f030000, 0x7f030000);
  ASSERT_OVERLAY_ENTRY(overlay_entries[2], 0x7f030001, 0x7f030002);
}

TEST(IdmapTests, CreateIdmapFromBinaryStream) {
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data), idmap_raw_data_len);
  std::istringstream stream(raw);

  auto result = Idmap::FromBinaryStream(stream);
  ASSERT_TRUE(result);
  const auto idmap = std::move(*result);

  ASSERT_THAT(idmap->GetHeader(), NotNull());
  ASSERT_EQ(idmap->GetHeader()->GetMagic(), 0x504d4449U);
  ASSERT_EQ(idmap->GetHeader()->GetVersion(), 0x05U);
  ASSERT_EQ(idmap->GetHeader()->GetTargetCrc(), 0x1234U);
  ASSERT_EQ(idmap->GetHeader()->GetOverlayCrc(), 0x5678U);
  ASSERT_EQ(idmap->GetHeader()->GetFulfilledPolicies(), 0x11);
  ASSERT_EQ(idmap->GetHeader()->GetEnforceOverlayable(), true);
  ASSERT_EQ(idmap->GetHeader()->GetTargetPath().to_string(), "targetX.apk");
  ASSERT_EQ(idmap->GetHeader()->GetOverlayPath().to_string(), "overlayX.apk");

  const std::vector<std::unique_ptr<const IdmapData>>& dataBlocks = idmap->GetData();
  ASSERT_EQ(dataBlocks.size(), 1U);

  const std::unique_ptr<const IdmapData>& data = dataBlocks[0];
  ASSERT_THAT(data, NotNull());

  const auto& target_entries = data->GetTargetEntries();
  ASSERT_EQ(target_entries.size(), 3U);
  ASSERT_TARGET_ENTRY(target_entries[0], 0x7f020000, 0x7f020000);
  ASSERT_TARGET_ENTRY(target_entries[1], 0x7f030000, 0x7f030000);
  ASSERT_TARGET_ENTRY(target_entries[2], 0x7f030002, 0x7f030001);

  const auto& target_inline_entries = data->GetTargetInlineEntries();
  ASSERT_EQ(target_inline_entries.size(), 1U);
  ASSERT_TARGET_INLINE_ENTRY(target_inline_entries[0], 0x7f040000, Res_value::TYPE_INT_HEX,
                             0x12345678);

  const auto& overlay_entries = data->GetOverlayEntries();
  ASSERT_EQ(target_entries.size(), 3U);
  ASSERT_OVERLAY_ENTRY(overlay_entries[0], 0x7f020000, 0x7f020000);
  ASSERT_OVERLAY_ENTRY(overlay_entries[1], 0x7f030000, 0x7f030000);
  ASSERT_OVERLAY_ENTRY(overlay_entries[2], 0x7f030001, 0x7f030002);
}

TEST(IdmapTests, GracefullyFailToCreateIdmapFromCorruptBinaryStream) {
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data),
                  10);  // data too small
  std::istringstream stream(raw);

  const auto result = Idmap::FromBinaryStream(stream);
  ASSERT_FALSE(result);
}

TEST(IdmapTests, CreateIdmapHeaderFromApkAssets) {
  std::string target_apk_path = GetTestDataPath() + "/target/target.apk";
  std::string overlay_apk_path = GetTestDataPath() + "/overlay/overlay.apk";

  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  ASSERT_THAT(target_apk, NotNull());

  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  ASSERT_THAT(overlay_apk, NotNull());

  auto idmap_result = Idmap::FromApkAssets(*target_apk, *overlay_apk, PolicyFlags::PUBLIC,
                                           /* enforce_overlayable */ true);
  ASSERT_TRUE(idmap_result) << idmap_result.GetErrorMessage();
  auto& idmap = *idmap_result;
  ASSERT_THAT(idmap, NotNull());

  ASSERT_THAT(idmap->GetHeader(), NotNull());
  ASSERT_EQ(idmap->GetHeader()->GetMagic(), 0x504d4449U);
  ASSERT_EQ(idmap->GetHeader()->GetVersion(), 0x05U);
  ASSERT_EQ(idmap->GetHeader()->GetTargetCrc(), android::idmap2::TestConstants::TARGET_CRC);
  ASSERT_EQ(idmap->GetHeader()->GetOverlayCrc(), android::idmap2::TestConstants::OVERLAY_CRC);
  ASSERT_EQ(idmap->GetHeader()->GetFulfilledPolicies(), PolicyFlags::PUBLIC);
  ASSERT_EQ(idmap->GetHeader()->GetEnforceOverlayable(), true);
  ASSERT_EQ(idmap->GetHeader()->GetTargetPath().to_string(), target_apk_path);
  ASSERT_EQ(idmap->GetHeader()->GetOverlayPath(), overlay_apk_path);
}

Result<std::unique_ptr<const IdmapData>> TestIdmapDataFromApkAssets(
    const android::StringPiece& local_target_apk_path,
    const android::StringPiece& local_overlay_apk_path, const OverlayManifestInfo& overlay_info,
    const PolicyBitmask& fulfilled_policies, bool enforce_overlayable) {
  const std::string target_apk_path(GetTestDataPath() + local_target_apk_path.data());
  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  if (!target_apk) {
    return Error(R"(Failed to load target apk "%s")", target_apk_path.data());
  }

  const std::string overlay_apk_path(GetTestDataPath() + local_overlay_apk_path.data());
  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  if (!overlay_apk) {
    return Error(R"(Failed to load overlay apk "%s")", overlay_apk_path.data());
  }

  LogInfo log_info;
  auto mapping = ResourceMapping::FromApkAssets(*target_apk, *overlay_apk, overlay_info,
                                                fulfilled_policies, enforce_overlayable, log_info);

  if (!mapping) {
    return mapping.GetError();
  }

  return IdmapData::FromResourceMapping(*mapping);
}

TEST(IdmapTests, CreateIdmapDataFromApkAssets) {
  std::string target_apk_path = GetTestDataPath() + "/target/target.apk";
  std::string overlay_apk_path = GetTestDataPath() + "/overlay/overlay.apk";

  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  ASSERT_THAT(target_apk, NotNull());

  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  ASSERT_THAT(overlay_apk, NotNull());

  auto idmap_result = Idmap::FromApkAssets(*target_apk, *overlay_apk, PolicyFlags::PUBLIC,
                                           /* enforce_overlayable */ true);
  ASSERT_TRUE(idmap_result) << idmap_result.GetErrorMessage();
  auto& idmap = *idmap_result;
  ASSERT_THAT(idmap, NotNull());

  const std::vector<std::unique_ptr<const IdmapData>>& dataBlocks = idmap->GetData();
  ASSERT_EQ(dataBlocks.size(), 1U);

  const std::unique_ptr<const IdmapData>& data = dataBlocks[0];
  ASSERT_THAT(data, NotNull());

  const auto& target_entries = data->GetTargetEntries();
  ASSERT_EQ(target_entries.size(), 4U);
  ASSERT_TARGET_ENTRY(target_entries[0], R::target::integer::int1, R::overlay::integer::int1);
  ASSERT_TARGET_ENTRY(target_entries[1], R::target::string::str1, R::overlay::string::str1);
  ASSERT_TARGET_ENTRY(target_entries[2], R::target::string::str3, R::overlay::string::str3);
  ASSERT_TARGET_ENTRY(target_entries[3], R::target::string::str4, R::overlay::string::str4);

  const auto& target_inline_entries = data->GetTargetInlineEntries();
  ASSERT_EQ(target_inline_entries.size(), 0U);

  const auto& overlay_entries = data->GetOverlayEntries();
  ASSERT_EQ(target_entries.size(), 4U);
  ASSERT_OVERLAY_ENTRY(overlay_entries[0], R::overlay::integer::int1, R::target::integer::int1);
  ASSERT_OVERLAY_ENTRY(overlay_entries[1], R::overlay::string::str1, R::target::string::str1);
  ASSERT_OVERLAY_ENTRY(overlay_entries[2], R::overlay::string::str3, R::target::string::str3);
  ASSERT_OVERLAY_ENTRY(overlay_entries[3], R::overlay::string::str4, R::target::string::str4);
}

TEST(IdmapTests, CreateIdmapDataFromApkAssetsSharedLibOverlay) {
  std::string target_apk_path = GetTestDataPath() + "/target/target.apk";
  std::string overlay_apk_path = GetTestDataPath() + "/overlay/overlay-shared.apk";

  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  ASSERT_THAT(target_apk, NotNull());

  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  ASSERT_THAT(overlay_apk, NotNull());

  auto idmap_result = Idmap::FromApkAssets(*target_apk, *overlay_apk, PolicyFlags::PUBLIC,
                                           /* enforce_overlayable */ true);
  ASSERT_TRUE(idmap_result) << idmap_result.GetErrorMessage();
  auto& idmap = *idmap_result;
  ASSERT_THAT(idmap, NotNull());

  const std::vector<std::unique_ptr<const IdmapData>>& dataBlocks = idmap->GetData();
  ASSERT_EQ(dataBlocks.size(), 1U);

  const std::unique_ptr<const IdmapData>& data = dataBlocks[0];
  ASSERT_THAT(data, NotNull());

  const auto& target_entries = data->GetTargetEntries();
  ASSERT_EQ(target_entries.size(), 4U);
  ASSERT_TARGET_ENTRY(target_entries[0], R::target::integer::int1,
                      R::overlay_shared::integer::int1);
  ASSERT_TARGET_ENTRY(target_entries[1], R::target::string::str1, R::overlay_shared::string::str1);
  ASSERT_TARGET_ENTRY(target_entries[2], R::target::string::str3, R::overlay_shared::string::str3);
  ASSERT_TARGET_ENTRY(target_entries[3], R::target::string::str4, R::overlay_shared::string::str4);

  const auto& target_inline_entries = data->GetTargetInlineEntries();
  ASSERT_EQ(target_inline_entries.size(), 0U);

  const auto& overlay_entries = data->GetOverlayEntries();
  ASSERT_EQ(target_entries.size(), 4U);
  ASSERT_OVERLAY_ENTRY(overlay_entries[0], R::overlay_shared::integer::int1,
                       R::target::integer::int1);
  ASSERT_OVERLAY_ENTRY(overlay_entries[1], R::overlay_shared::string::str1,
                       R::target::string::str1);
  ASSERT_OVERLAY_ENTRY(overlay_entries[2], R::overlay_shared::string::str3,
                       R::target::string::str3);
  ASSERT_OVERLAY_ENTRY(overlay_entries[3], R::overlay_shared::string::str4,
                       R::target::string::str4);
}

TEST(IdmapTests, CreateIdmapDataDoNotRewriteNonOverlayResourceId) {
  OverlayManifestInfo info{};
  info.target_package = "test.target";
  info.target_name = "TestResources";
  info.resource_mapping = 0x7f030001;  // xml/overlays_different_packages
  auto idmap_data = TestIdmapDataFromApkAssets("/target/target.apk", "/overlay/overlay.apk", info,
                                               PolicyFlags::PUBLIC,
                                               /* enforce_overlayable */ false);

  ASSERT_TRUE(idmap_data) << idmap_data.GetErrorMessage();
  auto& data = *idmap_data;

  const auto& target_entries = data->GetTargetEntries();
  ASSERT_EQ(target_entries.size(), 2U);
  ASSERT_TARGET_ENTRY(target_entries[0], R::target::string::str1,
                      0x0104000a);  // -> android:string/ok
  ASSERT_TARGET_ENTRY(target_entries[1], R::target::string::str3, R::overlay::string::str3);

  const auto& target_inline_entries = data->GetTargetInlineEntries();
  ASSERT_EQ(target_inline_entries.size(), 0U);

  const auto& overlay_entries = data->GetOverlayEntries();
  ASSERT_EQ(overlay_entries.size(), 1U);
  ASSERT_OVERLAY_ENTRY(overlay_entries[0], R::overlay::string::str3, R::target::string::str3);
}

TEST(IdmapTests, CreateIdmapDataInlineResources) {
  OverlayManifestInfo info{};
  info.target_package = "test.target";
  info.target_name = "TestResources";
  info.resource_mapping = 0x7f030002;  // xml/overlays_inline
  auto idmap_data = TestIdmapDataFromApkAssets("/target/target.apk", "/overlay/overlay.apk", info,
                                               PolicyFlags::PUBLIC,
                                               /* enforce_overlayable */ false);

  ASSERT_TRUE(idmap_data) << idmap_data.GetErrorMessage();
  auto& data = *idmap_data;

  const auto& target_entries = data->GetTargetEntries();
  ASSERT_EQ(target_entries.size(), 0U);

  constexpr size_t overlay_string_pool_size = 8U;
  const auto& target_inline_entries = data->GetTargetInlineEntries();
  ASSERT_EQ(target_inline_entries.size(), 2U);
  ASSERT_TARGET_INLINE_ENTRY(target_inline_entries[0], R::target::integer::int1,
                             Res_value::TYPE_INT_DEC, 73U);  // -> 73
  ASSERT_TARGET_INLINE_ENTRY(target_inline_entries[1], R::target::string::str1,
                             Res_value::TYPE_STRING,
                             overlay_string_pool_size + 0U);  // -> "Hello World"

  const auto& overlay_entries = data->GetOverlayEntries();
  ASSERT_EQ(overlay_entries.size(), 0U);
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

  const auto result = Idmap::FromApkAssets(*target_apk, *overlay_apk, PolicyFlags::PUBLIC,
                                           /* enforce_overlayable */ true);
  ASSERT_FALSE(result);
}

TEST(IdmapTests, IdmapHeaderIsUpToDate) {
  fclose(stderr);  // silence expected warnings from libandroidfw

  const std::string target_apk_path(GetTestDataPath() + "/target/target.apk");
  std::unique_ptr<const ApkAssets> target_apk = ApkAssets::Load(target_apk_path);
  ASSERT_THAT(target_apk, NotNull());

  const std::string overlay_apk_path(GetTestDataPath() + "/overlay/overlay.apk");
  std::unique_ptr<const ApkAssets> overlay_apk = ApkAssets::Load(overlay_apk_path);
  ASSERT_THAT(overlay_apk, NotNull());

  auto result = Idmap::FromApkAssets(*target_apk, *overlay_apk, PolicyFlags::PUBLIC,
                                     /* enforce_overlayable */ true);
  ASSERT_TRUE(result);
  const auto idmap = std::move(*result);

  std::stringstream stream;
  BinaryStreamVisitor visitor(stream);
  idmap->accept(&visitor);

  std::unique_ptr<const IdmapHeader> header = IdmapHeader::FromBinaryStream(stream);
  ASSERT_THAT(header, NotNull());
  ASSERT_TRUE(header->IsUpToDate(target_apk_path.c_str(), overlay_apk_path.c_str(),
                                 PolicyFlags::PUBLIC, /* enforce_overlayable */ true));

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
  ASSERT_FALSE(bad_magic_header->IsUpToDate(target_apk_path.c_str(), overlay_apk_path.c_str(),
                                            PolicyFlags::PUBLIC, /* enforce_overlayable */ true));

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
  ASSERT_FALSE(bad_magic_header->IsUpToDate(target_apk_path.c_str(), overlay_apk_path.c_str(),
                                            PolicyFlags::PUBLIC, /* enforce_overlayable */ true));

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
  ASSERT_FALSE(bad_magic_header->IsUpToDate(target_apk_path.c_str(), overlay_apk_path.c_str(),
                                            PolicyFlags::PUBLIC, /* enforce_overlayable */ true));

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
  ASSERT_FALSE(bad_magic_header->IsUpToDate(target_apk_path.c_str(), overlay_apk_path.c_str(),
                                            PolicyFlags::PUBLIC, /* enforce_overlayable */ true));

  // fulfilled policy: bytes (0x10, 0x13)
  std::string bad_policy_string(stream.str());
  bad_policy_string[0x10] = '.';
  bad_policy_string[0x11] = '.';
  bad_policy_string[0x12] = '.';
  bad_policy_string[0x13] = '.';
  std::stringstream bad_policy_stream(bad_policy_string);
  std::unique_ptr<const IdmapHeader> bad_policy_header =
      IdmapHeader::FromBinaryStream(bad_policy_stream);
  ASSERT_THAT(bad_policy_header, NotNull());
  ASSERT_NE(header->GetFulfilledPolicies(), bad_policy_header->GetFulfilledPolicies());
  ASSERT_FALSE(bad_policy_header->IsUpToDate(target_apk_path.c_str(), overlay_apk_path.c_str(),
                                             PolicyFlags::PUBLIC, /* enforce_overlayable */ true));

  // enforce overlayable: bytes (0x14)
  std::string bad_enforce_string(stream.str());
  bad_enforce_string[0x14] = '\0';
  std::stringstream bad_enforce_stream(bad_enforce_string);
  std::unique_ptr<const IdmapHeader> bad_enforce_header =
      IdmapHeader::FromBinaryStream(bad_enforce_stream);
  ASSERT_THAT(bad_enforce_header, NotNull());
  ASSERT_NE(header->GetEnforceOverlayable(), bad_enforce_header->GetEnforceOverlayable());
  ASSERT_FALSE(bad_enforce_header->IsUpToDate(target_apk_path.c_str(), overlay_apk_path.c_str(),
                                              PolicyFlags::PUBLIC, /* enforce_overlayable */ true));

  // target path: bytes (0x18, 0x117)
  std::string bad_target_path_string(stream.str());
  bad_target_path_string[0x18] = '\0';
  std::stringstream bad_target_path_stream(bad_target_path_string);
  std::unique_ptr<const IdmapHeader> bad_target_path_header =
      IdmapHeader::FromBinaryStream(bad_target_path_stream);
  ASSERT_THAT(bad_target_path_header, NotNull());
  ASSERT_NE(header->GetTargetPath(), bad_target_path_header->GetTargetPath());
  ASSERT_FALSE(bad_magic_header->IsUpToDate(target_apk_path.c_str(), overlay_apk_path.c_str(),
                                            PolicyFlags::PUBLIC, /* enforce_overlayable */ true));

  // overlay path: bytes (0x118, 0x217)
  std::string bad_overlay_path_string(stream.str());
  bad_overlay_path_string[0x118] = '\0';
  std::stringstream bad_overlay_path_stream(bad_overlay_path_string);
  std::unique_ptr<const IdmapHeader> bad_overlay_path_header =
      IdmapHeader::FromBinaryStream(bad_overlay_path_stream);
  ASSERT_THAT(bad_overlay_path_header, NotNull());
  ASSERT_NE(header->GetOverlayPath(), bad_overlay_path_header->GetOverlayPath());
  ASSERT_FALSE(bad_magic_header->IsUpToDate(target_apk_path.c_str(), overlay_apk_path.c_str(),
                                            PolicyFlags::PUBLIC, /* enforce_overlayable */ true));
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

 private:
  std::ostream& stream_;
};

TEST(IdmapTests, TestVisitor) {
  std::string raw(reinterpret_cast<const char*>(idmap_raw_data), idmap_raw_data_len);
  std::istringstream stream(raw);

  const auto idmap = Idmap::FromBinaryStream(stream);
  ASSERT_TRUE(idmap);

  std::stringstream test_stream;
  TestVisitor visitor(test_stream);
  (*idmap)->accept(&visitor);

  ASSERT_EQ(test_stream.str(),
            "TestVisitor::visit(IdmapHeader)\n"
            "TestVisitor::visit(Idmap)\n"
            "TestVisitor::visit(IdmapData::Header)\n"
            "TestVisitor::visit(IdmapData)\n");
}

}  // namespace android::idmap2
