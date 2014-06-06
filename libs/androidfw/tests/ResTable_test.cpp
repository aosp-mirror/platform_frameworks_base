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

using namespace android;

namespace {

/**
 * Include a binary resource table.
 *
 * Package: com.android.test.basic
 */
#include "data/basic/basic_arsc.h"

enum { MAY_NOT_BE_BAG = false };

static const uint32_t attr_attr1            = 0x7f010000;
static const uint32_t attr_attr2            = 0x7f010001;
static const uint32_t string_test1          = 0x7f020000;
static const uint32_t string_test2          = 0x7f020001;
static const uint32_t integer_number1       = 0x7f030000;
static const uint32_t integer_number2       = 0x7f030001;
static const uint32_t style_Theme1          = 0x7f040000;
static const uint32_t style_Theme2          = 0x7f040001;
static const uint32_t array_integerArray1   = 0x7f050000;

TEST(ResTableTest, shouldLoadSuccessfully) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));
}

TEST(ResTableTest, simpleTypeIsRetrievedCorrectly) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    Res_value val;
    ssize_t block = table.getResource(string_test1, &val, MAY_NOT_BE_BAG);

    ASSERT_GE(block, 0);
    ASSERT_EQ(Res_value::TYPE_STRING, val.dataType);

    const ResStringPool* pool = table.getTableStringBlock(block);
    ASSERT_TRUE(NULL != pool);
    ASSERT_EQ(String8("test1"), pool->string8ObjectAt(val.data));
}

TEST(ResTableTest, resourceNameIsResolved) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    String16 defPackage("com.android.test.basic");
    String16 testName("@string/test1");
    uint32_t resID = table.identifierForName(testName.string(), testName.size(),
                                             0, 0,
                                             defPackage.string(), defPackage.size());
    ASSERT_NE(uint32_t(0x00000000), resID);
    ASSERT_EQ(string_test1, resID);
}

TEST(ResTableTest, noParentThemeIsAppliedCorrectly) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    ResTable::Theme theme(table);
    ASSERT_EQ(NO_ERROR, theme.applyStyle(style_Theme1));

    Res_value val;
    uint32_t specFlags = 0;
    ssize_t index = theme.getAttribute(attr_attr1, &val, &specFlags);
    ASSERT_GE(index, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);
    ASSERT_EQ(uint32_t(100), val.data);

    index = theme.getAttribute(attr_attr2, &val, &specFlags);
    ASSERT_GE(index, 0);
    ASSERT_EQ(Res_value::TYPE_REFERENCE, val.dataType);
    ASSERT_EQ(integer_number1, val.data);
}

TEST(ResTableTest, parentThemeIsAppliedCorrectly) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    ResTable::Theme theme(table);
    ASSERT_EQ(NO_ERROR, theme.applyStyle(style_Theme2));

    Res_value val;
    uint32_t specFlags = 0;
    ssize_t index = theme.getAttribute(attr_attr1, &val, &specFlags);
    ASSERT_GE(index, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);
    ASSERT_EQ(uint32_t(300), val.data);

    index = theme.getAttribute(attr_attr2, &val, &specFlags);
    ASSERT_GE(index, 0);
    ASSERT_EQ(Res_value::TYPE_REFERENCE, val.dataType);
    ASSERT_EQ(integer_number1, val.data);
}

TEST(ResTableTest, referenceToBagIsNotResolved) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    Res_value val;
    ssize_t block = table.getResource(integer_number2, &val, MAY_NOT_BE_BAG);
    ASSERT_GE(block, 0);
    ASSERT_EQ(Res_value::TYPE_REFERENCE, val.dataType);
    ASSERT_EQ(array_integerArray1, val.data);

    ssize_t newBlock = table.resolveReference(&val, block);
    EXPECT_EQ(block, newBlock);
    EXPECT_EQ(Res_value::TYPE_REFERENCE, val.dataType);
    EXPECT_EQ(array_integerArray1, val.data);
}

TEST(ResTableTest, resourcesStillAccessibleAfterParameterChange) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    Res_value val;
    ssize_t block = table.getResource(integer_number1, &val, MAY_NOT_BE_BAG);
    ASSERT_GE(block, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);

    const ResTable::bag_entry* entry;
    ssize_t count = table.lockBag(array_integerArray1, &entry);
    ASSERT_GE(count, 0);
    table.unlockBag(entry);

    ResTable_config param;
    memset(&param, 0, sizeof(param));
    param.density = 320;
    table.setParameters(&param);

    block = table.getResource(integer_number1, &val, MAY_NOT_BE_BAG);
    ASSERT_GE(block, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);

    count = table.lockBag(array_integerArray1, &entry);
    ASSERT_GE(count, 0);
    table.unlockBag(entry);
}

TEST(ResTableTest, resourceIsOverridenWithBetterConfig) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    Res_value val;
    ssize_t block = table.getResource(integer_number1, &val, MAY_NOT_BE_BAG);
    ASSERT_GE(block, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);
    ASSERT_EQ(uint32_t(200), val.data);

    ResTable_config param;
    memset(&param, 0, sizeof(param));
    param.language[0] = 's';
    param.language[1] = 'v';
    param.country[0] = 'S';
    param.country[1] = 'E';
    table.setParameters(&param);

    block = table.getResource(integer_number1, &val, MAY_NOT_BE_BAG);
    ASSERT_GE(block, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);
    ASSERT_EQ(uint32_t(400), val.data);
}

}
