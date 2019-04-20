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

#include "format/proto/ProtoSerialize.h"

#include "ResourceUtils.h"
#include "format/proto/ProtoDeserialize.h"
#include "test/Test.h"

using ::android::ConfigDescription;
using ::android::StringPiece;
using ::testing::Eq;
using ::testing::IsEmpty;
using ::testing::NotNull;
using ::testing::SizeIs;
using ::testing::StrEq;

namespace aapt {

class MockFileCollection : public io::IFileCollection {
 public:
  MOCK_METHOD1(FindFile, io::IFile*(const StringPiece& path));
  MOCK_METHOD0(Iterator, std::unique_ptr<io::IFileCollectionIterator>());
  MOCK_METHOD0(GetDirSeparator, char());
};

TEST(ProtoSerializeTest, SerializeSinglePackage) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("com.app.a", 0x7f)
          .AddFileReference("com.app.a:layout/main", ResourceId(0x7f020000), "res/layout/main.xml")
          .AddReference("com.app.a:layout/other", ResourceId(0x7f020001), "com.app.a:layout/main")
          .AddString("com.app.a:string/text", {}, "hi")
          .AddValue("com.app.a:id/foo", {}, util::make_unique<Id>())
          .SetSymbolState("com.app.a:bool/foo", {}, Visibility::Level::kUndefined,
                          true /*allow_new*/)
          .Build();

  Visibility public_symbol;
  public_symbol.level = Visibility::Level::kPublic;
  ASSERT_TRUE(table->SetVisibilityWithId(test::ParseNameOrDie("com.app.a:layout/main"),
                                         public_symbol, ResourceId(0x7f020000),
                                         context->GetDiagnostics()));

  Id* id = test::GetValue<Id>(table.get(), "com.app.a:id/foo");
  ASSERT_THAT(id, NotNull());

  // Make a plural.
  std::unique_ptr<Plural> plural = util::make_unique<Plural>();
  plural->values[Plural::One] = util::make_unique<String>(table->string_pool.MakeRef("one"));
  ASSERT_TRUE(table->AddResource(test::ParseNameOrDie("com.app.a:plurals/hey"), ConfigDescription{},
                                 {}, std::move(plural), context->GetDiagnostics()));

  // Make a styled string.
  StyleString style_string;
  style_string.str = "hello";
  style_string.spans.push_back(Span{"b", 0u, 4u});
  ASSERT_TRUE(
      table->AddResource(test::ParseNameOrDie("com.app.a:string/styled"), ConfigDescription{}, {},
                         util::make_unique<StyledString>(table->string_pool.MakeRef(style_string)),
                         context->GetDiagnostics()));

  // Make a resource with different products.
  ASSERT_TRUE(table->AddResource(
      test::ParseNameOrDie("com.app.a:integer/one"), test::ParseConfigOrDie("land"), {},
      test::BuildPrimitive(android::Res_value::TYPE_INT_DEC, 123u), context->GetDiagnostics()));
  ASSERT_TRUE(table->AddResource(
      test::ParseNameOrDie("com.app.a:integer/one"), test::ParseConfigOrDie("land"), "tablet",
      test::BuildPrimitive(android::Res_value::TYPE_INT_DEC, 321u), context->GetDiagnostics()));

  // Make a reference with both resource name and resource ID.
  // The reference should point to a resource outside of this table to test that both name and id
  // get serialized.
  Reference expected_ref;
  expected_ref.name = test::ParseNameOrDie("android:layout/main");
  expected_ref.id = ResourceId(0x01020000);
  ASSERT_TRUE(table->AddResource(
      test::ParseNameOrDie("com.app.a:layout/abc"), ConfigDescription::DefaultConfig(), {},
      util::make_unique<Reference>(expected_ref), context->GetDiagnostics()));

  // Make an overlayable resource.
  OverlayableItem overlayable_item(std::make_shared<Overlayable>(
      "OverlayableName", "overlay://theme", Source("res/values/overlayable.xml", 40)));
  overlayable_item.source = Source("res/values/overlayable.xml", 42);
  ASSERT_TRUE(table->SetOverlayable(test::ParseNameOrDie("com.app.a:integer/overlayable"),
                                    overlayable_item, test::GetDiagnostics()));

  pb::ResourceTable pb_table;
  SerializeTableToPb(*table, &pb_table, context->GetDiagnostics());

  test::TestFile file_a("res/layout/main.xml");
  MockFileCollection files;
  EXPECT_CALL(files, FindFile(Eq("res/layout/main.xml")))
      .WillRepeatedly(::testing::Return(&file_a));

  ResourceTable new_table;
  std::string error;
  ASSERT_TRUE(DeserializeTableFromPb(pb_table, &files, &new_table, &error)) << error;
  EXPECT_THAT(error, IsEmpty());

  Id* new_id = test::GetValue<Id>(&new_table, "com.app.a:id/foo");
  ASSERT_THAT(new_id, NotNull());
  EXPECT_THAT(new_id->IsWeak(), Eq(id->IsWeak()));

  Maybe<ResourceTable::SearchResult> result =
      new_table.FindResource(test::ParseNameOrDie("com.app.a:layout/main"));
  ASSERT_TRUE(result);

  EXPECT_THAT(result.value().type->visibility_level, Eq(Visibility::Level::kPublic));
  EXPECT_THAT(result.value().entry->visibility.level, Eq(Visibility::Level::kPublic));

  result = new_table.FindResource(test::ParseNameOrDie("com.app.a:bool/foo"));
  ASSERT_TRUE(result);
  EXPECT_THAT(result.value().entry->visibility.level, Eq(Visibility::Level::kUndefined));
  EXPECT_TRUE(result.value().entry->allow_new);

  // Find the product-dependent values
  BinaryPrimitive* prim = test::GetValueForConfigAndProduct<BinaryPrimitive>(
      &new_table, "com.app.a:integer/one", test::ParseConfigOrDie("land"), "");
  ASSERT_THAT(prim, NotNull());
  EXPECT_THAT(prim->value.data, Eq(123u));

  prim = test::GetValueForConfigAndProduct<BinaryPrimitive>(
      &new_table, "com.app.a:integer/one", test::ParseConfigOrDie("land"), "tablet");
  ASSERT_THAT(prim, NotNull());
  EXPECT_THAT(prim->value.data, Eq(321u));

  Reference* actual_ref = test::GetValue<Reference>(&new_table, "com.app.a:layout/abc");
  ASSERT_THAT(actual_ref, NotNull());
  ASSERT_TRUE(actual_ref->name);
  ASSERT_TRUE(actual_ref->id);
  EXPECT_THAT(*actual_ref, Eq(expected_ref));

  FileReference* actual_file_ref =
      test::GetValue<FileReference>(&new_table, "com.app.a:layout/main");
  ASSERT_THAT(actual_file_ref, NotNull());
  EXPECT_THAT(actual_file_ref->file, Eq(&file_a));

  StyledString* actual_styled_str =
      test::GetValue<StyledString>(&new_table, "com.app.a:string/styled");
  ASSERT_THAT(actual_styled_str, NotNull());
  EXPECT_THAT(actual_styled_str->value->value, Eq("hello"));
  ASSERT_THAT(actual_styled_str->value->spans, SizeIs(1u));
  EXPECT_THAT(*actual_styled_str->value->spans[0].name, Eq("b"));
  EXPECT_THAT(actual_styled_str->value->spans[0].first_char, Eq(0u));
  EXPECT_THAT(actual_styled_str->value->spans[0].last_char, Eq(4u));

  Maybe<ResourceTable::SearchResult> search_result =
      new_table.FindResource(test::ParseNameOrDie("com.app.a:integer/overlayable"));
  ASSERT_TRUE(search_result);
  ASSERT_THAT(search_result.value().entry, NotNull());
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  OverlayableItem& result_overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(result_overlayable_item.overlayable->name, Eq("OverlayableName"));
  EXPECT_THAT(result_overlayable_item.overlayable->actor, Eq("overlay://theme"));
  EXPECT_THAT(result_overlayable_item.overlayable->source.path, Eq("res/values/overlayable.xml"));
  EXPECT_THAT(result_overlayable_item.overlayable->source.line, Eq(40));
  EXPECT_THAT(result_overlayable_item.policies, Eq(OverlayableItem::Policy::kNone));
  EXPECT_THAT(result_overlayable_item.source.path, Eq("res/values/overlayable.xml"));
  EXPECT_THAT(result_overlayable_item.source.line, Eq(42));
}

TEST(ProtoSerializeTest, SerializeAndDeserializeXml) {
  xml::Element element;
  element.line_number = 22;
  element.column_number = 23;
  element.name = "element";
  element.namespace_uri = "uri://";

  xml::NamespaceDecl decl;
  decl.prefix = "android";
  decl.uri = xml::kSchemaAndroid;
  decl.line_number = 21;
  decl.column_number = 24;

  element.namespace_decls.push_back(decl);

  xml::Attribute attr;
  attr.name = "name";
  attr.namespace_uri = xml::kSchemaAndroid;
  attr.value = "23dp";
  attr.compiled_attribute = xml::AaptAttribute(Attribute{}, ResourceId(0x01010000));
  attr.compiled_value =
      ResourceUtils::TryParseItemForAttribute(attr.value, android::ResTable_map::TYPE_DIMENSION);
  attr.compiled_value->SetSource(Source().WithLine(25));
  element.attributes.push_back(std::move(attr));

  std::unique_ptr<xml::Text> text = util::make_unique<xml::Text>();
  text->line_number = 25;
  text->column_number = 3;
  text->text = "hey there";
  element.AppendChild(std::move(text));

  std::unique_ptr<xml::Element> child = util::make_unique<xml::Element>();
  child->name = "child";

  text = util::make_unique<xml::Text>();
  text->text = "woah there";
  child->AppendChild(std::move(text));

  element.AppendChild(std::move(child));

  pb::XmlNode pb_xml;
  SerializeXmlToPb(element, &pb_xml);

  StringPool pool;
  xml::Element actual_el;
  std::string error;
  ASSERT_TRUE(DeserializeXmlFromPb(pb_xml, &actual_el, &pool, &error));
  ASSERT_THAT(error, IsEmpty());

  EXPECT_THAT(actual_el.name, StrEq("element"));
  EXPECT_THAT(actual_el.namespace_uri, StrEq("uri://"));
  EXPECT_THAT(actual_el.line_number, Eq(22u));
  EXPECT_THAT(actual_el.column_number, Eq(23u));

  ASSERT_THAT(actual_el.namespace_decls, SizeIs(1u));
  const xml::NamespaceDecl& actual_decl = actual_el.namespace_decls[0];
  EXPECT_THAT(actual_decl.prefix, StrEq("android"));
  EXPECT_THAT(actual_decl.uri, StrEq(xml::kSchemaAndroid));
  EXPECT_THAT(actual_decl.line_number, Eq(21u));
  EXPECT_THAT(actual_decl.column_number, Eq(24u));

  ASSERT_THAT(actual_el.attributes, SizeIs(1u));
  const xml::Attribute& actual_attr = actual_el.attributes[0];
  EXPECT_THAT(actual_attr.name, StrEq("name"));
  EXPECT_THAT(actual_attr.namespace_uri, StrEq(xml::kSchemaAndroid));
  EXPECT_THAT(actual_attr.value, StrEq("23dp"));

  ASSERT_THAT(actual_attr.compiled_value, NotNull());
  const BinaryPrimitive* prim = ValueCast<BinaryPrimitive>(actual_attr.compiled_value.get());
  ASSERT_THAT(prim, NotNull());
  EXPECT_THAT(prim->value.dataType, Eq(android::Res_value::TYPE_DIMENSION));

  ASSERT_TRUE(actual_attr.compiled_attribute);
  ASSERT_TRUE(actual_attr.compiled_attribute.value().id);

  ASSERT_THAT(actual_el.children, SizeIs(2u));
  const xml::Text* child_text = xml::NodeCast<xml::Text>(actual_el.children[0].get());
  ASSERT_THAT(child_text, NotNull());
  const xml::Element* child_el = xml::NodeCast<xml::Element>(actual_el.children[1].get());
  ASSERT_THAT(child_el, NotNull());

  EXPECT_THAT(child_text->line_number, Eq(25u));
  EXPECT_THAT(child_text->column_number, Eq(3u));
  EXPECT_THAT(child_text->text, StrEq("hey there"));

  EXPECT_THAT(child_el->name, StrEq("child"));
  ASSERT_THAT(child_el->children, SizeIs(1u));

  child_text = xml::NodeCast<xml::Text>(child_el->children[0].get());
  ASSERT_THAT(child_text, NotNull());
  EXPECT_THAT(child_text->text, StrEq("woah there"));
}

TEST(ProtoSerializeTest, SerializeAndDeserializeXmlTrimEmptyWhitepsace) {
  xml::Element element;
  element.line_number = 22;
  element.column_number = 23;
  element.name = "element";

  std::unique_ptr<xml::Text> trim_text = util::make_unique<xml::Text>();
  trim_text->line_number = 25;
  trim_text->column_number = 3;
  trim_text->text = "  \n   ";
  element.AppendChild(std::move(trim_text));

  std::unique_ptr<xml::Text> keep_text = util::make_unique<xml::Text>();
  keep_text->line_number = 26;
  keep_text->column_number = 3;
  keep_text->text = "  hello   ";
  element.AppendChild(std::move(keep_text));

  pb::XmlNode pb_xml;
  SerializeXmlOptions options;
  options.remove_empty_text_nodes = true;
  SerializeXmlToPb(element, &pb_xml, options);

  StringPool pool;
  xml::Element actual_el;
  std::string error;
  ASSERT_TRUE(DeserializeXmlFromPb(pb_xml, &actual_el, &pool, &error));
  ASSERT_THAT(error, IsEmpty());

  // Only the child that does not consist of only whitespace should remain
  ASSERT_THAT(actual_el.children, SizeIs(1u));
  const xml::Text* child_text_keep = xml::NodeCast<xml::Text>(actual_el.children[0].get());
  ASSERT_THAT(child_text_keep, NotNull());
  EXPECT_THAT(child_text_keep->text, StrEq( "  hello   "));
}

TEST(ProtoSerializeTest, SerializeAndDeserializePrimitives) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddValue("android:bool/boolean_true",
                    test::BuildPrimitive(android::Res_value::TYPE_INT_BOOLEAN, true))
          .AddValue("android:bool/boolean_false",
                    test::BuildPrimitive(android::Res_value::TYPE_INT_BOOLEAN, false))
          .AddValue("android:color/color_rgb8", ResourceUtils::TryParseColor("#AABBCC"))
          .AddValue("android:color/color_argb8", ResourceUtils::TryParseColor("#11223344"))
          .AddValue("android:color/color_rgb4", ResourceUtils::TryParseColor("#DEF"))
          .AddValue("android:color/color_argb4", ResourceUtils::TryParseColor("#5678"))
          .AddValue("android:integer/integer_444", ResourceUtils::TryParseInt("444"))
          .AddValue("android:integer/integer_neg_333", ResourceUtils::TryParseInt("-333"))
          .AddValue("android:integer/hex_int_abcd", ResourceUtils::TryParseInt("0xABCD"))
          .AddValue("android:dimen/dimen_1.39mm", ResourceUtils::TryParseFloat("1.39mm"))
          .AddValue("android:fraction/fraction_27", ResourceUtils::TryParseFloat("27%"))
          .AddValue("android:dimen/neg_2.3in", ResourceUtils::TryParseFloat("-2.3in"))
          .AddValue("android:integer/null", ResourceUtils::MakeEmpty())
          .Build();

  pb::ResourceTable pb_table;
  SerializeTableToPb(*table, &pb_table, context->GetDiagnostics());

  test::TestFile file_a("res/layout/main.xml");
  MockFileCollection files;
  EXPECT_CALL(files, FindFile(Eq("res/layout/main.xml")))
      .WillRepeatedly(::testing::Return(&file_a));

  ResourceTable new_table;
  std::string error;
  ASSERT_TRUE(DeserializeTableFromPb(pb_table, &files, &new_table, &error));
  EXPECT_THAT(error, IsEmpty());

  BinaryPrimitive* bp = test::GetValueForConfigAndProduct<BinaryPrimitive>(
      &new_table, "android:bool/boolean_true", ConfigDescription::DefaultConfig(), "");
  ASSERT_THAT(bp, NotNull());
  EXPECT_THAT(bp->value.dataType, Eq(android::Res_value::TYPE_INT_BOOLEAN));
  EXPECT_THAT(bp->value.data, Eq(ResourceUtils::TryParseBool("true")->value.data));

  bp = test::GetValueForConfigAndProduct<BinaryPrimitive>(&new_table, "android:bool/boolean_false",
                                                          ConfigDescription::DefaultConfig(), "");
  ASSERT_THAT(bp, NotNull());
  EXPECT_THAT(bp->value.dataType, Eq(android::Res_value::TYPE_INT_BOOLEAN));
  EXPECT_THAT(bp->value.data, Eq(ResourceUtils::TryParseBool("false")->value.data));

  bp = test::GetValueForConfigAndProduct<BinaryPrimitive>(&new_table, "android:color/color_rgb8",
                                                          ConfigDescription::DefaultConfig(), "");
  ASSERT_THAT(bp, NotNull());
  EXPECT_THAT(bp->value.dataType, Eq(android::Res_value::TYPE_INT_COLOR_RGB8));
  EXPECT_THAT(bp->value.data, Eq(ResourceUtils::TryParseColor("#AABBCC")->value.data));

  bp = test::GetValueForConfigAndProduct<BinaryPrimitive>(&new_table, "android:color/color_argb8",
                                                          ConfigDescription::DefaultConfig(), "");
  ASSERT_THAT(bp, NotNull());
  EXPECT_THAT(bp->value.dataType, Eq(android::Res_value::TYPE_INT_COLOR_ARGB8));
  EXPECT_THAT(bp->value.data, Eq(ResourceUtils::TryParseColor("#11223344")->value.data));

  bp = test::GetValueForConfigAndProduct<BinaryPrimitive>(&new_table, "android:color/color_rgb4",
                                                          ConfigDescription::DefaultConfig(), "");
  ASSERT_THAT(bp, NotNull());
  EXPECT_THAT(bp->value.dataType, Eq(android::Res_value::TYPE_INT_COLOR_RGB4));
  EXPECT_THAT(bp->value.data, Eq(ResourceUtils::TryParseColor("#DEF")->value.data));

  bp = test::GetValueForConfigAndProduct<BinaryPrimitive>(&new_table, "android:color/color_argb4",
                                                          ConfigDescription::DefaultConfig(), "");
  ASSERT_THAT(bp, NotNull());
  EXPECT_THAT(bp->value.dataType, Eq(android::Res_value::TYPE_INT_COLOR_ARGB4));
  EXPECT_THAT(bp->value.data, Eq(ResourceUtils::TryParseColor("#5678")->value.data));

  bp = test::GetValueForConfigAndProduct<BinaryPrimitive>(&new_table, "android:integer/integer_444",
                                                          ConfigDescription::DefaultConfig(), "");
  ASSERT_THAT(bp, NotNull());
  EXPECT_THAT(bp->value.dataType, Eq(android::Res_value::TYPE_INT_DEC));
  EXPECT_THAT(bp->value.data, Eq(ResourceUtils::TryParseInt("444")->value.data));

  bp = test::GetValueForConfigAndProduct<BinaryPrimitive>(
      &new_table, "android:integer/integer_neg_333", ConfigDescription::DefaultConfig(), "");
  ASSERT_THAT(bp, NotNull());
  EXPECT_THAT(bp->value.dataType, Eq(android::Res_value::TYPE_INT_DEC));
  EXPECT_THAT(bp->value.data, Eq(ResourceUtils::TryParseInt("-333")->value.data));

  bp = test::GetValueForConfigAndProduct<BinaryPrimitive>(
      &new_table, "android:integer/hex_int_abcd", ConfigDescription::DefaultConfig(), "");
  ASSERT_THAT(bp, NotNull());
  EXPECT_THAT(bp->value.dataType, Eq(android::Res_value::TYPE_INT_HEX));
  EXPECT_THAT(bp->value.data, Eq(ResourceUtils::TryParseInt("0xABCD")->value.data));

  bp = test::GetValueForConfigAndProduct<BinaryPrimitive>(&new_table, "android:dimen/dimen_1.39mm",
                                                          ConfigDescription::DefaultConfig(), "");
  ASSERT_THAT(bp, NotNull());
  EXPECT_THAT(bp->value.dataType, Eq(android::Res_value::TYPE_DIMENSION));
  EXPECT_THAT(bp->value.data, Eq(ResourceUtils::TryParseFloat("1.39mm")->value.data));

  bp = test::GetValueForConfigAndProduct<BinaryPrimitive>(
      &new_table, "android:fraction/fraction_27", ConfigDescription::DefaultConfig(), "");
  ASSERT_THAT(bp, NotNull());
  EXPECT_THAT(bp->value.dataType, Eq(android::Res_value::TYPE_FRACTION));
  EXPECT_THAT(bp->value.data, Eq(ResourceUtils::TryParseFloat("27%")->value.data));

  bp = test::GetValueForConfigAndProduct<BinaryPrimitive>(&new_table, "android:dimen/neg_2.3in",
                                                          ConfigDescription::DefaultConfig(), "");
  ASSERT_THAT(bp, NotNull());
  EXPECT_THAT(bp->value.dataType, Eq(android::Res_value::TYPE_DIMENSION));
  EXPECT_THAT(bp->value.data, Eq(ResourceUtils::TryParseFloat("-2.3in")->value.data));

  bp = test::GetValueForConfigAndProduct<BinaryPrimitive>(&new_table, "android:integer/null",
                                                          ConfigDescription::DefaultConfig(), "");
  ASSERT_THAT(bp, NotNull());
  EXPECT_THAT(bp->value.dataType, Eq(android::Res_value::TYPE_NULL));
  EXPECT_THAT(bp->value.data, Eq(ResourceUtils::MakeEmpty()->value.data));
}

static void ExpectConfigSerializes(const StringPiece& config_str) {
  const ConfigDescription expected_config = test::ParseConfigOrDie(config_str);
  pb::Configuration pb_config;
  SerializeConfig(expected_config, &pb_config);

  ConfigDescription actual_config;
  std::string error;
  ASSERT_TRUE(DeserializeConfigFromPb(pb_config, &actual_config, &error));
  ASSERT_THAT(error, IsEmpty());
  EXPECT_EQ(expected_config, actual_config);
}

TEST(ProtoSerializeTest, SerializeDeserializeConfiguration) {
  ExpectConfigSerializes("");

  ExpectConfigSerializes("mcc123");

  ExpectConfigSerializes("mnc123");

  ExpectConfigSerializes("en");
  ExpectConfigSerializes("en-rGB");
  ExpectConfigSerializes("b+en+GB");

  ExpectConfigSerializes("ldltr");
  ExpectConfigSerializes("ldrtl");

  ExpectConfigSerializes("sw3600dp");

  ExpectConfigSerializes("w300dp");

  ExpectConfigSerializes("h400dp");

  ExpectConfigSerializes("small");
  ExpectConfigSerializes("normal");
  ExpectConfigSerializes("large");
  ExpectConfigSerializes("xlarge");

  ExpectConfigSerializes("long");
  ExpectConfigSerializes("notlong");

  ExpectConfigSerializes("round");
  ExpectConfigSerializes("notround");

  ExpectConfigSerializes("widecg");
  ExpectConfigSerializes("nowidecg");

  ExpectConfigSerializes("highdr");
  ExpectConfigSerializes("lowdr");

  ExpectConfigSerializes("port");
  ExpectConfigSerializes("land");
  ExpectConfigSerializes("square");

  ExpectConfigSerializes("desk");
  ExpectConfigSerializes("car");
  ExpectConfigSerializes("television");
  ExpectConfigSerializes("appliance");
  ExpectConfigSerializes("watch");
  ExpectConfigSerializes("vrheadset");

  ExpectConfigSerializes("night");
  ExpectConfigSerializes("notnight");

  ExpectConfigSerializes("300dpi");
  ExpectConfigSerializes("hdpi");

  ExpectConfigSerializes("notouch");
  ExpectConfigSerializes("stylus");
  ExpectConfigSerializes("finger");

  ExpectConfigSerializes("keysexposed");
  ExpectConfigSerializes("keyshidden");
  ExpectConfigSerializes("keyssoft");

  ExpectConfigSerializes("nokeys");
  ExpectConfigSerializes("qwerty");
  ExpectConfigSerializes("12key");

  ExpectConfigSerializes("navhidden");
  ExpectConfigSerializes("navexposed");

  ExpectConfigSerializes("nonav");
  ExpectConfigSerializes("dpad");
  ExpectConfigSerializes("trackball");
  ExpectConfigSerializes("wheel");

  ExpectConfigSerializes("300x200");

  ExpectConfigSerializes("v8");

  ExpectConfigSerializes(
      "mcc123-mnc456-b+en+GB-ldltr-sw300dp-w300dp-h400dp-large-long-round-widecg-highdr-land-car-"
      "night-xhdpi-stylus-keysexposed-qwerty-navhidden-dpad-300x200-v23");
}

TEST(ProtoSerializeTest, SerializeAndDeserializeOverlayable) {
  OverlayableItem overlayable_item_foo(std::make_shared<Overlayable>(
      "CustomizableResources", "overlay://customization"));
  overlayable_item_foo.policies |= OverlayableItem::Policy::kSystem;
  overlayable_item_foo.policies |= OverlayableItem::Policy::kProduct;

  OverlayableItem overlayable_item_bar(std::make_shared<Overlayable>(
      "TaskBar", "overlay://theme"));
  overlayable_item_bar.policies |= OverlayableItem::Policy::kPublic;
  overlayable_item_bar.policies |= OverlayableItem::Policy::kVendor;

  OverlayableItem overlayable_item_baz(std::make_shared<Overlayable>(
      "FontPack", "overlay://theme"));
  overlayable_item_baz.policies |= OverlayableItem::Policy::kPublic;

  OverlayableItem overlayable_item_boz(std::make_shared<Overlayable>(
      "IconPack", "overlay://theme"));
  overlayable_item_boz.policies |= OverlayableItem::Policy::kSignature;
  overlayable_item_boz.policies |= OverlayableItem::Policy::kOdm;
  overlayable_item_boz.policies |= OverlayableItem::Policy::kOem;

  OverlayableItem overlayable_item_biz(std::make_shared<Overlayable>(
      "Other", "overlay://customization"));
  overlayable_item_biz.comment ="comment";

  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetOverlayable("com.app.a:bool/foo", overlayable_item_foo)
          .SetOverlayable("com.app.a:bool/bar", overlayable_item_bar)
          .SetOverlayable("com.app.a:bool/baz", overlayable_item_baz)
          .SetOverlayable("com.app.a:bool/boz", overlayable_item_boz)
          .SetOverlayable("com.app.a:bool/biz", overlayable_item_biz)
          .AddValue("com.app.a:bool/fiz", ResourceUtils::TryParseBool("true"))
          .Build();

  pb::ResourceTable pb_table;
  SerializeTableToPb(*table, &pb_table, context->GetDiagnostics());

  MockFileCollection files;
  ResourceTable new_table;
  std::string error;
  ASSERT_TRUE(DeserializeTableFromPb(pb_table, &files, &new_table, &error));
  EXPECT_THAT(error, IsEmpty());

  Maybe<ResourceTable::SearchResult> search_result =
      new_table.FindResource(test::ParseNameOrDie("com.app.a:bool/foo"));
  ASSERT_TRUE(search_result);
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  OverlayableItem& overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(overlayable_item.overlayable->name, Eq("CustomizableResources"));
  EXPECT_THAT(overlayable_item.overlayable->actor, Eq("overlay://customization"));
  EXPECT_THAT(overlayable_item.policies, Eq(OverlayableItem::Policy::kSystem
                                              | OverlayableItem::Policy::kProduct));

  search_result = new_table.FindResource(test::ParseNameOrDie("com.app.a:bool/bar"));
  ASSERT_TRUE(search_result);
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(overlayable_item.overlayable->name, Eq("TaskBar"));
  EXPECT_THAT(overlayable_item.overlayable->actor, Eq("overlay://theme"));
  EXPECT_THAT(overlayable_item.policies, Eq(OverlayableItem::Policy::kPublic
                                              | OverlayableItem::Policy::kVendor));

  search_result = new_table.FindResource(test::ParseNameOrDie("com.app.a:bool/baz"));
  ASSERT_TRUE(search_result);
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(overlayable_item.overlayable->name, Eq("FontPack"));
  EXPECT_THAT(overlayable_item.overlayable->actor, Eq("overlay://theme"));
  EXPECT_THAT(overlayable_item.policies, Eq(OverlayableItem::Policy::kPublic));

  search_result = new_table.FindResource(test::ParseNameOrDie("com.app.a:bool/boz"));
  ASSERT_TRUE(search_result);
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(overlayable_item.overlayable->name, Eq("IconPack"));
  EXPECT_THAT(overlayable_item.overlayable->actor, Eq("overlay://theme"));
  EXPECT_THAT(overlayable_item.policies, Eq(OverlayableItem::Policy::kSignature
                                            | OverlayableItem::Policy::kOdm
                                            | OverlayableItem::Policy::kOem));

  search_result = new_table.FindResource(test::ParseNameOrDie("com.app.a:bool/biz"));
  ASSERT_TRUE(search_result);
  ASSERT_TRUE(search_result.value().entry->overlayable_item);
  overlayable_item = search_result.value().entry->overlayable_item.value();
  EXPECT_THAT(overlayable_item.overlayable->name, Eq("Other"));
  EXPECT_THAT(overlayable_item.policies, Eq(OverlayableItem::Policy::kNone));
  EXPECT_THAT(overlayable_item.comment, Eq("comment"));

  search_result = new_table.FindResource(test::ParseNameOrDie("com.app.a:bool/fiz"));
  ASSERT_TRUE(search_result);
  ASSERT_FALSE(search_result.value().entry->overlayable_item);
}

}  // namespace aapt
