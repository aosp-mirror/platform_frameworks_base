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
#include "data/basic/R.h"

#include <gtest/gtest.h>

using namespace android;

namespace {

/**
 * Include a binary resource table. This table
 * is a base table for an APK split.
 *
 * Package: com.android.test.basic
 */
#include "data/basic/basic_arsc.h"

/**
 * Include a binary resource table. This table
 * is a configuration split table for an APK split.
 *
 * Package: com.android.test.basic
 */
#include "data/basic/split_de_fr_arsc.h"

/**
 * Include a binary resource table. This table
 * is a feature split table for an APK split.
 *
 * Package: com.android.test.basic
 */
#include "data/feature/feature_arsc.h"

enum { MAY_NOT_BE_BAG = false };

void makeConfigFrench(ResTable_config* config) {
    memset(config, 0, sizeof(*config));
    config->language[0] = 'f';
    config->language[1] = 'r';
}

TEST(SplitTest, TestLoadBase) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));
}

TEST(SplitTest, TestGetResourceFromBase) {
    ResTable_config frenchConfig;
    makeConfigFrench(&frenchConfig);

    ResTable table;
    table.setParameters(&frenchConfig);

    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    ResTable_config expectedConfig;
    memset(&expectedConfig, 0, sizeof(expectedConfig));

    Res_value val;
    ResTable_config config;
    ssize_t block = table.getResource(base::R::string::test1, &val, MAY_NOT_BE_BAG, 0, NULL, &config);

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

    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));
    ASSERT_EQ(NO_ERROR, table.add(split_de_fr_arsc, split_de_fr_arsc_len));

    Res_value val;
    ResTable_config config;
    ssize_t block = table.getResource(base::R::string::test1, &val, MAY_NOT_BE_BAG, 0, NULL, &config);

    EXPECT_GE(block, 0);

    EXPECT_EQ(0, expectedConfig.compare(config));

    EXPECT_EQ(Res_value::TYPE_STRING, val.dataType);
}

TEST(SplitTest, ResourcesFromBaseAndSplitHaveSameNames) {
    ResTable_config expectedConfig;
    makeConfigFrench(&expectedConfig);

    ResTable table;
    table.setParameters(&expectedConfig);

    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    ResTable::resource_name baseName;
    EXPECT_TRUE(table.getResourceName(base::R::string::test1, false, &baseName));

    ASSERT_EQ(NO_ERROR, table.add(split_de_fr_arsc, split_de_fr_arsc_len));

    ResTable::resource_name frName;
    EXPECT_TRUE(table.getResourceName(base::R::string::test1, false, &frName));

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
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    Res_value val;
    uint32_t specFlags = 0;
    ssize_t block = table.getResource(base::R::string::test1, &val, MAY_NOT_BE_BAG, 0, &specFlags, NULL);
    EXPECT_GE(block, 0);

    EXPECT_EQ(static_cast<uint32_t>(0), specFlags);

    ASSERT_EQ(NO_ERROR, table.add(split_de_fr_arsc, split_de_fr_arsc_len));

    uint32_t frSpecFlags = 0;
    block = table.getResource(base::R::string::test1, &val, MAY_NOT_BE_BAG, 0, &frSpecFlags, NULL);
    EXPECT_GE(block, 0);

    EXPECT_EQ(ResTable_config::CONFIG_LOCALE, frSpecFlags);
}

TEST(SplitFeatureTest, TestNewResourceIsAccessible) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    Res_value val;
    ssize_t block = table.getResource(base::R::string::test3, &val, MAY_NOT_BE_BAG);
    EXPECT_LT(block, 0);

    ASSERT_EQ(NO_ERROR, table.add(feature_arsc, feature_arsc_len));

    block = table.getResource(base::R::string::test3, &val, MAY_NOT_BE_BAG);
    EXPECT_GE(block, 0);

    EXPECT_EQ(Res_value::TYPE_STRING, val.dataType);
}

TEST(SplitFeatureTest, TestNewResourceIsAccessibleByName) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    ResTable::resource_name name;
    EXPECT_FALSE(table.getResourceName(base::R::string::test3, false, &name));

    ASSERT_EQ(NO_ERROR, table.add(feature_arsc, feature_arsc_len));

    EXPECT_TRUE(table.getResourceName(base::R::string::test3, false, &name));

    EXPECT_EQ(String16("com.android.test.basic"),
            String16(name.package, name.packageLen));

    EXPECT_EQ(String16("string"),
            String16(name.type, name.typeLen));

    EXPECT_EQ(String16("test3"),
            String16(name.name, name.nameLen));
}

} // namespace
