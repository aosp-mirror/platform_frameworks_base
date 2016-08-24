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

#include "Diagnostics.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "util/Util.h"

#include "test/Builders.h"

#include <algorithm>
#include <gtest/gtest.h>
#include <ostream>
#include <string>

namespace aapt {

TEST(ResourceTableTest, FailToAddResourceWithBadName) {
    ResourceTable table;

    EXPECT_FALSE(table.addResource(
            ResourceNameRef(u"android", ResourceType::kId, u"hey,there"),
            ConfigDescription{}, "",
            test::ValueBuilder<Id>().setSource("test.xml", 21u).build(),
            test::getDiagnostics()));

    EXPECT_FALSE(table.addResource(
            ResourceNameRef(u"android", ResourceType::kId, u"hey:there"),
            ConfigDescription{}, "",
            test::ValueBuilder<Id>().setSource("test.xml", 21u).build(),
            test::getDiagnostics()));
}

TEST(ResourceTableTest, AddOneResource) {
    ResourceTable table;

    EXPECT_TRUE(table.addResource(test::parseNameOrDie(u"@android:attr/id"),
                                  ConfigDescription{},
                                  "",
                                  test::ValueBuilder<Id>()
                                          .setSource("test/path/file.xml", 23u).build(),
                                  test::getDiagnostics()));

    ASSERT_NE(nullptr, test::getValue<Id>(&table, u"@android:attr/id"));
}

TEST(ResourceTableTest, AddMultipleResources) {
    ResourceTable table;

    ConfigDescription config;
    ConfigDescription languageConfig;
    memcpy(languageConfig.language, "pl", sizeof(languageConfig.language));

    EXPECT_TRUE(table.addResource(
            test::parseNameOrDie(u"@android:attr/layout_width"),
            config,
            "",
            test::ValueBuilder<Id>().setSource("test/path/file.xml", 10u).build(),
            test::getDiagnostics()));

    EXPECT_TRUE(table.addResource(
            test::parseNameOrDie(u"@android:attr/id"),
            config,
            "",
            test::ValueBuilder<Id>().setSource("test/path/file.xml", 12u).build(),
            test::getDiagnostics()));

    EXPECT_TRUE(table.addResource(
            test::parseNameOrDie(u"@android:string/ok"),
            config,
            "",
            test::ValueBuilder<Id>().setSource("test/path/file.xml", 14u).build(),
            test::getDiagnostics()));

    EXPECT_TRUE(table.addResource(
            test::parseNameOrDie(u"@android:string/ok"),
            languageConfig,
            "",
            test::ValueBuilder<BinaryPrimitive>(android::Res_value{})
                    .setSource("test/path/file.xml", 20u)
                    .build(),
            test::getDiagnostics()));

    ASSERT_NE(nullptr, test::getValue<Id>(&table, u"@android:attr/layout_width"));
    ASSERT_NE(nullptr, test::getValue<Id>(&table, u"@android:attr/id"));
    ASSERT_NE(nullptr, test::getValue<Id>(&table, u"@android:string/ok"));
    ASSERT_NE(nullptr, test::getValueForConfig<BinaryPrimitive>(&table, u"@android:string/ok",
                                                                languageConfig));
}

TEST(ResourceTableTest, OverrideWeakResourceValue) {
    ResourceTable table;

    ASSERT_TRUE(table.addResource(test::parseNameOrDie(u"@android:attr/foo"), ConfigDescription{},
                                  "", util::make_unique<Attribute>(true), test::getDiagnostics()));

    Attribute* attr = test::getValue<Attribute>(&table, u"@android:attr/foo");
    ASSERT_NE(nullptr, attr);
    EXPECT_TRUE(attr->isWeak());

    ASSERT_TRUE(table.addResource(test::parseNameOrDie(u"@android:attr/foo"), ConfigDescription{},
                                  "", util::make_unique<Attribute>(false), test::getDiagnostics()));

    attr = test::getValue<Attribute>(&table, u"@android:attr/foo");
    ASSERT_NE(nullptr, attr);
    EXPECT_FALSE(attr->isWeak());
}

TEST(ResourceTableTest, ProductVaryingValues) {
    ResourceTable table;

    EXPECT_TRUE(table.addResource(test::parseNameOrDie(u"@android:string/foo"),
                                  test::parseConfigOrDie("land"),
                                  "tablet",
                                  util::make_unique<Id>(),
                                  test::getDiagnostics()));
    EXPECT_TRUE(table.addResource(test::parseNameOrDie(u"@android:string/foo"),
                                  test::parseConfigOrDie("land"),
                                  "phone",
                                  util::make_unique<Id>(),
                                  test::getDiagnostics()));

    EXPECT_NE(nullptr, test::getValueForConfigAndProduct<Id>(&table, u"@android:string/foo",
                                                             test::parseConfigOrDie("land"),
                                                             "tablet"));
    EXPECT_NE(nullptr, test::getValueForConfigAndProduct<Id>(&table, u"@android:string/foo",
                                                             test::parseConfigOrDie("land"),
                                                             "phone"));

    Maybe<ResourceTable::SearchResult> sr = table.findResource(
            test::parseNameOrDie(u"@android:string/foo"));
    AAPT_ASSERT_TRUE(sr);
    std::vector<ResourceConfigValue*> values = sr.value().entry->findAllValues(
            test::parseConfigOrDie("land"));
    ASSERT_EQ(2u, values.size());
    EXPECT_EQ(std::string("phone"), values[0]->product);
    EXPECT_EQ(std::string("tablet"), values[1]->product);
}

} // namespace aapt
