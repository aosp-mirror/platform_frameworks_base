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

#include "idmap2/Idmap.h"
#include "idmap2/Policies.h"
#include "idmap2/PrettyPrintVisitor.h"

#include "TestHelpers.h"

using ::testing::NotNull;

using android::ApkAssets;
using android::idmap2::PolicyBitmask;

namespace android::idmap2 {

TEST(PrettyPrintVisitorTests, CreatePrettyPrintVisitor) {
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
  PrettyPrintVisitor visitor(stream);
  (*idmap)->accept(&visitor);

  ASSERT_NE(stream.str().find("target apk path  : "), std::string::npos);
  ASSERT_NE(stream.str().find("overlay apk path : "), std::string::npos);
  ASSERT_NE(stream.str().find("0x7f010000 -> 0x7f010000 integer/int1\n"), std::string::npos);
}

TEST(PrettyPrintVisitorTests, CreatePrettyPrintVisitorWithoutAccessToApks) {
  fclose(stderr);  // silence expected warnings from libandroidfw

  std::string raw(reinterpret_cast<const char*>(idmap_raw_data), idmap_raw_data_len);
  std::istringstream raw_stream(raw);

  const auto idmap = Idmap::FromBinaryStream(raw_stream);
  ASSERT_TRUE(idmap);

  std::stringstream stream;
  PrettyPrintVisitor visitor(stream);
  (*idmap)->accept(&visitor);

  ASSERT_NE(stream.str().find("target apk path  : "), std::string::npos);
  ASSERT_NE(stream.str().find("overlay apk path : "), std::string::npos);
  ASSERT_NE(stream.str().find("0x7f020000 -> 0x7f020000\n"), std::string::npos);
}

}  // namespace android::idmap2
