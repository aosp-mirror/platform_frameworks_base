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

#include "Link.h"

#include "LoadedApk.h"
#include "test/Test.h"

using testing::Eq;
using testing::Ne;

namespace aapt {

using LinkTest = CommandTestFixture;

TEST_F(LinkTest, RemoveRawXmlStrings) {
  StdErrDiagnostics diag;
  const std::string compiled_files_dir = GetTestPath("compiled");
  ASSERT_TRUE(CompileFile(GetTestPath("res/xml/test.xml"), R"(<Item AgentCode="007"/>)",
                          compiled_files_dir, &diag));

  const std::string out_apk = GetTestPath("out.apk");
  std::vector<std::string> link_args = {
      "--manifest", GetDefaultManifest(),
      "-o", out_apk,
  };

  ASSERT_TRUE(Link(link_args, compiled_files_dir, &diag));

  // Load the binary xml tree
  android::ResXMLTree tree;
  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(out_apk, &diag);
  std::unique_ptr<io::IData> data = OpenFileAsData(apk.get(), "res/xml/test.xml");
  ASSERT_THAT(data, Ne(nullptr));
  AssertLoadXml(apk.get(), data.get(), &tree);

  // Check that the raw string index has not been assigned
  EXPECT_THAT(tree.getAttributeValueStringID(0), Eq(-1));
}

TEST_F(LinkTest, KeepRawXmlStrings) {
  StdErrDiagnostics diag;
  const std::string compiled_files_dir = GetTestPath("compiled");
  ASSERT_TRUE(CompileFile(GetTestPath("res/xml/test.xml"), R"(<Item AgentCode="007"/>)",
                          compiled_files_dir, &diag));

  const std::string out_apk = GetTestPath("out.apk");
  std::vector<std::string> link_args = {
      "--manifest", GetDefaultManifest(),
      "-o", out_apk,
      "--keep-raw-values"
  };

  ASSERT_TRUE(Link(link_args, compiled_files_dir, &diag));

  // Load the binary xml tree
  android::ResXMLTree tree;
  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(out_apk, &diag);
  std::unique_ptr<io::IData> data = OpenFileAsData(apk.get(), "res/xml/test.xml");
  ASSERT_THAT(data, Ne(nullptr));
  AssertLoadXml(apk.get(), data.get(), &tree);

  // Check that the raw string index has been set to the correct string pool entry
  int32_t raw_index = tree.getAttributeValueStringID(0);
  ASSERT_THAT(raw_index, Ne(-1));
  EXPECT_THAT(util::GetString(tree.getStrings(), static_cast<size_t>(raw_index)), Eq("007"));
}

TEST_F(LinkTest, NoCompressAssets) {
  StdErrDiagnostics diag;
  std::string content(500, 'a');
  WriteFile(GetTestPath("assets/testtxt"), content);
  WriteFile(GetTestPath("assets/testtxt2"), content);
  WriteFile(GetTestPath("assets/test.txt"), content);
  WriteFile(GetTestPath("assets/test.hello.txt"), content);
  WriteFile(GetTestPath("assets/test.hello.xml"), content);

  const std::string out_apk = GetTestPath("out.apk");
  std::vector<std::string> link_args = {
      "--manifest", GetDefaultManifest(),
      "-o", out_apk,
      "-0", ".txt",
      "-0", "txt2",
      "-0", ".hello.txt",
      "-0", "hello.xml",
      "-A", GetTestPath("assets")
  };

  ASSERT_TRUE(Link(link_args, &diag));

  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(out_apk, &diag);
  ASSERT_THAT(apk, Ne(nullptr));
  io::IFileCollection* zip = apk->GetFileCollection();
  ASSERT_THAT(zip, Ne(nullptr));

  auto file = zip->FindFile("assets/testtxt");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_TRUE(file->WasCompressed());

  file = zip->FindFile("assets/testtxt2");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_FALSE(file->WasCompressed());

  file = zip->FindFile("assets/test.txt");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_FALSE(file->WasCompressed());

  file = zip->FindFile("assets/test.hello.txt");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_FALSE(file->WasCompressed());

  file = zip->FindFile("assets/test.hello.xml");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_FALSE(file->WasCompressed());
}

TEST_F(LinkTest, NoCompressResources) {
  StdErrDiagnostics diag;
  std::string content(500, 'a');
  const std::string compiled_files_dir = GetTestPath("compiled");
  ASSERT_TRUE(CompileFile(GetTestPath("res/raw/testtxt"), content, compiled_files_dir, &diag));
  ASSERT_TRUE(CompileFile(GetTestPath("res/raw/test.txt"), content, compiled_files_dir, &diag));
  ASSERT_TRUE(CompileFile(GetTestPath("res/raw/test1.hello.txt"), content, compiled_files_dir,
              &diag));
  ASSERT_TRUE(CompileFile(GetTestPath("res/raw/test2.goodbye.xml"), content, compiled_files_dir,
              &diag));

  const std::string out_apk = GetTestPath("out.apk");
  std::vector<std::string> link_args = {
      "--manifest", GetDefaultManifest(),
      "-o", out_apk,
      "-0", ".txt",
      "-0", ".hello.txt",
      "-0", "goodbye.xml",
  };

  ASSERT_TRUE(Link(link_args, compiled_files_dir, &diag));

  std::unique_ptr<LoadedApk> apk = LoadedApk::LoadApkFromPath(out_apk, &diag);
  ASSERT_THAT(apk, Ne(nullptr));
  io::IFileCollection* zip = apk->GetFileCollection();
  ASSERT_THAT(zip, Ne(nullptr));

  auto file = zip->FindFile("res/raw/testtxt");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_TRUE(file->WasCompressed());

  file = zip->FindFile("res/raw/test.txt");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_FALSE(file->WasCompressed());

  file = zip->FindFile("res/raw/test1.hello.hello.txt");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_FALSE(file->WasCompressed());

  file = zip->FindFile("res/raw/test2.goodbye.goodbye.xml");
  ASSERT_THAT(file, Ne(nullptr));
  EXPECT_FALSE(file->WasCompressed());
}

}  // namespace aapt