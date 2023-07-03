/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "ApkInfo.h"

#include "ApkInfo.pb.h"
#include "LoadedApk.h"
#include "android-base/unique_fd.h"
#include "io/StringStream.h"
#include "test/Test.h"

using testing::Eq;
using testing::Ne;

namespace aapt {

using ApkInfoTest = CommandTestFixture;

void AssertProducedAndExpectedInfo(const std::string& produced_path,
                                   const std::string& expected_path) {
  android::base::unique_fd fd(open(produced_path.c_str(), O_RDONLY));
  ASSERT_NE(fd.get(), -1);

  pb::ApkInfo produced_apk_info;
  produced_apk_info.ParseFromFileDescriptor(fd.get());

  std::string expected;
  ::android::base::ReadFileToString(expected_path, &expected);

  EXPECT_EQ(produced_apk_info.DebugString(), expected);
}

static android::NoOpDiagnostics noop_diag;

TEST_F(ApkInfoTest, ApkInfoWithBadging) {
  auto apk_path = file::BuildPath(
      {android::base::GetExecutableDirectory(), "integration-tests", "DumpTest", "components.apk"});
  auto out_info_path = GetTestPath("apk_info.pb");

  ApkInfoCommand command(&noop_diag);
  command.Execute({"-o", out_info_path, apk_path}, &std::cerr);

  auto expected_path =
      file::BuildPath({android::base::GetExecutableDirectory(), "integration-tests", "DumpTest",
                       "components_expected_proto.txt"});
  AssertProducedAndExpectedInfo(out_info_path, expected_path);
}

TEST_F(ApkInfoTest, FullApkInfo) {
  auto apk_path = file::BuildPath(
      {android::base::GetExecutableDirectory(), "integration-tests", "DumpTest", "components.apk"});
  auto out_info_path = GetTestPath("apk_info.pb");

  ApkInfoCommand command(&noop_diag);
  command.Execute({"-o", out_info_path, "--include-resource-table", "--include-xml",
                   "AndroidManifest.xml", "--include-xml", "res/oy.xml", apk_path},
                  &std::cerr);

  auto expected_path =
      file::BuildPath({android::base::GetExecutableDirectory(), "integration-tests", "DumpTest",
                       "components_full_proto.txt"});
  AssertProducedAndExpectedInfo(out_info_path, expected_path);
}

}  // namespace aapt
