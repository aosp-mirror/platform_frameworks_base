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

#include <sstream>
#include <string>

#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "test/Test.h"
#include "xml/XmlPullParser.h"

using ::android::StringPiece;
using ::testing::Eq;
using ::testing::NotNull;

namespace aapt {

constexpr const char* kXmlPreamble =
    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

TEST(ResourceParserSingleTest, FailToParseWithNoRootResourcesElement) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::stringstream input(kXmlPreamble);
  input << "<attr name=\"foo\"/>" << std::endl;
  ResourceTable table;
  ResourceParser parser(context->GetDiagnostics(), &table, Source{"test"}, {});
  xml::XmlPullParser xml_parser(input);
  ASSERT_FALSE(parser.Parse(&xml_parser));
}

class ResourceParserTest : public ::testing::Test {
 public:
  void SetUp() override { context_ = test::ContextBuilder().Build(); }

  ::testing::AssertionResult TestParse(const StringPiece& str) {
    return TestParse(str, ConfigDescription{});
  }

  ::testing::AssertionResult TestParse(const StringPiece& str,
                                       const ConfigDescription& config) {
    std::stringstream input(kXmlPreamble);
    input << "<resources>\n" << str << "\n</resources>" << std::endl;
    ResourceParserOptions parserOptions;
    ResourceParser parser(context_->GetDiagnostics(), &table_, Source{"test"},
                          config, parserOptions);
    xml::XmlPullParser xmlParser(input);
    if (parser.Parse(&xmlParser)) {
      return ::testing::AssertionSuccess();
    }
    return ::testing::AssertionFailure();
  }

 protected:
  ResourceTable table_;
  std::unique_ptr<IAaptContext> context_;
};

TEST_F(ResourceParserTest, ParseQuotedString) {
  std::string input = "<string name=\"foo\">   \"  hey there \" </string>";
  ASSERT_TRUE(TestParse(input));

  String* str = test::GetValue<String>(&table_, "string/foo");
  ASSERT_NE(nullptr, str);
  EXPECT_EQ(std::string("  hey there "), *str->value);
  EXPECT_TRUE(str->untranslatable_sections.empty());
}

TEST_F(ResourceParserTest, ParseEscapedString) {
  std::string input = "<string name=\"foo\">\\?123</string>";
  ASSERT_TRUE(TestParse(input));

  String* str = test::GetValue<String>(&table_, "string/foo");
  ASSERT_NE(nullptr, str);
  EXPECT_EQ(std::string("?123"), *str->value);
  EXPECT_TRUE(str->untranslatable_sections.empty());
}

TEST_F(ResourceParserTest, ParseFormattedString) {
  std::string input = "<string name=\"foo\">%d %s</string>";
  ASSERT_FALSE(TestParse(input));

  input = "<string name=\"foo\">%1$d %2$s</string>";
  ASSERT_TRUE(TestParse(input));
}

TEST_F(ResourceParserTest, ParseStyledString) {
  // Use a surrogate pair unicode point so that we can verify that the span
  // indices use UTF-16 length and not UTF-8 length.
  std::string input =
      "<string name=\"foo\">This is my aunt\u2019s <b>fickle <small>string</small></b></string>";
  ASSERT_TRUE(TestParse(input));

  StyledString* str = test::GetValue<StyledString>(&table_, "string/foo");
  ASSERT_NE(nullptr, str);

  const std::string expected_str = "This is my aunt\u2019s fickle string";
  EXPECT_EQ(expected_str, *str->value->str);
  EXPECT_EQ(2u, str->value->spans.size());
  EXPECT_TRUE(str->untranslatable_sections.empty());

  EXPECT_EQ(std::string("b"), *str->value->spans[0].name);
  EXPECT_EQ(17u, str->value->spans[0].first_char);
  EXPECT_EQ(30u, str->value->spans[0].last_char);

  EXPECT_EQ(std::string("small"), *str->value->spans[1].name);
  EXPECT_EQ(24u, str->value->spans[1].first_char);
  EXPECT_EQ(30u, str->value->spans[1].last_char);
}

TEST_F(ResourceParserTest, ParseStringWithWhitespace) {
  std::string input = "<string name=\"foo\">  This is what  I think  </string>";
  ASSERT_TRUE(TestParse(input));

  String* str = test::GetValue<String>(&table_, "string/foo");
  ASSERT_NE(nullptr, str);
  EXPECT_EQ(std::string("This is what I think"), *str->value);
  EXPECT_TRUE(str->untranslatable_sections.empty());

  input = "<string name=\"foo2\">\"  This is what  I think  \"</string>";
  ASSERT_TRUE(TestParse(input));

  str = test::GetValue<String>(&table_, "string/foo2");
  ASSERT_NE(nullptr, str);
  EXPECT_EQ(std::string("  This is what  I think  "), *str->value);
}

TEST_F(ResourceParserTest, IgnoreXliffTagsOtherThanG) {
  std::string input = R"EOF(
      <string name="foo" xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
          There are <xliff:source>no</xliff:source> apples</string>)EOF";
  ASSERT_TRUE(TestParse(input));

  String* str = test::GetValue<String>(&table_, "string/foo");
  ASSERT_NE(nullptr, str);
  EXPECT_EQ(StringPiece("There are no apples"), StringPiece(*str->value));
  EXPECT_TRUE(str->untranslatable_sections.empty());
}

TEST_F(ResourceParserTest, NestedXliffGTagsAreIllegal) {
  std::string input = R"EOF(
      <string name="foo" xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
          Do not <xliff:g>translate <xliff:g>this</xliff:g></xliff:g></string>)EOF";
  EXPECT_FALSE(TestParse(input));
}

TEST_F(ResourceParserTest, RecordUntranslateableXliffSectionsInString) {
  std::string input = R"EOF(
      <string name="foo" xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
          There are <xliff:g id="count">%1$d</xliff:g> apples</string>)EOF";
  ASSERT_TRUE(TestParse(input));

  String* str = test::GetValue<String>(&table_, "string/foo");
  ASSERT_NE(nullptr, str);
  EXPECT_EQ(StringPiece("There are %1$d apples"), StringPiece(*str->value));

  ASSERT_EQ(1u, str->untranslatable_sections.size());

  // We expect indices and lengths that span to include the whitespace
  // before %1$d. This is due to how the StringBuilder withholds whitespace unless
  // needed (to deal with line breaks, etc.).
  EXPECT_EQ(9u, str->untranslatable_sections[0].start);
  EXPECT_EQ(14u, str->untranslatable_sections[0].end);
}

TEST_F(ResourceParserTest, RecordUntranslateableXliffSectionsInStyledString) {
  std::string input = R"EOF(
      <string name="foo" xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
          There are <b><xliff:g id="count">%1$d</xliff:g></b> apples</string>)EOF";
  ASSERT_TRUE(TestParse(input));

  StyledString* str = test::GetValue<StyledString>(&table_, "string/foo");
  ASSERT_NE(nullptr, str);
  EXPECT_EQ(StringPiece("There are %1$d apples"), StringPiece(*str->value->str));

  ASSERT_EQ(1u, str->untranslatable_sections.size());

  // We expect indices and lengths that span to include the whitespace
  // before %1$d. This is due to how the StringBuilder withholds whitespace unless
  // needed (to deal with line breaks, etc.).
  EXPECT_EQ(9u, str->untranslatable_sections[0].start);
  EXPECT_EQ(14u, str->untranslatable_sections[0].end);
}

TEST_F(ResourceParserTest, ParseNull) {
  std::string input = "<integer name=\"foo\">@null</integer>";
  ASSERT_TRUE(TestParse(input));

  // The Android runtime treats a value of android::Res_value::TYPE_NULL as
  // a non-existing value, and this causes problems in styles when trying to
  // resolve an attribute. Null values must be encoded as android::Res_value::TYPE_REFERENCE
  // with a data value of 0.
  BinaryPrimitive* integer = test::GetValue<BinaryPrimitive>(&table_, "integer/foo");
  ASSERT_NE(nullptr, integer);
  EXPECT_EQ(uint16_t(android::Res_value::TYPE_REFERENCE), integer->value.dataType);
  EXPECT_EQ(0u, integer->value.data);
}

TEST_F(ResourceParserTest, ParseEmpty) {
  std::string input = "<integer name=\"foo\">@empty</integer>";
  ASSERT_TRUE(TestParse(input));

  BinaryPrimitive* integer =
      test::GetValue<BinaryPrimitive>(&table_, "integer/foo");
  ASSERT_NE(nullptr, integer);
  EXPECT_EQ(uint16_t(android::Res_value::TYPE_NULL), integer->value.dataType);
  EXPECT_EQ(uint32_t(android::Res_value::DATA_NULL_EMPTY), integer->value.data);
}

TEST_F(ResourceParserTest, ParseAttr) {
  std::string input =
      "<attr name=\"foo\" format=\"string\"/>\n"
      "<attr name=\"bar\"/>";
  ASSERT_TRUE(TestParse(input));

  Attribute* attr = test::GetValue<Attribute>(&table_, "attr/foo");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ(uint32_t(android::ResTable_map::TYPE_STRING), attr->type_mask);

  attr = test::GetValue<Attribute>(&table_, "attr/bar");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ(uint32_t(android::ResTable_map::TYPE_ANY), attr->type_mask);
}

// Old AAPT allowed attributes to be defined under different configurations, but
// ultimately
// stored them with the default configuration. Check that we have the same
// behavior.
TEST_F(ResourceParserTest,
       ParseAttrAndDeclareStyleableUnderConfigButRecordAsNoConfig) {
  const ConfigDescription watch_config = test::ParseConfigOrDie("watch");
  std::string input = R"EOF(
        <attr name="foo" />
        <declare-styleable name="bar">
          <attr name="baz" />
        </declare-styleable>)EOF";
  ASSERT_TRUE(TestParse(input, watch_config));

  EXPECT_EQ(nullptr, test::GetValueForConfig<Attribute>(&table_, "attr/foo",
                                                        watch_config));
  EXPECT_EQ(nullptr, test::GetValueForConfig<Attribute>(&table_, "attr/baz",
                                                        watch_config));
  EXPECT_EQ(nullptr, test::GetValueForConfig<Styleable>(
                         &table_, "styleable/bar", watch_config));

  EXPECT_NE(nullptr, test::GetValue<Attribute>(&table_, "attr/foo"));
  EXPECT_NE(nullptr, test::GetValue<Attribute>(&table_, "attr/baz"));
  EXPECT_NE(nullptr, test::GetValue<Styleable>(&table_, "styleable/bar"));
}

TEST_F(ResourceParserTest, ParseAttrWithMinMax) {
  std::string input =
      "<attr name=\"foo\" min=\"10\" max=\"23\" format=\"integer\"/>";
  ASSERT_TRUE(TestParse(input));

  Attribute* attr = test::GetValue<Attribute>(&table_, "attr/foo");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ(uint32_t(android::ResTable_map::TYPE_INTEGER), attr->type_mask);
  EXPECT_EQ(10, attr->min_int);
  EXPECT_EQ(23, attr->max_int);
}

TEST_F(ResourceParserTest, FailParseAttrWithMinMaxButNotInteger) {
  std::string input =
      "<attr name=\"foo\" min=\"10\" max=\"23\" format=\"string\"/>";
  ASSERT_FALSE(TestParse(input));
}

TEST_F(ResourceParserTest, ParseUseAndDeclOfAttr) {
  std::string input =
      "<declare-styleable name=\"Styleable\">\n"
      "  <attr name=\"foo\" />\n"
      "</declare-styleable>\n"
      "<attr name=\"foo\" format=\"string\"/>";
  ASSERT_TRUE(TestParse(input));

  Attribute* attr = test::GetValue<Attribute>(&table_, "attr/foo");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ(uint32_t(android::ResTable_map::TYPE_STRING), attr->type_mask);
}

TEST_F(ResourceParserTest, ParseDoubleUseOfAttr) {
  std::string input =
      "<declare-styleable name=\"Theme\">"
      "  <attr name=\"foo\" />\n"
      "</declare-styleable>\n"
      "<declare-styleable name=\"Window\">\n"
      "  <attr name=\"foo\" format=\"boolean\"/>\n"
      "</declare-styleable>";
  ASSERT_TRUE(TestParse(input));

  Attribute* attr = test::GetValue<Attribute>(&table_, "attr/foo");
  ASSERT_NE(nullptr, attr);
  EXPECT_EQ(uint32_t(android::ResTable_map::TYPE_BOOLEAN), attr->type_mask);
}

TEST_F(ResourceParserTest, ParseEnumAttr) {
  std::string input =
      "<attr name=\"foo\">\n"
      "  <enum name=\"bar\" value=\"0\"/>\n"
      "  <enum name=\"bat\" value=\"1\"/>\n"
      "  <enum name=\"baz\" value=\"2\"/>\n"
      "</attr>";
  ASSERT_TRUE(TestParse(input));

  Attribute* enum_attr = test::GetValue<Attribute>(&table_, "attr/foo");
  ASSERT_NE(enum_attr, nullptr);
  EXPECT_EQ(enum_attr->type_mask, android::ResTable_map::TYPE_ENUM);
  ASSERT_EQ(enum_attr->symbols.size(), 3u);

  AAPT_ASSERT_TRUE(enum_attr->symbols[0].symbol.name);
  EXPECT_EQ(enum_attr->symbols[0].symbol.name.value().entry, "bar");
  EXPECT_EQ(enum_attr->symbols[0].value, 0u);

  AAPT_ASSERT_TRUE(enum_attr->symbols[1].symbol.name);
  EXPECT_EQ(enum_attr->symbols[1].symbol.name.value().entry, "bat");
  EXPECT_EQ(enum_attr->symbols[1].value, 1u);

  AAPT_ASSERT_TRUE(enum_attr->symbols[2].symbol.name);
  EXPECT_EQ(enum_attr->symbols[2].symbol.name.value().entry, "baz");
  EXPECT_EQ(enum_attr->symbols[2].value, 2u);
}

TEST_F(ResourceParserTest, ParseFlagAttr) {
  std::string input =
      "<attr name=\"foo\">\n"
      "  <flag name=\"bar\" value=\"0\"/>\n"
      "  <flag name=\"bat\" value=\"1\"/>\n"
      "  <flag name=\"baz\" value=\"2\"/>\n"
      "</attr>";
  ASSERT_TRUE(TestParse(input));

  Attribute* flag_attr = test::GetValue<Attribute>(&table_, "attr/foo");
  ASSERT_NE(nullptr, flag_attr);
  EXPECT_EQ(flag_attr->type_mask, android::ResTable_map::TYPE_FLAGS);
  ASSERT_EQ(flag_attr->symbols.size(), 3u);

  AAPT_ASSERT_TRUE(flag_attr->symbols[0].symbol.name);
  EXPECT_EQ(flag_attr->symbols[0].symbol.name.value().entry, "bar");
  EXPECT_EQ(flag_attr->symbols[0].value, 0u);

  AAPT_ASSERT_TRUE(flag_attr->symbols[1].symbol.name);
  EXPECT_EQ(flag_attr->symbols[1].symbol.name.value().entry, "bat");
  EXPECT_EQ(flag_attr->symbols[1].value, 1u);

  AAPT_ASSERT_TRUE(flag_attr->symbols[2].symbol.name);
  EXPECT_EQ(flag_attr->symbols[2].symbol.name.value().entry, "baz");
  EXPECT_EQ(flag_attr->symbols[2].value, 2u);

  std::unique_ptr<BinaryPrimitive> flag_value =
      ResourceUtils::TryParseFlagSymbol(flag_attr, "baz|bat");
  ASSERT_NE(nullptr, flag_value);
  EXPECT_EQ(flag_value->value.data, 1u | 2u);
}

TEST_F(ResourceParserTest, FailToParseEnumAttrWithNonUniqueKeys) {
  std::string input =
      "<attr name=\"foo\">\n"
      "  <enum name=\"bar\" value=\"0\"/>\n"
      "  <enum name=\"bat\" value=\"1\"/>\n"
      "  <enum name=\"bat\" value=\"2\"/>\n"
      "</attr>";
  ASSERT_FALSE(TestParse(input));
}

TEST_F(ResourceParserTest, ParseStyle) {
  std::string input =
      "<style name=\"foo\" parent=\"@style/fu\">\n"
      "  <item name=\"bar\">#ffffffff</item>\n"
      "  <item name=\"bat\">@string/hey</item>\n"
      "  <item name=\"baz\"><b>hey</b></item>\n"
      "</style>";
  ASSERT_TRUE(TestParse(input));

  Style* style = test::GetValue<Style>(&table_, "style/foo");
  ASSERT_NE(nullptr, style);
  AAPT_ASSERT_TRUE(style->parent);
  AAPT_ASSERT_TRUE(style->parent.value().name);
  EXPECT_EQ(test::ParseNameOrDie("style/fu"),
            style->parent.value().name.value());
  ASSERT_EQ(3u, style->entries.size());

  AAPT_ASSERT_TRUE(style->entries[0].key.name);
  EXPECT_EQ(test::ParseNameOrDie("attr/bar"),
            style->entries[0].key.name.value());

  AAPT_ASSERT_TRUE(style->entries[1].key.name);
  EXPECT_EQ(test::ParseNameOrDie("attr/bat"),
            style->entries[1].key.name.value());

  AAPT_ASSERT_TRUE(style->entries[2].key.name);
  EXPECT_EQ(test::ParseNameOrDie("attr/baz"),
            style->entries[2].key.name.value());
}

TEST_F(ResourceParserTest, ParseStyleWithShorthandParent) {
  std::string input = "<style name=\"foo\" parent=\"com.app:Theme\"/>";
  ASSERT_TRUE(TestParse(input));

  Style* style = test::GetValue<Style>(&table_, "style/foo");
  ASSERT_NE(nullptr, style);
  AAPT_ASSERT_TRUE(style->parent);
  AAPT_ASSERT_TRUE(style->parent.value().name);
  EXPECT_EQ(test::ParseNameOrDie("com.app:style/Theme"),
            style->parent.value().name.value());
}

TEST_F(ResourceParserTest, ParseStyleWithPackageAliasedParent) {
  std::string input =
      "<style xmlns:app=\"http://schemas.android.com/apk/res/android\"\n"
      "       name=\"foo\" parent=\"app:Theme\"/>";
  ASSERT_TRUE(TestParse(input));

  Style* style = test::GetValue<Style>(&table_, "style/foo");
  ASSERT_NE(nullptr, style);
  AAPT_ASSERT_TRUE(style->parent);
  AAPT_ASSERT_TRUE(style->parent.value().name);
  EXPECT_EQ(test::ParseNameOrDie("android:style/Theme"),
            style->parent.value().name.value());
}

TEST_F(ResourceParserTest, ParseStyleWithPackageAliasedItems) {
  std::string input =
      "<style xmlns:app=\"http://schemas.android.com/apk/res/android\" "
      "name=\"foo\">\n"
      "  <item name=\"app:bar\">0</item>\n"
      "</style>";
  ASSERT_TRUE(TestParse(input));

  Style* style = test::GetValue<Style>(&table_, "style/foo");
  ASSERT_NE(nullptr, style);
  ASSERT_EQ(1u, style->entries.size());
  EXPECT_EQ(test::ParseNameOrDie("android:attr/bar"),
            style->entries[0].key.name.value());
}

TEST_F(ResourceParserTest, ParseStyleWithInferredParent) {
  std::string input = "<style name=\"foo.bar\"/>";
  ASSERT_TRUE(TestParse(input));

  Style* style = test::GetValue<Style>(&table_, "style/foo.bar");
  ASSERT_NE(nullptr, style);
  AAPT_ASSERT_TRUE(style->parent);
  AAPT_ASSERT_TRUE(style->parent.value().name);
  EXPECT_EQ(style->parent.value().name.value(),
            test::ParseNameOrDie("style/foo"));
  EXPECT_TRUE(style->parent_inferred);
}

TEST_F(ResourceParserTest,
       ParseStyleWithInferredParentOverridenByEmptyParentAttribute) {
  std::string input = "<style name=\"foo.bar\" parent=\"\"/>";
  ASSERT_TRUE(TestParse(input));

  Style* style = test::GetValue<Style>(&table_, "style/foo.bar");
  ASSERT_NE(nullptr, style);
  AAPT_EXPECT_FALSE(style->parent);
  EXPECT_FALSE(style->parent_inferred);
}

TEST_F(ResourceParserTest, ParseStyleWithPrivateParentReference) {
  std::string input =
      R"EOF(<style name="foo" parent="*android:style/bar" />)EOF";
  ASSERT_TRUE(TestParse(input));

  Style* style = test::GetValue<Style>(&table_, "style/foo");
  ASSERT_NE(nullptr, style);
  AAPT_ASSERT_TRUE(style->parent);
  EXPECT_TRUE(style->parent.value().private_reference);
}

TEST_F(ResourceParserTest, ParseAutoGeneratedIdReference) {
  std::string input = "<string name=\"foo\">@+id/bar</string>";
  ASSERT_TRUE(TestParse(input));

  Id* id = test::GetValue<Id>(&table_, "id/bar");
  ASSERT_NE(id, nullptr);
}

TEST_F(ResourceParserTest, ParseAttributesDeclareStyleable) {
  std::string input =
      "<declare-styleable name=\"foo\">\n"
      "  <attr name=\"bar\" />\n"
      "  <attr name=\"bat\" format=\"string|reference\"/>\n"
      "  <attr name=\"baz\">\n"
      "    <enum name=\"foo\" value=\"1\"/>\n"
      "  </attr>\n"
      "</declare-styleable>";
  ASSERT_TRUE(TestParse(input));

  Maybe<ResourceTable::SearchResult> result =
      table_.FindResource(test::ParseNameOrDie("styleable/foo"));
  AAPT_ASSERT_TRUE(result);
  EXPECT_EQ(SymbolState::kPublic, result.value().entry->symbol_status.state);

  Attribute* attr = test::GetValue<Attribute>(&table_, "attr/bar");
  ASSERT_NE(attr, nullptr);
  EXPECT_TRUE(attr->IsWeak());

  attr = test::GetValue<Attribute>(&table_, "attr/bat");
  ASSERT_NE(attr, nullptr);
  EXPECT_TRUE(attr->IsWeak());

  attr = test::GetValue<Attribute>(&table_, "attr/baz");
  ASSERT_NE(attr, nullptr);
  EXPECT_TRUE(attr->IsWeak());
  EXPECT_EQ(1u, attr->symbols.size());

  EXPECT_NE(nullptr, test::GetValue<Id>(&table_, "id/foo"));

  Styleable* styleable = test::GetValue<Styleable>(&table_, "styleable/foo");
  ASSERT_NE(styleable, nullptr);
  ASSERT_EQ(3u, styleable->entries.size());

  EXPECT_EQ(test::ParseNameOrDie("attr/bar"),
            styleable->entries[0].name.value());
  EXPECT_EQ(test::ParseNameOrDie("attr/bat"),
            styleable->entries[1].name.value());
}

TEST_F(ResourceParserTest, ParsePrivateAttributesDeclareStyleable) {
  std::string input =
      "<declare-styleable name=\"foo\" "
      "xmlns:privAndroid=\"http://schemas.android.com/apk/prv/res/android\">\n"
      "  <attr name=\"*android:bar\" />\n"
      "  <attr name=\"privAndroid:bat\" />\n"
      "</declare-styleable>";
  ASSERT_TRUE(TestParse(input));
  Styleable* styleable = test::GetValue<Styleable>(&table_, "styleable/foo");
  ASSERT_NE(nullptr, styleable);
  ASSERT_EQ(2u, styleable->entries.size());

  EXPECT_TRUE(styleable->entries[0].private_reference);
  AAPT_ASSERT_TRUE(styleable->entries[0].name);
  EXPECT_EQ(std::string("android"), styleable->entries[0].name.value().package);

  EXPECT_TRUE(styleable->entries[1].private_reference);
  AAPT_ASSERT_TRUE(styleable->entries[1].name);
  EXPECT_EQ(std::string("android"), styleable->entries[1].name.value().package);
}

TEST_F(ResourceParserTest, ParseArray) {
  std::string input =
      "<array name=\"foo\">\n"
      "  <item>@string/ref</item>\n"
      "  <item>hey</item>\n"
      "  <item>23</item>\n"
      "</array>";
  ASSERT_TRUE(TestParse(input));

  Array* array = test::GetValue<Array>(&table_, "array/foo");
  ASSERT_NE(array, nullptr);
  ASSERT_EQ(3u, array->items.size());

  EXPECT_NE(nullptr, ValueCast<Reference>(array->items[0].get()));
  EXPECT_NE(nullptr, ValueCast<String>(array->items[1].get()));
  EXPECT_NE(nullptr, ValueCast<BinaryPrimitive>(array->items[2].get()));
}

TEST_F(ResourceParserTest, ParseStringArray) {
  std::string input = R"EOF(
      <string-array name="foo">
        <item>"Werk"</item>"
      </string-array>)EOF";
  ASSERT_TRUE(TestParse(input));
  EXPECT_NE(nullptr, test::GetValue<Array>(&table_, "array/foo"));
}

TEST_F(ResourceParserTest, ParseArrayWithFormat) {
  std::string input = R"EOF(
      <array name="foo" format="string">
        <item>100</item>
      </array>)EOF";
  ASSERT_TRUE(TestParse(input));

  Array* array = test::GetValue<Array>(&table_, "array/foo");
  ASSERT_NE(nullptr, array);

  ASSERT_EQ(1u, array->items.size());

  String* str = ValueCast<String>(array->items[0].get());
  ASSERT_NE(nullptr, str);
  EXPECT_EQ(std::string("100"), *str->value);
}

TEST_F(ResourceParserTest, ParseArrayWithBadFormat) {
  std::string input = R"EOF(
      <array name="foo" format="integer">
        <item>Hi</item>
      </array>)EOF";
  ASSERT_FALSE(TestParse(input));
}

TEST_F(ResourceParserTest, ParsePlural) {
  std::string input =
      "<plurals name=\"foo\">\n"
      "  <item quantity=\"other\">apples</item>\n"
      "  <item quantity=\"one\">apple</item>\n"
      "</plurals>";
  ASSERT_TRUE(TestParse(input));

  Plural* plural = test::GetValue<Plural>(&table_, "plurals/foo");
  ASSERT_NE(nullptr, plural);
  EXPECT_EQ(nullptr, plural->values[Plural::Zero]);
  EXPECT_EQ(nullptr, plural->values[Plural::Two]);
  EXPECT_EQ(nullptr, plural->values[Plural::Few]);
  EXPECT_EQ(nullptr, plural->values[Plural::Many]);

  EXPECT_NE(nullptr, plural->values[Plural::One]);
  EXPECT_NE(nullptr, plural->values[Plural::Other]);
}

TEST_F(ResourceParserTest, ParseCommentsWithResource) {
  std::string input =
      "<!--This is a comment-->\n"
      "<string name=\"foo\">Hi</string>";
  ASSERT_TRUE(TestParse(input));

  String* value = test::GetValue<String>(&table_, "string/foo");
  ASSERT_NE(nullptr, value);
  EXPECT_EQ(value->GetComment(), "This is a comment");
}

TEST_F(ResourceParserTest, DoNotCombineMultipleComments) {
  std::string input =
      "<!--One-->\n"
      "<!--Two-->\n"
      "<string name=\"foo\">Hi</string>";

  ASSERT_TRUE(TestParse(input));

  String* value = test::GetValue<String>(&table_, "string/foo");
  ASSERT_NE(nullptr, value);
  EXPECT_EQ(value->GetComment(), "Two");
}

TEST_F(ResourceParserTest, IgnoreCommentBeforeEndTag) {
  std::string input =
      "<!--One-->\n"
      "<string name=\"foo\">\n"
      "  Hi\n"
      "<!--Two-->\n"
      "</string>";

  ASSERT_TRUE(TestParse(input));

  String* value = test::GetValue<String>(&table_, "string/foo");
  ASSERT_NE(nullptr, value);
  EXPECT_EQ(value->GetComment(), "One");
}

TEST_F(ResourceParserTest, ParseNestedComments) {
  // We only care about declare-styleable and enum/flag attributes because
  // comments
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
  ASSERT_TRUE(TestParse(input));

  Styleable* styleable = test::GetValue<Styleable>(&table_, "styleable/foo");
  ASSERT_NE(nullptr, styleable);
  ASSERT_EQ(1u, styleable->entries.size());

  EXPECT_EQ(StringPiece("The name of the bar"),
            styleable->entries.front().GetComment());

  Attribute* attr = test::GetValue<Attribute>(&table_, "attr/foo");
  ASSERT_NE(nullptr, attr);
  ASSERT_EQ(1u, attr->symbols.size());

  EXPECT_EQ(StringPiece("The very first"),
            attr->symbols.front().symbol.GetComment());
}

/*
 * Declaring an ID as public should not require a separate definition
 * (as an ID has no value).
 */
TEST_F(ResourceParserTest, ParsePublicIdAsDefinition) {
  std::string input = "<public type=\"id\" name=\"foo\"/>";
  ASSERT_TRUE(TestParse(input));

  Id* id = test::GetValue<Id>(&table_, "id/foo");
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
  ASSERT_TRUE(TestParse(input));

  EXPECT_NE(nullptr, test::GetValueForConfigAndProduct<String>(
                         &table_, "string/foo",
                         ConfigDescription::DefaultConfig(), "phone"));
  EXPECT_NE(nullptr, test::GetValueForConfigAndProduct<String>(
                         &table_, "string/foo",
                         ConfigDescription::DefaultConfig(), "no-sdcard"));
  EXPECT_NE(nullptr,
            test::GetValueForConfigAndProduct<String>(
                &table_, "string/bar", ConfigDescription::DefaultConfig(), ""));
  EXPECT_NE(nullptr,
            test::GetValueForConfigAndProduct<String>(
                &table_, "string/baz", ConfigDescription::DefaultConfig(), ""));
  EXPECT_NE(nullptr, test::GetValueForConfigAndProduct<String>(
                         &table_, "string/bit",
                         ConfigDescription::DefaultConfig(), "phablet"));
  EXPECT_NE(nullptr, test::GetValueForConfigAndProduct<String>(
                         &table_, "string/bot",
                         ConfigDescription::DefaultConfig(), "default"));
}

TEST_F(ResourceParserTest, AutoIncrementIdsInPublicGroup) {
  std::string input = R"EOF(
    <public-group type="attr" first-id="0x01010040">
      <public name="foo" />
      <public name="bar" />
    </public-group>)EOF";
  ASSERT_TRUE(TestParse(input));

  Maybe<ResourceTable::SearchResult> result =
      table_.FindResource(test::ParseNameOrDie("attr/foo"));
  AAPT_ASSERT_TRUE(result);

  AAPT_ASSERT_TRUE(result.value().package->id);
  AAPT_ASSERT_TRUE(result.value().type->id);
  AAPT_ASSERT_TRUE(result.value().entry->id);
  ResourceId actual_id(result.value().package->id.value(),
                       result.value().type->id.value(),
                       result.value().entry->id.value());
  EXPECT_EQ(ResourceId(0x01010040), actual_id);

  result = table_.FindResource(test::ParseNameOrDie("attr/bar"));
  AAPT_ASSERT_TRUE(result);

  AAPT_ASSERT_TRUE(result.value().package->id);
  AAPT_ASSERT_TRUE(result.value().type->id);
  AAPT_ASSERT_TRUE(result.value().entry->id);
  actual_id = ResourceId(result.value().package->id.value(),
                         result.value().type->id.value(),
                         result.value().entry->id.value());
  EXPECT_EQ(ResourceId(0x01010041), actual_id);
}

TEST_F(ResourceParserTest, ExternalTypesShouldOnlyBeReferences) {
  std::string input =
      R"EOF(<item type="layout" name="foo">@layout/bar</item>)EOF";
  ASSERT_TRUE(TestParse(input));

  input = R"EOF(<item type="layout" name="bar">"this is a string"</item>)EOF";
  ASSERT_FALSE(TestParse(input));
}

TEST_F(ResourceParserTest, AddResourcesElementShouldAddEntryWithUndefinedSymbol) {
  std::string input = R"EOF(<add-resource name="bar" type="string" />)EOF";
  ASSERT_TRUE(TestParse(input));

  Maybe<ResourceTable::SearchResult> result =
      table_.FindResource(test::ParseNameOrDie("string/bar"));
  AAPT_ASSERT_TRUE(result);
  const ResourceEntry* entry = result.value().entry;
  ASSERT_NE(nullptr, entry);
  EXPECT_EQ(SymbolState::kUndefined, entry->symbol_status.state);
  EXPECT_TRUE(entry->symbol_status.allow_new);
}

TEST_F(ResourceParserTest, ParseItemElementWithFormat) {
  std::string input = R"(<item name="foo" type="integer" format="float">0.3</item>)";
  ASSERT_TRUE(TestParse(input));

  BinaryPrimitive* val = test::GetValue<BinaryPrimitive>(&table_, "integer/foo");
  ASSERT_THAT(val, NotNull());
  EXPECT_THAT(val->value.dataType, Eq(android::Res_value::TYPE_FLOAT));

  input = R"(<item name="bar" type="integer" format="fraction">100</item>)";
  ASSERT_FALSE(TestParse(input));
}

// An <item> without a format specifier accepts all types of values.
TEST_F(ResourceParserTest, ParseItemElementWithoutFormat) {
  std::string input = R"(<item name="foo" type="integer">100%p</item>)";
  ASSERT_TRUE(TestParse(input));

  BinaryPrimitive* val = test::GetValue<BinaryPrimitive>(&table_, "integer/foo");
  ASSERT_THAT(val, NotNull());
  EXPECT_THAT(val->value.dataType, Eq(android::Res_value::TYPE_FRACTION));
}

TEST_F(ResourceParserTest, ParseConfigVaryingItem) {
  std::string input = R"EOF(<item name="foo" type="configVarying">Hey</item>)EOF";
  ASSERT_TRUE(TestParse(input));
  ASSERT_NE(nullptr, test::GetValue<String>(&table_, "configVarying/foo"));
}

TEST_F(ResourceParserTest, ParseBagElement) {
  std::string input =
      R"EOF(<bag name="bag" type="configVarying"><item name="test">Hello!</item></bag>)EOF";
  ASSERT_TRUE(TestParse(input));

  Style* val = test::GetValue<Style>(&table_, "configVarying/bag");
  ASSERT_NE(nullptr, val);

  ASSERT_EQ(1u, val->entries.size());
  EXPECT_EQ(Reference(test::ParseNameOrDie("attr/test")), val->entries[0].key);
  EXPECT_NE(nullptr, ValueCast<RawString>(val->entries[0].value.get()));
}

}  // namespace aapt
