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

#include "Linker.h"
#include "ResourceTable.h"
#include "ResourceTableResolver.h"
#include "ResourceValues.h"
#include "Util.h"

#include <androidfw/AssetManager.h>
#include <gtest/gtest.h>
#include <string>

namespace aapt {

struct LinkerTest : public ::testing::Test {
    virtual void SetUp() override {
        mTable = std::make_shared<ResourceTable>();
        mTable->setPackage(u"android");
        mTable->setPackageId(0x01);
        mLinker = std::make_shared<Linker>(mTable, std::make_shared<ResourceTableResolver>(
                mTable, std::vector<std::shared_ptr<const android::AssetManager>>()),
                Linker::Options{});

        // Create a few attributes for use in the tests.

        addResource(ResourceName{ {}, ResourceType::kAttr, u"integer" },
                    util::make_unique<Attribute>(false, android::ResTable_map::TYPE_INTEGER));

        addResource(ResourceName{ {}, ResourceType::kAttr, u"string" },
                    util::make_unique<Attribute>(false, android::ResTable_map::TYPE_STRING));

        addResource(ResourceName{ {}, ResourceType::kId, u"apple" }, util::make_unique<Id>());

        addResource(ResourceName{ {}, ResourceType::kId, u"banana" }, util::make_unique<Id>());

        std::unique_ptr<Attribute> flagAttr = util::make_unique<Attribute>(
                false, android::ResTable_map::TYPE_FLAGS);
        flagAttr->symbols.push_back(Attribute::Symbol{
                ResourceNameRef{ u"android", ResourceType::kId, u"apple" }, 1 });
        flagAttr->symbols.push_back(Attribute::Symbol{
                ResourceNameRef{ u"android", ResourceType::kId, u"banana" }, 2 });
        addResource(ResourceName{ {}, ResourceType::kAttr, u"flags" }, std::move(flagAttr));
    }

    /*
     * Convenience method for adding resources with the default configuration and some
     * bogus source line.
     */
    bool addResource(const ResourceNameRef& name, std::unique_ptr<Value> value) {
        return mTable->addResource(name, {}, SourceLine{ "test.xml", 21 }, std::move(value));
    }

    std::shared_ptr<ResourceTable> mTable;
    std::shared_ptr<Linker> mLinker;
};

TEST_F(LinkerTest, DoNotInterpretEscapedStringAsReference) {
    ASSERT_TRUE(addResource(ResourceName{ u"android", ResourceType::kString, u"foo" },
                util::make_unique<String>(mTable->getValueStringPool().makeRef(u"?123"))));

    ASSERT_TRUE(mLinker->linkAndValidate());
    EXPECT_TRUE(mLinker->getUnresolvedReferences().empty());
}

TEST_F(LinkerTest, EscapeAndConvertRawString) {
    std::unique_ptr<Style> style = util::make_unique<Style>();
    style->entries.push_back(Style::Entry{
            ResourceNameRef{ u"android", ResourceType::kAttr, u"integer" },
            util::make_unique<RawString>(mTable->getValueStringPool().makeRef(u"  123"))
    });
    const Style* result = style.get();
    ASSERT_TRUE(addResource(ResourceName{ u"android", ResourceType::kStyle, u"foo" },
                std::move(style)));

    ASSERT_TRUE(mLinker->linkAndValidate());
    EXPECT_TRUE(mLinker->getUnresolvedReferences().empty());

    EXPECT_NE(nullptr, dynamic_cast<BinaryPrimitive*>(result->entries.front().value.get()));
}

TEST_F(LinkerTest, FailToConvertRawString) {
    std::unique_ptr<Style> style = util::make_unique<Style>();
    style->entries.push_back(Style::Entry{
            ResourceNameRef{ u"android", ResourceType::kAttr, u"integer" },
            util::make_unique<RawString>(mTable->getValueStringPool().makeRef(u"yo what is up?"))
    });
    ASSERT_TRUE(addResource(ResourceName{ u"android", ResourceType::kStyle, u"foo" },
                std::move(style)));

    ASSERT_FALSE(mLinker->linkAndValidate());
}

TEST_F(LinkerTest, ConvertRawStringToString) {
    std::unique_ptr<Style> style = util::make_unique<Style>();
    style->entries.push_back(Style::Entry{
            ResourceNameRef{ u"android", ResourceType::kAttr, u"string" },
            util::make_unique<RawString>(
                    mTable->getValueStringPool().makeRef(u"  \"this  is  \\u00fa\"."))
    });
    const Style* result = style.get();
    ASSERT_TRUE(addResource(ResourceName{ u"android", ResourceType::kStyle, u"foo" },
                std::move(style)));

    ASSERT_TRUE(mLinker->linkAndValidate());
    EXPECT_TRUE(mLinker->getUnresolvedReferences().empty());

    const String* str = dynamic_cast<const String*>(result->entries.front().value.get());
    ASSERT_NE(nullptr, str);
    EXPECT_EQ(*str->value, u"this  is  \u00fa.");
}

TEST_F(LinkerTest, ConvertRawStringToFlags) {
    std::unique_ptr<Style> style = util::make_unique<Style>();
    style->entries.push_back(Style::Entry{
            ResourceNameRef{ u"android", ResourceType::kAttr, u"flags" },
            util::make_unique<RawString>(mTable->getValueStringPool().makeRef(u"banana | apple"))
    });
    const Style* result = style.get();
    ASSERT_TRUE(addResource(ResourceName{ u"android", ResourceType::kStyle, u"foo" },
                std::move(style)));

    ASSERT_TRUE(mLinker->linkAndValidate());
    EXPECT_TRUE(mLinker->getUnresolvedReferences().empty());

    const BinaryPrimitive* bin = dynamic_cast<const BinaryPrimitive*>(
            result->entries.front().value.get());
    ASSERT_NE(nullptr, bin);
    EXPECT_EQ(bin->value.data, 1u | 2u);
}

TEST_F(LinkerTest, AllowReferenceWithOnlyResourceIdPointingToDifferentPackage) {
    ASSERT_TRUE(addResource(ResourceName{ u"android", ResourceType::kInteger, u"foo" },
                util::make_unique<Reference>(ResourceId{ 0x02, 0x01, 0x01 })));

    ASSERT_TRUE(mLinker->linkAndValidate());
    EXPECT_TRUE(mLinker->getUnresolvedReferences().empty());
}

} // namespace aapt
