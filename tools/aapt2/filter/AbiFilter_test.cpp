/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "filter/AbiFilter.h"

#include <string>

#include "gtest/gtest.h"

namespace aapt {
namespace {

using ::aapt::configuration::Abi;

struct TestData {
  std::string path;
  bool kept;
};

const TestData kTestData[] = {
    /* Keep. */
    {"lib/mips/libnative.so", true},
    {"not/native/file.txt", true},
    // Not sure if this is a valid use case.
    {"lib/listing.txt", true},
    {"lib/mips/foo/bar/baz.so", true},
    {"lib/mips/x86/foo.so", true},
    /* Discard. */
    {"lib/mips_horse/foo.so", false},
    {"lib/horse_mips/foo.so", false},
    {"lib/mips64/armeabi-v7a/foo.so", false},
    {"lib/mips64/x86_64/x86.so", false},
    {"lib/x86/libnative.so", false},
    {"lib/x86/foo/bar/baz.so", false},
    {"lib/x86/x86/foo.so", false},
    {"lib/x86_horse/foo.so", false},
    {"lib/horse_x86/foo.so", false},
    {"lib/x86/armeabi-v7a/foo.so", false},
    {"lib/x86_64/x86_64/x86.so", false},
};

class AbiFilterTest : public ::testing::TestWithParam<TestData> {};

TEST_P(AbiFilterTest, Keep) {
  auto mips = AbiFilter::FromAbiList({Abi::kMips});
  const TestData& data = GetParam();
  EXPECT_EQ(mips->Keep(data.path), data.kept);
}

INSTANTIATE_TEST_CASE_P(NativePaths, AbiFilterTest, ::testing::ValuesIn(kTestData));

}  // namespace
}  // namespace aapt
