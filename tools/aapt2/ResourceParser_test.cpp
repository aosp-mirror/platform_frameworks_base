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

#include "ResourceParser.h"
#include "ResourceTable.h"
#include "ResourceValues.h"
#include "SourceXmlPullParser.h"

#include <gtest/gtest.h>
#include <sstream>
#include <string>

namespace aapt {

constexpr const char* kXmlPreamble = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

TEST(ResourceParserReferenceTest, ParseReferenceWithNoPackage) {
    ResourceNameRef expected = { {}, ResourceType::kColor, u"foo" };
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceParser::tryParseReference(u"@color/foo", &actual, &create, &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_FALSE(create);
    EXPECT_FALSE(privateRef);
}

TEST(ResourceParserReferenceTest, ParseReferenceWithPackage) {
    ResourceNameRef expected = { u"android", ResourceType::kColor, u"foo" };
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceParser::tryParseReference(u"@android:color/foo", &actual, &create,
                                                  &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_FALSE(create);
    EXPECT_FALSE(privateRef);
}

TEST(ResourceParserReferenceTest, ParseReferenceWithSurroundingWhitespace) {
    ResourceNameRef expected = { u"android", ResourceType::kColor, u"foo" };
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceParser::tryParseReference(u"\t @android:color/foo\n \n\t", &actual,
                                                  &create, &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_FALSE(create);
    EXPECT_FALSE(privateRef);
}

TEST(ResourceParserReferenceTest, ParseAutoCreateIdReference) {
    ResourceNameRef expected = { u"android", ResourceType::kId, u"foo" };
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceParser::tryParseReference(u"@+android:id/foo", &actual, &create,
                                                  &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_TRUE(create);
    EXPECT_FALSE(privateRef);
}

TEST(ResourceParserReferenceTest, ParsePrivateReference) {
    ResourceNameRef expected = { u"android", ResourceType::kId, u"foo" };
    ResourceNameRef actual;
    bool create = false;
    bool privateRef = false;
    EXPECT_TRUE(ResourceParser::tryParseReference(u"@*android:id/foo", &actual, &create,
                                                  &privateRef));
    EXPECT_EQ(expected, actual);
    EXPECT_FALSE(create);
    EXPECT_TRUE(privateRef);
}

TEST(ResourceParserReferenceTest, FailToParseAutoCreateNonIdReference) {
    bool create = false;
    bool privateRef = false;
    ResourceNameRef actual;
    EXPECT_FALSE(ResourceParser::tryParseReference(u"@+android:color/foo", &actual, &create,
                                                   &privateRef));
}

TEST(ResourceParserReferenceTest, ParseStyleParentReference) {
    Reference ref;
    std::string errStr;
    EXPECT_TRUE(ResourceParser::parseStyleParentReference(u"@android:style/foo", &ref, &errStr));
    EXPECT_EQ(ref.name, (ResourceName{ u"android", ResourceType::kStyle, u"foo" }));

    EXPECT_TRUE(ResourceParser::parseStyleParentReference(u"@style/foo", &ref, &errStr));
    EXPECT_EQ(ref.name, (ResourceName{ {}, ResourceType::kStyle, u"foo" }));

    EXPECT_TRUE(ResourceParser::parseStyleParentReference(u"?android:style/foo", &ref, &errStr));
    EXPECT_EQ(ref.name, (ResourceName{ u"android", ResourceType::kStyle, u"foo" }));

    EXPECT_TRUE(ResourceParser::parseStyleParentReference(u"?style/foo", &ref, &errStr));
    EXPECT_EQ(ref.name, (ResourceName{ {}, ResourceType::kStyle, u"foo" }));

    EXPECT_TRUE(ResourceParser::parseStyleParentReference(u"android:style/foo", &ref, &errStr));
    EXPECT_EQ(ref.name, (ResourceName{ u"android", ResourceType::kStyle, u"foo" }));

    EXPECT_TRUE(ResourceParser::parseStyleParentReference(u"android:foo", &ref, &errStr));
    EXPECT_EQ(ref.name, (ResourceName{ u"android", ResourceType::kStyle, u"foo" }));

    EXPECT_TRUE(ResourceParser::parseStyleParentReference(u"foo", &ref, &errStr));
    EXPECT_EQ(ref.name, (ResourceName{ {}, ResourceType::kStyle, u"foo" }));
}

struct ResourceParserTest : public ::testing::Test {
    virtual void SetUp() override {
        mTable = std::make_shared<ResourceTable>();
        mTable->setPackage(u"android");
    }

    ::testing::AssertionResult testParse(std::istream& in) {
        std::stringstream input(kXmlPreamble);
        input << "<resources>" << std::endl
              << in.rdbuf() << std::endl
              << "</resources>" << std::endl;
        ResourceParser parser(mTable, Source{ "test" }, {},
                              std::make_shared<SourceXmlPullParser>(input));
        if (parser.parse()) {
            return ::testing::AssertionSuccess();
        }
        return ::testing::AssertionFailure();
    }

    template <typename T>
    const T* findResource(const ResourceNameRef& name, const ConfigDescription& config) {
        using std::begin;
        using std::end;

        const ResourceTableType* type;
        const ResourceEntry* entry;
        std::tie(type, entry) = mTable->findResource(name);
        if (!type || !entry) {
            return nullptr;
        }

        for (const auto& configValue : entry->values) {
            if (configValue.config == config) {
                return dynamic_cast<const T*>(configValue.value.get());
            }
        }
        return nullptr;
    }

    template <typename T>
    const T* findResource(const ResourceNameRef& name) {
        return findResource<T>(name, {});
    }

    std::shared_ptr<ResourceTable> mTable;
};

TEST_F(ResourceParserTest, FailToParseWithNoRootResourcesElement) {
    std::stringstream input(kXmlPreamble);
    input << "<attr name=\"foo\"/>" << std::endl;
    ResourceParser parser(mTable, {}, {}, std::make_shared<SourceXmlPullParser>(input));
    ASSERT_FALSE(parser.parse());
}

TEST_F(ResourceParserTest, ParseQuotedString) {
    std::stringstream input("<string name=\"foo\">   \"  hey there \" </string>");
    ASSERT_TRUE(testParse(input));

    const String* str = findResource<String>(ResourceName{
            u"android", ResourceType::kString, u"foo"});
    ASSERT_NE(nullptr, str);
    EXPECT_EQ(std::u16string(u"  hey there "), *str->value);
}

TEST_F(ResourceParserTest, ParseEscapedString) {
    std::stringstream input("<string name=\"foo\">\\?123</string>");
    ASSERT_TRUE(testParse(input));

    const String* str = findResource<String>(ResourceName{
            u"android", ResourceType::kString, u"foo" });
    ASSERT_NE(nullptr, str);
    EXPECT_EQ(std::u16string(u"?123"), *str->value);
}

TEST_F(ResourceParserTest, ParseAttr) {
    std::stringstream input;
    input << "<attr name=\"foo\" format=\"string\"/>" << std::endl
          << "<attr name=\"bar\"/>" << std::endl;
    ASSERT_TRUE(testParse(input));

    const Attribute* attr = findResource<Attribute>(ResourceName{
            u"android", ResourceType::kAttr, u"foo"});
    EXPECT_NE(nullptr, attr);
    EXPECT_EQ(uint32_t(android::ResTable_map::TYPE_STRING), attr->typeMask);

    attr = findResource<Attribute>(ResourceName{
            u"android", ResourceType::kAttr, u"bar"});
    EXPECT_NE(nullptr, attr);
    EXPECT_EQ(uint32_t(android::ResTable_map::TYPE_ANY), attr->typeMask);
}

TEST_F(ResourceParserTest, ParseUseAndDeclOfAttr) {
    std::stringstream input;
    input << "<declare-styleable name=\"Styleable\">" << std::endl
          << "  <attr name=\"foo\" />" << std::endl
          << "</declare-styleable>" << std::endl
          << "<attr name=\"foo\" format=\"string\"/>" << std::endl;
    ASSERT_TRUE(testParse(input));

    const Attribute* attr = findResource<Attribute>(ResourceName{
            u"android", ResourceType::kAttr, u"foo"});
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ(uint32_t(android::ResTable_map::TYPE_STRING), attr->typeMask);
}

TEST_F(ResourceParserTest, ParseDoubleUseOfAttr) {
    std::stringstream input;
    input << "<declare-styleable name=\"Theme\">" << std::endl
          << "  <attr name=\"foo\" />" << std::endl
          << "</declare-styleable>" << std::endl
          << "<declare-styleable name=\"Window\">" << std::endl
          << "  <attr name=\"foo\" format=\"boolean\"/>" << std::endl
          << "</declare-styleable>" << std::endl;

    ASSERT_TRUE(testParse(input));

    const Attribute* attr = findResource<Attribute>(ResourceName{
            u"android", ResourceType::kAttr, u"foo"});
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ(uint32_t(android::ResTable_map::TYPE_BOOLEAN), attr->typeMask);
}

TEST_F(ResourceParserTest, ParseEnumAttr) {
    std::stringstream input;
    input << "<attr name=\"foo\">" << std::endl
          << "  <enum name=\"bar\" value=\"0\"/>" << std::endl
          << "  <enum name=\"bat\" value=\"1\"/>" << std::endl
          << "  <enum name=\"baz\" value=\"2\"/>" << std::endl
          << "</attr>" << std::endl;
    ASSERT_TRUE(testParse(input));

    const Attribute* enumAttr = findResource<Attribute>(ResourceName{
            u"android", ResourceType::kAttr, u"foo"});
    ASSERT_NE(enumAttr, nullptr);
    EXPECT_EQ(enumAttr->typeMask, android::ResTable_map::TYPE_ENUM);
    ASSERT_EQ(enumAttr->symbols.size(), 3u);

    EXPECT_EQ(enumAttr->symbols[0].symbol.name.entry, u"bar");
    EXPECT_EQ(enumAttr->symbols[0].value, 0u);

    EXPECT_EQ(enumAttr->symbols[1].symbol.name.entry, u"bat");
    EXPECT_EQ(enumAttr->symbols[1].value, 1u);

    EXPECT_EQ(enumAttr->symbols[2].symbol.name.entry, u"baz");
    EXPECT_EQ(enumAttr->symbols[2].value, 2u);
}

TEST_F(ResourceParserTest, ParseFlagAttr) {
    std::stringstream input;
    input << "<attr name=\"foo\">" << std::endl
          << "  <flag name=\"bar\" value=\"0\"/>" << std::endl
          << "  <flag name=\"bat\" value=\"1\"/>" << std::endl
          << "  <flag name=\"baz\" value=\"2\"/>" << std::endl
          << "</attr>" << std::endl;
    ASSERT_TRUE(testParse(input));

    const Attribute* flagAttr = findResource<Attribute>(ResourceName{
            u"android", ResourceType::kAttr, u"foo"});
    ASSERT_NE(flagAttr, nullptr);
    EXPECT_EQ(flagAttr->typeMask, android::ResTable_map::TYPE_FLAGS);
    ASSERT_EQ(flagAttr->symbols.size(), 3u);

    EXPECT_EQ(flagAttr->symbols[0].symbol.name.entry, u"bar");
    EXPECT_EQ(flagAttr->symbols[0].value, 0u);

    EXPECT_EQ(flagAttr->symbols[1].symbol.name.entry, u"bat");
    EXPECT_EQ(flagAttr->symbols[1].value, 1u);

    EXPECT_EQ(flagAttr->symbols[2].symbol.name.entry, u"baz");
    EXPECT_EQ(flagAttr->symbols[2].value, 2u);

    std::unique_ptr<BinaryPrimitive> flagValue =
            ResourceParser::tryParseFlagSymbol(*flagAttr, u"baz|bat");
    ASSERT_NE(flagValue, nullptr);
    EXPECT_EQ(flagValue->value.data, 1u | 2u);
}

TEST_F(ResourceParserTest, FailToParseEnumAttrWithNonUniqueKeys) {
    std::stringstream input;
    input << "<attr name=\"foo\">" << std::endl
          << "  <enum name=\"bar\" value=\"0\"/>" << std::endl
          << "  <enum name=\"bat\" value=\"1\"/>" << std::endl
          << "  <enum name=\"bat\" value=\"2\"/>" << std::endl
          << "</attr>" << std::endl;
    ASSERT_FALSE(testParse(input));
}

TEST_F(ResourceParserTest, ParseStyle) {
    std::stringstream input;
    input << "<style name=\"foo\" parent=\"@style/fu\">" << std::endl
          << "  <item name=\"bar\">#ffffffff</item>" << std::endl
          << "  <item name=\"bat\">@string/hey</item>" << std::endl
          << "  <item name=\"baz\"><b>hey</b></item>" << std::endl
          << "</style>" << std::endl;
    ASSERT_TRUE(testParse(input));

    const Style* style = findResource<Style>(ResourceName{
            u"android", ResourceType::kStyle, u"foo"});
    ASSERT_NE(style, nullptr);
    EXPECT_EQ(ResourceNameRef(u"android", ResourceType::kStyle, u"fu"), style->parent.name);
    ASSERT_EQ(style->entries.size(), 3u);

    EXPECT_EQ(style->entries[0].key.name,
              (ResourceName{ u"android", ResourceType::kAttr, u"bar" }));
    EXPECT_EQ(style->entries[1].key.name,
              (ResourceName{ u"android", ResourceType::kAttr, u"bat" }));
    EXPECT_EQ(style->entries[2].key.name,
              (ResourceName{ u"android", ResourceType::kAttr, u"baz" }));
}

TEST_F(ResourceParserTest, ParseStyleWithShorthandParent) {
    std::stringstream input;
    input << "<style name=\"foo\" parent=\"com.app:Theme\"/>" << std::endl;
    ASSERT_TRUE(testParse(input));

    const Style* style = findResource<Style>(
            ResourceName{ u"android", ResourceType::kStyle, u"foo" });
    ASSERT_NE(style, nullptr);
    EXPECT_EQ(ResourceNameRef(u"com.app", ResourceType::kStyle, u"Theme"), style->parent.name);
}

TEST_F(ResourceParserTest, ParseAutoGeneratedIdReference) {
    std::stringstream input;
    input << "<string name=\"foo\">@+id/bar</string>" << std::endl;
    ASSERT_TRUE(testParse(input));

    const Id* id = findResource<Id>(ResourceName{ u"android", ResourceType::kId, u"bar"});
    ASSERT_NE(id, nullptr);
}

TEST_F(ResourceParserTest, ParseAttributesDeclareStyleable) {
    std::stringstream input;
    input << "<declare-styleable name=\"foo\">" << std::endl
          << "  <attr name=\"bar\" />" << std::endl
          << "  <attr name=\"bat\" format=\"string|reference\"/>" << std::endl
          << "</declare-styleable>" << std::endl;
    ASSERT_TRUE(testParse(input));

    const Attribute* attr = findResource<Attribute>(ResourceName{
            u"android", ResourceType::kAttr, u"bar"});
    ASSERT_NE(attr, nullptr);
    EXPECT_TRUE(attr->isWeak());

    attr = findResource<Attribute>(ResourceName{ u"android", ResourceType::kAttr, u"bat"});
    ASSERT_NE(attr, nullptr);
    EXPECT_TRUE(attr->isWeak());

    const Styleable* styleable = findResource<Styleable>(ResourceName{
            u"android", ResourceType::kStyleable, u"foo" });
    ASSERT_NE(styleable, nullptr);
    ASSERT_EQ(2u, styleable->entries.size());

    EXPECT_EQ((ResourceName{u"android", ResourceType::kAttr, u"bar"}), styleable->entries[0].name);
    EXPECT_EQ((ResourceName{u"android", ResourceType::kAttr, u"bat"}), styleable->entries[1].name);
}

TEST_F(ResourceParserTest, ParseArray) {
    std::stringstream input;
    input << "<array name=\"foo\">" << std::endl
          << "  <item>@string/ref</item>" << std::endl
          << "  <item>hey</item>" << std::endl
          << "  <item>23</item>" << std::endl
          << "</array>" << std::endl;
    ASSERT_TRUE(testParse(input));

    const Array* array = findResource<Array>(ResourceName{
            u"android", ResourceType::kArray, u"foo" });
    ASSERT_NE(array, nullptr);
    ASSERT_EQ(3u, array->items.size());

    EXPECT_NE(nullptr, dynamic_cast<const Reference*>(array->items[0].get()));
    EXPECT_NE(nullptr, dynamic_cast<const String*>(array->items[1].get()));
    EXPECT_NE(nullptr, dynamic_cast<const BinaryPrimitive*>(array->items[2].get()));
}

TEST_F(ResourceParserTest, ParsePlural) {
    std::stringstream input;
    input << "<plurals name=\"foo\">" << std::endl
          << "  <item quantity=\"other\">apples</item>" << std::endl
          << "  <item quantity=\"one\">apple</item>" << std::endl
          << "</plurals>" << std::endl
          << std::endl;
    ASSERT_TRUE(testParse(input));
}

TEST_F(ResourceParserTest, ParseCommentsWithResource) {
    std::stringstream input;
    input << "<!-- This is a comment -->" << std::endl
          << "<string name=\"foo\">Hi</string>" << std::endl;
    ASSERT_TRUE(testParse(input));

    const ResourceTableType* type;
    const ResourceEntry* entry;
    std::tie(type, entry) = mTable->findResource(ResourceName{
            u"android", ResourceType::kString, u"foo"});
    ASSERT_NE(type, nullptr);
    ASSERT_NE(entry, nullptr);
    ASSERT_FALSE(entry->values.empty());
    EXPECT_EQ(entry->values.front().comment, u"This is a comment");
}

/*
 * Declaring an ID as public should not require a separate definition
 * (as an ID has no value).
 */
TEST_F(ResourceParserTest, ParsePublicIdAsDefinition) {
    std::stringstream input("<public type=\"id\" name=\"foo\"/>");
    ASSERT_TRUE(testParse(input));

    const Id* id = findResource<Id>(ResourceName{ u"android", ResourceType::kId, u"foo" });
    ASSERT_NE(nullptr, id);
}

} // namespace aapt
