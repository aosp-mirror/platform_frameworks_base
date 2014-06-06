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

#include <utils/String8.h>
#include <utils/String16.h>
#include "TestHelpers.h"

#include <gtest/gtest.h>

/**
 * Include a binary resource table. This table
 * is a base table for an APK split.
 *
 * Package: com.android.example.split
 *
 * layout/main          0x7f020000 {default, fr-sw600dp-v13}
 *
 * string/app_title     0x7f030000 {default}
 * string/test          0x7f030001 {default}
 * string/boom          0x7f030002 {default}
 * string/blah          0x7f030003 {default}
 *
 * array/lotsofstrings  0x7f040000 {default}
 * array/numList        0x7f040001 {default}
 * array/ary            0x7f040002 {default}
 *
 */
#include "data/split_base_arsc.h"

/**
 * Include a binary resource table. This table
 * is a configuration split table for an APK split.
 *
 * Package: com.android.example.split
 *
 * string/app_title     0x7f030000 {fr}
 * string/test          0x7f030001 {de,fr}
 * string/blah          0x7f030003 {fr}
 *
 * array/lotsofstrings  0x7f040000 {fr}
 *
 */
#include "data/split_de_fr_arsc.h"


using namespace android;

enum { MAY_NOT_BE_BAG = false };

void makeConfigFrench(ResTable_config* config) {
    memset(config, 0, sizeof(*config));
    config->language[0] = 'f';
    config->language[1] = 'r';
}

TEST(SplitTest, TestLoadBase) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(split_base_arsc, split_base_arsc_len));
}

TEST(SplitTest, TestGetResourceFromBase) {
    ResTable_config frenchConfig;
    makeConfigFrench(&frenchConfig);

    ResTable table;
    table.setParameters(&frenchConfig);

    ASSERT_EQ(NO_ERROR, table.add(split_base_arsc, split_base_arsc_len));

    ResTable_config expectedConfig;
    memset(&expectedConfig, 0, sizeof(expectedConfig));

    Res_value val;
    ResTable_config config;
    ssize_t block = table.getResource(0x7f030000, &val, MAY_NOT_BE_BAG, 0, NULL, &config);

    // The returned block should tell us which string pool to get the value, if it is a string.
    EXPECT_GE(block, 0);

    // We expect the default resource to be selected since it is the only resource configuration.
    EXPECT_EQ(0, expectedConfig.compare(config));

    EXPECT_EQ(Res_value::TYPE_STRING, val.dataType);
}

TEST(SplitTest, TestGetResourceFromSplit) {
    ResTable_config expectedConfig;
    makeConfigFrench(&expectedConfig);

    ResTable table;
    table.setParameters(&expectedConfig);

    ASSERT_EQ(NO_ERROR, table.add(split_base_arsc, split_base_arsc_len));
    ASSERT_EQ(NO_ERROR, table.add(split_de_fr_arsc, split_de_fr_arsc_len));

    Res_value val;
    ResTable_config config;
    ssize_t block = table.getResource(0x7f030000, &val, MAY_NOT_BE_BAG, 0, NULL, &config);

    EXPECT_GE(block, 0);

    EXPECT_EQ(0, expectedConfig.compare(config));

    EXPECT_EQ(Res_value::TYPE_STRING, val.dataType);
}

TEST(SplitTest, ResourcesFromBaseAndSplitHaveSameNames) {
    ResTable_config expectedConfig;
    makeConfigFrench(&expectedConfig);

    ResTable table;
    table.setParameters(&expectedConfig);

    ASSERT_EQ(NO_ERROR, table.add(split_base_arsc, split_base_arsc_len));

    ResTable::resource_name baseName;
    EXPECT_TRUE(table.getResourceName(0x7f030003, false, &baseName));

    ASSERT_EQ(NO_ERROR, table.add(split_de_fr_arsc, split_de_fr_arsc_len));

    ResTable::resource_name frName;
    EXPECT_TRUE(table.getResourceName(0x7f030003, false, &frName));

    EXPECT_EQ(
            String16(baseName.package, baseName.packageLen),
            String16(frName.package, frName.packageLen));

    EXPECT_EQ(
            String16(baseName.type, baseName.typeLen),
            String16(frName.type, frName.typeLen));

    EXPECT_EQ(
            String16(baseName.name, baseName.nameLen),
            String16(frName.name, frName.nameLen));
}

TEST(SplitTest, TypeEntrySpecFlagsAreUpdated) {
    ResTable_config defaultConfig;
    memset(&defaultConfig, 0, sizeof(defaultConfig));

    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(split_base_arsc, split_base_arsc_len));

    Res_value val;
    uint32_t specFlags = 0;
    ssize_t block = table.getResource(0x7f030000, &val, MAY_NOT_BE_BAG, 0, &specFlags, NULL);
    EXPECT_GE(block, 0);

    EXPECT_EQ(static_cast<uint32_t>(0), specFlags);

    ASSERT_EQ(NO_ERROR, table.add(split_de_fr_arsc, split_de_fr_arsc_len));

    uint32_t frSpecFlags = 0;
    block = table.getResource(0x7f030000, &val, MAY_NOT_BE_BAG, 0, &frSpecFlags, NULL);
    EXPECT_GE(block, 0);

    EXPECT_EQ(ResTable_config::CONFIG_LOCALE, frSpecFlags);
}
