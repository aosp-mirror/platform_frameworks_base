/*
 * Copyright (C) 2022 The Android Open Source Project
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

#include "format/binary/ResEntryWriter.h"

#include "androidfw/BigBuffer.h"
#include "format/binary/ResourceTypeExtensions.h"
#include "test/Test.h"
#include "util/Util.h"

using ::android::BigBuffer;
using ::android::Res_value;
using ::android::ResTable_map;
using ::testing::Eq;
using ::testing::Ge;
using ::testing::IsNull;
using ::testing::Ne;
using ::testing::NotNull;

namespace aapt {

using SequentialResEntryWriterTest = CommandTestFixture;
using DeduplicateItemsResEntryWriterTest = CommandTestFixture;

std::vector<int32_t> WriteAllEntries(const ResourceTableView& table, ResEntryWriter& writer) {
  std::vector<int32_t> result = {};
  for (const auto& type : table.packages[0].types) {
    for (const auto& entry : type.entries) {
      for (const auto& value : entry.values) {
        auto flat_entry = FlatEntry{&entry, value->value.get(), 0};
        result.push_back(writer.Write(&flat_entry));
      }
    }
  }
  return result;
}

TEST_F(SequentialResEntryWriterTest, WriteEntriesOneByOne) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddSimple("com.app.test:id/id1", ResourceId(0x7f010000))
          .AddSimple("com.app.test:id/id2", ResourceId(0x7f010001))
          .AddSimple("com.app.test:id/id3", ResourceId(0x7f010002))
          .Build();

  {
    BigBuffer out(512);
    SequentialResEntryWriter<false> writer(&out);
    auto offsets = WriteAllEntries(table->GetPartitionedView(), writer);

    std::vector<int32_t> expected_offsets{0, sizeof(ResEntryValuePair),
                                          2 * sizeof(ResEntryValuePair)};
    EXPECT_EQ(out.size(), 3 * sizeof(ResEntryValuePair));
    EXPECT_EQ(offsets, expected_offsets);
  }

  {
    /* expect a compact entry to only take sizeof(ResTable_entry) */
    BigBuffer out(512);
    SequentialResEntryWriter<true> writer(&out);
    auto offsets = WriteAllEntries(table->GetPartitionedView(), writer);

    std::vector<int32_t> expected_offsets{0, sizeof(ResTable_entry),
                                          2 * sizeof(ResTable_entry)};
    EXPECT_EQ(out.size(), 3 * sizeof(ResTable_entry));
    EXPECT_EQ(offsets, expected_offsets);
  }
};

TEST_F(SequentialResEntryWriterTest, WriteMapEntriesOneByOne) {
  std::unique_ptr<Array> array1 = util::make_unique<Array>();
  array1->elements.push_back(
      util::make_unique<BinaryPrimitive>(uint8_t(Res_value::TYPE_INT_DEC), 1u));
  array1->elements.push_back(
      util::make_unique<BinaryPrimitive>(uint8_t(Res_value::TYPE_INT_DEC), 2u));
  std::unique_ptr<Array> array2 = util::make_unique<Array>();
  array2->elements.push_back(
      util::make_unique<BinaryPrimitive>(uint8_t(Res_value::TYPE_INT_DEC), 1u));
  array2->elements.push_back(
      util::make_unique<BinaryPrimitive>(uint8_t(Res_value::TYPE_INT_DEC), 2u));

  std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
                                             .AddValue("com.app.test:array/arr1", std::move(array1))
                                             .AddValue("com.app.test:array/arr2", std::move(array2))
                                             .Build();

  {
    BigBuffer out(512);
    SequentialResEntryWriter<false> writer(&out);
    auto offsets = WriteAllEntries(table->GetPartitionedView(), writer);

    std::vector<int32_t> expected_offsets{0, sizeof(ResTable_entry_ext) + 2 * sizeof(ResTable_map)};
    EXPECT_EQ(out.size(), 2 * (sizeof(ResTable_entry_ext) + 2 * sizeof(ResTable_map)));
    EXPECT_EQ(offsets, expected_offsets);
  }

  {
    /* compact_entry should have no impact to map items */
    BigBuffer out(512);
    SequentialResEntryWriter<true> writer(&out);
    auto offsets = WriteAllEntries(table->GetPartitionedView(), writer);

    std::vector<int32_t> expected_offsets{0, sizeof(ResTable_entry_ext) + 2 * sizeof(ResTable_map)};
    EXPECT_EQ(out.size(), 2 * (sizeof(ResTable_entry_ext) + 2 * sizeof(ResTable_map)));
    EXPECT_EQ(offsets, expected_offsets);
  }
};

TEST_F(DeduplicateItemsResEntryWriterTest, DeduplicateItemEntries) {
  std::unique_ptr<ResourceTable> table =
      test::ResourceTableBuilder()
          .AddSimple("com.app.test:id/id1", ResourceId(0x7f010000))
          .AddSimple("com.app.test:id/id2", ResourceId(0x7f010001))
          .AddSimple("com.app.test:id/id3", ResourceId(0x7f010002))
          .Build();

  {
    BigBuffer out(512);
    DeduplicateItemsResEntryWriter<false> writer(&out);
    auto offsets = WriteAllEntries(table->GetPartitionedView(), writer);

    std::vector<int32_t> expected_offsets{0, 0, 0};
    EXPECT_EQ(out.size(), sizeof(ResEntryValuePair));
    EXPECT_EQ(offsets, expected_offsets);
  }

  {
    /* expect a compact entry to only take sizeof(ResTable_entry) */
    BigBuffer out(512);
    DeduplicateItemsResEntryWriter<true> writer(&out);
    auto offsets = WriteAllEntries(table->GetPartitionedView(), writer);

    std::vector<int32_t> expected_offsets{0, 0, 0};
    EXPECT_EQ(out.size(), sizeof(ResTable_entry));
    EXPECT_EQ(offsets, expected_offsets);
  }
};

TEST_F(DeduplicateItemsResEntryWriterTest, WriteMapEntriesOneByOne) {
  std::unique_ptr<Array> array1 = util::make_unique<Array>();
  array1->elements.push_back(
      util::make_unique<BinaryPrimitive>(uint8_t(Res_value::TYPE_INT_DEC), 1u));
  array1->elements.push_back(
      util::make_unique<BinaryPrimitive>(uint8_t(Res_value::TYPE_INT_DEC), 2u));
  std::unique_ptr<Array> array2 = util::make_unique<Array>();
  array2->elements.push_back(
      util::make_unique<BinaryPrimitive>(uint8_t(Res_value::TYPE_INT_DEC), 1u));
  array2->elements.push_back(
      util::make_unique<BinaryPrimitive>(uint8_t(Res_value::TYPE_INT_DEC), 2u));

  std::unique_ptr<ResourceTable> table = test::ResourceTableBuilder()
                                             .AddValue("com.app.test:array/arr1", std::move(array1))
                                             .AddValue("com.app.test:array/arr2", std::move(array2))
                                             .Build();

  {
    BigBuffer out(512);
    DeduplicateItemsResEntryWriter<false> writer(&out);
    auto offsets = WriteAllEntries(table->GetPartitionedView(), writer);

    std::vector<int32_t> expected_offsets{0, sizeof(ResTable_entry_ext) + 2 * sizeof(ResTable_map)};
    EXPECT_EQ(out.size(), 2 * (sizeof(ResTable_entry_ext) + 2 * sizeof(ResTable_map)));
    EXPECT_EQ(offsets, expected_offsets);
  }

  {
    /* compact_entry should have no impact to map items */
    BigBuffer out(512);
    DeduplicateItemsResEntryWriter<true> writer(&out);
    auto offsets = WriteAllEntries(table->GetPartitionedView(), writer);

    std::vector<int32_t> expected_offsets{0, sizeof(ResTable_entry_ext) + 2 * sizeof(ResTable_map)};
    EXPECT_EQ(out.size(), 2 * (sizeof(ResTable_entry_ext) + 2 * sizeof(ResTable_map)));
    EXPECT_EQ(offsets, expected_offsets);
  }
 };

}  // namespace aapt