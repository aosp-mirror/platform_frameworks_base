/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include "androidfw/LocaleDataLookup.h"

#include <cstddef>
#include <string>

#include "gtest/gtest.h"
#include "gmock/gmock.h"


namespace android {

constexpr const char NULL_SCRIPT[4] = {'\0', '\0', '\0','\0' };

#define EXPECT_SCEIPT_EQ(ex, s) EXPECT_EQ(0, s == nullptr ? -1 : memcmp(ex, s, 4))

// Similar to packLanguageOrRegion() in ResourceTypes.cpp
static uint32_t encodeLanguageOrRegionLiteral(const char* in, const char base) {
  size_t len = strlen(in);
  if (len <= 1) {
    return 0;
  }

  if (len == 2) {
      return (((uint8_t) in[0]) << 8) | ((uint8_t) in[1]);
  }
  uint8_t first = (in[0] - base) & 0x007f;
  uint8_t second = (in[1] - base) & 0x007f;
  uint8_t third = (in[2] - base) & 0x007f;

  return ((uint8_t) (0x80 | (third << 2) | (second >> 3)) << 8) | ((second << 5) | first);
}

static uint32_t encodeLocale(const char* language, const char* region) {
    return (encodeLanguageOrRegionLiteral(language, 'a') << 16) |
            encodeLanguageOrRegionLiteral(region, '0');
}

TEST(LocaleDataLookupTest, lookupLikelyScript) {
  EXPECT_EQ(nullptr, lookupLikelyScript(encodeLocale("", "")));
  EXPECT_SCEIPT_EQ("Latn", lookupLikelyScript(encodeLocale("en", "")));
  EXPECT_EQ(nullptr, lookupLikelyScript(encodeLocale("en", "US")));
  EXPECT_EQ(nullptr, lookupLikelyScript(encodeLocale("en", "GB")));
  EXPECT_SCEIPT_EQ("Latn", lookupLikelyScript(encodeLocale("fr", "")));
  EXPECT_EQ(nullptr, lookupLikelyScript(encodeLocale("fr", "FR")));


  EXPECT_SCEIPT_EQ("~~~A", lookupLikelyScript(encodeLocale("en", "XA")));
  EXPECT_SCEIPT_EQ("Latn", lookupLikelyScript(encodeLocale("ha", "")));
  EXPECT_SCEIPT_EQ("Arab", lookupLikelyScript(encodeLocale("ha", "SD")));
  EXPECT_EQ(nullptr, lookupLikelyScript(encodeLocale("ha", "Sd"))); // case sensitive
  EXPECT_SCEIPT_EQ("Hans", lookupLikelyScript(encodeLocale("zh", "")));
  EXPECT_EQ(nullptr, lookupLikelyScript(encodeLocale("zh", "CN")));
  EXPECT_SCEIPT_EQ("Hant", lookupLikelyScript(encodeLocale("zh", "HK")));

  EXPECT_SCEIPT_EQ("Nshu", lookupLikelyScript(encodeLocale("zhx", "")));
  EXPECT_SCEIPT_EQ("Nshu", lookupLikelyScript(0xDCF90000u)); // encoded "zhx"
}

TEST(LocaleDataLookupTest, isLocaleRepresentative) {
  EXPECT_TRUE(isLocaleRepresentative(encodeLocale("en", "US"), "Latn"));
  EXPECT_TRUE(isLocaleRepresentative(encodeLocale("en", "GB"), "Latn"));
  EXPECT_FALSE(isLocaleRepresentative(encodeLocale("en", "US"), NULL_SCRIPT));
  EXPECT_FALSE(isLocaleRepresentative(encodeLocale("en", ""), "Latn"));
  EXPECT_FALSE(isLocaleRepresentative(encodeLocale("en", ""), NULL_SCRIPT));
  EXPECT_FALSE(isLocaleRepresentative(encodeLocale("en", "US"), "Arab"));

  EXPECT_TRUE(isLocaleRepresentative(encodeLocale("fr", "FR"), "Latn"));

  EXPECT_TRUE(isLocaleRepresentative(encodeLocale("zh", "CN"), "Hans"));
  EXPECT_FALSE(isLocaleRepresentative(encodeLocale("zh", "TW"), "Hans"));
  EXPECT_FALSE(isLocaleRepresentative(encodeLocale("zhx", "CN"), "Hans"));
  EXPECT_FALSE(isLocaleRepresentative(0xDCF9434E, "Hans"));
  EXPECT_TRUE(isLocaleRepresentative(encodeLocale("zhx", "CN"), "Nshu"));
  EXPECT_TRUE(isLocaleRepresentative(0xDCF9434E, "Nshu"));
}

TEST(LocaleDataLookupTest, findParentLocalePackedKey) {
  EXPECT_EQ(encodeLocale("en", "001"), findParentLocalePackedKey("Latn", encodeLocale("en", "GB")));
  EXPECT_EQ(0x656E8400u, findParentLocalePackedKey("Latn", encodeLocale("en", "GB")));

  EXPECT_EQ(encodeLocale("en", "IN"), findParentLocalePackedKey("Deva", encodeLocale("hi", "")));

  EXPECT_EQ(encodeLocale("ar", "015"), findParentLocalePackedKey("Arab", encodeLocale("ar", "AE")));
  EXPECT_EQ(0x61729420u, findParentLocalePackedKey("Arab", encodeLocale("ar", "AE")));

  EXPECT_EQ(encodeLocale("ar", "015"), findParentLocalePackedKey("~~~B", encodeLocale("ar", "XB")));
  EXPECT_EQ(0x61729420u, findParentLocalePackedKey("Arab", encodeLocale("ar", "AE")));

  EXPECT_EQ(encodeLocale("zh", "HK"), findParentLocalePackedKey("Hant", encodeLocale("zh", "MO")));
}

}  // namespace android
