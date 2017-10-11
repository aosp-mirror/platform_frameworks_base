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

#include "test/Test.h"

namespace aapt {
namespace file {

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
  std::string base = "hello/";
  AppendPath(&base, "there");
  EXPECT_EQ(expected_path_, base);

  base = "hello";
  AppendPath(&base, "/there");
  EXPECT_EQ(expected_path_, base);

  base = "hello/";
  AppendPath(&base, "/there");
  EXPECT_EQ(expected_path_, base);
}

}  // namespace files
}  // namespace aapt
