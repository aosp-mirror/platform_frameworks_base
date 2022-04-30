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

#include "Dump.h"

#include "LoadedApk.h"
#include "io/StringStream.h"
#include "test/Test.h"
#include "text/Printer.h"

using ::aapt::io::StringOutputStream;
using ::aapt::text::Printer;
using testing::Eq;
using testing::Ne;

namespace aapt {

using DumpTest = CommandTestFixture;

class NoopDiagnostics : public IDiagnostics {
 public:
  void Log(Level level, DiagMessageActual& actualMsg) override {
  }
};
static NoopDiagnostics noop_diag;

void DumpBadgingToString(LoadedApk* loaded_apk, std::string* output, bool include_meta_data = false,
                         bool only_permissions = false) {
  StringOutputStream output_stream(output);
  Printer printer(&output_stream);

  DumpBadgingCommand command(&printer, &noop_diag);
  command.SetIncludeMetaData(include_meta_data);
  command.SetOnlyPermissions(only_permissions);
  ASSERT_EQ(command.Dump(loaded_apk), 0);
  output_stream.Flush();
}

TEST_F(DumpTest, DumpBadging) {
  auto apk_path = file::BuildPath(
      {android::base::GetExecutableDirectory(), "integration-tests", "DumpTest", "minimal.apk"});
  auto loaded_apk = LoadedApk::LoadApkFromPath(apk_path, &noop_diag);

  std::string output;
  DumpBadgingToString(loaded_apk.get(), &output);

  std::string expected;
  auto expected_path = file::BuildPath({android::base::GetExecutableDirectory(),
                                        "integration-tests", "DumpTest", "minimal_expected.txt"});
  ::android::base::ReadFileToString(expected_path, &expected);
  ASSERT_EQ(output, expected);
}

TEST_F(DumpTest, DumpBadgingAllComponents) {
  auto apk_path = file::BuildPath(
      {android::base::GetExecutableDirectory(), "integration-tests", "DumpTest", "components.apk"});
  auto loaded_apk = LoadedApk::LoadApkFromPath(apk_path, &noop_diag);

  std::string output;
  DumpBadgingToString(loaded_apk.get(), &output, /* include_meta_data= */ true);

  std::string expected;
  auto expected_path =
      file::BuildPath({android::base::GetExecutableDirectory(), "integration-tests", "DumpTest",
                       "components_expected.txt"});
  ::android::base::ReadFileToString(expected_path, &expected);
  ASSERT_EQ(output, expected);
}

TEST_F(DumpTest, DumpBadgingPermissionsOnly) {
  auto apk_path = file::BuildPath(
      {android::base::GetExecutableDirectory(), "integration-tests", "DumpTest", "components.apk"});
  auto loaded_apk = LoadedApk::LoadApkFromPath(apk_path, &noop_diag);

  std::string output;
  DumpBadgingToString(loaded_apk.get(), &output, /* include_meta_data= */ false,
                      /* only_permissions= */ true);

  std::string expected;
  auto expected_path =
      file::BuildPath({android::base::GetExecutableDirectory(), "integration-tests", "DumpTest",
                       "components_permissions_expected.txt"});
  ::android::base::ReadFileToString(expected_path, &expected);
  ASSERT_EQ(output, expected);
}

}  // namespace aapt