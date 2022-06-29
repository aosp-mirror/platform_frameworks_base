/*
 * Copyright (C) 2014 The Android Open Source Project
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

#include "androidfw/ResourceTypes.h"

#include "utils/Log.h"
#include "utils/String8.h"
#include "utils/Vector.h"

#include "TestHelpers.h"
#include "gtest/gtest.h"

namespace android {

static ResTable_config selectBest(const ResTable_config& target,
                                  const Vector<ResTable_config>& configs) {
  ResTable_config bestConfig;
  memset(&bestConfig, 0, sizeof(bestConfig));
  const size_t configCount = configs.size();
  for (size_t i = 0; i < configCount; i++) {
    const ResTable_config& thisConfig = configs[i];
    if (!thisConfig.match(target)) {
      continue;
    }

    if (thisConfig.isBetterThan(bestConfig, &target)) {
      bestConfig = thisConfig;
    }
  }
  return bestConfig;
}

static ResTable_config buildDensityConfig(int density) {
  ResTable_config config;
  memset(&config, 0, sizeof(config));
  config.density = uint16_t(density);
  config.sdkVersion = 4;
  return config;
}

TEST(ConfigTest, shouldSelectBestDensity) {
  ResTable_config deviceConfig;
  memset(&deviceConfig, 0, sizeof(deviceConfig));
  deviceConfig.density = ResTable_config::DENSITY_XHIGH;
  deviceConfig.sdkVersion = 21;

  Vector<ResTable_config> configs;

  ResTable_config expectedBest =
      buildDensityConfig(ResTable_config::DENSITY_HIGH);
  configs.add(expectedBest);
  ASSERT_EQ(expectedBest, selectBest(deviceConfig, configs));

  expectedBest = buildDensityConfig(ResTable_config::DENSITY_XXHIGH);
  configs.add(expectedBest);
  ASSERT_EQ(expectedBest, selectBest(deviceConfig, configs));

  expectedBest = buildDensityConfig(int(ResTable_config::DENSITY_XXHIGH) - 20);
  configs.add(expectedBest);
  ASSERT_EQ(expectedBest, selectBest(deviceConfig, configs));

  configs.add(buildDensityConfig(int(ResTable_config::DENSITY_HIGH) + 20));
  ASSERT_EQ(expectedBest, selectBest(deviceConfig, configs));

  configs.add(buildDensityConfig(int(ResTable_config::DENSITY_XHIGH) - 1));
  ASSERT_EQ(expectedBest, selectBest(deviceConfig, configs));

  expectedBest = buildDensityConfig(ResTable_config::DENSITY_XHIGH);
  configs.add(expectedBest);
  ASSERT_EQ(expectedBest, selectBest(deviceConfig, configs));

  expectedBest = buildDensityConfig(ResTable_config::DENSITY_ANY);
  expectedBest.sdkVersion = 21;
  configs.add(expectedBest);
  ASSERT_EQ(expectedBest, selectBest(deviceConfig, configs));
}

TEST(ConfigTest, shouldSelectBestDensityWhenNoneSpecified) {
  ResTable_config deviceConfig;
  memset(&deviceConfig, 0, sizeof(deviceConfig));
  deviceConfig.sdkVersion = 21;

  Vector<ResTable_config> configs;
  configs.add(buildDensityConfig(ResTable_config::DENSITY_HIGH));

  ResTable_config expectedBest =
      buildDensityConfig(ResTable_config::DENSITY_MEDIUM);
  configs.add(expectedBest);
  ASSERT_EQ(expectedBest, selectBest(deviceConfig, configs));

  expectedBest = buildDensityConfig(ResTable_config::DENSITY_ANY);
  configs.add(expectedBest);
  ASSERT_EQ(expectedBest, selectBest(deviceConfig, configs));
}

TEST(ConfigTest, shouldMatchRoundQualifier) {
  ResTable_config deviceConfig;
  memset(&deviceConfig, 0, sizeof(deviceConfig));

  ResTable_config roundConfig;
  memset(&roundConfig, 0, sizeof(roundConfig));
  roundConfig.screenLayout2 = ResTable_config::SCREENROUND_YES;

  EXPECT_FALSE(roundConfig.match(deviceConfig));

  deviceConfig.screenLayout2 = ResTable_config::SCREENROUND_YES;

  EXPECT_TRUE(roundConfig.match(deviceConfig));

  deviceConfig.screenLayout2 = ResTable_config::SCREENROUND_NO;

  EXPECT_FALSE(roundConfig.match(deviceConfig));

  ResTable_config notRoundConfig;
  memset(&notRoundConfig, 0, sizeof(notRoundConfig));
  notRoundConfig.screenLayout2 = ResTable_config::SCREENROUND_NO;

  EXPECT_TRUE(notRoundConfig.match(deviceConfig));
}

TEST(ConfigTest, RoundQualifierShouldHaveStableSortOrder) {
  ResTable_config defaultConfig;
  memset(&defaultConfig, 0, sizeof(defaultConfig));

  ResTable_config longConfig = defaultConfig;
  longConfig.screenLayout = ResTable_config::SCREENLONG_YES;

  ResTable_config longRoundConfig = longConfig;
  longRoundConfig.screenLayout2 = ResTable_config::SCREENROUND_YES;

  ResTable_config longRoundPortConfig = longConfig;
  longRoundPortConfig.orientation = ResTable_config::ORIENTATION_PORT;

  EXPECT_TRUE(longConfig.compare(longRoundConfig) < 0);
  EXPECT_TRUE(longConfig.compareLogical(longRoundConfig) < 0);
  EXPECT_TRUE(longRoundConfig.compare(longConfig) > 0);
  EXPECT_TRUE(longRoundConfig.compareLogical(longConfig) > 0);

  EXPECT_TRUE(longRoundConfig.compare(longRoundPortConfig) < 0);
  EXPECT_TRUE(longRoundConfig.compareLogical(longRoundPortConfig) < 0);
  EXPECT_TRUE(longRoundPortConfig.compare(longRoundConfig) > 0);
  EXPECT_TRUE(longRoundPortConfig.compareLogical(longRoundConfig) > 0);
}

TEST(ConfigTest, ScreenShapeHasCorrectDiff) {
  ResTable_config defaultConfig;
  memset(&defaultConfig, 0, sizeof(defaultConfig));

  ResTable_config roundConfig = defaultConfig;
  roundConfig.screenLayout2 = ResTable_config::SCREENROUND_YES;

  EXPECT_EQ(defaultConfig.diff(roundConfig),
            ResTable_config::CONFIG_SCREEN_ROUND);
}

TEST(ConfigTest, RoundIsMoreSpecific) {
  ResTable_config deviceConfig;
  memset(&deviceConfig, 0, sizeof(deviceConfig));
  deviceConfig.screenLayout2 = ResTable_config::SCREENROUND_YES;
  deviceConfig.screenLayout = ResTable_config::SCREENLONG_YES;

  ResTable_config targetConfigA;
  memset(&targetConfigA, 0, sizeof(targetConfigA));

  ResTable_config targetConfigB = targetConfigA;
  targetConfigB.screenLayout = ResTable_config::SCREENLONG_YES;

  ResTable_config targetConfigC = targetConfigB;
  targetConfigC.screenLayout2 = ResTable_config::SCREENROUND_YES;

  EXPECT_TRUE(targetConfigB.isBetterThan(targetConfigA, &deviceConfig));
  EXPECT_TRUE(targetConfigC.isBetterThan(targetConfigB, &deviceConfig));
}

TEST(ConfigTest, ScreenIsWideGamut) {
  ResTable_config defaultConfig;
  memset(&defaultConfig, 0, sizeof(defaultConfig));

  ResTable_config wideGamutConfig = defaultConfig;
  wideGamutConfig.colorMode = ResTable_config::WIDE_COLOR_GAMUT_YES;

  EXPECT_EQ(defaultConfig.diff(wideGamutConfig), ResTable_config::CONFIG_COLOR_MODE);
}

TEST(ConfigTest, ScreenIsHdr) {
  ResTable_config defaultConfig;
  memset(&defaultConfig, 0, sizeof(defaultConfig));

  ResTable_config hdrConfig = defaultConfig;
  hdrConfig.colorMode = ResTable_config::HDR_YES;

  EXPECT_EQ(defaultConfig.diff(hdrConfig), ResTable_config::CONFIG_COLOR_MODE);
}

}  // namespace android.
