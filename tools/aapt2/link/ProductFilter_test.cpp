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

#include "link/ProductFilter.h"
#include "test/Builders.h"
#include "test/Context.h"

#include <gtest/gtest.h>

namespace aapt {

TEST(ProductFilterTest, SelectTwoProducts) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();

    const ConfigDescription land = test::parseConfigOrDie("land");
    const ConfigDescription port = test::parseConfigOrDie("port");

    ResourceTable table;
    ASSERT_TRUE(table.addResource(test::parseNameOrDie(u"@android:string/one"),
                                  land, "",
                                  test::ValueBuilder<Id>()
                                          .setSource(Source("land/default.xml")).build(),
                                  context->getDiagnostics()));
    ASSERT_TRUE(table.addResource(test::parseNameOrDie(u"@android:string/one"),
                                  land, "tablet",
                                  test::ValueBuilder<Id>()
                                          .setSource(Source("land/tablet.xml")).build(),
                                  context->getDiagnostics()));

    ASSERT_TRUE(table.addResource(test::parseNameOrDie(u"@android:string/one"),
                                  port, "",
                                  test::ValueBuilder<Id>()
                                          .setSource(Source("port/default.xml")).build(),
                                  context->getDiagnostics()));
    ASSERT_TRUE(table.addResource(test::parseNameOrDie(u"@android:string/one"),
                                  port, "tablet",
                                  test::ValueBuilder<Id>()
                                          .setSource(Source("port/tablet.xml")).build(),
                                  context->getDiagnostics()));

    ProductFilter filter({ "tablet" });
    ASSERT_TRUE(filter.consume(context.get(), &table));

    EXPECT_EQ(nullptr, test::getValueForConfigAndProduct<Id>(&table, u"@android:string/one",
                                                             land, ""));
    EXPECT_NE(nullptr, test::getValueForConfigAndProduct<Id>(&table, u"@android:string/one",
                                                             land, "tablet"));
    EXPECT_EQ(nullptr, test::getValueForConfigAndProduct<Id>(&table, u"@android:string/one",
                                                             port, ""));
    EXPECT_NE(nullptr, test::getValueForConfigAndProduct<Id>(&table, u"@android:string/one",
                                                             port, "tablet"));
}

TEST(ProductFilterTest, SelectDefaultProduct) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();

    ResourceTable table;
    ASSERT_TRUE(table.addResource(test::parseNameOrDie(u"@android:string/one"),
                                  ConfigDescription::defaultConfig(), "",
                                  test::ValueBuilder<Id>()
                                          .setSource(Source("default.xml")).build(),
                                  context->getDiagnostics()));
    ASSERT_TRUE(table.addResource(test::parseNameOrDie(u"@android:string/one"),
                                  ConfigDescription::defaultConfig(), "tablet",
                                  test::ValueBuilder<Id>()
                                          .setSource(Source("tablet.xml")).build(),
                                  context->getDiagnostics()));

    ProductFilter filter({});
    ASSERT_TRUE(filter.consume(context.get(), &table));

    EXPECT_NE(nullptr, test::getValueForConfigAndProduct<Id>(&table, u"@android:string/one",
                                                             ConfigDescription::defaultConfig(),
                                                             ""));
    EXPECT_EQ(nullptr, test::getValueForConfigAndProduct<Id>(&table, u"@android:string/one",
                                                             ConfigDescription::defaultConfig(),
                                                             "tablet"));
}

TEST(ProductFilterTest, FailOnAmbiguousProduct) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();

    ResourceTable table;
    ASSERT_TRUE(table.addResource(test::parseNameOrDie(u"@android:string/one"),
                                  ConfigDescription::defaultConfig(), "",
                                  test::ValueBuilder<Id>()
                                          .setSource(Source("default.xml")).build(),
                                  context->getDiagnostics()));
    ASSERT_TRUE(table.addResource(test::parseNameOrDie(u"@android:string/one"),
                                  ConfigDescription::defaultConfig(), "tablet",
                                  test::ValueBuilder<Id>()
                                          .setSource(Source("tablet.xml")).build(),
                                  context->getDiagnostics()));
    ASSERT_TRUE(table.addResource(test::parseNameOrDie(u"@android:string/one"),
                                  ConfigDescription::defaultConfig(), "no-sdcard",
                                  test::ValueBuilder<Id>()
                                          .setSource(Source("no-sdcard.xml")).build(),
                                  context->getDiagnostics()));

    ProductFilter filter({ "tablet", "no-sdcard" });
    ASSERT_FALSE(filter.consume(context.get(), &table));
}

TEST(ProductFilterTest, FailOnMultipleDefaults) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();

    ResourceTable table;
    ASSERT_TRUE(table.addResource(test::parseNameOrDie(u"@android:string/one"),
                                  ConfigDescription::defaultConfig(), "",
                                  test::ValueBuilder<Id>()
                                          .setSource(Source(".xml")).build(),
                                  context->getDiagnostics()));
    ASSERT_TRUE(table.addResource(test::parseNameOrDie(u"@android:string/one"),
                                  ConfigDescription::defaultConfig(), "default",
                                  test::ValueBuilder<Id>()
                                          .setSource(Source("default.xml")).build(),
                                  context->getDiagnostics()));

    ProductFilter filter({});
    ASSERT_FALSE(filter.consume(context.get(), &table));
}

} // namespace aapt
