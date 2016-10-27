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

#include "flatten/TableFlattener.h"

#include "ResourceUtils.h"
#include "test/Test.h"
#include "unflatten/BinaryResourceParser.h"
#include "util/Util.h"

using namespace android;

namespace aapt {

class TableFlattenerTest : public ::testing::Test {
 public:
  void SetUp() override {
    context_ = test::ContextBuilder()
                   .SetCompilationPackage("com.app.test")
                   .SetPackageId(0x7f)
                   .Build();
  }

  ::testing::AssertionResult Flatten(ResourceTable* table,
                                     ResTable* out_table) {
    BigBuffer buffer(1024);
    TableFlattener flattener(&buffer);
    if (!flattener.Consume(context_.get(), table)) {
      return ::testing::AssertionFailure() << "failed to flatten ResourceTable";
    }

    std::unique_ptr<uint8_t[]> data = util::Copy(buffer);
    if (out_table->add(data.get(), buffer.size(), -1, true) != NO_ERROR) {
      return ::testing::AssertionFailure() << "flattened ResTable is corrupt";
    }
    return ::testing::AssertionSuccess();
  }

  ::testing::AssertionResult Flatten(ResourceTable* table,
                                     ResourceTable* out_table) {
    BigBuffer buffer(1024);
    TableFlattener flattener(&buffer);
    if (!flattener.Consume(context_.get(), table)) {
      return ::testing::AssertionFailure() << "failed to flatten ResourceTable";
    }

    std::unique_ptr<uint8_t[]> data = util::Copy(buffer);
    BinaryResourceParser parser(context_.get(), out_table, {}, data.get(),
                                buffer.size());
    if (!parser.Parse()) {
      return ::testing::AssertionFailure() << "flattened ResTable is corrupt";
    }
    return ::testing::AssertionSuccess();
  }

  ::testing::AssertionResult Exists(ResTable* table,
                                    const StringPiece& expected_name,
                                    const ResourceId& expected_id,
                                    const ConfigDescription& expected_config,
                                    const uint8_t expected_data_type,
                                    const uint32_t expected_data,
                                    const uint32_t expected_spec_flags) {
    const ResourceName expected_res_name = test::ParseNameOrDie(expected_name);

    table->setParameters(&expected_config);

    ResTable_config config;
    Res_value val;
    uint32_t spec_flags;
    if (table->getResource(expected_id.id, &val, false, 0, &spec_flags,
                           &config) < 0) {
      return ::testing::AssertionFailure() << "could not find resource with";
    }

    if (expected_data_type != val.dataType) {
      return ::testing::AssertionFailure()
             << "expected data type " << std::hex << (int)expected_data_type
             << " but got data type " << (int)val.dataType << std::dec
             << " instead";
    }

    if (expected_data != val.data) {
      return ::testing::AssertionFailure()
             << "expected data " << std::hex << expected_data
             << " but got data " << val.data << std::dec << " instead";
    }

    if (expected_spec_flags != spec_flags) {
      return ::testing::AssertionFailure()
             << "expected specFlags " << std::hex << expected_spec_flags
             << " but got specFlags " << spec_flags << std::dec << " instead";
    }

    ResTable::resource_name actual_name;
    if (!table->getResourceName(expected_id.id, false, &actual_name)) {
      return ::testing::AssertionFailure() << "failed to find resource name";
    }

    Maybe<ResourceName> resName = ResourceUtils::ToResourceName(actual_name);
    if (!resName) {
      return ::testing::AssertionFailure()
             << "expected name '" << expected_res_name << "' but got '"
             << StringPiece16(actual_name.package, actual_name.packageLen)
             << ":" << StringPiece16(actual_name.type, actual_name.typeLen)
             << "/" << StringPiece16(actual_name.name, actual_name.nameLen)
             << "'";
    }

    if (expected_config != config) {
      return ::testing::AssertionFailure() << "expected config '"
                                           << expected_config << "' but got '"
                                           << ConfigDescription(config) << "'";
    }
    return ::testing::AssertionSuccess();
  }

 private:
  std::unique_ptr<IAaptContext> context_;
};

TEST_F(TableFlattenerTest, FlattenFullyLinkedTable) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("com.app.test", 0x7f)
          .AddSimple("com.app.test:id/one", ResourceId(0x7f020000))
          .AddSimple("com.app.test:id/two", ResourceId(0x7f020001))
          .AddValue("com.app.test:id/three", ResourceId(0x7f020002),
                    test::BuildReference("com.app.test:id/one",
                                         ResourceId(0x7f020000)))
          .AddValue("com.app.test:integer/one", ResourceId(0x7f030000),
                    util::make_unique<BinaryPrimitive>(
                        uint8_t(Res_value::TYPE_INT_DEC), 1u))
          .AddValue("com.app.test:integer/one", test::ParseConfigOrDie("v1"),
                    ResourceId(0x7f030000),
                    util::make_unique<BinaryPrimitive>(
                        uint8_t(Res_value::TYPE_INT_DEC), 2u))
          .AddString("com.app.test:string/test", ResourceId(0x7f040000), "foo")
          .AddString("com.app.test:layout/bar", ResourceId(0x7f050000),
                     "res/layout/bar.xml")
          .Build();

  ResTable res_table;
  ASSERT_TRUE(Flatten(table.get(), &res_table));

  EXPECT_TRUE(Exists(&res_table, "com.app.test:id/one", ResourceId(0x7f020000),
                     {}, Res_value::TYPE_INT_BOOLEAN, 0u, 0u));

  EXPECT_TRUE(Exists(&res_table, "com.app.test:id/two", ResourceId(0x7f020001),
                     {}, Res_value::TYPE_INT_BOOLEAN, 0u, 0u));

  EXPECT_TRUE(Exists(&res_table, "com.app.test:id/three",
                     ResourceId(0x7f020002), {}, Res_value::TYPE_REFERENCE,
                     0x7f020000u, 0u));

  EXPECT_TRUE(Exists(&res_table, "com.app.test:integer/one",
                     ResourceId(0x7f030000), {}, Res_value::TYPE_INT_DEC, 1u,
                     ResTable_config::CONFIG_VERSION));

  EXPECT_TRUE(Exists(&res_table, "com.app.test:integer/one",
                     ResourceId(0x7f030000), test::ParseConfigOrDie("v1"),
                     Res_value::TYPE_INT_DEC, 2u,
                     ResTable_config::CONFIG_VERSION));

  std::u16string foo_str = u"foo";
  ssize_t idx = res_table.getTableStringBlock(0)->indexOfString(foo_str.data(),
                                                                foo_str.size());
  ASSERT_GE(idx, 0);
  EXPECT_TRUE(Exists(&res_table, "com.app.test:string/test",
                     ResourceId(0x7f040000), {}, Res_value::TYPE_STRING,
                     (uint32_t)idx, 0u));

  std::u16string bar_path = u"res/layout/bar.xml";
  idx = res_table.getTableStringBlock(0)->indexOfString(bar_path.data(),
                                                        bar_path.size());
  ASSERT_GE(idx, 0);
  EXPECT_TRUE(Exists(&res_table, "com.app.test:layout/bar",
                     ResourceId(0x7f050000), {}, Res_value::TYPE_STRING,
                     (uint32_t)idx, 0u));
}

TEST_F(TableFlattenerTest, FlattenEntriesWithGapsInIds) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("com.app.test", 0x7f)
          .AddSimple("com.app.test:id/one", ResourceId(0x7f020001))
          .AddSimple("com.app.test:id/three", ResourceId(0x7f020003))
          .Build();

  ResTable res_table;
  ASSERT_TRUE(Flatten(table.get(), &res_table));

  EXPECT_TRUE(Exists(&res_table, "com.app.test:id/one", ResourceId(0x7f020001),
                     {}, Res_value::TYPE_INT_BOOLEAN, 0u, 0u));
  EXPECT_TRUE(Exists(&res_table, "com.app.test:id/three",
                     ResourceId(0x7f020003), {}, Res_value::TYPE_INT_BOOLEAN,
                     0u, 0u));
}

TEST_F(TableFlattenerTest, FlattenMinMaxAttributes) {
  Attribute attr(false);
  attr.type_mask = android::ResTable_map::TYPE_INTEGER;
  attr.min_int = 10;
  attr.max_int = 23;
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .SetPackageId("android", 0x01)
          .AddValue("android:attr/foo", ResourceId(0x01010000),
                    util::make_unique<Attribute>(attr))
          .Build();

  ResourceTable result;
  ASSERT_TRUE(Flatten(table.get(), &result));

  Attribute* actualAttr =
      test::GetValue<Attribute>(&result, "android:attr/foo");
  ASSERT_NE(nullptr, actualAttr);
  EXPECT_EQ(attr.IsWeak(), actualAttr->IsWeak());
  EXPECT_EQ(attr.type_mask, actualAttr->type_mask);
  EXPECT_EQ(attr.min_int, actualAttr->min_int);
  EXPECT_EQ(attr.max_int, actualAttr->max_int);
}

}  // namespace aapt
