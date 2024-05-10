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

#include "compile/IdAssigner.h"

#include "test/Test.h"

namespace aapt {

struct IdAssignerTests : public ::testing::Test {
  void SetUp() override {
    context = test::ContextBuilder().SetCompilationPackage("android").SetPackageId(0x01).Build();
  }
  std::unique_ptr<IAaptContext> context;
};

::testing::AssertionResult VerifyIds(ResourceTable* table);

TEST_F(IdAssignerTests, AssignIds) {
  auto table = test::ResourceTableBuilder()
                   .AddSimple("android:attr/foo")
                   .AddSimple("android:attr/bar")
                   .AddSimple("android:id/foo")
                   .Build();
  IdAssigner assigner;

  ASSERT_TRUE(assigner.Consume(context.get(), table.get()));
  ASSERT_TRUE(VerifyIds(table.get()));
}

TEST_F(IdAssignerTests, AssignIdsWithReservedIds) {
  auto table = test::ResourceTableBuilder()
                   .AddSimple("android:id/foo", ResourceId(0x01010000))
                   .AddSimple("android:dimen/two")
                   .AddSimple("android:integer/three")
                   .AddSimple("android:string/five")
                   .AddSimple("android:attr/fun", ResourceId(0x01040000))
                   .AddSimple("android:attr/foo", ResourceId(0x01040006))
                   .AddSimple("android:attr/bar")
                   .AddSimple("android:attr/baz")
                   .Build();

  IdAssigner assigner;
  ASSERT_TRUE(assigner.Consume(context.get(), table.get()));
  ASSERT_TRUE(VerifyIds(table.get()));

  std::optional<ResourceTable::SearchResult> maybe_result;

  // Expect to fill in the gaps between 0x0101XXXX and 0x0104XXXX.

  maybe_result = table->FindResource(test::ParseNameOrDie("android:dimen/two"));
  ASSERT_TRUE(maybe_result);
  EXPECT_EQ(0x01020000, maybe_result.value().entry->id);

  maybe_result =
      table->FindResource(test::ParseNameOrDie("android:integer/three"));
  ASSERT_TRUE(maybe_result);
  EXPECT_EQ(0x01030000, maybe_result.value().entry->id);

  // Expect to bypass the reserved 0x0104XXXX IDs and use the next 0x0105XXXX
  // IDs.

  maybe_result =
      table->FindResource(test::ParseNameOrDie("android:string/five"));
  ASSERT_TRUE(maybe_result);
  EXPECT_EQ(0x01050000, maybe_result.value().entry->id);

  // Expect to fill in the gaps between 0x01040000 and 0x01040006.

  maybe_result = table->FindResource(test::ParseNameOrDie("android:attr/bar"));
  ASSERT_TRUE(maybe_result);
  EXPECT_EQ(0x01040001, maybe_result.value().entry->id);

  maybe_result = table->FindResource(test::ParseNameOrDie("android:attr/baz"));
  ASSERT_TRUE(maybe_result);
  EXPECT_EQ(0x01040002, maybe_result.value().entry->id);
}

TEST_F(IdAssignerTests, FailWhenNonUniqueIdsAssigned) {
  auto table = test::ResourceTableBuilder()
                   .AddSimple("android:attr/foo", ResourceId(0x01040006))
                   .AddSimple("android:attr/bar", ResourceId(0x01040006))
                   .Build();
  IdAssigner assigner;
  ASSERT_FALSE(assigner.Consume(context.get(), table.get()));
}

TEST_F(IdAssignerTests, FailWhenNonUniqueTypeIdsAssigned) {
  auto table = test::ResourceTableBuilder()
                   .AddSimple("android:string/foo", ResourceId(0x01040000))
                   .AddSimple("android:attr/bar", ResourceId(0x01040006))
                   .Build();
  IdAssigner assigner;
  ASSERT_FALSE(assigner.Consume(context.get(), table.get()));
}

TEST_F(IdAssignerTests, FailWhenTypeHasTwoNonStagedIds) {
  auto table = test::ResourceTableBuilder()
                   .AddSimple("android:attr/foo", ResourceId(0x01050000))
                   .AddSimple("android:attr/bar", ResourceId(0x01040006))
                   .Build();
  IdAssigner assigner;
  ASSERT_FALSE(assigner.Consume(context.get(), table.get()));
}

TEST_F(IdAssignerTests, FailWhenTypeHasTwoNonStagedIdsRegardlessOfStagedId) {
  auto table =
      test::ResourceTableBuilder()
          .AddSimple("android:attr/foo", ResourceId(0x01050000))
          .AddSimple("android:attr/bar", ResourceId(0x01ff0006))
          .Add(NewResourceBuilder("android:attr/staged_baz")
                   .SetId(0x01ff0000)
                   .SetVisibility({.staged_api = true, .level = Visibility::Level::kPublic})
                   .Build())
          .Build();
  IdAssigner assigner;
  ASSERT_FALSE(assigner.Consume(context.get(), table.get()));
}

TEST_F(IdAssignerTests, FailWhenTypeHaveBothStagedAndNonStagedIds) {
  auto table =
      test::ResourceTableBuilder()
          .AddSimple("android:attr/foo", ResourceId(0x01010000))
          .Add(NewResourceBuilder("android:bool/staged_baz")
                   .SetId(0x01010001)
                   .SetVisibility({.staged_api = true, .level = Visibility::Level::kPublic})
                   .Build())
          .Build();
  IdAssigner assigner;
  ASSERT_FALSE(assigner.Consume(context.get(), table.get()));
}

TEST_F(IdAssignerTests, AssignIdsWithIdMap) {
  auto table = test::ResourceTableBuilder()
                   .AddSimple("android:attr/foo")
                   .AddSimple("android:attr/bar")
                   .Build();
  std::unordered_map<ResourceName, ResourceId> id_map = {
      {test::ParseNameOrDie("android:attr/foo"), ResourceId(0x01010002)}};
  IdAssigner assigner(&id_map);
  ASSERT_TRUE(assigner.Consume(context.get(), table.get()));
  ASSERT_TRUE(VerifyIds(table.get()));
  auto result = table->FindResource(test::ParseNameOrDie("android:attr/foo"));
  ASSERT_TRUE(result);

  const ResourceTable::SearchResult& search_result = result.value();
  EXPECT_EQ(0x01010002, search_result.entry->id);
}

TEST_F(IdAssignerTests, UseAllEntryIds) {
  ResourceTable table;
  const size_t max_entry_id = std::numeric_limits<uint16_t>::max();
  for (size_t i = 0; i <= max_entry_id; i++) {
    ASSERT_TRUE(
        table.AddResource(NewResourceBuilder("android:attr/res" + std::to_string(i)).Build(),
                          context->GetDiagnostics()));
  }
  IdAssigner assigner;
  ASSERT_TRUE(assigner.Consume(context.get(), &table));
}

TEST_F(IdAssignerTests, ExaustEntryIds) {
  ResourceTable table;
  const size_t max_entry_id = std::numeric_limits<uint16_t>::max() + 1u;
  for (size_t i = 0; i <= max_entry_id; i++) {
    ASSERT_TRUE(
        table.AddResource(NewResourceBuilder("android:attr/res" + std::to_string(i)).Build(),
                          context->GetDiagnostics()));
  }
  IdAssigner assigner;
  ASSERT_FALSE(assigner.Consume(context.get(), &table));
}

TEST_F(IdAssignerTests, ExaustEntryIdsLastIdIsPublic) {
  ResourceTable table;
  ASSERT_TRUE(table.AddResource(NewResourceBuilder("android:attr/res").SetId(0x0101ffff).Build(),
                                context->GetDiagnostics()));
  const size_t max_entry_id = std::numeric_limits<uint16_t>::max();
  for (size_t i = 0; i <= max_entry_id; i++) {
    ASSERT_TRUE(
        table.AddResource(NewResourceBuilder("android:attr/res" + std::to_string(i)).Build(),
                          context->GetDiagnostics()));
  }
  IdAssigner assigner;
  ASSERT_FALSE(assigner.Consume(context.get(), &table));
}

::testing::AssertionResult VerifyIds(ResourceTable* table) {
  std::set<ResourceId> seen_ids;
  for (auto& package : table->packages) {
    for (auto& type : package->types) {
      for (auto& entry : type->entries) {
        if (!entry->id) {
          return ::testing::AssertionFailure()
                 << "resource " << ResourceNameRef(package->name, type->named_type, entry->name)
                 << " has no ID";
        }
        if (!seen_ids.insert(entry->id.value()).second) {
          return ::testing::AssertionFailure()
                 << "resource " << ResourceNameRef(package->name, type->named_type, entry->name)
                 << " has a non-unique ID" << std::hex << entry->id.value() << std::dec;
        }
      }
    }
  }

  return ::testing::AssertionSuccess() << "all IDs are unique and assigned";
}

}  // namespace aapt
