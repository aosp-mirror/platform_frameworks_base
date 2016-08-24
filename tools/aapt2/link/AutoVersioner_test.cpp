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

#include "ConfigDescription.h"
#include "link/Linkers.h"
#include "test/Builders.h"
#include "test/Context.h"

#include <gtest/gtest.h>

namespace aapt {

TEST(AutoVersionerTest, GenerateVersionedResources) {
    const ConfigDescription defaultConfig = {};
    const ConfigDescription landConfig = test::parseConfigOrDie("land");
    const ConfigDescription sw600dpLandConfig = test::parseConfigOrDie("sw600dp-land");

    ResourceEntry entry(u"foo");
    entry.values.push_back(util::make_unique<ResourceConfigValue>(defaultConfig, ""));
    entry.values.push_back(util::make_unique<ResourceConfigValue>(landConfig, ""));
    entry.values.push_back(util::make_unique<ResourceConfigValue>(sw600dpLandConfig, ""));

    EXPECT_TRUE(shouldGenerateVersionedResource(&entry, defaultConfig, 17));
    EXPECT_TRUE(shouldGenerateVersionedResource(&entry, landConfig, 17));
}

TEST(AutoVersionerTest, GenerateVersionedResourceWhenHigherVersionExists) {
    const ConfigDescription defaultConfig = {};
    const ConfigDescription sw600dpV13Config = test::parseConfigOrDie("sw600dp-v13");
    const ConfigDescription v21Config = test::parseConfigOrDie("v21");

    ResourceEntry entry(u"foo");
    entry.values.push_back(util::make_unique<ResourceConfigValue>(defaultConfig, ""));
    entry.values.push_back(util::make_unique<ResourceConfigValue>(sw600dpV13Config, ""));
    entry.values.push_back(util::make_unique<ResourceConfigValue>(v21Config, ""));

    EXPECT_TRUE(shouldGenerateVersionedResource(&entry, defaultConfig, 17));
    EXPECT_FALSE(shouldGenerateVersionedResource(&entry, defaultConfig, 22));
}

TEST(AutoVersionerTest, VersionStylesForTable) {
    std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
            .setPackageId(u"app", 0x7f)
            .addValue(u"@app:style/Foo", ResourceId(0x7f020000), test::parseConfigOrDie("v4"),
                      test::StyleBuilder()
                            .addItem(u"@android:attr/onClick", ResourceId(0x0101026f),
                                     util::make_unique<Id>())
                            .addItem(u"@android:attr/paddingStart", ResourceId(0x010103b3),
                                     util::make_unique<Id>())
                            .addItem(u"@android:attr/requiresSmallestWidthDp",
                                     ResourceId(0x01010364), util::make_unique<Id>())
                            .addItem(u"@android:attr/colorAccent", ResourceId(0x01010435),
                                     util::make_unique<Id>())
                            .build())
            .addValue(u"@app:style/Foo", ResourceId(0x7f020000), test::parseConfigOrDie("v21"),
                      test::StyleBuilder()
                            .addItem(u"@android:attr/paddingEnd", ResourceId(0x010103b4),
                                     util::make_unique<Id>())
                            .build())
            .build();

    std::unique_ptr<IAaptContext> context = test::ContextBuilder()
            .setCompilationPackage(u"app")
            .setPackageId(0x7f)
            .build();

    AutoVersioner versioner;
    ASSERT_TRUE(versioner.consume(context.get(), table.get()));

    Style* style = test::getValueForConfig<Style>(table.get(), u"@app:style/Foo",
                                                  test::parseConfigOrDie("v4"));
    ASSERT_NE(style, nullptr);
    ASSERT_EQ(style->entries.size(), 1u);
    AAPT_ASSERT_TRUE(style->entries.front().key.name);
    EXPECT_EQ(style->entries.front().key.name.value(),
              test::parseNameOrDie(u"@android:attr/onClick"));

    style = test::getValueForConfig<Style>(table.get(), u"@app:style/Foo",
                                           test::parseConfigOrDie("v13"));
    ASSERT_NE(style, nullptr);
    ASSERT_EQ(style->entries.size(), 2u);
    AAPT_ASSERT_TRUE(style->entries[0].key.name);
    EXPECT_EQ(style->entries[0].key.name.value(),
              test::parseNameOrDie(u"@android:attr/onClick"));
    AAPT_ASSERT_TRUE(style->entries[1].key.name);
    EXPECT_EQ(style->entries[1].key.name.value(),
                  test::parseNameOrDie(u"@android:attr/requiresSmallestWidthDp"));

    style = test::getValueForConfig<Style>(table.get(), u"@app:style/Foo",
                                           test::parseConfigOrDie("v17"));
    ASSERT_NE(style, nullptr);
    ASSERT_EQ(style->entries.size(), 3u);
    AAPT_ASSERT_TRUE(style->entries[0].key.name);
    EXPECT_EQ(style->entries[0].key.name.value(),
                  test::parseNameOrDie(u"@android:attr/onClick"));
    AAPT_ASSERT_TRUE(style->entries[1].key.name);
    EXPECT_EQ(style->entries[1].key.name.value(),
                  test::parseNameOrDie(u"@android:attr/requiresSmallestWidthDp"));
    AAPT_ASSERT_TRUE(style->entries[2].key.name);
    EXPECT_EQ(style->entries[2].key.name.value(),
                  test::parseNameOrDie(u"@android:attr/paddingStart"));

    style = test::getValueForConfig<Style>(table.get(), u"@app:style/Foo",
                                           test::parseConfigOrDie("v21"));
    ASSERT_NE(style, nullptr);
    ASSERT_EQ(style->entries.size(), 1u);
    AAPT_ASSERT_TRUE(style->entries.front().key.name);
    EXPECT_EQ(style->entries.front().key.name.value(),
              test::parseNameOrDie(u"@android:attr/paddingEnd"));
}

} // namespace aapt
