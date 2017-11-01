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

#include "proto/ProtoSerialize.h"

#include "ResourceTable.h"
#include "test/Test.h"

using ::google::protobuf::io::StringOutputStream;

namespace aapt {

TEST(TableProtoSerializer, SerializeSinglePackage) {
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
  ASSERT_TRUE(table->SetSymbolState(
      test::ParseNameOrDie("com.app.a:layout/main"), ResourceId(0x7f020000),
      public_symbol, context->GetDiagnostics()));

  Id* id = test::GetValue<Id>(table.get(), "com.app.a:id/foo");
  ASSERT_NE(nullptr, id);

  // Make a plural.
  std::unique_ptr<Plural> plural = util::make_unique<Plural>();
  plural->values[Plural::One] =
      util::make_unique<String>(table->string_pool.MakeRef("one"));
  ASSERT_TRUE(table->AddResource(test::ParseNameOrDie("com.app.a:plurals/hey"),
                                 ConfigDescription{}, {}, std::move(plural),
                                 context->GetDiagnostics()));

  // Make a resource with different products.
  ASSERT_TRUE(table->AddResource(
      test::ParseNameOrDie("com.app.a:integer/one"),
      test::ParseConfigOrDie("land"), {},
      test::BuildPrimitive(android::Res_value::TYPE_INT_DEC, 123u),
      context->GetDiagnostics()));
  ASSERT_TRUE(table->AddResource(
      test::ParseNameOrDie("com.app.a:integer/one"),
      test::ParseConfigOrDie("land"), "tablet",
      test::BuildPrimitive(android::Res_value::TYPE_INT_DEC, 321u),
      context->GetDiagnostics()));

  // Make a reference with both resource name and resource ID.
  // The reference should point to a resource outside of this table to test that
  // both
  // name and id get serialized.
  Reference expected_ref;
  expected_ref.name = test::ParseNameOrDie("android:layout/main");
  expected_ref.id = ResourceId(0x01020000);
  ASSERT_TRUE(table->AddResource(test::ParseNameOrDie("com.app.a:layout/abc"),
                                 ConfigDescription::DefaultConfig(), {},
                                 util::make_unique<Reference>(expected_ref),
                                 context->GetDiagnostics()));

  std::unique_ptr<pb::ResourceTable> pb_table = SerializeTableToPb(table.get());
  ASSERT_NE(nullptr, pb_table);

  std::unique_ptr<ResourceTable> new_table = DeserializeTableFromPb(
      *pb_table, Source{"test"}, context->GetDiagnostics());
  ASSERT_NE(nullptr, new_table);

  Id* new_id = test::GetValue<Id>(new_table.get(), "com.app.a:id/foo");
  ASSERT_NE(nullptr, new_id);
  EXPECT_EQ(id->IsWeak(), new_id->IsWeak());

  Maybe<ResourceTable::SearchResult> result =
      new_table->FindResource(test::ParseNameOrDie("com.app.a:layout/main"));
  AAPT_ASSERT_TRUE(result);
  EXPECT_EQ(SymbolState::kPublic, result.value().type->symbol_status.state);
  EXPECT_EQ(SymbolState::kPublic, result.value().entry->symbol_status.state);

  result = new_table->FindResource(test::ParseNameOrDie("com.app.a:bool/foo"));
  ASSERT_TRUE(result);
  EXPECT_EQ(SymbolState::kUndefined, result.value().entry->symbol_status.state);
  EXPECT_TRUE(result.value().entry->symbol_status.allow_new);

  // Find the product-dependent values
  BinaryPrimitive* prim = test::GetValueForConfigAndProduct<BinaryPrimitive>(
      new_table.get(), "com.app.a:integer/one", test::ParseConfigOrDie("land"), "");
  ASSERT_NE(nullptr, prim);
  EXPECT_EQ(123u, prim->value.data);

  prim = test::GetValueForConfigAndProduct<BinaryPrimitive>(
      new_table.get(), "com.app.a:integer/one", test::ParseConfigOrDie("land"), "tablet");
  ASSERT_NE(nullptr, prim);
  EXPECT_EQ(321u, prim->value.data);

  Reference* actual_ref = test::GetValue<Reference>(new_table.get(), "com.app.a:layout/abc");
  ASSERT_NE(nullptr, actual_ref);
  AAPT_ASSERT_TRUE(actual_ref->name);
  AAPT_ASSERT_TRUE(actual_ref->id);
  EXPECT_EQ(expected_ref.name.value(), actual_ref->name.value());
  EXPECT_EQ(expected_ref.id.value(), actual_ref->id.value());
}

TEST(TableProtoSerializer, SerializeFileHeader) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  ResourceFile f;
  f.config = test::ParseConfigOrDie("hdpi-v9");
  f.name = test::ParseNameOrDie("com.app.a:layout/main");
  f.source.path = "res/layout-hdpi-v9/main.xml";
  f.exported_symbols.push_back(
      SourcedResourceName{test::ParseNameOrDie("id/unchecked"), 23u});

  const std::string expected_data1 = "123";
  const std::string expected_data2 = "1234";

  std::string output_str;
  {
    std::unique_ptr<pb::CompiledFile> pb_file1 = SerializeCompiledFileToPb(f);

    f.name.entry = "__" + f.name.entry + "$0";
    std::unique_ptr<pb::CompiledFile> pb_file2 = SerializeCompiledFileToPb(f);

    StringOutputStream out_stream(&output_str);
    CompiledFileOutputStream out_file_stream(&out_stream);
    out_file_stream.WriteLittleEndian32(2);
    out_file_stream.WriteCompiledFile(pb_file1.get());
    out_file_stream.WriteData(expected_data1.data(), expected_data1.size());
    out_file_stream.WriteCompiledFile(pb_file2.get());
    out_file_stream.WriteData(expected_data2.data(), expected_data2.size());
    ASSERT_FALSE(out_file_stream.HadError());
  }

  CompiledFileInputStream in_file_stream(output_str.data(), output_str.size());
  uint32_t num_files = 0;
  ASSERT_TRUE(in_file_stream.ReadLittleEndian32(&num_files));
  ASSERT_EQ(2u, num_files);

  // Read the first compiled file.

  pb::CompiledFile new_pb_file;
  ASSERT_TRUE(in_file_stream.ReadCompiledFile(&new_pb_file));

  std::unique_ptr<ResourceFile> file = DeserializeCompiledFileFromPb(
      new_pb_file, Source("test"), context->GetDiagnostics());
  ASSERT_NE(nullptr, file);

  uint64_t offset, len;
  ASSERT_TRUE(in_file_stream.ReadDataMetaData(&offset, &len));

  std::string actual_data(output_str.data() + offset, len);
  EXPECT_EQ(expected_data1, actual_data);

  // Expect the data to be aligned.
  EXPECT_EQ(0u, offset & 0x03);

  ASSERT_EQ(1u, file->exported_symbols.size());
  EXPECT_EQ(test::ParseNameOrDie("id/unchecked"),
            file->exported_symbols[0].name);

  // Read the second compiled file.

  ASSERT_TRUE(in_file_stream.ReadCompiledFile(&new_pb_file));

  file = DeserializeCompiledFileFromPb(new_pb_file, Source("test"),
                                       context->GetDiagnostics());
  ASSERT_NE(nullptr, file);

  ASSERT_TRUE(in_file_stream.ReadDataMetaData(&offset, &len));

  actual_data = std::string(output_str.data() + offset, len);
  EXPECT_EQ(expected_data2, actual_data);

  // Expect the data to be aligned.
  EXPECT_EQ(0u, offset & 0x03);
}

TEST(TableProtoSerializer, DeserializeCorruptHeaderSafely) {
  ResourceFile f;
  std::unique_ptr<pb::CompiledFile> pb_file = SerializeCompiledFileToPb(f);

  const std::string expected_data = "1234";

  std::string output_str;
  {
    StringOutputStream out_stream(&output_str);
    CompiledFileOutputStream out_file_stream(&out_stream);
    out_file_stream.WriteLittleEndian32(1);
    out_file_stream.WriteCompiledFile(pb_file.get());
    out_file_stream.WriteData(expected_data.data(), expected_data.size());
    ASSERT_FALSE(out_file_stream.HadError());
  }

  output_str[4] = 0xff;

  CompiledFileInputStream in_file_stream(output_str.data(), output_str.size());

  uint32_t num_files = 0;
  EXPECT_TRUE(in_file_stream.ReadLittleEndian32(&num_files));
  EXPECT_EQ(1u, num_files);

  pb::CompiledFile new_pb_file;
  EXPECT_FALSE(in_file_stream.ReadCompiledFile(&new_pb_file));

  uint64_t offset, len;
  EXPECT_FALSE(in_file_stream.ReadDataMetaData(&offset, &len));
}

}  // namespace aapt
