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

    ::testing::AssertionResult testParse(const StringPiece& str) {
        std::stringstream input(kXmlPreamble);
        input << "<resources>\n" << str << "\n</resources>" << std::endl;
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
    std::string input = "<string name=\"foo\">   \"  hey there \" </string>";
    ASSERT_TRUE(testParse(input));

    const String* str = findResource<String>(ResourceName{
            u"android", ResourceType::kString, u"foo"});
    ASSERT_NE(nullptr, str);
    EXPECT_EQ(std::u16string(u"  hey there "), *str->value);
}

TEST_F(ResourceParserTest, ParseEscapedString) {
    std::string input = "<string name=\"foo\">\\?123</string>";
    ASSERT_TRUE(testParse(input));

    const String* str = findResource<String>(ResourceName{
            u"android", ResourceType::kString, u"foo" });
    ASSERT_NE(nullptr, str);
    EXPECT_EQ(std::u16string(u"?123"), *str->value);
}

TEST_F(ResourceParserTest, ParseNull) {
    std::string input = "<integer name=\"foo\">@null</integer>";
    ASSERT_TRUE(testParse(input));

    // The Android runtime treats a value of android::Res_value::TYPE_NULL as
    // a non-existing value, and this causes problems in styles when trying to resolve
    // an attribute. Null values must be encoded as android::Res_value::TYPE_REFERENCE
    // with a data value of 0.
    const BinaryPrimitive* integer = findResource<BinaryPrimitive>(ResourceName{
            u"android", ResourceType::kInteger, u"foo" });
    ASSERT_NE(nullptr, integer);
    EXPECT_EQ(uint16_t(android::Res_value::TYPE_REFERENCE), integer->value.dataType);
    EXPECT_EQ(0u, integer->value.data);
}

TEST_F(ResourceParserTest, ParseEmpty) {
    std::string input = "<integer name=\"foo\">@empty</integer>";
    ASSERT_TRUE(testParse(input));

    const BinaryPrimitive* integer = findResource<BinaryPrimitive>(ResourceName{
            u"android", ResourceType::kInteger, u"foo" });
    ASSERT_NE(nullptr, integer);
    EXPECT_EQ(uint16_t(android::Res_value::TYPE_NULL), integer->value.dataType);
    EXPECT_EQ(uint32_t(android::Res_value::DATA_NULL_EMPTY), integer->value.data);
}

TEST_F(ResourceParserTest, ParseAttr) {
    std::string input = "<attr name=\"foo\" format=\"string\"/>\n"
                        "<attr name=\"bar\"/>";
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
    std::string input = "<declare-styleable name=\"Styleable\">\n"
                        "  <attr name=\"foo\" />\n"
                        "</declare-styleable>\n"
                        "<attr name=\"foo\" format=\"string\"/>";
    ASSERT_TRUE(testParse(input));

    const Attribute* attr = findResource<Attribute>(ResourceName{
            u"android", ResourceType::kAttr, u"foo"});
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ(uint32_t(android::ResTable_map::TYPE_STRING), attr->typeMask);
}

TEST_F(ResourceParserTest, ParseDoubleUseOfAttr) {
    std::string input = "<declare-styleable name=\"Theme\">"
                        "  <attr name=\"foo\" />\n"
                        "</declare-styleable>\n"
                        "<declare-styleable name=\"Window\">\n"
                        "  <attr name=\"foo\" format=\"boolean\"/>\n"
                        "</declare-styleable>";
    ASSERT_TRUE(testParse(input));

    const Attribute* attr = findResource<Attribute>(ResourceName{
            u"android", ResourceType::kAttr, u"foo"});
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ(uint32_t(android::ResTable_map::TYPE_BOOLEAN), attr->typeMask);
}

TEST_F(ResourceParserTest, ParseEnumAttr) {
    std::string input = "<attr name=\"foo\">\n"
                        "  <enum name=\"bar\" value=\"0\"/>\n"
                        "  <enum name=\"bat\" value=\"1\"/>\n"
                        "  <enum name=\"baz\" value=\"2\"/>\n"
                        "</attr>";
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
    std::string input = "<attr name=\"foo\">\n"
                        "  <flag name=\"bar\" value=\"0\"/>\n"
                        "  <flag name=\"bat\" value=\"1\"/>\n"
                        "  <flag name=\"baz\" value=\"2\"/>\n"
                        "</attr>";
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
    std::string input = "<attr name=\"foo\">\n"
                        "  <enum name=\"bar\" value=\"0\"/>\n"
                        "  <enum name=\"bat\" value=\"1\"/>\n"
                        "  <enum name=\"bat\" value=\"2\"/>\n"
                        "</attr>";
    ASSERT_FALSE(testParse(input));
}

TEST_F(ResourceParserTest, ParseStyle) {
    std::string input = "<style name=\"foo\" parent=\"@style/fu\">\n"
                        "  <item name=\"bar\">#ffffffff</item>\n"
                        "  <item name=\"bat\">@string/hey</item>\n"
                        "  <item name=\"baz\"><b>hey</b></item>\n"
                        "</style>";
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
    std::string input = "<style name=\"foo\" parent=\"com.app:Theme\"/>";
    ASSERT_TRUE(testParse(input));

    const Style* style = findResource<Style>(
            ResourceName{ u"android", ResourceType::kStyle, u"foo" });
    ASSERT_NE(style, nullptr);
    EXPECT_EQ(ResourceNameRef(u"com.app", ResourceType::kStyle, u"Theme"), style->parent.name);
}

TEST_F(ResourceParserTest, ParseStyleWithPackageAliasedParent) {
    std::string input = "<style xmlns:app=\"http://schemas.android.com/apk/res/android\"\n"
                        "       name=\"foo\" parent=\"app:Theme\"/>";
    ASSERT_TRUE(testParse(input));

    const Style* style = findResource<Style>(ResourceName{
            u"android", ResourceType::kStyle, u"foo" });
    ASSERT_NE(style, nullptr);
    EXPECT_EQ(ResourceNameRef(u"android", ResourceType::kStyle, u"Theme"), style->parent.name);
}

TEST_F(ResourceParserTest, ParseStyleWithPackageAliasedItems) {
    std::string input =
            "<style xmlns:app=\"http://schemas.android.com/apk/res/android\" name=\"foo\">\n"
            "  <item name=\"app:bar\">0</item>\n"
            "</style>";
    ASSERT_TRUE(testParse(input));

    const Style* style = findResource<Style>(ResourceName{
            u"android", ResourceType::kStyle, u"foo" });
    ASSERT_NE(style, nullptr);
    ASSERT_EQ(1u, style->entries.size());
    EXPECT_EQ(ResourceNameRef(u"android", ResourceType::kAttr, u"bar"),
              style->entries[0].key.name);
}

TEST_F(ResourceParserTest, ParseStyleWithInferredParent) {
    std::string input = "<style name=\"foo.bar\"/>";
    ASSERT_TRUE(testParse(input));

    const Style* style = findResource<Style>(ResourceName{
            u"android", ResourceType::kStyle, u"foo.bar" });
    ASSERT_NE(style, nullptr);
    EXPECT_EQ(style->parent.name, (ResourceName{ u"android", ResourceType::kStyle, u"foo" }));
    EXPECT_TRUE(style->parentInferred);
}

TEST_F(ResourceParserTest, ParseStyleWithInferredParentOverridenByEmptyParentAttribute) {
    std::string input = "<style name=\"foo.bar\" parent=\"\"/>";
    ASSERT_TRUE(testParse(input));

    const Style* style = findResource<Style>(ResourceName{
            u"android", ResourceType::kStyle, u"foo.bar" });
    ASSERT_NE(style, nullptr);
    EXPECT_FALSE(style->parent.name.isValid());
    EXPECT_FALSE(style->parentInferred);
}

TEST_F(ResourceParserTest, ParseAutoGeneratedIdReference) {
    std::string input = "<string name=\"foo\">@+id/bar</string>";
    ASSERT_TRUE(testParse(input));

    const Id* id = findResource<Id>(ResourceName{ u"android", ResourceType::kId, u"bar"});
    ASSERT_NE(id, nullptr);
}

TEST_F(ResourceParserTest, ParseAttributesDeclareStyleable) {
    std::string input = "<declare-styleable name=\"foo\">\n"
                        "  <attr name=\"bar\" />\n"
                        "  <attr name=\"bat\" format=\"string|reference\"/>\n"
                        "</declare-styleable>";
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
    std::string input = "<array name=\"foo\">\n"
                        "  <item>@string/ref</item>\n"
                        "  <item>hey</item>\n"
                        "  <item>23</item>\n"
                        "</array>";
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
    std::string input = "<plurals name=\"foo\">\n"
                        "  <item quantity=\"other\">apples</item>\n"
                        "  <item quantity=\"one\">apple</item>\n"
                        "</plurals>";
    ASSERT_TRUE(testParse(input));
}

TEST_F(ResourceParserTest, ParseCommentsWithResource) {
    std::string input = "<!-- This is a comment -->\n"
                        "<string name=\"foo\">Hi</string>";
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
    std::string input = "<public type=\"id\" name=\"foo\"/>";
    ASSERT_TRUE(testParse(input));

    const Id* id = findResource<Id>(ResourceName{ u"android", ResourceType::kId, u"foo" });
    ASSERT_NE(nullptr, id);
}

} // namespace aapt
