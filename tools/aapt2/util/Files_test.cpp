/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "util/Files.h"

#include <sstream>

#include "android-base/stringprintf.h"
#include "android-base/utf8.h"

#include "test/Test.h"

using ::android::base::StringPrintf;

namespace aapt {
namespace file {

#ifdef _WIN32
constexpr const char sTestDirSep = '\\';
#else
constexpr const char sTestDirSep = '/';
#endif

class FilesTest : public ::testing::Test {
 public:
  void SetUp() override {
    std::stringstream builder;
    builder << "hello" << sDirSep << "there";
    expected_path_ = builder.str();
  }

 protected:
  std::string expected_path_;
};

TEST_F(FilesTest, AppendPath) {
  std::string base = "hello";
  AppendPath(&base, "there");
  EXPECT_EQ(expected_path_, base);
}

TEST_F(FilesTest, AppendPathWithLeadingOrTrailingSeparators) {
  std::string base = StringPrintf("hello%c", sTestDirSep);
  AppendPath(&base, "there");
  EXPECT_EQ(expected_path_, base);

  base = "hello";
  AppendPath(&base, StringPrintf("%cthere", sTestDirSep));
  EXPECT_EQ(expected_path_, base);

  base = StringPrintf("hello%c", sTestDirSep);
  AppendPath(&base, StringPrintf("%cthere", sTestDirSep));
  EXPECT_EQ(expected_path_, base);
}

#ifdef _WIN32
TEST_F(FilesTest, WindowsMkdirsLongPath) {
  // Creating directory paths longer than the Windows maximum path length (260 charatcers) should
  // succeed.
  const std::string kDirName = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
  const size_t kRecursiveDepth = 10u;

  // Recursively create the test file path and clean up the created directories after the files have
  // been created.
  std::function<void(std::string, size_t)> CreateResursiveDirs =
      [&kDirName, &CreateResursiveDirs](std::string current_path, const size_t n) -> void {
    AppendPath(&current_path, kDirName);

    if (n == 0) {
      ASSERT_TRUE(file::mkdirs(current_path)) << "Failed to create path " << current_path;
    } else {
      CreateResursiveDirs(current_path, n - 1);
    }

    // Clean up the created directories.
    _rmdir(current_path.data());
  };

  CreateResursiveDirs(
      android::base::StringPrintf(R"(\\?\%s)", android::base::GetExecutableDirectory().data()),
      kRecursiveDepth);
}

TEST_F(FilesTest, WindowsMkdirsLongPathMissingDrive) {
  ASSERT_FALSE(file::mkdirs(R"(\\?\local\path\to\file)"));
  ASSERT_FALSE(file::mkdirs(R"(\\?\:local\path\to\file)"));
  ASSERT_FALSE(file::mkdirs(R"(\\?\\local\path\to\file)"));
}
#endif

}  // namespace files
}  // namespace aapt
