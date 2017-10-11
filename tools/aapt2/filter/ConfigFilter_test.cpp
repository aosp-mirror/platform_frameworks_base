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

#include "filter/ConfigFilter.h"

#include "test/Test.h"

namespace aapt {

TEST(ConfigFilterTest, EmptyFilterMatchesAnything) {
  AxisConfigFilter filter;

  EXPECT_TRUE(filter.Match(test::ParseConfigOrDie("320dpi")));
  EXPECT_TRUE(filter.Match(test::ParseConfigOrDie("fr")));
}

TEST(ConfigFilterTest, MatchesConfigWithUnrelatedAxis) {
  AxisConfigFilter filter;
  filter.AddConfig(test::ParseConfigOrDie("fr"));

  EXPECT_TRUE(filter.Match(test::ParseConfigOrDie("320dpi")));
}

TEST(ConfigFilterTest, MatchesConfigWithSameValueAxis) {
  AxisConfigFilter filter;
  filter.AddConfig(test::ParseConfigOrDie("fr"));

  EXPECT_TRUE(filter.Match(test::ParseConfigOrDie("fr")));
}

TEST(ConfigFilterTest, MatchesConfigWithSameValueAxisAndOtherUnrelatedAxis) {
  AxisConfigFilter filter;
  filter.AddConfig(test::ParseConfigOrDie("fr"));

  EXPECT_TRUE(filter.Match(test::ParseConfigOrDie("fr-320dpi")));
}

TEST(ConfigFilterTest, MatchesConfigWithOneMatchingAxis) {
  AxisConfigFilter filter;
  filter.AddConfig(test::ParseConfigOrDie("fr-rFR"));
  filter.AddConfig(test::ParseConfigOrDie("sw360dp"));
  filter.AddConfig(test::ParseConfigOrDie("normal"));
  filter.AddConfig(test::ParseConfigOrDie("en-rUS"));

  EXPECT_TRUE(filter.Match(test::ParseConfigOrDie("en")));
}

TEST(ConfigFilterTest, DoesNotMatchConfigWithDifferentValueAxis) {
  AxisConfigFilter filter;
  filter.AddConfig(test::ParseConfigOrDie("fr"));

  EXPECT_FALSE(filter.Match(test::ParseConfigOrDie("de")));
}

TEST(ConfigFilterTest, DoesNotMatchWhenOneQualifierIsExplicitlyNotMatched) {
  AxisConfigFilter filter;
  filter.AddConfig(test::ParseConfigOrDie("fr-rFR"));
  filter.AddConfig(test::ParseConfigOrDie("en-rUS"));
  filter.AddConfig(test::ParseConfigOrDie("normal"));
  filter.AddConfig(test::ParseConfigOrDie("large"));
  filter.AddConfig(test::ParseConfigOrDie("xxhdpi"));
  filter.AddConfig(test::ParseConfigOrDie("sw320dp"));

  EXPECT_FALSE(filter.Match(test::ParseConfigOrDie("fr-sw600dp-v13")));
}

TEST(ConfigFilterTest, MatchesSmallestWidthWhenSmaller) {
  AxisConfigFilter filter;
  filter.AddConfig(test::ParseConfigOrDie("sw600dp"));

  EXPECT_TRUE(filter.Match(test::ParseConfigOrDie("fr-sw320dp-v13")));
}

TEST(ConfigFilterTest, MatchesConfigWithSameLanguageButNoRegionSpecified) {
  AxisConfigFilter filter;
  filter.AddConfig(test::ParseConfigOrDie("de-rDE"));

  EXPECT_TRUE(filter.Match(test::ParseConfigOrDie("de")));
}

TEST(ConfigFilterTest, IgnoresVersion) {
  AxisConfigFilter filter;
  filter.AddConfig(test::ParseConfigOrDie("normal-v4"));

  // The configs don't match on any axis besides version, which should be
  // ignored.
  EXPECT_TRUE(filter.Match(test::ParseConfigOrDie("sw600dp-v13")));
}

TEST(ConfigFilterTest, MatchesConfigWithRegion) {
  AxisConfigFilter filter;
  filter.AddConfig(test::ParseConfigOrDie("kok"));
  filter.AddConfig(test::ParseConfigOrDie("kok-rIN"));
  filter.AddConfig(test::ParseConfigOrDie("kok-v419"));

  EXPECT_TRUE(filter.Match(test::ParseConfigOrDie("kok-rIN")));
}

TEST(ConfigFilterTest, MatchesScripts) {
  AxisConfigFilter filter;

  // "sr" gets automatically computed the script "Cyrl"
  filter.AddConfig(test::ParseConfigOrDie("sr"));

  // "sr" -> "b+sr+Cyrl"
  EXPECT_TRUE(filter.Match(test::ParseConfigOrDie("b+sr+Cyrl")));

  // The incoming "sr" is also auto-computed to "b+sr+Cyrl".
  EXPECT_TRUE(filter.Match(test::ParseConfigOrDie("sr")));

  // "sr" -> "b+sr+Cyrl", which doesn't match "Latn".
  EXPECT_FALSE(filter.Match(test::ParseConfigOrDie("b+sr+Latn")));
}

}  // namespace aapt
