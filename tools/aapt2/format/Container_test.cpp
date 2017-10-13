/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "format/Container.h"

#include "google/protobuf/io/zero_copy_stream_impl_lite.h"

#include "io/StringStream.h"
#include "test/Test.h"

using ::google::protobuf::io::StringOutputStream;
using ::testing::Eq;
using ::testing::IsEmpty;
using ::testing::IsNull;
using ::testing::NotNull;
using ::testing::StrEq;

namespace aapt {

TEST(ContainerTest, SerializeCompiledFile) {
  std::unique_ptr<IAaptContext> context = test::ContextBuilder().Build();

  const std::string expected_data = "123";

  std::string output_str;
  {
    StringOutputStream out_stream(&output_str);
    ContainerWriter writer(&out_stream, 2u);
    ASSERT_FALSE(writer.HadError());

    pb::internal::CompiledFile pb_compiled_file;
    pb_compiled_file.set_resource_name("android:layout/main.xml");
    pb_compiled_file.set_type(pb::FileReference::PROTO_XML);
    pb_compiled_file.set_source_path("res/layout/main.xml");
    io::StringInputStream data(expected_data);
    ASSERT_TRUE(writer.AddResFileEntry(pb_compiled_file, &data));

    pb::ResourceTable pb_table;
    pb::Package* pb_pkg = pb_table.add_package();
    pb_pkg->set_package_name("android");
    pb_pkg->mutable_package_id()->set_id(0x01u);
    ASSERT_TRUE(writer.AddResTableEntry(pb_table));

    ASSERT_FALSE(writer.HadError());
  }

  io::StringInputStream input(output_str);
  ContainerReader reader(&input);
  ASSERT_FALSE(reader.HadError());

  ContainerReaderEntry* entry = reader.Next();
  ASSERT_THAT(entry, NotNull());
  ASSERT_THAT(entry->Type(), Eq(ContainerEntryType::kResFile));

  pb::internal::CompiledFile pb_new_file;
  off64_t offset;
  size_t len;
  ASSERT_TRUE(entry->GetResFileOffsets(&pb_new_file, &offset, &len)) << entry->GetError();
  EXPECT_THAT(offset & 0x03, Eq(0u));
  EXPECT_THAT(output_str.substr(static_cast<size_t>(offset), len), StrEq(expected_data));

  entry = reader.Next();
  ASSERT_THAT(entry, NotNull());
  ASSERT_THAT(entry->Type(), Eq(ContainerEntryType::kResTable));

  pb::ResourceTable pb_new_table;
  ASSERT_TRUE(entry->GetResTable(&pb_new_table));
  ASSERT_THAT(pb_new_table.package_size(), Eq(1));
  EXPECT_THAT(pb_new_table.package(0).package_name(), StrEq("android"));
  EXPECT_THAT(pb_new_table.package(0).package_id().id(), Eq(0x01u));

  EXPECT_THAT(reader.Next(), IsNull());
  EXPECT_FALSE(reader.HadError());
  EXPECT_THAT(reader.GetError(), IsEmpty());
}

}  // namespace aapt
