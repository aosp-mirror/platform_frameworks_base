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

#include <codecvt>
#include <locale>
#include <string>

#include <utils/String8.h>
#include <utils/String16.h>
#include "TestHelpers.h"
#include "data/basic/R.h"
#include "data/lib/R.h"

#include <gtest/gtest.h>

using namespace android;

namespace {

/**
 * Include a binary resource table.
 *
 * Package: com.android.test.basic
 */
#include "data/basic/basic_arsc.h"

/**
 * Include a binary library resource table.
 *
 * Package: com.android.test.basic
 */
#include "data/lib/lib_arsc.h"

/**
 * Include a system resource table.
 *
 * Package: android
 */
#include "data/system/system_arsc.h"

TEST(ResTableTest, shouldLoadSuccessfully) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));
}

TEST(ResTableTest, simpleTypeIsRetrievedCorrectly) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    EXPECT_TRUE(IsStringEqual(table, base::R::string::test1, "test1"));
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
    ASSERT_EQ(base::R::string::test1, resID);
}

TEST(ResTableTest, noParentThemeIsAppliedCorrectly) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    ResTable::Theme theme(table);
    ASSERT_EQ(NO_ERROR, theme.applyStyle(base::R::style::Theme1));

    Res_value val;
    uint32_t specFlags = 0;
    ssize_t index = theme.getAttribute(base::R::attr::attr1, &val, &specFlags);
    ASSERT_GE(index, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);
    ASSERT_EQ(uint32_t(100), val.data);

    index = theme.getAttribute(base::R::attr::attr2, &val, &specFlags);
    ASSERT_GE(index, 0);
    ASSERT_EQ(Res_value::TYPE_REFERENCE, val.dataType);
    ASSERT_EQ(base::R::integer::number1, val.data);
}

TEST(ResTableTest, parentThemeIsAppliedCorrectly) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    ResTable::Theme theme(table);
    ASSERT_EQ(NO_ERROR, theme.applyStyle(base::R::style::Theme2));

    Res_value val;
    uint32_t specFlags = 0;
    ssize_t index = theme.getAttribute(base::R::attr::attr1, &val, &specFlags);
    ASSERT_GE(index, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);
    ASSERT_EQ(uint32_t(300), val.data);

    index = theme.getAttribute(base::R::attr::attr2, &val, &specFlags);
    ASSERT_GE(index, 0);
    ASSERT_EQ(Res_value::TYPE_REFERENCE, val.dataType);
    ASSERT_EQ(base::R::integer::number1, val.data);
}

TEST(ResTableTest, libraryThemeIsAppliedCorrectly) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(lib_arsc, lib_arsc_len));

    ResTable::Theme theme(table);
    ASSERT_EQ(NO_ERROR, theme.applyStyle(lib::R::style::Theme));

    Res_value val;
    uint32_t specFlags = 0;
    ssize_t index = theme.getAttribute(lib::R::attr::attr1, &val, &specFlags);
    ASSERT_GE(index, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);
    ASSERT_EQ(uint32_t(700), val.data);

    index = theme.getAttribute(lib::R::attr::attr2, &val, &specFlags);
    ASSERT_GE(index, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);
    ASSERT_EQ(uint32_t(700), val.data);
}

TEST(ResTableTest, referenceToBagIsNotResolved) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    Res_value val;
    ssize_t block = table.getResource(base::R::integer::number2, &val, MAY_NOT_BE_BAG);
    ASSERT_GE(block, 0);
    ASSERT_EQ(Res_value::TYPE_REFERENCE, val.dataType);
    ASSERT_EQ(base::R::array::integerArray1, val.data);

    ssize_t newBlock = table.resolveReference(&val, block);
    EXPECT_EQ(block, newBlock);
    EXPECT_EQ(Res_value::TYPE_REFERENCE, val.dataType);
    EXPECT_EQ(base::R::array::integerArray1, val.data);
}

TEST(ResTableTest, resourcesStillAccessibleAfterParameterChange) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    Res_value val;
    ssize_t block = table.getResource(base::R::integer::number1, &val, MAY_NOT_BE_BAG);
    ASSERT_GE(block, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);

    const ResTable::bag_entry* entry;
    ssize_t count = table.lockBag(base::R::array::integerArray1, &entry);
    ASSERT_GE(count, 0);
    table.unlockBag(entry);

    ResTable_config param;
    memset(&param, 0, sizeof(param));
    param.density = 320;
    table.setParameters(&param);

    block = table.getResource(base::R::integer::number1, &val, MAY_NOT_BE_BAG);
    ASSERT_GE(block, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);

    count = table.lockBag(base::R::array::integerArray1, &entry);
    ASSERT_GE(count, 0);
    table.unlockBag(entry);
}

TEST(ResTableTest, resourceIsOverridenWithBetterConfig) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    Res_value val;
    ssize_t block = table.getResource(base::R::integer::number1, &val, MAY_NOT_BE_BAG);
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

    block = table.getResource(base::R::integer::number1, &val, MAY_NOT_BE_BAG);
    ASSERT_GE(block, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);
    ASSERT_EQ(uint32_t(400), val.data);
}

TEST(ResTableTest, emptyTableHasSensibleDefaults) {
    const int32_t assetCookie = 1;

    ResTable table;
    ASSERT_EQ(NO_ERROR, table.addEmpty(assetCookie));

    // Adding an empty table gives us one table!
    ASSERT_EQ(uint32_t(1), table.getTableCount());

    // Adding an empty table doesn't mean we get packages.
    ASSERT_EQ(uint32_t(0), table.getBasePackageCount());

    Res_value val;
    ASSERT_LT(table.getResource(base::R::integer::number1, &val, MAY_NOT_BE_BAG), 0);
}

void testU16StringToInt(const char16_t* str, uint32_t expectedValue,
                        bool expectSuccess, bool expectHex) {
    size_t len = std::char_traits<char16_t>::length(str);

    // Gtest can't print UTF-16 strings, so we have to convert to UTF-8 :(
    std::wstring_convert<std::codecvt_utf8_utf16<char16_t>, char16_t> convert;
    std::string s = convert.to_bytes(std::u16string(str, len));

    Res_value out = {};
    ASSERT_EQ(expectSuccess, U16StringToInt(str, len, &out))
        << "Failed with " << s;

    if (!expectSuccess) {
        ASSERT_EQ(out.TYPE_NULL, out.dataType) << "Failed with " << s;
        return;
    }

    if (expectHex) {
        ASSERT_EQ(out.TYPE_INT_HEX, out.dataType) << "Failed with " << s;
    } else {
        ASSERT_EQ(out.TYPE_INT_DEC, out.dataType) << "Failed with " << s;
    }

    ASSERT_EQ(expectedValue, out.data) << "Failed with " << s;
}

TEST(ResTableTest, U16StringToInt) {
    testU16StringToInt(u"", 0U, false, false);
    testU16StringToInt(u"    ", 0U, false, false);
    testU16StringToInt(u"\t\n", 0U, false, false);

    testU16StringToInt(u"abcd", 0U, false, false);
    testU16StringToInt(u"10abcd", 0U, false, false);
    testU16StringToInt(u"42 42", 0U, false, false);
    testU16StringToInt(u"- 42", 0U, false, false);
    testU16StringToInt(u"-", 0U, false, false);

    testU16StringToInt(u"0x", 0U, false, true);
    testU16StringToInt(u"0xnope", 0U, false, true);
    testU16StringToInt(u"0X42", 0U, false, true);
    testU16StringToInt(u"0x42 0x42", 0U, false, true);
    testU16StringToInt(u"-0x0", 0U, false, true);
    testU16StringToInt(u"-0x42", 0U, false, true);
    testU16StringToInt(u"- 0x42", 0U, false, true);

    // Note that u" 42" would pass. This preserves the old behavior, but it may
    // not be desired.
    testU16StringToInt(u"42 ", 0U, false, false);
    testU16StringToInt(u"0x42 ", 0U, false, true);

    // Decimal cases.
    testU16StringToInt(u"0", 0U, true, false);
    testU16StringToInt(u"-0", 0U, true, false);
    testU16StringToInt(u"42", 42U, true, false);
    testU16StringToInt(u" 42", 42U, true, false);
    testU16StringToInt(u"-42", static_cast<uint32_t>(-42), true, false);
    testU16StringToInt(u" -42", static_cast<uint32_t>(-42), true, false);
    testU16StringToInt(u"042", 42U, true, false);
    testU16StringToInt(u"-042", static_cast<uint32_t>(-42), true, false);

    // Hex cases.
    testU16StringToInt(u"0x0", 0x0, true, true);
    testU16StringToInt(u"0x42", 0x42, true, true);
    testU16StringToInt(u" 0x42", 0x42, true, true);

    // Just before overflow cases:
    testU16StringToInt(u"2147483647", INT_MAX, true, false);
    testU16StringToInt(u"-2147483648", static_cast<uint32_t>(INT_MIN), true,
                       false);
    testU16StringToInt(u"0xffffffff", UINT_MAX, true, true);

    // Overflow cases:
    testU16StringToInt(u"2147483648", 0U, false, false);
    testU16StringToInt(u"-2147483649", 0U, false, false);
    testU16StringToInt(u"0x1ffffffff", 0U, false, true);
}

TEST(ResTableTest, ShareButDontModifyResTable) {
    ResTable sharedTable;
    ASSERT_EQ(NO_ERROR, sharedTable.add(basic_arsc, basic_arsc_len));

    ResTable_config param;
    memset(&param, 0, sizeof(param));
    param.language[0] = 'v';
    param.language[1] = 's';
    sharedTable.setParameters(&param);

    // Check that we get the default value for @integer:number1
    Res_value val;
    ssize_t block = sharedTable.getResource(base::R::integer::number1, &val, MAY_NOT_BE_BAG);
    ASSERT_GE(block, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);
    ASSERT_EQ(uint32_t(600), val.data);

    // Create a new table that shares the entries of the shared table.
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(&sharedTable, false));

    // Set a new configuration on the new table.
    memset(&param, 0, sizeof(param));
    param.language[0] = 's';
    param.language[1] = 'v';
    param.country[0] = 'S';
    param.country[1] = 'E';
    table.setParameters(&param);

    // Check that we get a new value in the new table.
    block = table.getResource(base::R::integer::number1, &val, MAY_NOT_BE_BAG);
    ASSERT_GE(block, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);
    ASSERT_EQ(uint32_t(400), val.data);

    // Check that we still get the old value in the shared table.
    block = sharedTable.getResource(base::R::integer::number1, &val, MAY_NOT_BE_BAG);
    ASSERT_GE(block, 0);
    ASSERT_EQ(Res_value::TYPE_INT_DEC, val.dataType);
    ASSERT_EQ(uint32_t(600), val.data);
}

TEST(ResTableTest, GetConfigurationsReturnsUniqueList) {
    ResTable table;
    ASSERT_EQ(NO_ERROR, table.add(system_arsc, system_arsc_len));
    ASSERT_EQ(NO_ERROR, table.add(basic_arsc, basic_arsc_len));

    ResTable_config configSv;
    memset(&configSv, 0, sizeof(configSv));
    configSv.language[0] = 's';
    configSv.language[1] = 'v';

    Vector<ResTable_config> configs;
    table.getConfigurations(&configs);

    EXPECT_EQ(1, std::count(configs.begin(), configs.end(), configSv));

    Vector<String8> locales;
    table.getLocales(&locales);

    EXPECT_EQ(1, std::count(locales.begin(), locales.end(), String8("sv")));
}

} // namespace
