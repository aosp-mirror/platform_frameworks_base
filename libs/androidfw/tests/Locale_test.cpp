/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "androidfw/Locale.h"
#include "androidfw/Util.h"

#include <string>

#include "gtest/gtest.h"

namespace android {

static ::testing::AssertionResult TestLanguage(const char* input,
                                               const char* lang) {
  std::vector<std::string> parts = util::SplitAndLowercase(input, '-');
  LocaleValue lv;
  ssize_t count = lv.InitFromParts(std::begin(parts), std::end(parts));
  if (count < 0) {
    return ::testing::AssertionFailure() << " failed to parse '" << input
                                         << "'.";
  }

  if (count != 1) {
    return ::testing::AssertionFailure()
           << count << " parts were consumed parsing '" << input
           << "' but expected 1.";
  }

  if (memcmp(lv.language, lang, std::min(strlen(lang), sizeof(lv.language))) !=
      0) {
    return ::testing::AssertionFailure()
           << "expected " << lang << " but got "
           << std::string(lv.language, sizeof(lv.language)) << ".";
  }

  return ::testing::AssertionSuccess();
}

static ::testing::AssertionResult TestLanguageRegion(const char* input,
                                                     const char* lang,
                                                     const char* region) {
  std::vector<std::string> parts = util::SplitAndLowercase(input, '-');
  LocaleValue lv;
  ssize_t count = lv.InitFromParts(std::begin(parts), std::end(parts));
  if (count < 0) {
    return ::testing::AssertionFailure() << " failed to parse '" << input
                                         << "'.";
  }

  if (count != 2) {
    return ::testing::AssertionFailure()
           << count << " parts were consumed parsing '" << input
           << "' but expected 2.";
  }

  if (memcmp(lv.language, lang, std::min(strlen(lang), sizeof(lv.language))) !=
      0) {
    return ::testing::AssertionFailure()
           << "expected " << input << " but got "
           << std::string(lv.language, sizeof(lv.language)) << ".";
  }

  if (memcmp(lv.region, region, std::min(strlen(region), sizeof(lv.region))) !=
      0) {
    return ::testing::AssertionFailure()
           << "expected " << region << " but got "
           << std::string(lv.region, sizeof(lv.region)) << ".";
  }

  return ::testing::AssertionSuccess();
}

TEST(ConfigDescriptionTest, ParseLanguage) {
  EXPECT_TRUE(TestLanguage("en", "en"));
  EXPECT_TRUE(TestLanguage("fr", "fr"));
  EXPECT_FALSE(TestLanguage("land", ""));
  EXPECT_TRUE(TestLanguage("fr-land", "fr"));

  EXPECT_TRUE(TestLanguageRegion("fr-rCA", "fr", "CA"));
}

}  // namespace android
