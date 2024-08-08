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

#include "LoadedApk.h"
#include "cmd/Dump.h"
#include "io/StringStream.h"
#include "test/Test.h"
#include "text/Printer.h"

using ::aapt::io::StringOutputStream;
using ::aapt::text::Printer;
using testing::Eq;
using testing::Ne;

namespace aapt {

using FlaggedResourcesTest = CommandTestFixture;

static android::NoOpDiagnostics noop_diag;

void DumpStringPoolToString(LoadedApk* loaded_apk, std::string* output) {
  StringOutputStream output_stream(output);
  Printer printer(&output_stream);

  DumpStringsCommand command(&printer, &noop_diag);
  ASSERT_EQ(command.Dump(loaded_apk), 0);
  output_stream.Flush();
}

TEST_F(FlaggedResourcesTest, DisabledStringRemoved) {
  auto apk_path = file::BuildPath({android::base::GetExecutableDirectory(), "resapp.apk"});
  auto loaded_apk = LoadedApk::LoadApkFromPath(apk_path, &noop_diag);

  std::string output;
  DumpStringPoolToString(loaded_apk.get(), &output);

  std::string excluded = "DONTFIND";
  ASSERT_EQ(output.find(excluded), std::string::npos);
}

}  // namespace aapt
