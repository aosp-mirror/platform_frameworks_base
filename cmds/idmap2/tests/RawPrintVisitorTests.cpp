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
#include <sstream>
#include <string>

#include "gmock/gmock.h"
#include "gtest/gtest.h"

#include "idmap2/Idmap.h"
#include "idmap2/RawPrintVisitor.h"

#include "TestHelpers.h"

using ::testing::NotNull;

namespace android::idmap2 {

TEST(RawPrintVisitorTests, CreateRawPrintVisitor) {
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

  std::stringstream stream;
  RawPrintVisitor visitor(stream);
  idmap->accept(&visitor);

  ASSERT_NE(stream.str().find("00000000: 504d4449  magic\n"), std::string::npos);
  ASSERT_NE(stream.str().find("00000004: 00000001  version\n"), std::string::npos);
  ASSERT_NE(stream.str().find("00000008: ab7cf70d  target crc\n"), std::string::npos);
  ASSERT_NE(stream.str().find("0000000c: d470336b  overlay crc\n"), std::string::npos);
  ASSERT_NE(stream.str().find("0000021c: 00000000  0x7f010000 -> 0x7f010000 integer/int1\n"),
            std::string::npos);
}

TEST(RawPrintVisitorTests, CreateRawPrintVisitorWithoutAccessToApks) {
  fclose(stderr);  // silence expected warnings from libandroidfw

  std::string raw(reinterpret_cast<const char*>(idmap_raw_data), idmap_raw_data_len);
  std::istringstream raw_stream(raw);

  std::stringstream error;
  std::unique_ptr<const Idmap> idmap = Idmap::FromBinaryStream(raw_stream, error);
  ASSERT_THAT(idmap, NotNull());

  std::stringstream stream;
  RawPrintVisitor visitor(stream);
  idmap->accept(&visitor);

  ASSERT_NE(stream.str().find("00000000: 504d4449  magic\n"), std::string::npos);
  ASSERT_NE(stream.str().find("00000004: 00000001  version\n"), std::string::npos);
  ASSERT_NE(stream.str().find("00000008: 00001234  target crc\n"), std::string::npos);
  ASSERT_NE(stream.str().find("0000000c: 00005678  overlay crc\n"), std::string::npos);
  ASSERT_NE(stream.str().find("0000021c: 00000000  0x7f020000 -> 0x7f020000\n"), std::string::npos);
}

}  // namespace android::idmap2
