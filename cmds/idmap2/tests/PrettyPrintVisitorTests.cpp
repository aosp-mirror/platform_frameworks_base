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
#include <string>

#include "R.h"
#include "TestConstants.h"
#include "TestHelpers.h"
#include "androidfw/ApkAssets.h"
#include "androidfw/ResourceTypes.h"
#include "gmock/gmock.h"
#include "gtest/gtest.h"
#include "idmap2/Idmap.h"
#include "idmap2/PrettyPrintVisitor.h"

using android::base::StringPrintf;

using PolicyFlags = android::ResTable_overlayable_policy_header::PolicyFlags;

namespace android::idmap2 {

TEST(PrettyPrintVisitorTests, CreatePrettyPrintVisitor) {
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
  PrettyPrintVisitor visitor(stream);
  (*idmap)->accept(&visitor);

  ASSERT_NE(stream.str().find("target path  : "), std::string::npos);
  ASSERT_NE(stream.str().find("overlay path : "), std::string::npos);
  ASSERT_NE(stream.str().find(StringPrintf("0x%08x -> 0x%08x (integer/int1 -> integer/int1)\n",
                                           R::target::integer::int1, R::overlay::integer::int1)),
            std::string::npos);
}

TEST(PrettyPrintVisitorTests, CreatePrettyPrintVisitorWithoutAccessToApks) {
  fclose(stderr);  // silence expected warnings from libandroidfw

  std::string raw(reinterpret_cast<const char*>(kIdmapRawData), kIdmapRawDataLen);
  std::istringstream raw_stream(raw);

  const auto idmap = Idmap::FromBinaryStream(raw_stream);
  ASSERT_TRUE(idmap);

  std::stringstream stream;
  PrettyPrintVisitor visitor(stream);
  (*idmap)->accept(&visitor);

  ASSERT_NE(stream.str().find("target path  : "), std::string::npos);
  ASSERT_NE(stream.str().find("overlay path : "), std::string::npos);
  ASSERT_NE(stream.str().find("0x7f020000 -> 0x7f020000 (\?\?\? -> \?\?\?)\n"), std::string::npos);
}

}  // namespace android::idmap2
