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

#include <utils/String8.h>
#include <gtest/gtest.h>

#include "ConfigDescription.h"
#include "ResourceTable.h"
#include "TestHelper.h"

using android::String16;

TEST(ResourceTableTest, generateVersionedResources) {
    sp<ResourceTable::ConfigList> configs(new ResourceTable::ConfigList(String16(), SourcePos()));

    ConfigDescription defaultConfig = {};

    ConfigDescription landConfig = {};
    landConfig.orientation = ResTable_config::ORIENTATION_LAND;

    ConfigDescription sw600dpLandConfig = {};
    sw600dpLandConfig.orientation = ResTable_config::ORIENTATION_LAND;
    sw600dpLandConfig.smallestScreenWidthDp = 600;

    configs->addEntry(defaultConfig, new ResourceTable::Entry(String16(), SourcePos()));
    configs->addEntry(landConfig, new ResourceTable::Entry(String16(), SourcePos()));
    configs->addEntry(sw600dpLandConfig, new ResourceTable::Entry(String16(), SourcePos()));

    EXPECT_TRUE(ResourceTable::shouldGenerateVersionedResource(configs, defaultConfig, 17));
    EXPECT_TRUE(ResourceTable::shouldGenerateVersionedResource(configs, landConfig, 17));
}

TEST(ResourceTableTest, generateVersionedResourceWhenHigherVersionExists) {
    sp<ResourceTable::ConfigList> configs(new ResourceTable::ConfigList(String16(), SourcePos()));

    ConfigDescription defaultConfig = {};

    ConfigDescription v21Config = {};
    v21Config.sdkVersion = 21;

    ConfigDescription sw600dpV13Config = {};
    sw600dpV13Config.smallestScreenWidthDp = 600;
    sw600dpV13Config.sdkVersion = 13;

    configs->addEntry(defaultConfig, new ResourceTable::Entry(String16(), SourcePos()));
    configs->addEntry(v21Config, new ResourceTable::Entry(String16(), SourcePos()));
    configs->addEntry(sw600dpV13Config, new ResourceTable::Entry(String16(), SourcePos()));

    EXPECT_TRUE(ResourceTable::shouldGenerateVersionedResource(configs, defaultConfig, 17));
    EXPECT_FALSE(ResourceTable::shouldGenerateVersionedResource(configs, defaultConfig, 22));
}
