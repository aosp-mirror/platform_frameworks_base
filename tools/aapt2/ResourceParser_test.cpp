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
#include "io/StringStream.h"
#include "test/Test.h"
#include "xml/XmlPullParser.h"

using ::aapt::io::StringInputStream;
using ::aapt::test::StrValueEq;
using ::aapt::test::ValueEq;
using ::android::ConfigDescription;
using ::android::Res_value;
using ::android::ResTable_map;
using ::android::StringPiece;
using ::testing::Eq;
using ::testing::IsEmpty;
using ::testing::IsNull;
using ::testing::NotNull;
using ::testing::Pointee;
using ::testing::SizeIs;
using ::testing::StrEq;

namespace aapt {

constexpr const char* kXmlPreamble = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n";

TEST(ResourceParserSingleTest, FailToParseWithNoRootResourcesElement) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  ResourceTable table;
  ResourceParser parser(context->GetDiagnostics(), &table, Source{"test"}, {});

  std::string input = kXmlPreamble;
  input += R"(<attr name="foo"/>)";
  StringInputStream in(input);
  xml::XmlPullParser xml_parser(&in);
  ASSERT_FALSE(parser.Parse(&xml_parser));
}

class ResourceParserTest : public ::testing::Test {
 public:
  void SetUp() override {
    context_ = test::ContextBuilder().Build();
  }

  ::testing::AssertionResult TestParse(const StringPiece& str) {
    return TestParse(str, ConfigDescription{});
  }

  ::testing::AssertionResult TestParse(const StringPiece& str, const ConfigDescription& config) {
    ResourceParserOptions parserOptions;
    ResourceParser parser(context_->GetDiagnostics(), &table_, Source{"test"}, config,
                          parserOptions);

    std::string input = kXmlPreamble;
    input += "<resources>\n";
    input.append(str.data(), str.size());
    input += "\n</resources>";
    StringInputStream in(input);
    xml::XmlPullParser xmlParser(&in);
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
  ASSERT_TRUE(TestParse(R"(<string name="foo">   "  hey there " </string>)"));

  String* str = test::GetValue<String>(&table_, "string/foo");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str, StrValueEq("  hey there "));
  EXPECT_THAT(str->untranslatable_sections, IsEmpty());

  ASSERT_TRUE(TestParse(R"(<string name="bar">Isn\'t it cool?</string>)"));
  str = test::GetValue<String>(&table_, "string/bar");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str, StrValueEq("Isn't it cool?"));

  ASSERT_TRUE(TestParse(R"(<string name="baz">"Isn't it cool?"</string>)"));
  str = test::GetValue<String>(&table_, "string/baz");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str, StrValueEq("Isn't it cool?"));
}

TEST_F(ResourceParserTest, ParseEscapedString) {
  ASSERT_TRUE(TestParse(R"(<string name="foo">\?123</string>)"));

  String* str = test::GetValue<String>(&table_, "string/foo");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str, StrValueEq("?123"));
  EXPECT_THAT(str->untranslatable_sections, IsEmpty());

  ASSERT_TRUE(TestParse(R"(<string name="bar">This isn\’t a bad string</string>)"));
  str = test::GetValue<String>(&table_, "string/bar");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str, StrValueEq("This isn’t a bad string"));
}

TEST_F(ResourceParserTest, ParseFormattedString) {
  ASSERT_FALSE(TestParse(R"(<string name="foo">%d %s</string>)"));
  ASSERT_TRUE(TestParse(R"(<string name="foo">%1$d %2$s</string>)"));
}

TEST_F(ResourceParserTest, ParseStyledString) {
  // Use a surrogate pair unicode point so that we can verify that the span
  // indices use UTF-16 length and not UTF-8 length.
  std::string input =
      "<string name=\"foo\">This is my aunt\u2019s <b>fickle <small>string</small></b></string>";
  ASSERT_TRUE(TestParse(input));

  StyledString* str = test::GetValue<StyledString>(&table_, "string/foo");
  ASSERT_THAT(str, NotNull());

  EXPECT_THAT(str->value->value, StrEq("This is my aunt\u2019s fickle string"));
  EXPECT_THAT(str->value->spans, SizeIs(2));
  EXPECT_THAT(str->untranslatable_sections, IsEmpty());

  EXPECT_THAT(*str->value->spans[0].name, StrEq("b"));
  EXPECT_THAT(str->value->spans[0].first_char, Eq(18u));
  EXPECT_THAT(str->value->spans[0].last_char, Eq(30u));

  EXPECT_THAT(*str->value->spans[1].name, StrEq("small"));
  EXPECT_THAT(str->value->spans[1].first_char, Eq(25u));
  EXPECT_THAT(str->value->spans[1].last_char, Eq(30u));
}

TEST_F(ResourceParserTest, ParseStringWithWhitespace) {
  ASSERT_TRUE(TestParse(R"(<string name="foo">  This is what  I think  </string>)"));

  String* str = test::GetValue<String>(&table_, "string/foo");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str->value, StrEq("This is what I think"));
  EXPECT_THAT(str->untranslatable_sections, IsEmpty());

  ASSERT_TRUE(TestParse(R"(<string name="foo2">"  This is what  I think  "</string>)"));

  str = test::GetValue<String>(&table_, "string/foo2");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str, StrValueEq("  This is what  I think  "));
}

TEST_F(ResourceParserTest, ParseStringTruncateASCII) {
  // Tuncate leading and trailing whitespace
  EXPECT_TRUE(TestParse(R"(<string name="foo">&#32;Hello&#32;</string>)"));

  String* str = test::GetValue<String>(&table_, "string/foo");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str->value, StrEq("Hello"));
  EXPECT_THAT(str->untranslatable_sections, IsEmpty());

  // AAPT does not truncate unicode whitespace
  EXPECT_TRUE(TestParse(R"(<string name="foo2">\u0020\Hello\u0020</string>)"));

  str = test::GetValue<String>(&table_, "string/foo2");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str->value, StrEq(" Hello "));
  EXPECT_THAT(str->untranslatable_sections, IsEmpty());

  // Preserve non-ASCII whitespace including extended ASCII characters
  EXPECT_TRUE(TestParse(R"(<string name="foo3">&#160;Hello&#x202F;World&#160;</string>)"));

  str = test::GetValue<String>(&table_, "string/foo3");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str->value, StrEq("\xC2\xA0Hello\xE2\x80\xAFWorld\xC2\xA0"));
  EXPECT_THAT(str->untranslatable_sections, IsEmpty());

  EXPECT_TRUE(TestParse(R"(<string name="foo4">2005年6月1日</string>)"));

  str = test::GetValue<String>(&table_, "string/foo4");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str->value, StrEq("2005年6月1日"));
  EXPECT_THAT(str->untranslatable_sections, IsEmpty());
}

TEST_F(ResourceParserTest, ParseStyledStringWithWhitespace) {
  std::string input = R"(<string name="foo">  <b> My <i> favorite</i> string </b>  </string>)";
  ASSERT_TRUE(TestParse(input));

  StyledString* str = test::GetValue<StyledString>(&table_, "string/foo");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(str->value->value, StrEq("  My  favorite string  "));
  EXPECT_THAT(str->untranslatable_sections, IsEmpty());

  ASSERT_THAT(str->value->spans, SizeIs(2u));
  EXPECT_THAT(*str->value->spans[0].name, StrEq("b"));
  EXPECT_THAT(str->value->spans[0].first_char, Eq(1u));
  EXPECT_THAT(str->value->spans[0].last_char, Eq(21u));

  EXPECT_THAT(*str->value->spans[1].name, StrEq("i"));
  EXPECT_THAT(str->value->spans[1].first_char, Eq(5u));
  EXPECT_THAT(str->value->spans[1].last_char, Eq(13u));
}

TEST_F(ResourceParserTest, ParseStringTranslatableAttribute) {
  // If there is no translate attribute the default is 'true'
  EXPECT_TRUE(TestParse(R"(<string name="foo1">Translate</string>)"));
  String* str = test::GetValue<String>(&table_, "string/foo1");
  ASSERT_THAT(str, NotNull());
  ASSERT_TRUE(str->IsTranslatable());

  // Explicit 'true' translate attribute
  EXPECT_TRUE(TestParse(R"(<string name="foo2" translatable="true">Translate</string>)"));
  str = test::GetValue<String>(&table_, "string/foo2");
  ASSERT_THAT(str, NotNull());
  ASSERT_TRUE(str->IsTranslatable());

  // Explicit 'false' translate attribute
  EXPECT_TRUE(TestParse(R"(<string name="foo3" translatable="false">Do not translate</string>)"));
  str = test::GetValue<String>(&table_, "string/foo3");
  ASSERT_THAT(str, NotNull());
  ASSERT_FALSE(str->IsTranslatable());

  // Invalid value for the translate attribute, should be boolean ('true' or 'false')
  EXPECT_FALSE(TestParse(R"(<string name="foo4" translatable="yes">Translate</string>)"));
}

TEST_F(ResourceParserTest, IgnoreXliffTagsOtherThanG) {
  std::string input = R"(
      <string name="foo" xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
          There are <xliff:source>no</xliff:source> apples</string>)";
  ASSERT_TRUE(TestParse(input));

  String* str = test::GetValue<String>(&table_, "string/foo");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str, StrValueEq("There are no apples"));
  EXPECT_THAT(str->untranslatable_sections, IsEmpty());
}

TEST_F(ResourceParserTest, NestedXliffGTagsAreIllegal) {
  std::string input = R"(
      <string name="foo" xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
          Do not <xliff:g>translate <xliff:g>this</xliff:g></xliff:g></string>)";
  EXPECT_FALSE(TestParse(input));
}

TEST_F(ResourceParserTest, RecordUntranslateableXliffSectionsInString) {
  std::string input = R"(
      <string name="foo" xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
          There are <xliff:g id="count">%1$d</xliff:g> apples</string>)";
  ASSERT_TRUE(TestParse(input));

  String* str = test::GetValue<String>(&table_, "string/foo");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str, StrValueEq("There are %1$d apples"));

  ASSERT_THAT(str->untranslatable_sections, SizeIs(1));
  EXPECT_THAT(str->untranslatable_sections[0].start, Eq(10u));
  EXPECT_THAT(str->untranslatable_sections[0].end, Eq(14u));
}

TEST_F(ResourceParserTest, RecordUntranslateableXliffSectionsInStyledString) {
  std::string input = R"(
      <string name="foo" xmlns:xliff="urn:oasis:names:tc:xliff:document:1.2">
          There are <b><xliff:g id="count">%1$d</xliff:g></b> apples</string>)";
  ASSERT_TRUE(TestParse(input));

  StyledString* str = test::GetValue<StyledString>(&table_, "string/foo");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(str->value->value, Eq(" There are %1$d apples"));

  ASSERT_THAT(str->untranslatable_sections, SizeIs(1));
  EXPECT_THAT(str->untranslatable_sections[0].start, Eq(11u));
  EXPECT_THAT(str->untranslatable_sections[0].end, Eq(15u));

  ASSERT_THAT(str->value->spans, SizeIs(1u));
  EXPECT_THAT(*str->value->spans[0].name, StrEq("b"));
  EXPECT_THAT(str->value->spans[0].first_char, Eq(11u));
  EXPECT_THAT(str->value->spans[0].last_char, Eq(14u));
}

TEST_F(ResourceParserTest, ParseNull) {
  std::string input = R"(<integer name="foo">@null</integer>)";
  ASSERT_TRUE(TestParse(input));

  // The Android runtime treats a value of android::Res_value::TYPE_NULL as
  // a non-existing value, and this causes problems in styles when trying to
  // resolve an attribute. Null values must be encoded as android::Res_value::TYPE_REFERENCE
  // with a data value of 0.
  Reference* null_ref = test::GetValue<Reference>(&table_, "integer/foo");
  ASSERT_THAT(null_ref, NotNull());
  EXPECT_FALSE(null_ref->name);
  EXPECT_FALSE(null_ref->id);
  EXPECT_THAT(null_ref->reference_type, Eq(Reference::Type::kResource));
}

TEST_F(ResourceParserTest, ParseEmpty) {
  std::string input = R"(<integer name="foo">@empty</integer>)";
  ASSERT_TRUE(TestParse(input));

  BinaryPrimitive* integer = test::GetValue<BinaryPrimitive>(&table_, "integer/foo");
  ASSERT_THAT(integer, NotNull());
  EXPECT_THAT(integer->value.dataType, Eq(Res_value::TYPE_NULL));
  EXPECT_THAT(integer->value.data, Eq(Res_value::DATA_NULL_EMPTY));
}

TEST_F(ResourceParserTest, ParseAttr) {
  std::string input = R"(
      <attr name="foo" format="string"/>
      <attr name="bar"/>)";
  ASSERT_TRUE(TestParse(input));

  Attribute* attr = test::GetValue<Attribute>(&table_, "attr/foo");
  ASSERT_THAT(attr, NotNull());
  EXPECT_THAT(attr->type_mask, Eq(ResTable_map::TYPE_STRING));

  attr = test::GetValue<Attribute>(&table_, "attr/bar");
  ASSERT_THAT(attr, NotNull());
  EXPECT_THAT(attr->type_mask, Eq(ResTable_map::TYPE_ANY));
}

// Old AAPT allowed attributes to be defined under different configurations, but ultimately
// stored them with the default configuration. Check that we have the same behavior.
TEST_F(ResourceParserTest, ParseAttrAndDeclareStyleableUnderConfigButRecordAsNoConfig) {
  const ConfigDescription watch_config = test::ParseConfigOrDie("watch");
  std::string input = R"(
      <attr name="foo" />
      <declare-styleable name="bar">
        <attr name="baz" format="reference"/>
      </declare-styleable>)";
  ASSERT_TRUE(TestParse(input, watch_config));

  EXPECT_THAT(test::GetValueForConfig<Attribute>(&table_, "attr/foo", watch_config), IsNull());
  EXPECT_THAT(test::GetValueForConfig<Attribute>(&table_, "attr/baz", watch_config), IsNull());
  EXPECT_THAT(test::GetValueForConfig<Styleable>(&table_, "styleable/bar", watch_config), IsNull());

  EXPECT_THAT(test::GetValue<Attribute>(&table_, "attr/foo"), NotNull());
  EXPECT_THAT(test::GetValue<Attribute>(&table_, "attr/baz"), NotNull());
  EXPECT_THAT(test::GetValue<Styleable>(&table_, "styleable/bar"), NotNull());
}

TEST_F(ResourceParserTest, ParseAttrWithMinMax) {
  std::string input = R"(<attr name="foo" min="10" max="23" format="integer"/>)";
  ASSERT_TRUE(TestParse(input));

  Attribute* attr = test::GetValue<Attribute>(&table_, "attr/foo");
  ASSERT_THAT(attr, NotNull());
  EXPECT_THAT(attr->type_mask, Eq(ResTable_map::TYPE_INTEGER));
  EXPECT_THAT(attr->min_int, Eq(10));
  EXPECT_THAT(attr->max_int, Eq(23));
}

TEST_F(ResourceParserTest, FailParseAttrWithMinMaxButNotInteger) {
  ASSERT_FALSE(TestParse(R"(<attr name="foo" min="10" max="23" format="string"/>)"));
}

TEST_F(ResourceParserTest, ParseUseAndDeclOfAttr) {
  std::string input = R"(
      <declare-styleable name="Styleable">
        <attr name="foo" />
      </declare-styleable>
      <attr name="foo" format="string"/>)";
  ASSERT_TRUE(TestParse(input));

  Attribute* attr = test::GetValue<Attribute>(&table_, "attr/foo");
  ASSERT_THAT(attr, NotNull());
  EXPECT_THAT(attr->type_mask, Eq(ResTable_map::TYPE_STRING));
}

TEST_F(ResourceParserTest, ParseDoubleUseOfAttr) {
  std::string input = R"(
      <declare-styleable name="Theme">
        <attr name="foo" />
      </declare-styleable>
      <declare-styleable name="Window">
        <attr name="foo" format="boolean"/>
      </declare-styleable>)";
  ASSERT_TRUE(TestParse(input));

  Attribute* attr = test::GetValue<Attribute>(&table_, "attr/foo");
  ASSERT_THAT(attr, NotNull());
  EXPECT_THAT(attr->type_mask, Eq(ResTable_map::TYPE_BOOLEAN));
}

TEST_F(ResourceParserTest, ParseEnumAttr) {
  std::string input = R"(
      <attr name="foo">
        <enum name="bar" value="0"/>
        <enum name="bat" value="1"/>
        <enum name="baz" value="2"/>
      </attr>)";
  ASSERT_TRUE(TestParse(input));

  Attribute* enum_attr = test::GetValue<Attribute>(&table_, "attr/foo");
  ASSERT_THAT(enum_attr, NotNull());
  EXPECT_THAT(enum_attr->type_mask, Eq(ResTable_map::TYPE_ENUM));
  ASSERT_THAT(enum_attr->symbols, SizeIs(3));

  ASSERT_TRUE(enum_attr->symbols[0].symbol.name);
  EXPECT_THAT(enum_attr->symbols[0].symbol.name.value().entry, Eq("bar"));
  EXPECT_THAT(enum_attr->symbols[0].value, Eq(0u));

  ASSERT_TRUE(enum_attr->symbols[1].symbol.name);
  EXPECT_THAT(enum_attr->symbols[1].symbol.name.value().entry, Eq("bat"));
  EXPECT_THAT(enum_attr->symbols[1].value, Eq(1u));

  ASSERT_TRUE(enum_attr->symbols[2].symbol.name);
  EXPECT_THAT(enum_attr->symbols[2].symbol.name.value().entry, Eq("baz"));
  EXPECT_THAT(enum_attr->symbols[2].value, Eq(2u));
}

TEST_F(ResourceParserTest, ParseFlagAttr) {
  std::string input = R"(
      <attr name="foo">
        <flag name="bar" value="0"/>
        <flag name="bat" value="1"/>
        <flag name="baz" value="2"/>
      </attr>)";
  ASSERT_TRUE(TestParse(input));

  Attribute* flag_attr = test::GetValue<Attribute>(&table_, "attr/foo");
  ASSERT_THAT(flag_attr, NotNull());
  EXPECT_THAT(flag_attr->type_mask, Eq(ResTable_map::TYPE_FLAGS));
  ASSERT_THAT(flag_attr->symbols, SizeIs(3));

  ASSERT_TRUE(flag_attr->symbols[0].symbol.name);
  EXPECT_THAT(flag_attr->symbols[0].symbol.name.value().entry, Eq("bar"));
  EXPECT_THAT(flag_attr->symbols[0].value, Eq(0u));

  ASSERT_TRUE(flag_attr->symbols[1].symbol.name);
  EXPECT_THAT(flag_attr->symbols[1].symbol.name.value().entry, Eq("bat"));
  EXPECT_THAT(flag_attr->symbols[1].value, Eq(1u));

  ASSERT_TRUE(flag_attr->symbols[2].symbol.name);
  EXPECT_THAT(flag_attr->symbols[2].symbol.name.value().entry, Eq("baz"));
  EXPECT_THAT(flag_attr->symbols[2].value, Eq(2u));

  std::unique_ptr<BinaryPrimitive> flag_value =
      ResourceUtils::TryParseFlagSymbol(flag_attr, "baz|bat");
  ASSERT_THAT(flag_value, NotNull());
  EXPECT_THAT(flag_value->value.data, Eq(1u | 2u));
}

TEST_F(ResourceParserTest, FailToParseEnumAttrWithNonUniqueKeys) {
  std::string input = R"(
      <attr name="foo">
        <enum name="bar" value="0"/>
        <enum name="bat" value="1"/>
        <enum name="bat" value="2"/>
      </attr>)";
  ASSERT_FALSE(TestParse(input));
}

TEST_F(ResourceParserTest, ParseStyle) {
  std::string input = R"(
      <style name="foo" parent="@style/fu">
        <item name="bar">#ffffffff</item>
        <item name="bat">@string/hey</item>
        <item name="baz"><b>hey</b></item>
      </style>)";
  ASSERT_TRUE(TestParse(input));

  Style* style = test::GetValue<Style>(&table_, "style/foo");
  ASSERT_THAT(style, NotNull());
  ASSERT_TRUE(style->parent);
  EXPECT_THAT(style->parent.value().name, Eq(make_value(test::ParseNameOrDie("style/fu"))));
  ASSERT_THAT(style->entries, SizeIs(3));

  EXPECT_THAT(style->entries[0].key.name, Eq(make_value(test::ParseNameOrDie("attr/bar"))));
  EXPECT_THAT(style->entries[1].key.name, Eq(make_value(test::ParseNameOrDie("attr/bat"))));
  EXPECT_THAT(style->entries[2].key.name, Eq(make_value(test::ParseNameOrDie("attr/baz"))));
}

TEST_F(ResourceParserTest, ParseStyleWithShorthandParent) {
  ASSERT_TRUE(TestParse(R"(<style name="foo" parent="com.app:Theme"/>)"));

  Style* style = test::GetValue<Style>(&table_, "style/foo");
  ASSERT_THAT(style, NotNull());
  ASSERT_TRUE(style->parent);
  EXPECT_THAT(style->parent.value().name, Eq(make_value(test::ParseNameOrDie("com.app:style/Theme"))));
}

TEST_F(ResourceParserTest, ParseStyleWithPackageAliasedParent) {
  std::string input = R"(
      <style xmlns:app="http://schemas.android.com/apk/res/android"
          name="foo" parent="app:Theme"/>)";
  ASSERT_TRUE(TestParse(input));

  Style* style = test::GetValue<Style>(&table_, "style/foo");
  ASSERT_THAT(style, NotNull());
  ASSERT_TRUE(style->parent);
  ASSERT_TRUE(style->parent.value().name);
  EXPECT_THAT(style->parent.value().name, Eq(make_value(test::ParseNameOrDie("android:style/Theme"))));
}

TEST_F(ResourceParserTest, ParseStyleWithPackageAliasedItems) {
  std::string input = R"(
      <style xmlns:app="http://schemas.android.com/apk/res/android" name="foo">
        <item name="app:bar">0</item>
      </style>)";
  ASSERT_TRUE(TestParse(input));

  Style* style = test::GetValue<Style>(&table_, "style/foo");
  ASSERT_THAT(style, NotNull());
  ASSERT_THAT(style->entries, SizeIs(1));
  EXPECT_THAT(style->entries[0].key.name, Eq(make_value(test::ParseNameOrDie("android:attr/bar"))));
}

TEST_F(ResourceParserTest, ParseStyleWithRawStringItem) {
  std::string input = R"(
      <style name="foo">
        <item name="bar">
          com.helloworld.AppClass
        </item>
      </style>)";
  ASSERT_TRUE(TestParse(input));

  Style* style = test::GetValue<Style>(&table_, "style/foo");
  ASSERT_THAT(style, NotNull());
  EXPECT_THAT(style->entries[0].value, NotNull());
  RawString* value = ValueCast<RawString>(style->entries[0].value.get());
  EXPECT_THAT(value, NotNull());
  EXPECT_THAT(*value->value, StrEq(R"(com.helloworld.AppClass)"));
}


TEST_F(ResourceParserTest, ParseStyleWithInferredParent) {
  ASSERT_TRUE(TestParse(R"(<style name="foo.bar"/>)"));

  Style* style = test::GetValue<Style>(&table_, "style/foo.bar");
  ASSERT_THAT(style, NotNull());
  ASSERT_TRUE(style->parent);
  EXPECT_THAT(style->parent.value().name, Eq(make_value(test::ParseNameOrDie("style/foo"))));
  EXPECT_TRUE(style->parent_inferred);
}

TEST_F(ResourceParserTest, ParseStyleWithInferredParentOverridenByEmptyParentAttribute) {
  ASSERT_TRUE(TestParse(R"(<style name="foo.bar" parent=""/>)"));

  Style* style = test::GetValue<Style>(&table_, "style/foo.bar");
  ASSERT_THAT(style, NotNull());
  EXPECT_FALSE(style->parent);
  EXPECT_FALSE(style->parent_inferred);
}

TEST_F(ResourceParserTest, ParseStyleWithPrivateParentReference) {
  ASSERT_TRUE(TestParse(R"(<style name="foo" parent="*android:style/bar" />)"));

  Style* style = test::GetValue<Style>(&table_, "style/foo");
  ASSERT_THAT(style, NotNull());
  ASSERT_TRUE(style->parent);
  EXPECT_TRUE(style->parent.value().private_reference);
}

TEST_F(ResourceParserTest, ParseAutoGeneratedIdReference) {
  ASSERT_TRUE(TestParse(R"(<string name="foo">@+id/bar</string>)"));
  ASSERT_THAT(test::GetValue<Id>(&table_, "id/bar"), NotNull());
}

TEST_F(ResourceParserTest, ParseAttributesDeclareStyleable) {
  std::string input = R"(
      <declare-styleable name="foo">
        <attr name="bar" />
        <attr name="bat" format="string|reference"/>
        <attr name="baz">
          <enum name="foo" value="1"/>
        </attr>
      </declare-styleable>)";
  ASSERT_TRUE(TestParse(input));

  Maybe<ResourceTable::SearchResult> result =
      table_.FindResource(test::ParseNameOrDie("styleable/foo"));
  ASSERT_TRUE(result);
  EXPECT_THAT(result.value().entry->visibility.level, Eq(Visibility::Level::kPublic));

  Attribute* attr = test::GetValue<Attribute>(&table_, "attr/bar");
  ASSERT_THAT(attr, IsNull());

  attr = test::GetValue<Attribute>(&table_, "attr/bat");
  ASSERT_THAT(attr, NotNull());
  EXPECT_TRUE(attr->IsWeak());

  attr = test::GetValue<Attribute>(&table_, "attr/baz");
  ASSERT_THAT(attr, NotNull());
  EXPECT_TRUE(attr->IsWeak());
  EXPECT_THAT(attr->symbols, SizeIs(1));

  EXPECT_THAT(test::GetValue<Id>(&table_, "id/foo"), NotNull());

  Styleable* styleable = test::GetValue<Styleable>(&table_, "styleable/foo");
  ASSERT_THAT(styleable, NotNull());
  ASSERT_THAT(styleable->entries, SizeIs(3));

  EXPECT_THAT(styleable->entries[0].name, Eq(make_value(test::ParseNameOrDie("attr/bar"))));
  EXPECT_THAT(styleable->entries[1].name, Eq(make_value(test::ParseNameOrDie("attr/bat"))));
  EXPECT_THAT(styleable->entries[2].name, Eq(make_value(test::ParseNameOrDie("attr/baz"))));
}

TEST_F(ResourceParserTest, ParsePrivateAttributesDeclareStyleable) {
  std::string input = R"(
      <declare-styleable xmlns:privAndroid="http://schemas.android.com/apk/prv/res/android"
          name="foo">
        <attr name="*android:bar" />
        <attr name="privAndroid:bat" />
      </declare-styleable>)";
  ASSERT_TRUE(TestParse(input));
  Styleable* styleable = test::GetValue<Styleable>(&table_, "styleable/foo");
  ASSERT_THAT(styleable, NotNull());
  ASSERT_THAT(styleable->entries, SizeIs(2));

  EXPECT_TRUE(styleable->entries[0].private_reference);
  ASSERT_TRUE(styleable->entries[0].name);
  EXPECT_THAT(styleable->entries[0].name.value().package, Eq("android"));

  EXPECT_TRUE(styleable->entries[1].private_reference);
  ASSERT_TRUE(styleable->entries[1].name);
  EXPECT_THAT(styleable->entries[1].name.value().package, Eq("android"));
}

TEST_F(ResourceParserTest, ParseArray) {
  std::string input = R"(
      <array name="foo">
        <item>@string/ref</item>
        <item>hey</item>
        <item>23</item>
      </array>)";
  ASSERT_TRUE(TestParse(input));

  Array* array = test::GetValue<Array>(&table_, "array/foo");
  ASSERT_THAT(array, NotNull());
  ASSERT_THAT(array->elements, SizeIs(3));

  EXPECT_THAT(ValueCast<Reference>(array->elements[0].get()), NotNull());
  EXPECT_THAT(ValueCast<String>(array->elements[1].get()), NotNull());
  EXPECT_THAT(ValueCast<BinaryPrimitive>(array->elements[2].get()), NotNull());
}

TEST_F(ResourceParserTest, ParseStringArray) {
  std::string input = R"(
      <string-array name="foo">
        <item>"Werk"</item>"
      </string-array>)";
  ASSERT_TRUE(TestParse(input));
  EXPECT_THAT(test::GetValue<Array>(&table_, "array/foo"), NotNull());
}

TEST_F(ResourceParserTest, ParseArrayWithFormat) {
  std::string input = R"(
      <array name="foo" format="string">
        <item>100</item>
      </array>)";
  ASSERT_TRUE(TestParse(input));

  Array* array = test::GetValue<Array>(&table_, "array/foo");
  ASSERT_THAT(array, NotNull());
  ASSERT_THAT(array->elements, SizeIs(1));

  String* str = ValueCast<String>(array->elements[0].get());
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str, StrValueEq("100"));
}

TEST_F(ResourceParserTest, ParseArrayWithBadFormat) {
  std::string input = R"(
      <array name="foo" format="integer">
        <item>Hi</item>
      </array>)";
  ASSERT_FALSE(TestParse(input));
}

TEST_F(ResourceParserTest, ParsePlural) {
  std::string input = R"(
      <plurals name="foo">
        <item quantity="other">apples</item>
        <item quantity="one">apple</item>
      </plurals>)";
  ASSERT_TRUE(TestParse(input));

  Plural* plural = test::GetValue<Plural>(&table_, "plurals/foo");
  ASSERT_THAT(plural, NotNull());
  EXPECT_THAT(plural->values[Plural::Zero], IsNull());
  EXPECT_THAT(plural->values[Plural::Two], IsNull());
  EXPECT_THAT(plural->values[Plural::Few], IsNull());
  EXPECT_THAT(plural->values[Plural::Many], IsNull());

  EXPECT_THAT(plural->values[Plural::One], NotNull());
  EXPECT_THAT(plural->values[Plural::Other], NotNull());
}

TEST_F(ResourceParserTest, ParseCommentsWithResource) {
  std::string input = R"(
      <!--This is a comment-->
      <string name="foo">Hi</string>)";
  ASSERT_TRUE(TestParse(input));

  String* value = test::GetValue<String>(&table_, "string/foo");
  ASSERT_THAT(value, NotNull());
  EXPECT_THAT(value->GetComment(), Eq("This is a comment"));
}

TEST_F(ResourceParserTest, DoNotCombineMultipleComments) {
  std::string input = R"(
      <!--One-->
      <!--Two-->
      <string name="foo">Hi</string>)";

  ASSERT_TRUE(TestParse(input));

  String* value = test::GetValue<String>(&table_, "string/foo");
  ASSERT_THAT(value, NotNull());
  EXPECT_THAT(value->GetComment(), Eq("Two"));
}

TEST_F(ResourceParserTest, IgnoreCommentBeforeEndTag) {
  std::string input = R"(
      <!--One-->
      <string name="foo">
        Hi
      <!--Two-->
      </string>)";
  ASSERT_TRUE(TestParse(input));

  String* value = test::GetValue<String>(&table_, "string/foo");
  ASSERT_THAT(value, NotNull());
  EXPECT_THAT(value->GetComment(), Eq("One"));
}

TEST_F(ResourceParserTest, ParseNestedComments) {
  // We only care about declare-styleable and enum/flag attributes because
  // comments from those end up in R.java
  std::string input = R"(
      <declare-styleable name="foo">
        <!-- The name of the bar -->
        <attr name="barName" format="string|reference" />
      </declare-styleable>

      <attr name="foo">
        <!-- The very first -->
        <enum name="one" value="1" />
      </attr>)";
  ASSERT_TRUE(TestParse(input));

  Styleable* styleable = test::GetValue<Styleable>(&table_, "styleable/foo");
  ASSERT_THAT(styleable, NotNull());
  ASSERT_THAT(styleable->entries, SizeIs(1));
  EXPECT_THAT(styleable->entries[0].GetComment(), Eq("The name of the bar"));

  Attribute* attr = test::GetValue<Attribute>(&table_, "attr/foo");
  ASSERT_THAT(attr, NotNull());
  ASSERT_THAT(attr->symbols, SizeIs(1));
  EXPECT_THAT(attr->symbols[0].symbol.GetComment(), Eq("The very first"));
}

// Declaring an ID as public should not require a separate definition (as an ID has no value).
TEST_F(ResourceParserTest, ParsePublicIdAsDefinition) {
  ASSERT_TRUE(TestParse(R"(<public type="id" name="foo"/>)"));
  ASSERT_THAT(test::GetValue<Id>(&table_, "id/foo"), NotNull());
}

TEST_F(ResourceParserTest, KeepAllProducts) {
  std::string input = R"(
      <string name="foo" product="phone">hi</string>
      <string name="foo" product="no-sdcard">ho</string>
      <string name="bar" product="">wee</string>
      <string name="baz">woo</string>
      <string name="bit" product="phablet">hoot</string>
      <string name="bot" product="default">yes</string>)";
  ASSERT_TRUE(TestParse(input));

  ASSERT_THAT(test::GetValueForConfigAndProduct<String>(&table_, "string/foo", ConfigDescription::DefaultConfig(), "phone"), NotNull());
  ASSERT_THAT(test::GetValueForConfigAndProduct<String>(&table_, "string/foo",ConfigDescription::DefaultConfig(), "no-sdcard"), NotNull());
  ASSERT_THAT(test::GetValueForConfigAndProduct<String>(&table_, "string/bar", ConfigDescription::DefaultConfig(), ""), NotNull());
  ASSERT_THAT(test::GetValueForConfigAndProduct<String>(&table_, "string/baz", ConfigDescription::DefaultConfig(), ""), NotNull());
  ASSERT_THAT(test::GetValueForConfigAndProduct<String>(&table_, "string/bit", ConfigDescription::DefaultConfig(), "phablet"), NotNull());
  ASSERT_THAT(test::GetValueForConfigAndProduct<String>(&table_, "string/bot", ConfigDescription::DefaultConfig(), "default"), NotNull());
}

TEST_F(ResourceParserTest, AutoIncrementIdsInPublicGroup) {
  std::string input = R"(
      <public-group type="attr" first-id="0x01010040">
        <public name="foo" />
        <public name="bar" />
      </public-group>)";
  ASSERT_TRUE(TestParse(input));

  Maybe<ResourceTable::SearchResult> result = table_.FindResource(test::ParseNameOrDie("attr/foo"));
  ASSERT_TRUE(result);

  ASSERT_TRUE(result.value().package->id);
  ASSERT_TRUE(result.value().type->id);
  ASSERT_TRUE(result.value().entry->id);
  ResourceId actual_id(result.value().package->id.value(),
                       result.value().type->id.value(),
                       result.value().entry->id.value());
  EXPECT_THAT(actual_id, Eq(ResourceId(0x01010040)));

  result = table_.FindResource(test::ParseNameOrDie("attr/bar"));
  ASSERT_TRUE(result);

  ASSERT_TRUE(result.value().package->id);
  ASSERT_TRUE(result.value().type->id);
  ASSERT_TRUE(result.value().entry->id);
  actual_id = ResourceId(result.value().package->id.value(),
                         result.value().type->id.value(),
                         result.value().entry->id.value());
  EXPECT_THAT(actual_id, Eq(ResourceId(0x01010041)));
}

TEST_F(ResourceParserTest, StrongestSymbolVisibilityWins) {
  std::string input = R"(
      <!-- private -->
      <java-symbol type="string" name="foo" />
      <!-- public -->
      <public type="string" name="foo" id="0x01020000" />
      <!-- private2 -->
      <java-symbol type="string" name="foo" />)";
  ASSERT_TRUE(TestParse(input));

  Maybe<ResourceTable::SearchResult> result =
      table_.FindResource(test::ParseNameOrDie("string/foo"));
  ASSERT_TRUE(result);

  ResourceEntry* entry = result.value().entry;
  ASSERT_THAT(entry, NotNull());
  EXPECT_THAT(entry->visibility.level, Eq(Visibility::Level::kPublic));
  EXPECT_THAT(entry->visibility.comment, StrEq("public"));
}

TEST_F(ResourceParserTest, ExternalTypesShouldOnlyBeReferences) {
  ASSERT_TRUE(TestParse(R"(<item type="layout" name="foo">@layout/bar</item>)"));
  ASSERT_FALSE(TestParse(R"(<item type="layout" name="bar">"this is a string"</item>)"));
}

TEST_F(ResourceParserTest, AddResourcesElementShouldAddEntryWithUndefinedSymbol) {
  ASSERT_TRUE(TestParse(R"(<add-resource name="bar" type="string" />)"));

  Maybe<ResourceTable::SearchResult> result =
      table_.FindResource(test::ParseNameOrDie("string/bar"));
  ASSERT_TRUE(result);
  const ResourceEntry* entry = result.value().entry;
  ASSERT_THAT(entry, NotNull());
  EXPECT_THAT(entry->visibility.level, Eq(Visibility::Level::kUndefined));
  EXPECT_TRUE(entry->allow_new);
}

TEST_F(ResourceParserTest, ParseItemElementWithFormat) {
  ASSERT_TRUE(TestParse(R"(<item name="foo" type="integer" format="float">0.3</item>)"));

  BinaryPrimitive* val = test::GetValue<BinaryPrimitive>(&table_, "integer/foo");
  ASSERT_THAT(val, NotNull());
  EXPECT_THAT(val->value.dataType, Eq(Res_value::TYPE_FLOAT));

  ASSERT_FALSE(TestParse(R"(<item name="bar" type="integer" format="fraction">100</item>)"));
}

// An <item> without a format specifier accepts all types of values.
TEST_F(ResourceParserTest, ParseItemElementWithoutFormat) {
  ASSERT_TRUE(TestParse(R"(<item name="foo" type="integer">100%p</item>)"));

  BinaryPrimitive* val = test::GetValue<BinaryPrimitive>(&table_, "integer/foo");
  ASSERT_THAT(val, NotNull());
  EXPECT_THAT(val->value.dataType, Eq(Res_value::TYPE_FRACTION));
}

TEST_F(ResourceParserTest, ParseConfigVaryingItem) {
  ASSERT_TRUE(TestParse(R"(<item name="foo" type="configVarying">Hey</item>)"));
  ASSERT_THAT(test::GetValue<String>(&table_, "configVarying/foo"), NotNull());
}

TEST_F(ResourceParserTest, ParseBagElement) {
  std::string input = R"(
      <bag name="bag" type="configVarying">
        <item name="test">Hello!</item>
      </bag>)";
  ASSERT_TRUE(TestParse(input));

  Style* val = test::GetValue<Style>(&table_, "configVarying/bag");
  ASSERT_THAT(val, NotNull());
  ASSERT_THAT(val->entries, SizeIs(1));

  EXPECT_THAT(val->entries[0].key, Eq(Reference(test::ParseNameOrDie("attr/test"))));
  EXPECT_THAT(ValueCast<RawString>(val->entries[0].value.get()), NotNull());
}

TEST_F(ResourceParserTest, ParseElementWithNoValue) {
  std::string input = R"(
      <item type="drawable" format="reference" name="foo" />
      <string name="foo" />)";
  ASSERT_TRUE(TestParse(input));
  ASSERT_THAT(test::GetValue(&table_, "drawable/foo"), Pointee(ValueEq(Reference())));

  String* str = test::GetValue<String>(&table_, "string/foo");
  ASSERT_THAT(str, NotNull());
  EXPECT_THAT(*str, StrValueEq(""));
}

TEST_F(ResourceParserTest, ParsePlatformIndependentNewline) {
  ASSERT_TRUE(TestParse(R"(<string name="foo">%1$s %n %2$s</string>)"));
}

TEST_F(ResourceParserTest, ParseOverlayable) {
  std::string input = R"(
      <overlayable name="Name" actor="overlay://theme">
          <policy type="signature">
            <item type="string" name="foo" />
            <item type="drawable" name="bar" />
          </policy>
      </overlayable>)";
  ASSERT_TRUE(TestParse(input));

  auto search_result = table_.FindResource(test::ParseNameOrDie("string/foo"));
  ASSERT_TRUE(search_result);
  ASSERT_THAT(search_result.value().entry, NotNull());
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  OverlayableItem& result_overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(result_overlayable_item.overlayable->name, Eq("Name"));
  EXPECT_THAT(result_overlayable_item.overlayable->actor, Eq("overlay://theme"));
  EXPECT_THAT(result_overlayable_item.policies, Eq(OverlayableItem::Policy::kSignature));

  search_result = table_.FindResource(test::ParseNameOrDie("drawable/bar"));
  ASSERT_TRUE(search_result);
  ASSERT_THAT(search_result.value().entry, NotNull());
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  result_overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(result_overlayable_item.overlayable->name, Eq("Name"));
  EXPECT_THAT(result_overlayable_item.overlayable->actor, Eq("overlay://theme"));
  EXPECT_THAT(result_overlayable_item.policies, Eq(OverlayableItem::Policy::kSignature));
}

TEST_F(ResourceParserTest, ParseOverlayableRequiresName) {
  EXPECT_FALSE(TestParse(R"(<overlayable actor="overlay://theme" />)"));
  EXPECT_TRUE(TestParse(R"(<overlayable name="Name" />)"));
  EXPECT_TRUE(TestParse(R"(<overlayable name="Name" actor="overlay://theme" />)"));
}

TEST_F(ResourceParserTest, ParseOverlayableBadActorFail) {
  EXPECT_FALSE(TestParse(R"(<overlayable name="Name" actor="overley://theme" />)"));
}

TEST_F(ResourceParserTest, ParseOverlayablePolicy) {
  std::string input = R"(
      <overlayable name="Name">
        <policy type="product">
          <item type="string" name="bar" />
        </policy>
        <policy type="system">
          <item type="string" name="fiz" />
        </policy>
        <policy type="vendor">
          <item type="string" name="fuz" />
        </policy>
        <policy type="public">
          <item type="string" name="faz" />
        </policy>
        <policy type="signature">
          <item type="string" name="foz" />
        </policy>
        <policy type="odm">
          <item type="string" name="biz" />
        </policy>
        <policy type="oem">
          <item type="string" name="buz" />
        </policy>
      </overlayable>)";
  ASSERT_TRUE(TestParse(input));

  auto search_result = table_.FindResource(test::ParseNameOrDie("string/bar"));
  ASSERT_TRUE(search_result);
  ASSERT_THAT(search_result.value().entry, NotNull());
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  OverlayableItem result_overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(result_overlayable_item.overlayable->name, Eq("Name"));
  EXPECT_THAT(result_overlayable_item.policies, Eq(OverlayableItem::Policy::kProduct));

  search_result = table_.FindResource(test::ParseNameOrDie("string/fiz"));
  ASSERT_TRUE(search_result);
  ASSERT_THAT(search_result.value().entry, NotNull());
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  result_overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(result_overlayable_item.overlayable->name, Eq("Name"));
  EXPECT_THAT(result_overlayable_item.policies, Eq(OverlayableItem::Policy::kSystem));

  search_result = table_.FindResource(test::ParseNameOrDie("string/fuz"));
  ASSERT_TRUE(search_result);
  ASSERT_THAT(search_result.value().entry, NotNull());
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  result_overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(result_overlayable_item.overlayable->name, Eq("Name"));
  EXPECT_THAT(result_overlayable_item.policies, Eq(OverlayableItem::Policy::kVendor));

  search_result = table_.FindResource(test::ParseNameOrDie("string/faz"));
  ASSERT_TRUE(search_result);
  ASSERT_THAT(search_result.value().entry, NotNull());
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  result_overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(result_overlayable_item.overlayable->name, Eq("Name"));
  EXPECT_THAT(result_overlayable_item.policies, Eq(OverlayableItem::Policy::kPublic));

  search_result = table_.FindResource(test::ParseNameOrDie("string/foz"));
  ASSERT_TRUE(search_result);
  ASSERT_THAT(search_result.value().entry, NotNull());
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  result_overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(result_overlayable_item.overlayable->name, Eq("Name"));
  EXPECT_THAT(result_overlayable_item.policies, Eq(OverlayableItem::Policy::kSignature));

  search_result = table_.FindResource(test::ParseNameOrDie("string/biz"));
  ASSERT_TRUE(search_result);
  ASSERT_THAT(search_result.value().entry, NotNull());
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  result_overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(result_overlayable_item.overlayable->name, Eq("Name"));
  EXPECT_THAT(result_overlayable_item.policies, Eq(OverlayableItem::Policy::kOdm));

  search_result = table_.FindResource(test::ParseNameOrDie("string/buz"));
  ASSERT_TRUE(search_result);
  ASSERT_THAT(search_result.value().entry, NotNull());
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  result_overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(result_overlayable_item.overlayable->name, Eq("Name"));
  EXPECT_THAT(result_overlayable_item.policies, Eq(OverlayableItem::Policy::kOem));
}

TEST_F(ResourceParserTest, ParseOverlayableNoPolicyError) {
  std::string input = R"(
      <overlayable name="Name">
        <item type="string" name="foo" />
      </overlayable>)";
  EXPECT_FALSE(TestParse(input));

  input = R"(
      <overlayable name="Name">
        <policy>
          <item name="foo" />
        </policy>
      </overlayable>)";
  EXPECT_FALSE(TestParse(input));
}

TEST_F(ResourceParserTest, ParseOverlayableBadPolicyError) {
  std::string input = R"(
      <overlayable name="Name">
        <policy type="illegal_policy">
          <item type="string" name="foo" />
        </policy>
      </overlayable>)";
  EXPECT_FALSE(TestParse(input));

  input = R"(
      <overlayable name="Name">
        <policy type="product">
          <item name="foo" />
        </policy>
      </overlayable>)";
  EXPECT_FALSE(TestParse(input));

  input = R"(
      <overlayable name="Name">
        <policy type="vendor">
          <item type="string" />
        </policy>
      </overlayable>)";
  EXPECT_FALSE(TestParse(input));
}

TEST_F(ResourceParserTest, ParseOverlayableMultiplePolicy) {
  std::string input = R"(
      <overlayable name="Name">
        <policy type="vendor|public">
          <item type="string" name="foo" />
        </policy>
        <policy type="product|system">
          <item type="string" name="bar" />
        </policy>
      </overlayable>)";
  ASSERT_TRUE(TestParse(input));

  auto search_result = table_.FindResource(test::ParseNameOrDie("string/foo"));
  ASSERT_TRUE(search_result);
  ASSERT_THAT(search_result.value().entry, NotNull());
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  OverlayableItem result_overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(result_overlayable_item.overlayable->name, Eq("Name"));
  EXPECT_THAT(result_overlayable_item.policies, Eq(OverlayableItem::Policy::kVendor
                                                   | OverlayableItem::Policy::kPublic));

  search_result = table_.FindResource(test::ParseNameOrDie("string/bar"));
  ASSERT_TRUE(search_result);
  ASSERT_THAT(search_result.value().entry, NotNull());
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  result_overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(result_overlayable_item.overlayable->name, Eq("Name"));
  EXPECT_THAT(result_overlayable_item.policies, Eq(OverlayableItem::Policy::kProduct
                                                   | OverlayableItem::Policy::kSystem));
}

TEST_F(ResourceParserTest, DuplicateOverlayableIsError) {
  std::string input = R"(
      <overlayable name="Name">
        <item type="string" name="foo" />
        <item type="string" name="foo" />
      </overlayable>)";
  EXPECT_FALSE(TestParse(input));

  input = R"(
      <overlayable name="Name">
        <item type="string" name="foo" />
      </overlayable>
      <overlayable name="Name">
        <item type="string" name="foo" />
      </overlayable>)";
  EXPECT_FALSE(TestParse(input));

  input = R"(
      <overlayable name="Name">
        <item type="string" name="foo" />
      </overlayable>
      <overlayable name="Other">
        <item type="string" name="foo" />
      </overlayable>)";
  EXPECT_FALSE(TestParse(input));

  input = R"(
      <overlayable name="Name" actor="overlay://my.actor.one">
        <item type="string" name="foo" />
      </overlayable>
      <overlayable name="Other" actor="overlay://my.actor.two">
        <item type="string" name="foo" />
      </overlayable>)";
  EXPECT_FALSE(TestParse(input));

  input = R"(
      <overlayable name="Name">
        <policy type="product">
          <item type="string" name="foo" />
          <item type="string" name="foo" />
        </policy>
      </overlayable>)";
  EXPECT_FALSE(TestParse(input));

  input = R"(
      <overlayable name="Name">
        <policy type="product">
          <item type="string" name="foo" />
        </policy>
        <item type="string" name="foo" />
      </overlayable>)";
  EXPECT_FALSE(TestParse(input));

  input = R"(
      <overlayable name="Name">
        <policy type="product">
          <item type="string" name="foo" />
        </policy>
        <policy type="vendor">
          <item type="string" name="foo" />
        </policy>
      </overlayable>)";
  EXPECT_FALSE(TestParse(input));

  input = R"(
      <overlayable name="Name">
        <policy type="product">
          <item type="string" name="foo" />
        </policy>
      </overlayable>

      <overlayable name="Name">
        <policy type="product">
          <item type="string" name="foo" />
        </policy>
      </overlayable>)";
  EXPECT_FALSE(TestParse(input));
}

TEST_F(ResourceParserTest, NestPolicyInOverlayableError) {
  std::string input = R"(
      <overlayable name="Name">
        <policy type="vendor|product">
          <policy type="public">
            <item type="string" name="foo" />
          </policy>
        </policy>
      </overlayable>)";
  EXPECT_FALSE(TestParse(input));
}

TEST_F(ResourceParserTest, ParseIdItem) {
  std::string input = R"(
    <item name="foo" type="id">@id/bar</item>
    <item name="bar" type="id"/>
    <item name="baz" type="id"></item>)";
  ASSERT_TRUE(TestParse(input));

  ASSERT_THAT(test::GetValue<Reference>(&table_, "id/foo"), NotNull());
  ASSERT_THAT(test::GetValue<Id>(&table_, "id/bar"), NotNull());
  ASSERT_THAT(test::GetValue<Id>(&table_, "id/baz"), NotNull());

  input = R"(
    <id name="foo2">@id/bar</id>
    <id name="bar2"/>
    <id name="baz2"></id>)";
  ASSERT_TRUE(TestParse(input));

  ASSERT_THAT(test::GetValue<Reference>(&table_, "id/foo2"), NotNull());
  ASSERT_THAT(test::GetValue<Id>(&table_, "id/bar2"), NotNull());
  ASSERT_THAT(test::GetValue<Id>(&table_, "id/baz2"), NotNull());

  // Reject attribute references
  input = R"(<item name="foo3" type="id">?attr/bar"</item>)";
  ASSERT_FALSE(TestParse(input));

  // Reject non-references
  input = R"(<item name="foo4" type="id">0x7f010001</item>)";
  ASSERT_FALSE(TestParse(input));
  input = R"(<item name="foo5" type="id">@drawable/my_image</item>)";
  ASSERT_FALSE(TestParse(input));
  input = R"(<item name="foo6" type="id"><string name="biz"></string></item>)";
  ASSERT_FALSE(TestParse(input));

  // Ids that reference other resource ids cannot be public
  input = R"(<public name="foo7" type="id">@id/bar7</item>)";
  ASSERT_FALSE(TestParse(input));
}

TEST_F(ResourceParserTest, ParseCData) {
  // Double quotes should still change the state of whitespace processing
  std::string input = R"(<string name="foo">Hello<![CDATA[ "</string>' ]]>      World</string>)";
  ASSERT_TRUE(TestParse(input));
  auto output = test::GetValue<String>(&table_, "string/foo");
  ASSERT_THAT(output, NotNull());
  EXPECT_THAT(*output, StrValueEq(std::string("Hello </string>'       World").data()));

  input = R"(<string name="foo2"><![CDATA[Hello
                                          World]]></string>)";
  ASSERT_TRUE(TestParse(input));
  output = test::GetValue<String>(&table_, "string/foo2");
  ASSERT_THAT(output, NotNull());
  EXPECT_THAT(*output, StrValueEq(std::string("Hello World").data()));

  // Cdata blocks should have their whitespace trimmed
  input = R"(<string name="foo3">     <![CDATA[ text ]]>     </string>)";
  ASSERT_TRUE(TestParse(input));
  output = test::GetValue<String>(&table_, "string/foo3");
  ASSERT_THAT(output, NotNull());
  EXPECT_THAT(*output, StrValueEq(std::string("text").data()));

  input = R"(<string name="foo4">     <![CDATA[]]>     </string>)";
  ASSERT_TRUE(TestParse(input));
  output = test::GetValue<String>(&table_, "string/foo4");
  ASSERT_THAT(output, NotNull());
  EXPECT_THAT(*output, StrValueEq(std::string("").data()));

  input = R"(<string name="foo5">     <![CDATA[    ]]>     </string>)";
  ASSERT_TRUE(TestParse(input));
  output = test::GetValue<String>(&table_, "string/foo5");
  ASSERT_THAT(output, NotNull());
  EXPECT_THAT(*output, StrValueEq(std::string("").data()));

  // Single quotes must still be escaped
  input = R"(<string name="foo6"><![CDATA[some text and ' apostrophe]]></string>)";
  ASSERT_FALSE(TestParse(input));
}

}  // namespace aapt
