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
#include <memory>
#include <regex>
#include <sstream>
#include <string>

#include "TestConstants.h"
#include "TestHelpers.h"
#include "android-base/stringprintf.h"
#include "androidfw/ResourceTypes.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "idmap2/Idmap.h"
#include "idmap2/RawPrintVisitor.h"

using android::base::StringPrintf;

using PolicyFlags = android::ResTable_overlayable_policy_header::PolicyFlags;

namespace android::idmap2 {

#define ASSERT_CONTAINS_REGEX(pattern, str)                         \
  do {                                                              \
    ASSERT_TRUE(std::regex_search(str, std::regex(pattern)))        \
        << "pattern '" << (pattern) << "' not found in\n--------\n" \
        << (str) << "--------";                                     \
  } while (0)

#define ADDRESS "[0-9a-f]{8}: "

TEST(RawPrintVisitorTests, CreateRawPrintVisitor) {
  fclose(stderr);  // silence expected warnings

  const std::string target_apk_path(GetTestDataPath() + "/target/target.apk");
  auto target = TargetResourceContainer::FromPath(target_apk_path);
  ASSERT_TRUE(target);

  const std::string overlay_apk_path(GetTestDataPath() + "/overlay/overlay.apk");
  auto overlay = OverlayResourceContainer::FromPath(overlay_apk_path);
  ASSERT_TRUE(overlay);

  const auto idmap = Idmap::FromContainers(**target, **overlay, TestConstants::OVERLAY_NAME_DEFAULT,
                                           PolicyFlags::PUBLIC, /* enforce_overlayable */ true);
  ASSERT_TRUE(idmap);

  std::stringstream stream;
  RawPrintVisitor visitor(stream);
  (*idmap)->accept(&visitor);

  ASSERT_CONTAINS_REGEX(ADDRESS "504d4449  magic\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00000008  version\n", stream.str());
  ASSERT_CONTAINS_REGEX(
      StringPrintf(ADDRESS "%s  target crc\n", android::idmap2::TestConstants::TARGET_CRC_STRING),
      stream.str());
  ASSERT_CONTAINS_REGEX(
      StringPrintf(ADDRESS "%s  overlay crc\n", android::idmap2::TestConstants::OVERLAY_CRC_STRING),
      stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00000001  fulfilled policies: public\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00000001  enforce overlayable\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00000004  target entry count", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00000000  target inline entry count", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00000004  overlay entry count", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "0000000a  string pool index offset", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f010000  target id: integer/int1", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f010000  overlay id: integer/int1", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f02000e  target id: string/str1", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f02000b  overlay id: string/str1", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f020010  target id: string/str3", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f02000c  overlay id: string/str3", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f020011  target id: string/str4", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f02000d  overlay id: string/str4", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f010000  overlay id: integer/int1", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f010000  target id: integer/int1", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f02000b  overlay id: string/str1", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f02000e  target id: string/str1", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f02000c  overlay id: string/str3", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f020010  target id: string/str3", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f02000d  overlay id: string/str4", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f020011  target id: string/str4", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "000000b4  string pool size", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "........  string pool", stream.str());
}

TEST(RawPrintVisitorTests, CreateRawPrintVisitorWithoutAccessToApks) {
  fclose(stderr);  // silence expected warnings from libandroidfw

  std::string raw(reinterpret_cast<const char*>(kIdmapRawData), kIdmapRawDataLen);
  std::istringstream raw_stream(raw);

  const auto idmap = Idmap::FromBinaryStream(raw_stream);
  ASSERT_TRUE(idmap);

  std::stringstream stream;
  RawPrintVisitor visitor(stream);
  (*idmap)->accept(&visitor);

  ASSERT_CONTAINS_REGEX(ADDRESS "504d4449  magic\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00000008  version\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00001234  target crc\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00005678  overlay crc\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00000011  fulfilled policies: public|signature\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00000001  enforce overlayable\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "0000000b  target path size\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "........  target path: targetX.apk\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "0000000c  overlay path size\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "........  overlay path: overlayX.apk\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "0000000b  overlay name size\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "........  overlay name: OverlayName\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00000003  target entry count\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00000001  target inline entry count\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00000003  overlay entry count\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00000000  string pool index offset\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f020000  target id\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f020000  overlay id\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f020000  target id\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "      11  type: integer\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "12345678  data\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f020000  overlay id\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "7f030002  target id\n", stream.str());
  ASSERT_CONTAINS_REGEX(ADDRESS "00000004  string pool size\n", stream.str());
  ASSERT_CONTAINS_REGEX("000000a4: ........  string pool\n", stream.str());
}

}  // namespace android::idmap2
