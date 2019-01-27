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

#include "Convert.h"

#include "LoadedApk.h"
#include "test/Test.h"
#include "ziparchive/zip_archive.h"

using testing::Eq;
using testing::Ne;

namespace aapt {

using ConvertTest = CommandTestFixture;

TEST_F(ConvertTest, RemoveRawXmlStrings) {
  StdErrDiagnostics diag;
  const std::string compiled_files_dir = GetTestPath("compiled");
  ASSERT_TRUE(CompileFile(GetTestPath("res/xml/test.xml"), R"(<Item AgentCode="007"/>)",
                          compiled_files_dir, &diag));

  const std::string out_apk = GetTestPath("out.apk");
  std::vector<std::string> link_args = {
      "--manifest", GetDefaultManifest(),
      "-o", out_apk,
      "--keep-raw-values",
      "--proto-format"
  };

  ASSERT_TRUE(Link(link_args, compiled_files_dir, &diag));

  const std::string out_convert_apk = GetTestPath("out_convert.apk");
  std::vector<android::StringPiece> convert_args = {
      "-o", out_convert_apk,
      "--output-format", "binary",
      out_apk,
  };
  ASSERT_THAT(ConvertCommand().Execute(convert_args, &std::cerr), Eq(0));

  // Load the binary xml tree
  android::ResXMLTree tree;
  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(out_convert_apk, &diag);

  std::unique_ptr<io::IData> data = OpenFileAsData(apk.get(), "res/xml/test.xml");
  ASSERT_THAT(data, Ne(nullptr));

  AssertLoadXml(apk.get(), data.get(), &tree);

  // Check that the raw string index has not been assigned
  EXPECT_THAT(tree.getAttributeValueStringID(0), Eq(-1));
}

TEST_F(ConvertTest, KeepRawXmlStrings) {
  StdErrDiagnostics diag;
  const std::string compiled_files_dir = GetTestPath("compiled");
  ASSERT_TRUE(CompileFile(GetTestPath("res/xml/test.xml"), R"(<Item AgentCode="007"/>)",
                          compiled_files_dir, &diag));

  const std::string out_apk = GetTestPath("out.apk");
  std::vector<std::string> link_args = {
      "--manifest", GetDefaultManifest(),
      "-o", out_apk,
      "--keep-raw-values",
      "--proto-format"
  };

  ASSERT_TRUE(Link(link_args, compiled_files_dir, &diag));

  const std::string out_convert_apk = GetTestPath("out_convert.apk");
  std::vector<android::StringPiece> convert_args = {
      "-o", out_convert_apk,
      "--output-format", "binary",
      "--keep-raw-values",
      out_apk,
  };
  ASSERT_THAT(ConvertCommand().Execute(convert_args, &std::cerr), Eq(0));

  // Load the binary xml tree
  android::ResXMLTree tree;
  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(out_convert_apk, &diag);

  std::unique_ptr<io::IData> data = OpenFileAsData(apk.get(), "res/xml/test.xml");
  ASSERT_THAT(data, Ne(nullptr));

  AssertLoadXml(apk.get(), data.get(), &tree);

  // Check that the raw string index has been set to the correct string pool entry
  int32_t raw_index = tree.getAttributeValueStringID(0);
  ASSERT_THAT(raw_index, Ne(-1));
  EXPECT_THAT(util::GetString(tree.getStrings(), static_cast<size_t>(raw_index)), Eq("007"));
}

TEST_F(ConvertTest, DuplicateEntriesWrittenOnce) {
  StdErrDiagnostics diag;
  const std::string apk_path =
      file::BuildPath({android::base::GetExecutableDirectory(),
                       "integration-tests", "ConvertTest", "duplicate_entries.apk"});

  const std::string out_convert_apk = GetTestPath("out_convert.apk");
  std::vector<android::StringPiece> convert_args = {
      "-o", out_convert_apk,
      "--output-format", "proto",
      apk_path
  };
  ASSERT_THAT(ConvertCommand().Execute(convert_args, &std::cerr), Eq(0));

  ZipArchiveHandle handle;
  ASSERT_THAT(OpenArchive(out_convert_apk.c_str(), &handle), Eq(0));

  void* cookie = nullptr;

  ZipString prefix("res/theme/10");
  int32_t result = StartIteration(handle, &cookie, &prefix, nullptr);

  // If this is -5, that means we've found a duplicate entry and this test has failed
  EXPECT_THAT(result, Eq(0));

  // But if read succeeds, verify only one res/theme/10 entry
  int count = 0;

  // Can't pass nullptrs into Next()
  ZipString zip_name;
  ZipEntry zip_data;

  while ((result = Next(cookie, &zip_data, &zip_name)) == 0) {
    count++;
  }

  EndIteration(cookie);

  EXPECT_THAT(count, Eq(1));
}

}  // namespace aapt