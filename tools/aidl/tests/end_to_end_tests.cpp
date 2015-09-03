/*
 * Copyright (C) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include <memory>
#include <string>
#include <vector>

#include <base/logging.h>
#include <base/files/file_path.h>
#include <base/files/file_util.h>
#include <gtest/gtest.h>

#include "aidl.h"
#include "options.h"
#include "tests/test_data.h"

using base::FilePath;
using std::string;
using std::unique_ptr;
using std::vector;

using namespace aidl::test_data;

namespace {

const char kDiffTemplate[] = "diff %s %s";
const char kStubInterfaceTemplate[] = "package %s;\ninterface %s { }";
const char kStubParcelableTemplate[] = "package %s;\nparcelable %s;";

FilePath GetPathForPackageClass(const char* package_class,
                                const char* extension) {
  string rel_path{package_class};
  for (char& c : rel_path) {
    if (c == '.') {
      c = FilePath::kSeparators[0];
    }
  }
  rel_path += extension;
  return FilePath(rel_path);
}

void SplitPackageClass(const string& package_class,
                       FilePath* rel_path,
                       string* package,
                       string* class_name) {
  *package = string{package_class, 0, package_class.rfind('.')};
  *class_name = string{package_class, package_class.rfind('.') + 1};
  *rel_path = GetPathForPackageClass(package_class.c_str(), ".aidl");
}

}  // namespace

class EndToEndTest : public ::testing::Test {
 protected:
  virtual void SetUp() {
    ASSERT_TRUE(base::CreateNewTempDirectory(
        string{"end_to_end_testsyyyy"}, &tmpDir_));
    inputDir_ = tmpDir_.Append("input");
    outputDir_ = tmpDir_.Append("output");
    ASSERT_TRUE(base::CreateDirectory(inputDir_));
    ASSERT_TRUE(base::CreateDirectory(outputDir_));
  }

  virtual void TearDown() {
    ASSERT_TRUE(DeleteFile(tmpDir_, true))
        << "Failed to remove temp directory: " << tmpDir_.value();
  }

  FilePath CreateInputFile(const FilePath& relative_path,
                           const char contents[],
                           int size) {
    const FilePath created_file = inputDir_.Append(relative_path);
    EXPECT_TRUE(base::CreateDirectory(created_file.DirName()));
    EXPECT_TRUE(base::WriteFile(created_file, contents, size));
    return created_file;
  }

  void CreateStubAidlFile(const string& package_class,
                          const char* file_template) {
    string package, class_name;
    FilePath rel_path;
    SplitPackageClass(package_class, &rel_path, &package, &class_name);
    const size_t buf_len =
        strlen(file_template) + package.length() + class_name.length() + 1;
    unique_ptr<char[]> contents(new char[buf_len]);
    const int written = snprintf(contents.get(), buf_len, file_template,
                                 package.c_str(), class_name.c_str());
    EXPECT_GT(written, 0);
    CreateInputFile(rel_path, contents.get(), written);
  }

  void WriteStubAidls(const char** parcelables, const char** interfaces) {
    while (*parcelables) {
      CreateStubAidlFile(string{*parcelables}, kStubParcelableTemplate);
      ++parcelables;
    }
    while (*interfaces) {
      CreateStubAidlFile(string{*interfaces}, kStubInterfaceTemplate);
      ++interfaces;
    }
  }

  void CheckFileContents(const FilePath& rel_path,
                         const string& expected_content) {
    string actual_contents;
    FilePath actual_path = outputDir_.Append(rel_path);
    if (!ReadFileToString(actual_path, &actual_contents)) {
      FAIL() << "Failed to read expected output file: " << rel_path.value();
    }
    // Generated .java files mention the "original" file as part of their
    // comment header.  Thus we look for expected_content being a substring.
    if (actual_contents.find(expected_content) == string::npos) {
      // When the match fails, display a diff of what's wrong.  This greatly
      // aids in debugging.
      FilePath expected_path;
      EXPECT_TRUE(CreateTemporaryFileInDir(tmpDir_, &expected_path));
      base::WriteFile(expected_path, expected_content.c_str(),
                      expected_content.length());
      const size_t buf_len =
          strlen(kDiffTemplate) + actual_path.value().length() +
          expected_path.value().length() + 1;
      unique_ptr<char[]> diff_cmd(new char[buf_len]);
      EXPECT_GT(snprintf(diff_cmd.get(), buf_len, kDiffTemplate,
                         expected_path.value().c_str(),
                         actual_path.value().c_str()), 0);
      system(diff_cmd.get());
      FAIL() << "Actual contents of " << rel_path.value()
             << " did not match expected content";
    }
  }

  FilePath tmpDir_;
  FilePath inputDir_;
  FilePath outputDir_;
};

TEST_F(EndToEndTest, IExampleInterface) {
  Options options;
  options.failOnParcelable = true;
  options.importPaths.push_back(inputDir_.value());
  options.inputFileName =
      CreateInputFile(GetPathForPackageClass(kIExampleInterfaceClass, ".aidl"),
                      kIExampleInterfaceContents,
                      strlen(kIExampleInterfaceContents)).value();
  options.autoDepFile = true;
  options.outputBaseFolder = outputDir_.value();
  WriteStubAidls(kIExampleInterfaceParcelables, kIExampleInterfaceInterfaces);
  EXPECT_EQ(compile_aidl(options), 0);
  CheckFileContents(GetPathForPackageClass(kIExampleInterfaceClass, ".java"),
                    kIExampleInterfaceJava);
  // We'd like to check the depends file, but it mentions unique file paths.
}
