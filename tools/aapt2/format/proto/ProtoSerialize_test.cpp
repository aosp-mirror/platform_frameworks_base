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

#include "google/protobuf/io/zero_copy_stream_impl_lite.h"

#include "ResourceUtils.h"
#include "format/proto/ProtoDeserialize.h"
#include "test/Test.h"

using ::android::StringPiece;
using ::google::protobuf::io::StringOutputStream;
using ::testing::Eq;
using ::testing::IsEmpty;
using ::testing::NotNull;
using ::testing::SizeIs;
using ::testing::StrEq;

namespace aapt {

TEST(ProtoSerializeTest, SerializeSinglePackage) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("com.app.a", 0x7f)
          .AddFileReference("com.app.a:layout/main", ResourceId(0x7f020000), "res/layout/main.xml")
          .AddReference("com.app.a:layout/other", ResourceId(0x7f020001), "com.app.a:layout/main")
          .AddString("com.app.a:string/text", {}, "hi")
          .AddValue("com.app.a:id/foo", {}, util::make_unique<Id>())
          .SetSymbolState("com.app.a:bool/foo", {}, SymbolState::kUndefined, true /*allow_new*/)
          .Build();

  Symbol public_symbol;
  public_symbol.state = SymbolState::kPublic;
  ASSERT_TRUE(table->SetSymbolState(test::ParseNameOrDie("com.app.a:layout/main"),
                                    ResourceId(0x7f020000), public_symbol,
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

  pb::ResourceTable pb_table;
  SerializeTableToPb(*table, &pb_table);

  ResourceTable new_table;
  std::string error;
  ASSERT_TRUE(DeserializeTableFromPb(pb_table, &new_table, &error));
  EXPECT_THAT(error, IsEmpty());

  Id* new_id = test::GetValue<Id>(&new_table, "com.app.a:id/foo");
  ASSERT_THAT(new_id, NotNull());
  EXPECT_THAT(new_id->IsWeak(), Eq(id->IsWeak()));

  Maybe<ResourceTable::SearchResult> result =
      new_table.FindResource(test::ParseNameOrDie("com.app.a:layout/main"));
  ASSERT_TRUE(result);

  EXPECT_THAT(result.value().type->symbol_status.state, Eq(SymbolState::kPublic));
  EXPECT_THAT(result.value().entry->symbol_status.state, Eq(SymbolState::kPublic));

  result = new_table.FindResource(test::ParseNameOrDie("com.app.a:bool/foo"));
  ASSERT_TRUE(result);
  EXPECT_THAT(result.value().entry->symbol_status.state, Eq(SymbolState::kUndefined));
  EXPECT_TRUE(result.value().entry->symbol_status.allow_new);

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

  StyledString* actual_styled_str =
      test::GetValue<StyledString>(&new_table, "com.app.a:string/styled");
  ASSERT_THAT(actual_styled_str, NotNull());
  EXPECT_THAT(actual_styled_str->value->value, Eq("hello"));
  ASSERT_THAT(actual_styled_str->value->spans, SizeIs(1u));
  EXPECT_THAT(*actual_styled_str->value->spans[0].name, Eq("b"));
  EXPECT_THAT(actual_styled_str->value->spans[0].first_char, Eq(0u));
  EXPECT_THAT(actual_styled_str->value->spans[0].last_char, Eq(4u));
}

TEST(ProtoSerializeTest, SerializeFileHeader) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  ResourceFile f;
  f.config = test::ParseConfigOrDie("hdpi-v9");
  f.name = test::ParseNameOrDie("com.app.a:layout/main");
  f.source.path = "res/layout-hdpi-v9/main.xml";
  f.exported_symbols.push_back(SourcedResourceName{test::ParseNameOrDie("id/unchecked"), 23u});

  const std::string expected_data1 = "123";
  const std::string expected_data2 = "1234";

  std::string output_str;
  {
    pb::internal::CompiledFile pb_f1, pb_f2;
    SerializeCompiledFileToPb(f, &pb_f1);

    f.name.entry = "__" + f.name.entry + "$0";
    SerializeCompiledFileToPb(f, &pb_f2);

    StringOutputStream out_stream(&output_str);
    CompiledFileOutputStream out_file_stream(&out_stream);
    out_file_stream.WriteLittleEndian32(2);
    out_file_stream.WriteCompiledFile(pb_f1);
    out_file_stream.WriteData(expected_data1.data(), expected_data1.size());
    out_file_stream.WriteCompiledFile(pb_f2);
    out_file_stream.WriteData(expected_data2.data(), expected_data2.size());
    ASSERT_FALSE(out_file_stream.HadError());
  }

  CompiledFileInputStream in_file_stream(output_str.data(), output_str.size());
  uint32_t num_files = 0;
  ASSERT_TRUE(in_file_stream.ReadLittleEndian32(&num_files));
  ASSERT_EQ(2u, num_files);

  // Read the first compiled file.

  pb::internal::CompiledFile new_pb_f1;
  ASSERT_TRUE(in_file_stream.ReadCompiledFile(&new_pb_f1));

  ResourceFile new_f1;
  std::string error;
  ASSERT_TRUE(DeserializeCompiledFileFromPb(new_pb_f1, &new_f1, &error));
  EXPECT_THAT(error, IsEmpty());

  uint64_t offset, len;
  ASSERT_TRUE(in_file_stream.ReadDataMetaData(&offset, &len));

  std::string actual_data(output_str.data() + offset, len);
  EXPECT_EQ(expected_data1, actual_data);

  // Expect the data to be aligned.
  EXPECT_EQ(0u, offset & 0x03);

  ASSERT_EQ(1u, new_f1.exported_symbols.size());
  EXPECT_EQ(test::ParseNameOrDie("id/unchecked"), new_f1.exported_symbols[0].name);

  // Read the second compiled file.

  pb::internal::CompiledFile new_pb_f2;
  ASSERT_TRUE(in_file_stream.ReadCompiledFile(&new_pb_f2));

  ResourceFile new_f2;
  ASSERT_TRUE(DeserializeCompiledFileFromPb(new_pb_f2, &new_f2, &error));
  EXPECT_THAT(error, IsEmpty());

  ASSERT_TRUE(in_file_stream.ReadDataMetaData(&offset, &len));

  actual_data = std::string(output_str.data() + offset, len);
  EXPECT_EQ(expected_data2, actual_data);

  // Expect the data to be aligned.
  EXPECT_EQ(0u, offset & 0x03);
}

TEST(ProtoSerializeTest, DeserializeCorruptHeaderSafely) {
  ResourceFile f;
  pb::internal::CompiledFile pb_file;
  SerializeCompiledFileToPb(f, &pb_file);

  const std::string expected_data = "1234";

  std::string output_str;
  {
    StringOutputStream out_stream(&output_str);
    CompiledFileOutputStream out_file_stream(&out_stream);
    out_file_stream.WriteLittleEndian32(1);
    out_file_stream.WriteCompiledFile(pb_file);
    out_file_stream.WriteData(expected_data.data(), expected_data.size());
    ASSERT_FALSE(out_file_stream.HadError());
  }

  output_str[4] = 0xff;

  CompiledFileInputStream in_file_stream(output_str.data(), output_str.size());

  uint32_t num_files = 0;
  EXPECT_TRUE(in_file_stream.ReadLittleEndian32(&num_files));
  EXPECT_EQ(1u, num_files);

  pb::internal::CompiledFile new_pb_file;
  EXPECT_FALSE(in_file_stream.ReadCompiledFile(&new_pb_file));

  uint64_t offset, len;
  EXPECT_FALSE(in_file_stream.ReadDataMetaData(&offset, &len));
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
  attr.compiled_attribute = xml::AaptAttribute({}, ResourceId(0x01010000));
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

}  // namespace aapt
