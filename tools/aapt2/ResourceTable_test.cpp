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

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "Util.h"

#include <algorithm>
#include <gtest/gtest.h>
#include <ostream>
#include <string>

namespace aapt {

struct TestValue : public Value {
    std::u16string value;

    explicit TestValue(StringPiece16 str) : value(str.toString()) {
    }

    TestValue* clone(StringPool* /*newPool*/) const override {
        return new TestValue(value);
    }

    void print(std::ostream& out) const override {
        out << "(test) " << value;
    }

    virtual void accept(ValueVisitor&, ValueVisitorArgs&&) override {}
    virtual void accept(ConstValueVisitor&, ValueVisitorArgs&&) const override {}
};

struct TestWeakValue : public Value {
    bool isWeak() const override {
        return true;
    }

    TestWeakValue* clone(StringPool* /*newPool*/) const override {
        return new TestWeakValue();
    }

    void print(std::ostream& out) const override {
        out << "(test) [weak]";
    }

    virtual void accept(ValueVisitor&, ValueVisitorArgs&&) override {}
    virtual void accept(ConstValueVisitor&, ValueVisitorArgs&&) const override {}
};

TEST(ResourceTableTest, FailToAddResourceWithBadName) {
    ResourceTable table;
    table.setPackage(u"android");

    EXPECT_FALSE(table.addResource(
            ResourceNameRef{ u"android", ResourceType::kId, u"hey,there" },
            {}, SourceLine{ "test.xml", 21 },
            util::make_unique<TestValue>(u"rawValue")));

    EXPECT_FALSE(table.addResource(
            ResourceNameRef{ u"android", ResourceType::kId, u"hey:there" },
            {}, SourceLine{ "test.xml", 21 },
            util::make_unique<TestValue>(u"rawValue")));
}

TEST(ResourceTableTest, AddOneResource) {
    const std::u16string kAndroidPackage = u"android";

    ResourceTable table;
    table.setPackage(kAndroidPackage);

    const ResourceName name = { kAndroidPackage, ResourceType::kAttr, u"id" };

    EXPECT_TRUE(table.addResource(name, {}, SourceLine{ "test/path/file.xml", 23 },
                                  util::make_unique<TestValue>(u"rawValue")));

    const ResourceTableType* type;
    const ResourceEntry* entry;
    std::tie(type, entry) = table.findResource(name);
    ASSERT_NE(nullptr, type);
    ASSERT_NE(nullptr, entry);
    EXPECT_EQ(name.entry, entry->name);

    ASSERT_NE(std::end(entry->values),
              std::find_if(std::begin(entry->values), std::end(entry->values),
                      [](const ResourceConfigValue& val) -> bool {
                          return val.config == ConfigDescription{};
                      }));
}

TEST(ResourceTableTest, AddMultipleResources) {
    const std::u16string kAndroidPackage = u"android";
    ResourceTable table;
    table.setPackage(kAndroidPackage);

    ConfigDescription config;
    ConfigDescription languageConfig;
    memcpy(languageConfig.language, "pl", sizeof(languageConfig.language));

    EXPECT_TRUE(table.addResource(
            ResourceName{ kAndroidPackage, ResourceType::kAttr, u"layout_width" },
            config, SourceLine{ "test/path/file.xml", 10 },
            util::make_unique<TestValue>(u"rawValue")));

    EXPECT_TRUE(table.addResource(
            ResourceName{ kAndroidPackage, ResourceType::kAttr, u"id" },
            config, SourceLine{ "test/path/file.xml", 12 },
            util::make_unique<TestValue>(u"rawValue")));

    EXPECT_TRUE(table.addResource(
            ResourceName{ kAndroidPackage, ResourceType::kString, u"ok" },
            config, SourceLine{ "test/path/file.xml", 14 },
            util::make_unique<TestValue>(u"Ok")));

    EXPECT_TRUE(table.addResource(
            ResourceName{ kAndroidPackage, ResourceType::kString, u"ok" },
            languageConfig, SourceLine{ "test/path/file.xml", 20 },
            util::make_unique<TestValue>(u"Tak")));

    const auto endTypeIter = std::end(table);
    auto typeIter = std::begin(table);

    ASSERT_NE(endTypeIter, typeIter);
    EXPECT_EQ(ResourceType::kAttr, (*typeIter)->type);

    {
        const std::unique_ptr<ResourceTableType>& type = *typeIter;
        const auto endEntryIter = std::end(type->entries);
        auto entryIter = std::begin(type->entries);
        ASSERT_NE(endEntryIter, entryIter);
        EXPECT_EQ(std::u16string(u"id"), (*entryIter)->name);

        ++entryIter;
        ASSERT_NE(endEntryIter, entryIter);
        EXPECT_EQ(std::u16string(u"layout_width"), (*entryIter)->name);

        ++entryIter;
        ASSERT_EQ(endEntryIter, entryIter);
    }

    ++typeIter;
    ASSERT_NE(endTypeIter, typeIter);
    EXPECT_EQ(ResourceType::kString, (*typeIter)->type);

    {
        const std::unique_ptr<ResourceTableType>& type = *typeIter;
        const auto endEntryIter = std::end(type->entries);
        auto entryIter = std::begin(type->entries);
        ASSERT_NE(endEntryIter, entryIter);
        EXPECT_EQ(std::u16string(u"ok"), (*entryIter)->name);

        {
            const std::unique_ptr<ResourceEntry>& entry = *entryIter;
            const auto endConfigIter = std::end(entry->values);
            auto configIter = std::begin(entry->values);

            ASSERT_NE(endConfigIter, configIter);
            EXPECT_EQ(config, configIter->config);
            const TestValue* value =
                    dynamic_cast<const TestValue*>(configIter->value.get());
            ASSERT_NE(nullptr, value);
            EXPECT_EQ(std::u16string(u"Ok"), value->value);

            ++configIter;
            ASSERT_NE(endConfigIter, configIter);
            EXPECT_EQ(languageConfig, configIter->config);
            EXPECT_NE(nullptr, configIter->value);

            value = dynamic_cast<const TestValue*>(configIter->value.get());
            ASSERT_NE(nullptr, value);
            EXPECT_EQ(std::u16string(u"Tak"), value->value);

            ++configIter;
            EXPECT_EQ(endConfigIter, configIter);
        }

        ++entryIter;
        ASSERT_EQ(endEntryIter, entryIter);
    }

    ++typeIter;
    EXPECT_EQ(endTypeIter, typeIter);
}

TEST(ResourceTableTest, OverrideWeakResourceValue) {
    const std::u16string kAndroid = u"android";

    ResourceTable table;
    table.setPackage(kAndroid);
    table.setPackageId(0x01);

    ASSERT_TRUE(table.addResource(
            ResourceName{ kAndroid, ResourceType::kAttr, u"foo" },
            {}, {}, util::make_unique<TestWeakValue>()));

    const ResourceTableType* type;
    const ResourceEntry* entry;
    std::tie(type, entry) = table.findResource(
            ResourceNameRef{ kAndroid, ResourceType::kAttr, u"foo" });
    ASSERT_NE(nullptr, type);
    ASSERT_NE(nullptr, entry);
    ASSERT_EQ(entry->values.size(), 1u);
    EXPECT_TRUE(entry->values.front().value->isWeak());

    ASSERT_TRUE(table.addResource(ResourceName{ kAndroid, ResourceType::kAttr, u"foo" }, {}, {},
                                  util::make_unique<TestValue>(u"bar")));

    std::tie(type, entry) = table.findResource(
            ResourceNameRef{ kAndroid, ResourceType::kAttr, u"foo" });
    ASSERT_NE(nullptr, type);
    ASSERT_NE(nullptr, entry);
    ASSERT_EQ(entry->values.size(), 1u);
    EXPECT_FALSE(entry->values.front().value->isWeak());
}

} // namespace aapt
