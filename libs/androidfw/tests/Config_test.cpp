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

#include <androidfw/ResourceTypes.h>
#include <utils/Log.h>
#include <utils/String8.h>
#include <utils/Vector.h>

#include "TestHelpers.h"
#include <gtest/gtest.h>

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

    ResTable_config expectedBest = buildDensityConfig(ResTable_config::DENSITY_HIGH);
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

    ResTable_config expectedBest = buildDensityConfig(ResTable_config::DENSITY_MEDIUM);
    configs.add(expectedBest);
    ASSERT_EQ(expectedBest, selectBest(deviceConfig, configs));

    expectedBest = buildDensityConfig(ResTable_config::DENSITY_ANY);
    configs.add(expectedBest);
    ASSERT_EQ(expectedBest, selectBest(deviceConfig, configs));
}

}  // namespace android.
