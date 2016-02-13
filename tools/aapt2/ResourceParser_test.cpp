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
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "test/Context.h"
#include "xml/XmlPullParser.h"

#include <gtest/gtest.h>
#include <sstream>
#include <string>

namespace aapt {

constexpr const char* kXmlPreamble = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

TEST(ResourceParserSingleTest, FailToParseWithNoRootResourcesElement) {
    std::unique_ptr<IAaptContext> context = test::ContextBuilder().build();
    std::stringstream input(kXmlPreamble);
    input << "<attr name=\"foo\"/>" << std::endl;
    ResourceTable table;
    ResourceParser parser(context->getDiagnostics(), &table, Source{ "test" }, {});
    xml::XmlPullParser xmlParser(input);
    ASSERT_FALSE(parser.parse(&xmlParser));
}

struct ResourceParserTest : public ::testing::Test {
    ResourceTable mTable;
    std::unique_ptr<IAaptContext> mContext;

    void SetUp() override {
        mContext = test::ContextBuilder().build();
    }

    ::testing::AssertionResult testParse(const StringPiece& str) {
        return testParse(str, ConfigDescription{});
    }

    ::testing::AssertionResult testParse(const StringPiece& str, const ConfigDescription& config) {
        std::stringstream input(kXmlPreamble);
        input << "<resources>\n" << str << "\n</resources>" << std::endl;
        ResourceParserOptions parserOptions;
        ResourceParser parser(mContext->getDiagnostics(), &mTable, Source{ "test" }, config,
                              parserOptions);
        xml::XmlPullParser xmlParser(input);
        if (parser.parse(&xmlParser)) {
            return ::testing::AssertionSuccess();
        }
        return ::testing::AssertionFailure();
    }
};

TEST_F(ResourceParserTest, ParseQuotedString) {
    std::string input = "<string name=\"foo\">   \"  hey there \" </string>";
    ASSERT_TRUE(testParse(input));

    String* str = test::getValue<String>(&mTable, u"@string/foo");
    ASSERT_NE(nullptr, str);
    EXPECT_EQ(std::u16string(u"  hey there "), *str->value);
}

TEST_F(ResourceParserTest, ParseEscapedString) {
    std::string input = "<string name=\"foo\">\\?123</string>";
    ASSERT_TRUE(testParse(input));

    String* str = test::getValue<String>(&mTable, u"@string/foo");
    ASSERT_NE(nullptr, str);
    EXPECT_EQ(std::u16string(u"?123"), *str->value);
}

TEST_F(ResourceParserTest, ParseFormattedString) {
    std::string input = "<string name=\"foo\">%d %s</string>";
    ASSERT_FALSE(testParse(input));

    input = "<string name=\"foo\">%1$d %2$s</string>";
    ASSERT_TRUE(testParse(input));
}

TEST_F(ResourceParserTest, IgnoreXliffTags) {
    std::string input = "<string name=\"foo\" \n"
                        "        xmlns:xliff=\"urn:oasis:names:tc:xliff:document:1.2\">\n"
                        "  There are <xliff:g id=\"count\">%1$d</xliff:g> apples</string>";
    ASSERT_TRUE(testParse(input));

    String* str = test::getValue<String>(&mTable, u"@string/foo");
    ASSERT_NE(nullptr, str);
    EXPECT_EQ(StringPiece16(u"There are %1$d apples"), StringPiece16(*str->value));
}

TEST_F(ResourceParserTest, ParseNull) {
    std::string input = "<integer name=\"foo\">@null</integer>";
    ASSERT_TRUE(testParse(input));

    // The Android runtime treats a value of android::Res_value::TYPE_NULL as
    // a non-existing value, and this causes problems in styles when trying to resolve
    // an attribute. Null values must be encoded as android::Res_value::TYPE_REFERENCE
    // with a data value of 0.
    BinaryPrimitive* integer = test::getValue<BinaryPrimitive>(&mTable, u"@integer/foo");
    ASSERT_NE(nullptr, integer);
    EXPECT_EQ(uint16_t(android::Res_value::TYPE_REFERENCE), integer->value.dataType);
    EXPECT_EQ(0u, integer->value.data);
}

TEST_F(ResourceParserTest, ParseEmpty) {
    std::string input = "<integer name=\"foo\">@empty</integer>";
    ASSERT_TRUE(testParse(input));

    BinaryPrimitive* integer = test::getValue<BinaryPrimitive>(&mTable, u"@integer/foo");
    ASSERT_NE(nullptr, integer);
    EXPECT_EQ(uint16_t(android::Res_value::TYPE_NULL), integer->value.dataType);
    EXPECT_EQ(uint32_t(android::Res_value::DATA_NULL_EMPTY), integer->value.data);
}

TEST_F(ResourceParserTest, ParseAttr) {
    std::string input = "<attr name=\"foo\" format=\"string\"/>\n"
                        "<attr name=\"bar\"/>";
    ASSERT_TRUE(testParse(input));

    Attribute* attr = test::getValue<Attribute>(&mTable, u"@attr/foo");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ(uint32_t(android::ResTable_map::TYPE_STRING), attr->typeMask);

    attr = test::getValue<Attribute>(&mTable, u"@attr/bar");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ(uint32_t(android::ResTable_map::TYPE_ANY), attr->typeMask);
}

// Old AAPT allowed attributes to be defined under different configurations, but ultimately
// stored them with the default configuration. Check that we have the same behavior.
TEST_F(ResourceParserTest, ParseAttrAndDeclareStyleableUnderConfigButRecordAsNoConfig) {
    const ConfigDescription watchConfig = test::parseConfigOrDie("watch");
    std::string input = R"EOF(
        <attr name="foo" />
        <declare-styleable name="bar">
          <attr name="baz" />
        </declare-styleable>)EOF";
    ASSERT_TRUE(testParse(input, watchConfig));

    EXPECT_EQ(nullptr, test::getValueForConfig<Attribute>(&mTable, u"@attr/foo", watchConfig));
    EXPECT_EQ(nullptr, test::getValueForConfig<Attribute>(&mTable, u"@attr/baz", watchConfig));
    EXPECT_EQ(nullptr, test::getValueForConfig<Styleable>(&mTable, u"@styleable/bar", watchConfig));

    EXPECT_NE(nullptr, test::getValue<Attribute>(&mTable, u"@attr/foo"));
    EXPECT_NE(nullptr, test::getValue<Attribute>(&mTable, u"@attr/baz"));
    EXPECT_NE(nullptr, test::getValue<Styleable>(&mTable, u"@styleable/bar"));
}

TEST_F(ResourceParserTest, ParseAttrWithMinMax) {
    std::string input = "<attr name=\"foo\" min=\"10\" max=\"23\" format=\"integer\"/>";
    ASSERT_TRUE(testParse(input));

    Attribute* attr = test::getValue<Attribute>(&mTable, u"@attr/foo");
    ASSERT_NE(nullptr, attr);
    EXPECT_EQ(uint32_t(android::ResTable_map::TYPE_INTEGER), attr->typeMask);
    EXPECT_EQ(10, attr->minInt);
    EXPECT_EQ(23, attr->maxInt);
}

TEST_F(ResourceParserTest, FailParseAttrWithMinMaxButNotInteger) {
    std::string input = "<attr name=\"foo\" min=\"10\" max=\"23\" format=\"string\"/>";
    ASSERT_FALSE(testParse(input));
}

TEST_F(ResourceParserTest, ParseUseAndDeclOfAttr) {
    std::string input = "<declare-styleable name=\"Styleable\">\n"
                        "  <attr name=\"foo\" />\n"
                        "</declare-styleable>\n"
                        "<attr name=\"foo\" format=\"string\"/>";
    ASSERT_TRUE(testParse(input));

    Attribute* attr = test::getValue<Attribute>(&mTable, u"@attr/foo");
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

    Attribute* attr = test::getValue<Attribute>(&mTable, u"@attr/foo");
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

    Attribute* enumAttr = test::getValue<Attribute>(&mTable, u"@attr/foo");
    ASSERT_NE(enumAttr, nullptr);
    EXPECT_EQ(enumAttr->typeMask, android::ResTable_map::TYPE_ENUM);
    ASSERT_EQ(enumAttr->symbols.size(), 3u);

    AAPT_ASSERT_TRUE(enumAttr->symbols[0].symbol.name);
    EXPECT_EQ(enumAttr->symbols[0].symbol.name.value().entry, u"bar");
    EXPECT_EQ(enumAttr->symbols[0].value, 0u);

    AAPT_ASSERT_TRUE(enumAttr->symbols[1].symbol.name);
    EXPECT_EQ(enumAttr->symbols[1].symbol.name.value().entry, u"bat");
    EXPECT_EQ(enumAttr->symbols[1].value, 1u);

    AAPT_ASSERT_TRUE(enumAttr->symbols[2].symbol.name);
    EXPECT_EQ(enumAttr->symbols[2].symbol.name.value().entry, u"baz");
    EXPECT_EQ(enumAttr->symbols[2].value, 2u);
}

TEST_F(ResourceParserTest, ParseFlagAttr) {
    std::string input = "<attr name=\"foo\">\n"
                        "  <flag name=\"bar\" value=\"0\"/>\n"
                        "  <flag name=\"bat\" value=\"1\"/>\n"
                        "  <flag name=\"baz\" value=\"2\"/>\n"
                        "</attr>";
    ASSERT_TRUE(testParse(input));

    Attribute* flagAttr = test::getValue<Attribute>(&mTable, u"@attr/foo");
    ASSERT_NE(nullptr, flagAttr);
    EXPECT_EQ(flagAttr->typeMask, android::ResTable_map::TYPE_FLAGS);
    ASSERT_EQ(flagAttr->symbols.size(), 3u);

    AAPT_ASSERT_TRUE(flagAttr->symbols[0].symbol.name);
    EXPECT_EQ(flagAttr->symbols[0].symbol.name.value().entry, u"bar");
    EXPECT_EQ(flagAttr->symbols[0].value, 0u);

    AAPT_ASSERT_TRUE(flagAttr->symbols[1].symbol.name);
    EXPECT_EQ(flagAttr->symbols[1].symbol.name.value().entry, u"bat");
    EXPECT_EQ(flagAttr->symbols[1].value, 1u);

    AAPT_ASSERT_TRUE(flagAttr->symbols[2].symbol.name);
    EXPECT_EQ(flagAttr->symbols[2].symbol.name.value().entry, u"baz");
    EXPECT_EQ(flagAttr->symbols[2].value, 2u);

    std::unique_ptr<BinaryPrimitive> flagValue = ResourceUtils::tryParseFlagSymbol(flagAttr,
                                                                                   u"baz|bat");
    ASSERT_NE(nullptr, flagValue);
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

    Style* style = test::getValue<Style>(&mTable, u"@style/foo");
    ASSERT_NE(nullptr, style);
    AAPT_ASSERT_TRUE(style->parent);
    AAPT_ASSERT_TRUE(style->parent.value().name);
    EXPECT_EQ(test::parseNameOrDie(u"@style/fu"), style->parent.value().name.value());
    ASSERT_EQ(3u, style->entries.size());

    AAPT_ASSERT_TRUE(style->entries[0].key.name);
    EXPECT_EQ(test::parseNameOrDie(u"@attr/bar"), style->entries[0].key.name.value());

    AAPT_ASSERT_TRUE(style->entries[1].key.name);
    EXPECT_EQ(test::parseNameOrDie(u"@attr/bat"), style->entries[1].key.name.value());

    AAPT_ASSERT_TRUE(style->entries[2].key.name);
    EXPECT_EQ(test::parseNameOrDie(u"@attr/baz"), style->entries[2].key.name.value());
}

TEST_F(ResourceParserTest, ParseStyleWithShorthandParent) {
    std::string input = "<style name=\"foo\" parent=\"com.app:Theme\"/>";
    ASSERT_TRUE(testParse(input));

    Style* style = test::getValue<Style>(&mTable, u"@style/foo");
    ASSERT_NE(nullptr, style);
    AAPT_ASSERT_TRUE(style->parent);
    AAPT_ASSERT_TRUE(style->parent.value().name);
    EXPECT_EQ(test::parseNameOrDie(u"@com.app:style/Theme"), style->parent.value().name.value());
}

TEST_F(ResourceParserTest, ParseStyleWithPackageAliasedParent) {
    std::string input = "<style xmlns:app=\"http://schemas.android.com/apk/res/android\"\n"
                        "       name=\"foo\" parent=\"app:Theme\"/>";
    ASSERT_TRUE(testParse(input));

    Style* style = test::getValue<Style>(&mTable, u"@style/foo");
    ASSERT_NE(nullptr, style);
    AAPT_ASSERT_TRUE(style->parent);
    AAPT_ASSERT_TRUE(style->parent.value().name);
    EXPECT_EQ(test::parseNameOrDie(u"@android:style/Theme"), style->parent.value().name.value());
}

TEST_F(ResourceParserTest, ParseStyleWithPackageAliasedItems) {
    std::string input =
            "<style xmlns:app=\"http://schemas.android.com/apk/res/android\" name=\"foo\">\n"
            "  <item name=\"app:bar\">0</item>\n"
            "</style>";
    ASSERT_TRUE(testParse(input));

    Style* style = test::getValue<Style>(&mTable, u"@style/foo");
    ASSERT_NE(nullptr, style);
    ASSERT_EQ(1u, style->entries.size());
    EXPECT_EQ(test::parseNameOrDie(u"@android:attr/bar"), style->entries[0].key.name.value());
}

TEST_F(ResourceParserTest, ParseStyleWithInferredParent) {
    std::string input = "<style name=\"foo.bar\"/>";
    ASSERT_TRUE(testParse(input));

    Style* style = test::getValue<Style>(&mTable, u"@style/foo.bar");
    ASSERT_NE(nullptr, style);
    AAPT_ASSERT_TRUE(style->parent);
    AAPT_ASSERT_TRUE(style->parent.value().name);
    EXPECT_EQ(style->parent.value().name.value(), test::parseNameOrDie(u"@style/foo"));
    EXPECT_TRUE(style->parentInferred);
}

TEST_F(ResourceParserTest, ParseStyleWithInferredParentOverridenByEmptyParentAttribute) {
    std::string input = "<style name=\"foo.bar\" parent=\"\"/>";
    ASSERT_TRUE(testParse(input));

    Style* style = test::getValue<Style>(&mTable, u"@style/foo.bar");
    ASSERT_NE(nullptr, style);
    AAPT_EXPECT_FALSE(style->parent);
    EXPECT_FALSE(style->parentInferred);
}

TEST_F(ResourceParserTest, ParseStyleWithPrivateParentReference) {
    std::string input = R"EOF(<style name="foo" parent="*android:style/bar" />)EOF";
    ASSERT_TRUE(testParse(input));

    Style* style = test::getValue<Style>(&mTable, u"@style/foo");
    ASSERT_NE(nullptr, style);
    AAPT_ASSERT_TRUE(style->parent);
    EXPECT_TRUE(style->parent.value().privateReference);
}

TEST_F(ResourceParserTest, ParseAutoGeneratedIdReference) {
    std::string input = "<string name=\"foo\">@+id/bar</string>";
    ASSERT_TRUE(testParse(input));

    Id* id = test::getValue<Id>(&mTable, u"@id/bar");
    ASSERT_NE(id, nullptr);
}

TEST_F(ResourceParserTest, ParseAttributesDeclareStyleable) {
    std::string input = "<declare-styleable name=\"foo\">\n"
                        "  <attr name=\"bar\" />\n"
                        "  <attr name=\"bat\" format=\"string|reference\"/>\n"
                        "  <attr name=\"baz\">\n"
                        "    <enum name=\"foo\" value=\"1\"/>\n"
                        "  </attr>\n"
                        "</declare-styleable>";
    ASSERT_TRUE(testParse(input));

    Maybe<ResourceTable::SearchResult> result =
            mTable.findResource(test::parseNameOrDie(u"@styleable/foo"));
    AAPT_ASSERT_TRUE(result);
    EXPECT_EQ(SymbolState::kPublic, result.value().entry->symbolStatus.state);

    Attribute* attr = test::getValue<Attribute>(&mTable, u"@attr/bar");
    ASSERT_NE(attr, nullptr);
    EXPECT_TRUE(attr->isWeak());

    attr = test::getValue<Attribute>(&mTable, u"@attr/bat");
    ASSERT_NE(attr, nullptr);
    EXPECT_TRUE(attr->isWeak());

    attr = test::getValue<Attribute>(&mTable, u"@attr/baz");
    ASSERT_NE(attr, nullptr);
    EXPECT_TRUE(attr->isWeak());
    EXPECT_EQ(1u, attr->symbols.size());

    EXPECT_NE(nullptr, test::getValue<Id>(&mTable, u"@id/foo"));

    Styleable* styleable = test::getValue<Styleable>(&mTable, u"@styleable/foo");
    ASSERT_NE(styleable, nullptr);
    ASSERT_EQ(3u, styleable->entries.size());

    EXPECT_EQ(test::parseNameOrDie(u"@attr/bar"), styleable->entries[0].name.value());
    EXPECT_EQ(test::parseNameOrDie(u"@attr/bat"), styleable->entries[1].name.value());
}

TEST_F(ResourceParserTest, ParsePrivateAttributesDeclareStyleable) {
    std::string input = "<declare-styleable name=\"foo\" xmlns:privAndroid=\"http://schemas.android.com/apk/prv/res/android\">\n"
                        "  <attr name=\"*android:bar\" />\n"
                        "  <attr name=\"privAndroid:bat\" />\n"
                        "</declare-styleable>";
    ASSERT_TRUE(testParse(input));
    Styleable* styleable = test::getValue<Styleable>(&mTable, u"@styleable/foo");
    ASSERT_NE(nullptr, styleable);
    ASSERT_EQ(2u, styleable->entries.size());

    EXPECT_TRUE(styleable->entries[0].privateReference);
    AAPT_ASSERT_TRUE(styleable->entries[0].name);
    EXPECT_EQ(std::u16string(u"android"), styleable->entries[0].name.value().package);

    EXPECT_TRUE(styleable->entries[1].privateReference);
    AAPT_ASSERT_TRUE(styleable->entries[1].name);
    EXPECT_EQ(std::u16string(u"android"), styleable->entries[1].name.value().package);
}

TEST_F(ResourceParserTest, ParseArray) {
    std::string input = "<array name=\"foo\">\n"
                        "  <item>@string/ref</item>\n"
                        "  <item>hey</item>\n"
                        "  <item>23</item>\n"
                        "</array>";
    ASSERT_TRUE(testParse(input));

    Array* array = test::getValue<Array>(&mTable, u"@array/foo");
    ASSERT_NE(array, nullptr);
    ASSERT_EQ(3u, array->items.size());

    EXPECT_NE(nullptr, valueCast<Reference>(array->items[0].get()));
    EXPECT_NE(nullptr, valueCast<String>(array->items[1].get()));
    EXPECT_NE(nullptr, valueCast<BinaryPrimitive>(array->items[2].get()));
}

TEST_F(ResourceParserTest, ParseStringArray) {
    std::string input = "<string-array name=\"foo\">\n"
                        "  <item>\"Werk\"</item>\n"
                        "</string-array>\n";
    ASSERT_TRUE(testParse(input));
    EXPECT_NE(nullptr, test::getValue<Array>(&mTable, u"@array/foo"));
}

TEST_F(ResourceParserTest, ParsePlural) {
    std::string input = "<plurals name=\"foo\">\n"
                        "  <item quantity=\"other\">apples</item>\n"
                        "  <item quantity=\"one\">apple</item>\n"
                        "</plurals>";
    ASSERT_TRUE(testParse(input));
}

TEST_F(ResourceParserTest, ParseCommentsWithResource) {
    std::string input = "<!--This is a comment-->\n"
                        "<string name=\"foo\">Hi</string>";
    ASSERT_TRUE(testParse(input));

    String* value = test::getValue<String>(&mTable, u"@string/foo");
    ASSERT_NE(nullptr, value);
    EXPECT_EQ(value->getComment(), u"This is a comment");
}

TEST_F(ResourceParserTest, DoNotCombineMultipleComments) {
    std::string input = "<!--One-->\n"
                        "<!--Two-->\n"
                        "<string name=\"foo\">Hi</string>";

    ASSERT_TRUE(testParse(input));

    String* value = test::getValue<String>(&mTable, u"@string/foo");
    ASSERT_NE(nullptr, value);
    EXPECT_EQ(value->getComment(), u"Two");
}

TEST_F(ResourceParserTest, IgnoreCommentBeforeEndTag) {
    std::string input = "<!--One-->\n"
                        "<string name=\"foo\">\n"
                        "  Hi\n"
                        "<!--Two-->\n"
                        "</string>";

    ASSERT_TRUE(testParse(input));

    String* value = test::getValue<String>(&mTable, u"@string/foo");
    ASSERT_NE(nullptr, value);
    EXPECT_EQ(value->getComment(), u"One");
}

TEST_F(ResourceParserTest, ParseNestedComments) {
    // We only care about declare-styleable and enum/flag attributes because comments
    // from those end up in R.java
    std::string input = R"EOF(
        <declare-styleable name="foo">
          <!-- The name of the bar -->
          <attr name="barName" format="string|reference" />
        </declare-styleable>

        <attr name="foo">
          <!-- The very first -->
          <enum name="one" value="1" />
        </attr>)EOF";
    ASSERT_TRUE(testParse(input));

    Styleable* styleable = test::getValue<Styleable>(&mTable, u"@styleable/foo");
    ASSERT_NE(nullptr, styleable);
    ASSERT_EQ(1u, styleable->entries.size());

    EXPECT_EQ(StringPiece16(u"The name of the bar"), styleable->entries.front().getComment());

    Attribute* attr = test::getValue<Attribute>(&mTable, u"@attr/foo");
    ASSERT_NE(nullptr, attr);
    ASSERT_EQ(1u, attr->symbols.size());

    EXPECT_EQ(StringPiece16(u"The very first"), attr->symbols.front().symbol.getComment());
}

/*
 * Declaring an ID as public should not require a separate definition
 * (as an ID has no value).
 */
TEST_F(ResourceParserTest, ParsePublicIdAsDefinition) {
    std::string input = "<public type=\"id\" name=\"foo\"/>";
    ASSERT_TRUE(testParse(input));

    Id* id = test::getValue<Id>(&mTable, u"@id/foo");
    ASSERT_NE(nullptr, id);
}

TEST_F(ResourceParserTest, KeepAllProducts) {
    std::string input = R"EOF(
        <string name="foo" product="phone">hi</string>
        <string name="foo" product="no-sdcard">ho</string>
        <string name="bar" product="">wee</string>
        <string name="baz">woo</string>
        <string name="bit" product="phablet">hoot</string>
        <string name="bot" product="default">yes</string>
    )EOF";
    ASSERT_TRUE(testParse(input));

    EXPECT_NE(nullptr, test::getValueForConfigAndProduct<String>(&mTable, u"@string/foo",
                                                                 ConfigDescription::defaultConfig(),
                                                                 "phone"));
    EXPECT_NE(nullptr, test::getValueForConfigAndProduct<String>(&mTable, u"@string/foo",
                                                                 ConfigDescription::defaultConfig(),
                                                                 "no-sdcard"));
    EXPECT_NE(nullptr, test::getValueForConfigAndProduct<String>(&mTable, u"@string/bar",
                                                                 ConfigDescription::defaultConfig(),
                                                                 ""));
    EXPECT_NE(nullptr, test::getValueForConfigAndProduct<String>(&mTable, u"@string/baz",
                                                                 ConfigDescription::defaultConfig(),
                                                                 ""));
    EXPECT_NE(nullptr, test::getValueForConfigAndProduct<String>(&mTable, u"@string/bit",
                                                                 ConfigDescription::defaultConfig(),
                                                                 "phablet"));
    EXPECT_NE(nullptr, test::getValueForConfigAndProduct<String>(&mTable, u"@string/bot",
                                                                 ConfigDescription::defaultConfig(),
                                                                 "default"));
}

TEST_F(ResourceParserTest, AutoIncrementIdsInPublicGroup) {
    std::string input = R"EOF(
    <public-group type="attr" first-id="0x01010040">
      <public name="foo" />
      <public name="bar" />
    </public-group>)EOF";
    ASSERT_TRUE(testParse(input));

    Maybe<ResourceTable::SearchResult> result = mTable.findResource(
            test::parseNameOrDie(u"@attr/foo"));
    AAPT_ASSERT_TRUE(result);

    AAPT_ASSERT_TRUE(result.value().package->id);
    AAPT_ASSERT_TRUE(result.value().type->id);
    AAPT_ASSERT_TRUE(result.value().entry->id);
    ResourceId actualId(result.value().package->id.value(),
                        result.value().type->id.value(),
                        result.value().entry->id.value());
    EXPECT_EQ(ResourceId(0x01010040), actualId);

    result = mTable.findResource(test::parseNameOrDie(u"@attr/bar"));
    AAPT_ASSERT_TRUE(result);

    AAPT_ASSERT_TRUE(result.value().package->id);
    AAPT_ASSERT_TRUE(result.value().type->id);
    AAPT_ASSERT_TRUE(result.value().entry->id);
    actualId = ResourceId(result.value().package->id.value(),
                          result.value().type->id.value(),
                          result.value().entry->id.value());
    EXPECT_EQ(ResourceId(0x01010041), actualId);
}

TEST_F(ResourceParserTest, ExternalTypesShouldOnlyBeReferences) {
    std::string input = R"EOF(<item type="layout" name="foo">@layout/bar</item>)EOF";
    ASSERT_TRUE(testParse(input));

    input = R"EOF(<item type="layout" name="bar">"this is a string"</item>)EOF";
    ASSERT_FALSE(testParse(input));
}

TEST_F(ResourceParserTest, AddResourcesElementShouldAddEntryWithUndefinedSymbol) {
    std::string input = R"EOF(<add-resource name="bar" type="string" />)EOF";
    ASSERT_TRUE(testParse(input));

    Maybe<ResourceTable::SearchResult> result = mTable.findResource(
            test::parseNameOrDie(u"@string/bar"));
    AAPT_ASSERT_TRUE(result);
    const ResourceEntry* entry = result.value().entry;
    ASSERT_NE(nullptr, entry);
    EXPECT_EQ(SymbolState::kUndefined, entry->symbolStatus.state);
}

TEST_F(ResourceParserTest, ParseItemElementWithFormat) {
    std::string input = R"EOF(<item name="foo" type="integer" format="float">0.3</item>)EOF";
    ASSERT_TRUE(testParse(input));

    BinaryPrimitive* val = test::getValue<BinaryPrimitive>(&mTable, u"@integer/foo");
    ASSERT_NE(nullptr, val);

    EXPECT_EQ(uint32_t(android::Res_value::TYPE_FLOAT), val->value.dataType);
}

} // namespace aapt
